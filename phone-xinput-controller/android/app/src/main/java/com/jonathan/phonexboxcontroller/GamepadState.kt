package com.jonathan.phonexboxcontroller

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
    fun toJson(): String = JSONObject().apply {
        put("lx", lx)
        put("ly", ly)
        put("rx", rx)
        put("ry", ry)
        put("lt", lt)
        put("rt", rt)
        put("a", a)
        put("b", b)
        put("x", x)
        put("y", y)
        put("lb", lb)
        put("rb", rb)
        put("l3", l3)
        put("r3", r3)
        put("up", up)
        put("down", down)
        put("left", left)
        put("right", right)
        put("view", view)
        put("menu", menu)
        put("guide", guide)
        put("ts", System.nanoTime())
    }.toString()
}
