package com.kazamatech.kzdesk.pcstream

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Complete virtual-controller state carried as one unordered WebRTC data message. */
data class KzGamepadState(
    val buttons: Int = 0,
    val leftTrigger: Int = 0,
    val rightTrigger: Int = 0,
    val leftX: Int = 0,
    val leftY: Int = 0,
    val rightX: Int = 0,
    val rightY: Int = 0,
)

data class KzGamepadFrame(
    val sequence: Long,
    val timestampMs32: Long,
    val state: KzGamepadState,
)

/**
 * KzDesk Gamepad wire format v1. All multi-byte fields are little-endian.
 * The packet is a full XInput-compatible state snapshot, not a list of button
 * edges; hosts can recover after packet loss by applying the next frame.
 */
object KzGamepadPacketV1 {
    const val BYTE_SIZE = 28
    const val VERSION = 1
    const val TYPE_STATE = 1

    const val DPAD_UP = 0x0001
    const val DPAD_DOWN = 0x0002
    const val DPAD_LEFT = 0x0004
    const val DPAD_RIGHT = 0x0008
    const val START = 0x0010
    const val BACK = 0x0020
    const val LEFT_THUMB = 0x0040
    const val RIGHT_THUMB = 0x0080
    const val LEFT_SHOULDER = 0x0100
    const val RIGHT_SHOULDER = 0x0200
    const val A = 0x1000
    const val B = 0x2000
    const val X = 0x4000
    const val Y = 0x8000

    private const val MAGIC = 0x50475958 // ASCII "XYGP" in little-endian byte order.
    private const val UINT32_MAX = 0xFFFF_FFFFL
    private const val AXIS_MIN = -32_768
    private const val AXIS_MAX = 32_767

    fun encode(sequence: Long, timestampMs: Long, state: KzGamepadState): ByteArray {
        require(sequence in 0..UINT32_MAX) { "sequence must fit uint32" }
        require(timestampMs in 0..UINT32_MAX) { "timestamp must fit uint32 milliseconds" }
        require(state.buttons in 0..0xFFFF) { "buttons must fit uint16" }
        require(state.leftTrigger in 0..255 && state.rightTrigger in 0..255) {
            "trigger values must be in 0..255"
        }
        require(state.leftX in AXIS_MIN..AXIS_MAX && state.leftY in AXIS_MIN..AXIS_MAX &&
            state.rightX in AXIS_MIN..AXIS_MAX && state.rightY in AXIS_MIN..AXIS_MAX
        ) { "axis values must fit int16" }

        return ByteBuffer.allocate(BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(MAGIC)
            put(VERSION.toByte())
            put(TYPE_STATE.toByte())
            putShort(BYTE_SIZE.toShort())
            putInt(sequence.toInt())
            putInt(timestampMs.toInt())
            putShort(state.buttons.toShort())
            put(state.leftTrigger.toByte())
            put(state.rightTrigger.toByte())
            putShort(state.leftX.toShort())
            putShort(state.leftY.toShort())
            putShort(state.rightX.toShort())
            putShort(state.rightY.toShort())
        }.array()
    }

    fun decode(packet: ByteArray): KzGamepadFrame {
        require(packet.size == BYTE_SIZE) { "invalid gamepad packet length" }
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.int == MAGIC) { "invalid gamepad packet magic" }
        require((buffer.get().toInt() and 0xFF) == VERSION) { "unsupported gamepad packet version" }
        require((buffer.get().toInt() and 0xFF) == TYPE_STATE) { "unsupported gamepad packet type" }
        require((buffer.short.toInt() and 0xFFFF) == BYTE_SIZE) { "invalid gamepad packet header length" }
        val sequence = buffer.int.toLong() and UINT32_MAX
        val timestampMs = buffer.int.toLong() and UINT32_MAX
        val buttons = buffer.short.toInt() and 0xFFFF
        val leftTrigger = buffer.get().toInt() and 0xFF
        val rightTrigger = buffer.get().toInt() and 0xFF
        val state = KzGamepadState(
            buttons = buttons,
            leftTrigger = leftTrigger,
            rightTrigger = rightTrigger,
            leftX = buffer.short.toInt(),
            leftY = buffer.short.toInt(),
            rightX = buffer.short.toInt(),
            rightY = buffer.short.toInt(),
        )
        return KzGamepadFrame(sequence, timestampMs, state)
    }

    /** True when [candidate] follows [previous] under uint32 wraparound ordering. */
    fun isNewerSequence(candidate: Long, previous: Long): Boolean {
        require(candidate in 0..UINT32_MAX && previous in 0..UINT32_MAX) {
            "sequences must fit uint32"
        }
        val difference = (candidate - previous) and UINT32_MAX
        return difference != 0L && difference < 0x8000_0000L
    }
}
