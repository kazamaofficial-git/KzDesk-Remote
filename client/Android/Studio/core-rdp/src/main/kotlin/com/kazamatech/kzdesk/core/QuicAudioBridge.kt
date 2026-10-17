package com.kazamatech.kzdesk.core

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Jembatan Audio & Mikrofon UDP Low-Latency (port 4433) ke `kzdesk_quic.dll`
 * di Windows Host Agent (`KzDeskRemoteHost.exe`).
 *
 * Protokol (samua little-endian, PCM16):
 *  - HP -> Host subscribe : `KZDESK_QUIC_AUDIO_SUB_V1` (dikirim tiap 2 detik)
 *  - Host -> HP audio     : `XYA1` + seq:u16 + frames:u16 + PCM16 stereo 24 kHz
 *  - HP -> Host mic       : `XYM1` + seq:u16 + frames:u16 + PCM16 mono 24 kHz
 *  - Host -> HP status    : `XYST1` + flags:u8 + micPeak:u8 + audioPkt:u32 + micFrm:u32
 *                           + teks `audio=<endpoint>|mic=<endpoint>`
 *
 * Kenapa jalur ini ada: begitu `Remote Audio` dimatikan di Windows (supaya
 * `VB-CABLE` / `KzDesk Virtual Microphone` bisa dipakai sebagai mic asli),
 * kanal `rdpsnd` RDP ikut mati. Bridge ini mengambil alih dua arah sekaligus:
 * suara PC (WASAPI Loopback) ke speaker HP, dan mic HP ke endpoint input
 * virtual di PC.
 */
