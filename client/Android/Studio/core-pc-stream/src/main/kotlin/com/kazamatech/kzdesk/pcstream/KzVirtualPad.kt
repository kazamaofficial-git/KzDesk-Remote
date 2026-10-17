package com.kazamatech.kzdesk.pcstream

import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Inti gamepad virtual yang dipakai **kedua** jalur input KzDesk:
 *  - sesi RDP  : stik/tombol diterjemahkan menjadi pointer, klik, scroll, dan tombol
 *    (lihat pemetaan di sisi app)
 *  - PC/Game Stream: state yang sama dikemas menjadi paket [KzGamepadPacketV1]
 *    dan dikirim lewat data channel WebRTC
 *
 * Kelas ini sengaja murni Kotlin (tanpa import Android) supaya logika deadzone,
 * penskalaan sumbu, dan urutan frame bisa diuji di JVM tanpa emulator.
 */
object KzPadAxis {
    /** Below this magnitude a stick reports exactly zero, so the pointer never drifts. */
    const val DEFAULT_DEADZONE = 0.12f

    /** Fraction of full deflection required before a stick counts as a D-pad direction. */
    const val DPAD_THRESHOLD = 0.5f

    private const val AXIS_SCALE = 32_767f

    /**
     * Radial deadzone with rescaling: the output reaches full deflection at the
     * physical edge instead of jumping from 0 to `deadzone` at the threshold.
     */
    fun deadzone(x: Float, y: Float, deadzone: Float = DEFAULT_DEADZONE): FloatArray {
        require(deadzone in 0f..0.9f) { "deadzone must be within 0..0.9" }
        val magnitude = hypot(x, y)
        if (magnitude <= deadzone || magnitude == 0f) return floatArrayOf(0f, 0f)
        val scaled = ((magnitude - deadzone) / (1f - deadzone)).coerceIn(0f, 1f)
        return floatArrayOf(x / magnitude * scaled, y / magnitude * scaled)
    }

    fun clampUnit(value: Float): Float = value.coerceIn(-1f, 1f)

    /** Map a -1..1 axis onto the int16 range used by the wire format. */
    fun toInt16(value: Float): Int =
        (clampUnit(value) * AXIS_SCALE).roundToInt().coerceIn(-32_768, 32_767)

    /** Map a 0..1 analog trigger onto the 0..255 byte used by the wire format. */
    fun triggerToByte(value: Float): Int =
        (value.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)

    /** D-pad bitmask from a hat/stick position; 0 while centered. */
    fun dpadFromAxis(x: Float, y: Float, threshold: Float = DPAD_THRESHOLD): Int {
        var bits = 0
        if (y <= -threshold) bits = bits or KzGamepadPacketV1.DPAD_UP
        if (y >= threshold) bits = bits or KzGamepadPacketV1.DPAD_DOWN
        if (x <= -threshold) bits = bits or KzGamepadPacketV1.DPAD_LEFT
        if (x >= threshold) bits = bits or KzGamepadPacketV1.DPAD_RIGHT
        return bits
    }
}

/** On-screen control identifiers, each bound to its XInput bit in the wire format. */
enum class KzPadButton(val bit: Int) {
    A(KzGamepadPacketV1.A),
    B(KzGamepadPacketV1.B),
    X(KzGamepadPacketV1.X),
    Y(KzGamepadPacketV1.Y),
    LEFT_SHOULDER(KzGamepadPacketV1.LEFT_SHOULDER),
    RIGHT_SHOULDER(KzGamepadPacketV1.RIGHT_SHOULDER),
    START(KzGamepadPacketV1.START),
    BACK(KzGamepadPacketV1.BACK),
    LEFT_THUMB(KzGamepadPacketV1.LEFT_THUMB),
    RIGHT_THUMB(KzGamepadPacketV1.RIGHT_THUMB),
    DPAD_UP(KzGamepadPacketV1.DPAD_UP),
    DPAD_DOWN(KzGamepadPacketV1.DPAD_DOWN),
    DPAD_LEFT(KzGamepadPacketV1.DPAD_LEFT),
    DPAD_RIGHT(KzGamepadPacketV1.DPAD_RIGHT),
}

