package com.kazamatech.kzdesk.pcstream

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Signaling protocol tests against an in-process stub endpoint.
 *
 * The stub deliberately uses [ServerSocket] instead of `com.sun.net.httpserver`:
 * that JDK module is not on the Android unit-test compile classpath, so the test
 * would not build under `:core-pc-stream:testDebugUnitTest`. Only APIs that exist
 * in `android.jar` (plus JUnit/org.json/coroutines declared by the module) are used.
 */
class PcSignalClientTest {
    private lateinit var server: StubHttpEndpoint
    private lateinit var client: PcSignalClient
    private val observedAuthorization = CopyOnWriteArrayList<String>()
    private val observedSignalBodies = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        observedAuthorization.clear()
        observedSignalBodies.clear()
        server = StubHttpEndpoint { method, path, headers, body ->
            when {
                method == "POST" && path == "/api/pc/rooms" -> StubResponse(
                    201,
                    """{"room_id":"$ROOM_ID","host_token":"$HOST_TOKEN","pairing_code":"$PAIRING_CODE","expires_at":1735689600000}""",
                )

                method == "POST" && path == "/api/pc/rooms/$ROOM_ID/join" -> {
                    observedSignalBodies += body
                    StubResponse(
                        200,
                        """{"status":"ok","viewer_token":"$VIEWER_TOKEN","expires_at":1735690000000}""",
                    )
                }

                path == "/api/pc/rooms/$ROOM_ID/signal" -> {
                    observedAuthorization += headers["authorization"].orEmpty()
                    if (method == "POST") {
                        observedSignalBodies += body
                        StubResponse(201, """{"status":"ok","seq":7}""")
                    } else {
                        StubResponse(
                            200,
                            """{"status":"ok","role":"viewer","events":[{"seq":7,"from":"host","type":"offer","payload":{"sdp":"v=0"},"created_at":1735689500000}],"cursor":7,"latest_seq":7}""",
                        )
                    }
                }

                else -> StubResponse(404, """{"status":"not_found"}""")
            }
        }
        server.start()
        client = PcSignalClient("http://127.0.0.1:${server.port}")
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun createsJoinsPostsAndPollsWithoutLeakingSecretsInDiagnostics() = runBlocking {
        val host = client.createRoom()
        assertEquals(ROOM_ID, host.credentials.roomId)
        assertEquals(HOST_TOKEN, host.credentials.bearerToken)
        assertEquals(PAIRING_CODE, host.pairingCode)
        assertFalse(host.toString().contains(PAIRING_CODE))
        assertFalse(host.toString().contains(HOST_TOKEN))

        val viewer = client.joinRoom(ROOM_ID, PAIRING_CODE)
        assertEquals(PcSignalRole.VIEWER, viewer.role)
        assertEquals(VIEWER_TOKEN, viewer.bearerToken)

        val sequence = client.postSignal(
            host.credentials,
            "offer",
            JSONObject().put("sdp", "v=0"),
        )
        assertEquals(7L, sequence)

        val batch = client.pollSignals(viewer)
        assertEquals(7L, batch.cursor)
        assertEquals(7L, batch.latestSequence)
        assertEquals(1, batch.events.size)
        assertEquals("offer", batch.events.single().type)
        assertEquals("v=0", batch.events.single().payload?.getString("sdp"))
        assertEquals("Bearer $HOST_TOKEN", observedAuthorization[0])
        assertEquals("Bearer $VIEWER_TOKEN", observedAuthorization[1])
        assertEquals(PAIRING_CODE, JSONObject(observedSignalBodies[0]).getString("pairing_code"))
        assertEquals("offer", JSONObject(observedSignalBodies[1]).getString("type"))
    }

    @Test
    fun acceptsCleartextOnlyForLoopbackAndRejectsMalformedIds() {
        assertThrows(IllegalArgumentException::class.java) {
            PcSignalClient("http://example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.joinRoom("not-a-room", PAIRING_CODE) }
        }
    }

    private data class StubResponse(val status: Int, val body: String)

    /** Minimal single-threaded HTTP/1.1 responder bound to an ephemeral loopback port. */
    private class StubHttpEndpoint(
        private val route: (method: String, path: String, headers: Map<String, String>, body: String) -> StubResponse,
    ) {
        private val serverSocket = ServerSocket()
        private val stopped = AtomicBoolean(false)
        private var worker: Thread? = null

        val port: Int get() = serverSocket.localPort

        fun start() {
            serverSocket.reuseAddress = true
            serverSocket.bind(InetSocketAddress("127.0.0.1", 0))
            worker = Thread {
                while (!stopped.get()) {
                    val socket = try {
                        serverSocket.accept()
                    } catch (_: Exception) {
                        break
                    }
                    try {
                        serve(socket)
                    } catch (_: Exception) {
                        // A malformed stub request must not fail the whole test run.
                    } finally {
                        runCatching { socket.close() }
                    }
                }
            }.apply {
                isDaemon = true
                name = "pc-signal-stub"
                start()
            }
        }

        fun stop() {
            stopped.set(true)
            runCatching { serverSocket.close() }
            worker?.let { runCatching { it.join(2_000) } }
        }

        private fun serve(socket: Socket) {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val head = readHeaders(input)
            val requestLine = head.lines().firstOrNull().orEmpty().split(" ")
            if (requestLine.size < 2) return
            val method = requestLine[0]
            val rawTarget = requestLine[1]
            val path = rawTarget.substringBefore('?')
            val headers = HashMap<String, String>()
            head.lines().drop(1).forEach { line ->
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase()] =
                        line.substring(separator + 1).trim()
                }
            }
            val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val body = if (contentLength > 0) {
                String(readExactly(input, contentLength), StandardCharsets.UTF_8)
            } else {
                ""
            }
            val response = route(method, path, headers, body)
            val payload = response.body.toByteArray(StandardCharsets.UTF_8)
            val statusText = when (response.status) {
                200 -> "OK"
                201 -> "Created"
                404 -> "Not Found"
                else -> "Error"
            }
            val header = buildString {
                append("HTTP/1.1 ").append(response.status).append(' ').append(statusText).append("\r\n")
                append("Content-Type: application/json; charset=utf-8\r\n")
                append("Content-Length: ").append(payload.size).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(header.toByteArray(StandardCharsets.UTF_8))
            output.write(payload)
            output.flush()
        }

        private fun readHeaders(input: InputStream): String {
            val buffer = ByteArrayOutputStream()
            var matched = 0
            val terminator = "\r\n\r\n".toByteArray(StandardCharsets.UTF_8)
            while (true) {
                val byte = input.read()
                if (byte < 0) break
                buffer.write(byte)
                matched = if (byte.toByte() == terminator[matched]) matched + 1 else 0
                if (matched == terminator.size) break
                if (buffer.size() > MAX_HEADER_BYTES) break
            }
            return String(buffer.toByteArray(), StandardCharsets.UTF_8)
        }

        private fun readExactly(input: InputStream, length: Int): ByteArray {
            val bytes = ByteArray(length)
            var read = 0
            while (read < length) {
                val count = input.read(bytes, read, length - read)
                if (count < 0) break
                read += count
            }
            return if (read == length) bytes else bytes.copyOf(read)
        }

        private companion object {
            const val MAX_HEADER_BYTES = 16 * 1024
        }
    }

    companion object {
        private const val ROOM_ID = "00000000-0000-4000-8000-000000000001"
        private const val HOST_TOKEN = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val VIEWER_TOKEN = "ccccccccccccccccccccccccccccccccccccccccccc"
        private const val PAIRING_CODE = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