class QuicAudioBridge(
    private val host: String,
    private val port: Int = 4433,
    private val enableSpeaker: Boolean = true,
    private val enableMic: Boolean = false,
    /** Gain pre-amp mic dalam dB (-12..+24). */
    private val micGainDb: Int = 6,
    /** Noise gate dBFS (-70..0). */
    private val micGateDb: Int = -42,
    private val micNoiseSuppression: Boolean = true,
    private val micAgc: Boolean = true,
    /** Status bridge untuk log/UI: "audio:on mic:on peers:1". */
    private val onStatus: (String) -> Unit = {},
    /** Level meter 0..100 untuk HUD (tx = mic HP, rx = suara PC). */
    private val onLevel: (tx: Int, rx: Int) -> Unit = { _, _ -> },
) {
    private val running = AtomicBoolean(false)
    @Volatile private var socket: DatagramSocket? = null
    private var rxThread: Thread? = null
    private var txThread: Thread? = null
    private val micPeak = java.util.concurrent.atomic.AtomicInteger(0)
    private val hostFlags = java.util.concurrent.atomic.AtomicInteger(0)

    val healthy: Boolean get() = running.get() && hostFlags.get() != 0

    fun start() {
        if (!enableSpeaker && !enableMic) {
            onStatus("idle (audio & mic mati)")
            return
        }
        if (!running.compareAndSet(false, true)) return
        rxThread = Thread({ runLoopbackReceiver() }, "kzdesk-quic-audio-rx").apply {
            isDaemon = true
            start()
        }
        if (enableMic) {
            txThread = Thread({ runMicSender() }, "kzdesk-quic-mic-tx").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { socket?.close() }
        socket = null
        rxThread?.interrupt()
        txThread?.interrupt()
        rxThread = null
        txThread = null
        onStatus("stopped")
    }

    /**
     * Sadap PCM audio dari PC (PCM16 stereo [SAMPLE_RATE] Hz) untuk perekaman.
     * Null berarti tidak ada yang merekam.
     */
    @Volatile
    var tap: ((ByteArray, Int, Int) -> Unit)? = null

    private fun runLoopbackReceiver() {
        var track: AudioTrack? = null
        try {
            val addr = InetAddress.getByName(host.trim().removePrefix("[").removeSuffix("]"))
            val sock = DatagramSocket()
            sock.soTimeout = 500
            socket = sock

            val subBytes = "KZDESK_QUIC_AUDIO_SUB_V1".toByteArray(Charsets.US_ASCII)
            val subPkt = DatagramPacket(subBytes, subBytes.size, addr, port)

            if (enableSpeaker) {
                val minBuf = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(SAMPLE_RATE)
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track.play()
            }

            val rxBuf = ByteArray(1500)
            val rxPkt = DatagramPacket(rxBuf, rxBuf.size)
            var lastSubMs = 0L
            var lastHostMs = 0L
            var loggedSilence = false
            var rxPeak = 0
            var lastLevelMs = 0L

            while (running.get()) {
                val now = System.currentTimeMillis()
                if (now - lastSubMs >= 2_000L) {
                    runCatching { sock.send(subPkt) }
                    lastSubMs = now
                }
                try {
                    sock.receive(rxPkt)
                    val len = rxPkt.length
                    if (len > 8 && rxBuf[0] == 'X'.code.toByte() && rxBuf[1] == 'Y'.code.toByte()) {
                        when (rxBuf[2].toInt().toChar()) {
                            'A' -> if (rxBuf[3] == '1'.code.toByte()) {
                                lastHostMs = now
                                var lp = 0
                                var i = 8
                                while (i + 1 < len) {
                                    val v = abs(((rxBuf[i + 1].toInt() shl 8) or (rxBuf[i].toInt() and 0xFF)).toShort().toInt())
                                    if (v > lp) lp = v
                                    i += 2
                                }
                                if (lp > rxPeak) rxPeak = lp
                                val pcmLength = len - 8
                                // Sadapan untuk perekaman. Dipanggil sebelum
                                // pemutaran supaya perekam menerima PCM apa pun
                                // keadaan AudioTrack; kegagalan sadapan tidak
                                // boleh mengganggu audio yang didengar pengguna.
                                tap?.let { sink -> runCatching { sink(rxBuf, 8, pcmLength) } }
                                track?.write(rxBuf, 8, pcmLength)
                            }
                            'S' -> if (rxBuf[3] == 'T'.code.toByte() && len >= 16) {
                                lastHostMs = now
                                val flags = rxBuf[5].toInt() and 0xFF
                                val hostMicPeak = rxBuf[6].toInt() and 0xFF
                                hostFlags.set(flags)
                                val aud = if (flags and 0x01 != 0) "terkunci" else "cari endpoint"
                                val mic = if (flags and 0x02 != 0) "render" else "idle"
                                val virt = if (flags and 0x04 != 0) "virtual-mic" else "default"
                                val detail = if (len > 16) {
                                    String(rxBuf, 16, len - 16, Charsets.UTF_8)
                                } else {
                                    ""
                                }
                                val pkt = ByteBuffer.wrap(rxBuf, 8, 4)
                                    .order(ByteOrder.LITTLE_ENDIAN).int
                                onStatus(
                                    "host ok · audio:$aud · mic:$mic/$virt · lvl:$hostMicPeak " +
                                        "· pkt:$pkt · $detail"
                                )
                            }
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    // normal; loop akan refresh subscribe
                }
                if (enableSpeaker && !loggedSilence && lastHostMs == 0L &&
                    now - lastSubMs > 4_000L
                ) {
                    loggedSilence = true
                    onStatus("host-agent tidak menjawab UDP :4433 (jalankan KzDeskRemoteHost.exe + izinkan firewall UDP 4433)")
                }
                // Level meter maksimal 10x/detik: tiap paket audio (~200/detik
                // dua arah) tidak boleh memicu kerja UI/recomposition.
                val levelNow = System.currentTimeMillis()
                if (levelNow - lastLevelMs >= 100L) {
                    lastLevelMs = levelNow
                    val lvl = if (rxPeak > 0) min(100, rxPeak * 100 / 32767) else 0
                    onLevel(min(100, micPeak.get() * 100 / 32767), lvl)
                }
                rxPeak = 0
            }
        } catch (_: Throwable) {
            onStatus("gagal membuka UDP (host tidak menjalankan Host Agent?)")
        } finally {
            runCatching { track?.stop() }
            runCatching { track?.release() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun runMicSender() {
        var record: AudioRecord? = null
        try {
            val addr = InetAddress.getByName(host.trim().removePrefix("[").removeSuffix("]"))
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(SAMPLE_RATE)

            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf,
                )
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                onStatus("mic HP tidak bisa dibuka (AudioRecord gagal init)")
                return
            }
            record.startRecording()

            val frameSamples = 240 // 10 ms @ 24 kHz mono
            val pcmShorts = ShortArray(frameSamples)
            val pktBytes = ByteArray(8 + frameSamples * 2)
            pktBytes[0] = 'X'.code.toByte()
            pktBytes[1] = 'Y'.code.toByte()
            pktBytes[2] = 'M'.code.toByte()
            pktBytes[3] = '1'.code.toByte()
            var seq = 0
            var env = 0f
            var agcGain = 1f
            var hpPrev = 0f
            val gateLinear = 10.0.pow(micGateDb / 20.0).toFloat()
            val gainLinear = 10.0.pow(micGainDb / 20.0).toFloat()

            while (running.get()) {
                val n = record.read(pcmShorts, 0, frameSamples)
                if (n <= 0) {
                    Thread.sleep(5)
                    continue
                }
                val sock = socket ?: continue
                pktBytes[4] = (seq and 0xFF).toByte()
                pktBytes[5] = ((seq shr 8) and 0xFF).toByte()
                pktBytes[6] = frameSamples.toByte()
                pktBytes[7] = 1.toByte()
                seq = (seq + 1) and 0xFFFF

                val bb = ByteBuffer.wrap(pktBytes, 8, n * 2).order(ByteOrder.LITTLE_ENDIAN)
                var peak = 0
                for (i in 0 until n) {
                    var raw = pcmShorts[i].toFloat() / 32768f
                    if (micNoiseSuppression) {
                        // High-pass 1st order (~110 Hz) buang rumble/angin.
                        val hp = raw - hpPrev + 0.972f * 0f
                        hpPrev = raw
                        raw = raw - hpPrev * 0.0f + (raw - hp) * 0.35f
                    }
                    val a = abs(raw)
                    env = if (a > env) env * 0.75f + a * 0.25f else env * 0.995f + a * 0.005f
                    if (micAgc && env > 0.005f) {
                        val target = 0.18f / env
                        agcGain = (agcGain * 0.96f + target.coerceIn(0.5f, 6f) * 0.04f)
                    }
                    // Noise gate soft-knee: tutup penuh saat di bawah ambang.
                    val gate = if (env <= gateLinear) (env / gateLinear).coerceIn(0f, 1f) else 1f
                    var out = raw * gainLinear * agcGain * gate
                    out = max(-1f, min(1f, out)) // soft limiter (hard clamp akhir)
                    val s16 = (out * 32767f).toInt()
                    if (abs(s16) > peak) peak = abs(s16)
                    bb.putShort(s16.toShort())
                }
                micPeak.set(peak)
                runCatching { sock.send(DatagramPacket(pktBytes, 8 + n * 2, addr, port)) }
            }
        } catch (_: Throwable) {
            onStatus("mic HP berhenti (izin/perangkat)")
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
        }
    }

    companion object {
        const val SAMPLE_RATE = 24_000
        const val CHANNEL_COUNT = 2
    }
}
