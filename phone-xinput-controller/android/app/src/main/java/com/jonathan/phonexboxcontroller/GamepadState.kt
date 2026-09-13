package com.jonathan.phonexboxcontroller

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject

data class GamepadState(
    @Volatile var lx: Float = 0f,
    @Volatile var ly: Float = 0f,
    @Volatile var rx: Float = 0f,
    @Volatile var ry: Float = 0f,
    @Volatile var lt: Float = 0f,
    @Volatile var rt: Float = 0f,
    @Volatile var a: Boolean = false,
    @Volatile var b: Boolean = false,
    @Volatile var x: Boolean = false,
    @Volatile var y: Boolean = false,
    @Volatile var lb: Boolean = false,
    @Volatile var rb: Boolean = false,
    @Volatile var l3: Boolean = false,
    @Volatile var r3: Boolean = false,
    @Volatile var up: Boolean = false,
    @Volatile var down: Boolean = false,
    @Volatile var left: Boolean = false,
    @Volatile var right: Boolean = false,
    @Volatile var view: Boolean = false,
    @Volatile var menu: Boolean = false,
    @Volatile var guide: Boolean = false,
) {
    /**
     * V2 wire format (32 bytes, little endian):
     * magic[4] = XPC2, version[1], flags[1], sequence[4], timestampNs[8],
     * LX/LY/RX/RY int16[8], LT/RT uint8[2], buttons uint32[4].
     *
     * It is deliberately tiny and allocation-light compared with JSON. The receiver
     * still accepts the old JSON packets for backwards compatibility.
     */
    fun toBinary(sequence: Int): ByteArray {
        val out = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        out.put('X'.code.toByte())
        out.put('P'.code.toByte())
        out.put('C'.code.toByte())
        out.put('2'.code.toByte())
        out.put(2) // protocol version
        out.put(0) // reserved flags
        out.putInt(sequence)
        out.putLong(System.nanoTime())
        out.putShort(axisToShort(lx))
        out.putShort(axisToShort(ly))
        out.putShort(axisToShort(rx))
        out.putShort(axisToShort(ry))
        out.put(triggerToByte(lt))
        out.put(triggerToByte(rt))
        out.putInt(buttonMask())
        return out.array()
    }

    private fun buttonMask(): Int {
        var m = 0
        if (a) m = m or (1 shl 0)
        if (b) m = m or (1 shl 1)
        if (x) m = m or (1 shl 2)
        if (y) m = m or (1 shl 3)
        if (lb) m = m or (1 shl 4)
        if (rb) m = m or (1 shl 5)
        if (l3) m = m or (1 shl 6)
        if (r3) m = m or (1 shl 7)
        if (up) m = m or (1 shl 8)
        if (down) m = m or (1 shl 9)
        if (left) m = m or (1 shl 10)
        if (right) m = m or (1 shl 11)
        if (view) m = m or (1 shl 12)
        if (menu) m = m or (1 shl 13)
        if (guide) m = m or (1 shl 14)
        return m
    }

    fun toJson(): String = JSONObject().apply {
        put("lx", lx); put("ly", ly); put("rx", rx); put("ry", ry)
        put("lt", lt); put("rt", rt)
        put("a", a); put("b", b); put("x", x); put("y", y)
        put("lb", lb); put("rb", rb); put("l3", l3); put("r3", r3)
        put("up", up); put("down", down); put("left", left); put("right", right)
        put("view", view); put("menu", menu); put("guide", guide)
        put("ts", System.nanoTime())
    }.toString()

    companion object {
        const val PACKET_SIZE = 32

        private fun axisToShort(value: Float): Short {
            val v = value.coerceIn(-1f, 1f)
            return if (v >= 0f) {
                (v * Short.MAX_VALUE).toInt().coerceIn(0, Short.MAX_VALUE.toInt()).toShort()
            } else {
                (v * 32768f).toInt().coerceIn(Short.MIN_VALUE.toInt(), -1).toShort()
            }
        }

        private fun triggerToByte(value: Float): Byte =
            (value.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255).toByte()
    }
}
