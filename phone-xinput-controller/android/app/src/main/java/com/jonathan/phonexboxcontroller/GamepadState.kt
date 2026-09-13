package com.jonathan.phonexboxcontroller

import org.json.JSONObject

data class GamepadState(
    var lx: Float = 0f,
    var ly: Float = 0f,
    var rx: Float = 0f,
    var ry: Float = 0f,
    var lt: Float = 0f,
    var rt: Float = 0f,
    var a: Boolean = false,
    var b: Boolean = false,
    var x: Boolean = false,
    var y: Boolean = false,
    var lb: Boolean = false,
    var rb: Boolean = false,
    var l3: Boolean = false,
    var r3: Boolean = false,
    var up: Boolean = false,
    var down: Boolean = false,
    var left: Boolean = false,
    var right: Boolean = false,
    var view: Boolean = false,
    var menu: Boolean = false,
    var guide: Boolean = false,
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