/**
 * Holds the live on-screen pad state and turns it into wire frames.
 *
 * Not thread-safe by design: drive it from the UI thread (Compose callbacks) and
 * read [state] from the same thread. Packet transport runs on its own dispatcher.
 */
class KzVirtualPadTracker {
    private var leftX = 0f
    private var leftY = 0f
    private var rightX = 0f
    private var rightY = 0f
    private var leftTrigger = 0f
    private var rightTrigger = 0f
    private var buttons = 0
    private var sequence = 0L
    private var dirty = false

    /** Current normalized state; safe to diff against a previous snapshot. */
    fun state(): KzGamepadState = KzGamepadState(
        buttons = buttons,
        leftTrigger = KzPadAxis.triggerToByte(leftTrigger),
        rightTrigger = KzPadAxis.triggerToByte(rightTrigger),
        leftX = KzPadAxis.toInt16(leftX),
        leftY = KzPadAxis.toInt16(leftY),
        rightX = KzPadAxis.toInt16(rightX),
        rightY = KzPadAxis.toInt16(rightY),
    )

    fun setLeftStick(x: Float, y: Float) {
        val deflected = KzPadAxis.deadzone(x, y)
        if (deflected[0] != leftX || deflected[1] != leftY) {
            leftX = deflected[0]
            leftY = deflected[1]
            dirty = true
        }
    }

    fun setRightStick(x: Float, y: Float) {
        val deflected = KzPadAxis.deadzone(x, y)
        if (deflected[0] != rightX || deflected[1] != rightY) {
            rightX = deflected[0]
            rightY = deflected[1]
            dirty = true
        }
    }

    /** Analog trigger value in 0..1. */
    fun setTrigger(left: Float = leftTrigger, right: Float = rightTrigger) {
        val safeLeft = left.coerceIn(0f, 1f)
        val safeRight = right.coerceIn(0f, 1f)
        if (safeLeft != leftTrigger || safeRight != rightTrigger) {
            leftTrigger = safeLeft
            rightTrigger = safeRight
            dirty = true
        }
    }

    fun setButton(button: KzPadButton, down: Boolean) {
        val next = if (down) buttons or button.bit else buttons and button.bit.inv()
        if (next != buttons) {
            buttons = next
            dirty = true
        }
    }

    fun isPressed(button: KzPadButton): Boolean = (buttons and button.bit) != 0

    /** True when nothing is deflected or held, so callers can skip sending. */
    fun isIdle(): Boolean =
        buttons == 0 && leftX == 0f && leftY == 0f && rightX == 0f && rightY == 0f &&
            leftTrigger == 0f && rightTrigger == 0f

    /**
     * Build the next frame and advance the sequence. The sequence wraps inside
     * uint32; receivers order frames with [KzGamepadPacketV1.isNewerSequence].
     */
    fun nextFrame(timestampMs: Long): KzGamepadFrame {
        sequence = (sequence + 1L) and 0xFFFF_FFFFL
        dirty = false
        return KzGamepadFrame(sequence, timestampMs and 0xFFFF_FFFFL, state())
    }

    /** Reports and clears the change flag, for "send only when something moved". */
    fun consumeDirty(): Boolean {
        val was = dirty
        dirty = false
        return was
    }

    fun reset() {
        leftX = 0f
        leftY = 0f
        rightX = 0f
        rightY = 0f
        leftTrigger = 0f
        rightTrigger = 0f
        buttons = 0
        dirty = false
    }
}

/** Receives pad frames; one implementation per transport. */
fun interface KzGamepadSink {
    fun onFrame(frame: KzGamepadFrame)
}
