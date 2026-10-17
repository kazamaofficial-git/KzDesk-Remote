package com.kazamatech.kzdesk.pcstream

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Transport state for the independent WebRTC PC/Game Stream viewer. */
enum class PcStreamState {
    NEW,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    FAILED,
    CLOSED,
}

/**
 * Android viewer-side WebRTC endpoint. It creates an offer, receives H.264/VP8
 * video and audio tracks, and exposes the SCTP data channel for remote controls.
 * No RDP/FreeRDP classes are referenced by this module.
 *
 * State/video/control callbacks can arrive on WebRTC or signaling worker threads;
 * UI callers should dispatch them to the main thread. The supplied VideoSink is
 * attached directly to the remote track and should be detached before its view is
 * disposed by calling [setVideoSink] with null.
 *
 * This is a direct-ICE prototype: the default configuration uses a public STUN
 * server and has no TURN fallback. Supply additional ICE servers when relay
 * infrastructure is provisioned and tested.
 */
class PcStreamViewer(
    context: Context,
    private val credentials: PcSignalCredentials,
    private val signaling: PcSignalClient,
    private val eglContext: EglBase.Context,
    private val onStateChanged: (PcStreamState, String?) -> Unit = { _, _ -> },
    private val onRemoteVideoTrack: (VideoTrack?) -> Unit = {},
    private val onControlMessage: (ByteArray) -> Unit = {},
    initialVideoSink: VideoSink? = null,
    iceServers: List<PeerConnection.IceServer> = defaultIceServers(),
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val configuredIceServers = iceServers.toList()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = AtomicReference(PcStreamState.NEW)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val resourcesReleased = AtomicBoolean(false)
    private val offerReady = CompletableDeferred<JSONObject>()
    private val localIceMessages = Channel<LocalIceMessage>(Channel.UNLIMITED)
    private val remoteCandidateMutex = Mutex()
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    private val resourceLock = Any()
    private val videoLock = Any()
    private val dataChannelLock = Any()

    @Volatile private var peerConnectionFactory: PeerConnectionFactory? = null
    @Volatile private var peerConnection: PeerConnection? = null
    @Volatile private var controlChannel: DataChannel? = null
    @Volatile private var remoteVideoTrack: VideoTrack? = null
    @Volatile private var signalWriterJob: Job? = null
    @Volatile private var signalReaderJob: Job? = null
    private var videoSink: VideoSink? = initialVideoSink
    private var remoteDescriptionSet = false
    private var remoteAnswerApplied = false

    /** Begin signaling, create a recv-only video/audio PeerConnection, and offer. */
    fun start() {
        check(credentials.role == PcSignalRole.VIEWER) { "Android PC stream endpoint must use viewer credentials" }
        check(started.compareAndSet(false, true)) { "PC stream viewer already started" }
        if (closed.get()) throw IllegalStateException("PC stream viewer is closed")
        transition(PcStreamState.CONNECTING)
        scope.launch { initializePeerConnection() }
    }

    private fun initializePeerConnection() {
        synchronized(resourceLock) {
            if (closed.get()) return
            try {
                initializeWebRtc(applicationContext)
                val factory = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
                    .createPeerConnectionFactory()
                peerConnectionFactory = factory

                val configuration = PeerConnection.RTCConfiguration(configuredIceServers).apply {
                    iceTransportsType = PeerConnection.IceTransportsType.ALL
                    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    iceCandidatePoolSize = 1
                }
                val peer = factory.createPeerConnection(configuration, createPeerObserver())
                    ?: throw IOException("WebRTC could not create a PeerConnection")
                peerConnection = peer
                peer.setAudioRecording(false)
                peer.setAudioPlayout(true)
                peer.addTransceiver(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
                ) ?: throw IOException("WebRTC could not add a video receiver")
                peer.addTransceiver(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
                ) ?: throw IOException("WebRTC could not add an audio receiver")

                val channelInit = DataChannel.Init().apply {
                    // Input packets are state snapshots; freshness is more valuable than retransmitting stale axes.
                    ordered = false
                    maxRetransmits = 0
                    protocol = CONTROL_PROTOCOL
                }
                val channel = peer.createDataChannel(CONTROL_LABEL, channelInit)
                    ?: throw IOException("WebRTC could not create the control data channel")
                attachControlChannel(channel)

                signalWriterJob = scope.launch { writeSignals() }
                signalReaderJob = scope.launch { readSignals() }
                if (!closed.get()) createViewerOffer(peer)
            } catch (failure: Throwable) {
                fail(failure)
            }
        }
    }

    /** Attach, replace, or detach the video renderer without renegotiating. */
    fun setVideoSink(sink: VideoSink?) {
        synchronized(videoLock) {
            val safeSink = if (closed.get()) null else sink
            val track = remoteVideoTrack
            val old = videoSink
            if (old === safeSink) return
            if (track != null && old != null) track.removeSink(old)
            videoSink = safeSink
            if (track != null && safeSink != null) track.addSink(safeSink)
        }
    }

    /**
     * Send a bounded binary input packet on the low-latency WebRTC data channel.
     * The packet format/state sequence is defined by the shared input layer, not
     * by RDP. Callers should send complete state snapshots and periodically
     * refresh held controls so loss of an unreliable packet cannot stick a key.
     */
    fun sendControlPacket(packet: ByteArray): Boolean {
        if (closed.get() || packet.isEmpty() || packet.size > MAX_CONTROL_PACKET_BYTES) return false
        val channel = controlChannel ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        return try {
            channel.send(DataChannel.Buffer(ByteBuffer.wrap(packet), true))
        } catch (_: RuntimeException) {
            false
        }
    }

    /** Encode and send one complete virtual-gamepad snapshot. */
    fun sendGamepadState(sequence: Long, timestampMs: Long, state: KzGamepadState): Boolean {
        val packet = try {
            KzGamepadPacketV1.encode(sequence, timestampMs, state)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return sendControlPacket(packet)
    }

    /** Send a best-effort bye message, then release local WebRTC resources. */
    suspend fun disconnect(reason: String = "viewer_closed") {
        if (!closed.compareAndSet(false, true)) return
        localIceMessages.close()
        offerReady.cancel()
        try {
            withTimeout(DISCONNECT_SIGNAL_TIMEOUT_MS) {
                signaling.postSignal(
                    credentials,
                    "bye",
                    JSONObject().put("reason", reason.take(MAX_BYE_REASON_CHARS)),
                )
            }
        } catch (_: Throwable) {
            // Teardown must complete even if the signaling endpoint is offline or the room expired.
        }
        transitionTerminal(PcStreamState.CLOSED, null)
        releaseResources()
    }

    /** Immediate local teardown; use [disconnect] when a best-effort peer bye is desired. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        localIceMessages.close()
        offerReady.cancel()
        transitionTerminal(PcStreamState.CLOSED, null)
        releaseResources()
    }

    private fun createViewerOffer(peer: PeerConnection) {
        peer.createOffer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                if (description.type != SessionDescription.Type.OFFER) {
                    fail(IOException("WebRTC returned a non-offer description"))
                    return
                }
                peer.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(description: SessionDescription) = Unit

                    override fun onSetSuccess() {
                        val payload = JSONObject()
                            .put("type", description.type.canonicalForm())
                            .put("sdp", description.description)
                        offerReady.complete(payload)
                    }

                    override fun onCreateFailure(error: String) = Unit
                    override fun onSetFailure(error: String) = fail(IOException("Setting local WebRTC offer failed: $error"))
                }, description)
            }

            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String) = fail(IOException("Creating WebRTC offer failed: $error"))
            override fun onSetFailure(error: String) = Unit
        }, MediaConstraints())
    }

    private suspend fun writeSignals() {
        try {
            val offer = offerReady.await()
            if (closed.get()) return
            signaling.postSignal(credentials, "offer", offer)
            for (message in localIceMessages) {
                if (closed.get()) return
                signaling.postSignal(credentials, "candidate", message.payload)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (!closed.get()) fail(failure)
        }
    }

    private suspend fun readSignals() {
        var cursor = 0L
        var retryDelayMs = INITIAL_RETRY_DELAY_MS
        while (scope.isActive && !closed.get()) {
            try {
                val batch = signaling.pollSignals(credentials, cursor)
                cursor = batch.cursor
                retryDelayMs = INITIAL_RETRY_DELAY_MS
                for (message in batch.events) {
                    if (closed.get()) return
                    handleRemoteSignal(message)
                }
                delay(if (state.get() == PcStreamState.CONNECTED) CONNECTED_POLL_INTERVAL_MS else NEGOTIATION_POLL_INTERVAL_MS)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (closed.get()) return
                val httpFailure = failure as? PcSignalHttpException
                val transient = when {
                    failure is PcSignalProtocolException -> false
                    httpFailure == null -> failure is IOException
                    httpFailure.httpStatus == 401 || httpFailure.httpStatus == 410 -> false
                    else -> httpFailure.httpStatus == 429 || httpFailure.httpStatus >= 500
                }
                if (!transient) {
                    fail(failure)
                    return
                }
                delay(retryDelayMs)
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
            }
        }
    }

    private suspend fun handleRemoteSignal(message: PcSignalMessage) {
        when (message.type) {
            "answer" -> applyRemoteAnswer(message.payload)
            "candidate" -> addRemoteCandidate(message.payload)
            "bye" -> {
                closed.set(true)
                localIceMessages.close()
                offerReady.cancel()
                transitionTerminal(PcStreamState.CLOSED, "Peer ended the stream")
                releaseResources()
            }
            "offer" -> throw PcSignalProtocolException("Unexpected offer from the stream host")
            else -> throw PcSignalProtocolException("Unsupported remote signaling message")
        }
    }

    private suspend fun applyRemoteAnswer(payload: JSONObject?) {
        val safePayload = payload ?: throw PcSignalProtocolException("Missing SDP answer")
        if (remoteAnswerApplied) return
        val type = safePayload.optString("type")
        val sdp = safePayload.optString("sdp")
        if (type != "answer" || sdp.isBlank()) {
            throw PcSignalProtocolException("Invalid SDP answer")
        }
        val description = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        suspendCancellableCoroutine<Unit> { continuation ->
            val peer = peerConnection
            if (peer == null || closed.get()) {
                continuation.resumeWithException(IOException("PeerConnection is closed"))
                return@suspendCancellableCoroutine
            }
            peer.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) = Unit

                override fun onSetSuccess() {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onCreateFailure(error: String) = Unit
                override fun onSetFailure(error: String) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("Setting remote WebRTC answer failed: $error"))
                }
            }, description)
        }
        val queued = remoteCandidateMutex.withLock {
            remoteAnswerApplied = true
            remoteDescriptionSet = true
            pendingRemoteCandidates.toList().also { pendingRemoteCandidates.clear() }
        }
        queued.forEach(::addCandidateToPeer)
    }

    private suspend fun addRemoteCandidate(payload: JSONObject?) {
        // A null candidate denotes end-of-candidates; the WebRTC stack already
        // tracks ICE gathering completion, so no synthetic candidate is required.
        if (payload == null) return
        val sdp = payload.optString("candidate")
        val mLineIndex = payload.optInt("sdpMLineIndex", -1)
        val rawMid = payload.opt("sdpMid")
        val sdpMid = if (rawMid == null || rawMid == JSONObject.NULL) null else rawMid.toString()
        if (sdp.isBlank() || mLineIndex < 0 || (sdpMid != null && sdpMid.length > 64)) {
            throw PcSignalProtocolException("Invalid ICE candidate")
        }
        val candidate = IceCandidate(sdpMid, mLineIndex, sdp)
        val addNow = remoteCandidateMutex.withLock {
            if (remoteDescriptionSet) true else {
                pendingRemoteCandidates += candidate
                false
            }
        }
        if (addNow) addCandidateToPeer(candidate)
    }

    private fun addCandidateToPeer(candidate: IceCandidate) {
        val peer = peerConnection ?: throw IllegalStateException("PeerConnection is closed")
        if (!peer.addIceCandidate(candidate)) throw IOException("WebRTC rejected an ICE candidate")
    }

    private fun createPeerObserver(): PeerConnection.Observer = object : PeerConnection.Observer {
        override fun onSignalingChange(signalingState: PeerConnection.SignalingState) = Unit

        override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState) {
            when (iceConnectionState) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> transition(PcStreamState.CONNECTED)
                PeerConnection.IceConnectionState.DISCONNECTED -> transition(PcStreamState.DISCONNECTED)
                PeerConnection.IceConnectionState.FAILED -> fail(IOException("WebRTC ICE connection failed"))
                PeerConnection.IceConnectionState.CLOSED -> transitionTerminal(PcStreamState.CLOSED, null)
                else -> Unit
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(iceGatheringState: PeerConnection.IceGatheringState) {
            if (iceGatheringState == PeerConnection.IceGatheringState.COMPLETE) {
                localIceMessages.trySend(LocalIceMessage(null))
            }
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            val payload = JSONObject()
                .put("candidate", candidate.sdp)
                .put("sdpMid", candidate.sdpMid ?: JSONObject.NULL)
                .put("sdpMLineIndex", candidate.sdpMLineIndex)
            localIceMessages.trySend(LocalIceMessage(payload))
        }

        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit

        override fun onDataChannel(channel: DataChannel) {
            if (channel.label() == CONTROL_LABEL && controlChannel == null) attachControlChannel(channel)
            else {
                channel.close()
                channel.dispose()
            }
        }

        override fun onRenegotiationNeeded() = Unit

        override fun onTrack(transceiver: RtpTransceiver) {
            if (transceiver.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) {
                val track = transceiver.receiver.track() as? VideoTrack ?: return
                attachRemoteVideoTrack(track)
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> transition(PcStreamState.CONNECTED)
                PeerConnection.PeerConnectionState.DISCONNECTED -> transition(PcStreamState.DISCONNECTED)
                PeerConnection.PeerConnectionState.FAILED -> fail(IOException("WebRTC peer connection failed"))
                PeerConnection.PeerConnectionState.CLOSED -> transitionTerminal(PcStreamState.CLOSED, null)
                else -> Unit
            }
        }

        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<MediaStream>) {
            val track = receiver.track() as? VideoTrack ?: return
            attachRemoteVideoTrack(track)
        }
    }

    private fun attachRemoteVideoTrack(track: VideoTrack) {
        synchronized(videoLock) {
            if (closed.get() || remoteVideoTrack === track) return
            remoteVideoTrack?.let { previous -> videoSink?.let(previous::removeSink) }
            remoteVideoTrack = track
            videoSink?.let(track::addSink)
        }
        runCatching { onRemoteVideoTrack(track) }
    }

    private fun attachControlChannel(channel: DataChannel) {
        synchronized(dataChannelLock) {
            if (closed.get()) {
                channel.close()
                channel.dispose()
                return
            }
            val old = controlChannel
            if (old === channel) return
            if (old != null && old.label() == CONTROL_LABEL) {
                channel.close()
                channel.dispose()
                return
            }
            old?.unregisterObserver()
            old?.close()
            old?.dispose()
            controlChannel = channel
            channel.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit
                override fun onStateChange() = Unit

                override fun onMessage(buffer: DataChannel.Buffer) {
                    if (buffer.data.remaining() > MAX_CONTROL_PACKET_BYTES) return
                    val bytes = ByteArray(buffer.data.remaining())
                    buffer.data.get(bytes)
                    runCatching { onControlMessage(bytes) }
                }
            })
        }
    }

    private fun transition(next: PcStreamState, detail: String? = null) {
        if (closed.get()) return
        val current = state.get()
        if (current == PcStreamState.FAILED || current == PcStreamState.CLOSED) return
        if (state.getAndSet(next) != next) runCatching { onStateChanged(next, detail) }
    }

    private fun transitionTerminal(next: PcStreamState, detail: String?) {
        while (true) {
            val current = state.get()
            if (current == PcStreamState.FAILED || current == PcStreamState.CLOSED) return
            if (state.compareAndSet(current, next)) {
                runCatching { onStateChanged(next, detail) }
                return
            }
        }
    }

    private fun fail(failure: Throwable) {
        if (!closed.compareAndSet(false, true)) return
        transitionTerminal(PcStreamState.FAILED, failure.message ?: failure.javaClass.simpleName)
        localIceMessages.close(failure)
        offerReady.completeExceptionally(failure)
        releaseResources()
    }

    private fun releaseResources() {
        if (!resourcesReleased.compareAndSet(false, true)) return
        signalWriterJob?.cancel()
        signalReaderJob?.cancel()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        synchronized(resourceLock) {
            val detachedTrack = synchronized(videoLock) {
                val track = remoteVideoTrack
                val sink = videoSink
                if (track != null && sink != null) runCatching { track.removeSink(sink) }
                remoteVideoTrack = null
                videoSink = null
                track
            }
            if (detachedTrack != null) runCatching { onRemoteVideoTrack(null) }
            synchronized(dataChannelLock) {
                controlChannel?.let { channel ->
                    runCatching { channel.unregisterObserver() }
                    runCatching { channel.close() }
                    runCatching { channel.dispose() }
                }
                controlChannel = null
            }
            peerConnection?.let { peer ->
                runCatching { peer.close() }
                runCatching { peer.dispose() }
            }
            peerConnection = null
            peerConnectionFactory?.let { factory -> runCatching { factory.dispose() } }
            peerConnectionFactory = null
        }
    }

    private data class LocalIceMessage(val payload: JSONObject?)

    companion object {
        const val CONTROL_LABEL = "kzdesk-control-v1"
        const val MAX_CONTROL_PACKET_BYTES = 8 * 1024
        private const val CONTROL_PROTOCOL = "kzdesk-input-v1"
        private const val NEGOTIATION_POLL_INTERVAL_MS = 300L
        private const val CONNECTED_POLL_INTERVAL_MS = 1_000L
        private const val INITIAL_RETRY_DELAY_MS = 250L
        private const val MAX_RETRY_DELAY_MS = 4_000L
        private const val DISCONNECT_SIGNAL_TIMEOUT_MS = 1_500L
        private const val MAX_BYE_REASON_CHARS = 128
        private val initializationLock = Any()
        @Volatile private var initialized = false

        private fun defaultIceServers(): List<PeerConnection.IceServer> = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        )

        private fun initializeWebRtc(context: Context) {
            if (initialized) return
            synchronized(initializationLock) {
                if (initialized) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .createInitializationOptions(),
                )
                initialized = true
            }
        }
    }
}
