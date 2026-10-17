package com.kazamatech.kzdesk.pcstream

/**
 * PC/Game Stream sink: sends virtual-pad frames over the WebRTC data channel.
 *
 * Kept in its own file (separate from the pure-Kotlin [KzVirtualPadTracker]) so
 * the pad math stays compilable and testable on a plain JVM without the Android
 * or WebRTC classpath.
 *
 * The viewer is resolved lazily because a PC-stream session may not exist yet;
 * frames are dropped silently until a viewer is attached.
 */
class KzPcStreamGamepadSink(private val viewer: () -> PcStreamViewer?) : KzGamepadSink {
    override fun onFrame(frame: KzGamepadFrame) {
        viewer()?.sendGamepadState(frame.sequence, frame.timestampMs32, frame.state)
    }
}
