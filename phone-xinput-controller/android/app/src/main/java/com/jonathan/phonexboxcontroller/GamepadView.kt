package com.jonathan.phonexboxcontroller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

class GamepadView(context: Context) : View(context) {
    val state = GamepadState()

    var sensitivity = 1.0f
    var deadzone = 0.08f
    var vibrationEnabled = true

    private val vibrator = context.getSystemService(Vibrator::class.java)

    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(10, 12, 16) }
    private val shellShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(85, 0, 0, 0) }
    private val shell = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(239, 241, 244) }
    private val shellEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.rgb(188, 193, 200)
    }
    private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 33, 39) }
    private val dark2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(49, 53, 61) }
    private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(13, 14, 17) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.rgb(111, 116, 126)
    }
    private val whiteText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val blackText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(35, 38, 44)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private data class CircleButton(val key: String, val x: Float, val y: Float, val r: Float, val label: String)
    private data class RectButton(val key: String, val rect: RectF, val label: String)

    private val pointerKeys = mutableMapOf<Int, String>()
    private var leftStickPointer: Int? = null
    private var rightStickPointer: Int? = null

    private var leftCx = 0f
    private var leftCy = 0f
    private var rightCx = 0f
    private var rightCy = 0f
    private var stickRadius = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val m = min(w, h)

        canvas.drawRect(0f, 0f, w, h, background)
        drawControllerShell(canvas, w, h)

        // Phone-first ergonomics while preserving the Xbox layout relationship.
        // These positions match where both thumbs naturally fall while gripping
        // a rectangular phone in landscape with the hands near the lower corners.
        leftCx = w * 0.280f
        leftCy = h * 0.600f
        rightCx = w * 0.740f
        rightCy = h * 0.760f
        stickRadius = m * 0.112f

        drawStick(canvas, leftCx, leftCy, stickRadius, state.lx, -state.ly)
        drawStick(canvas, rightCx, rightCy, stickRadius, state.rx, -state.ry)

        drawDpad(canvas, w * 0.390f, h * 0.795f, m * 0.067f)
        drawFaceButtons(canvas, w, h, m)
        drawCenterButtons(canvas, w, h, m)
        drawShoulders(canvas, w, h)
    }

    private fun drawControllerShell(canvas: Canvas, w: Float, h: Float) {
        val p = Path().apply {
            moveTo(w * 0.17f, h * 0.12f)
            cubicTo(w * 0.10f, h * 0.13f, w * 0.055f, h * 0.28f, w * 0.045f, h * 0.48f)
            cubicTo(w * 0.035f, h * 0.68f, w * 0.06f, h * 0.88f, w * 0.14f, h * 0.94f)
            cubicTo(w * 0.19f, h * 0.98f, w * 0.255f, h * 0.84f, w * 0.30f, h * 0.74f)
            cubicTo(w * 0.345f, h * 0.645f, w * 0.405f, h * 0.62f, w * 0.50f, h * 0.62f)
            cubicTo(w * 0.595f, h * 0.62f, w * 0.655f, h * 0.645f, w * 0.70f, h * 0.74f)
            cubicTo(w * 0.745f, h * 0.84f, w * 0.81f, h * 0.98f, w * 0.86f, h * 0.94f)
            cubicTo(w * 0.94f, h * 0.88f, w * 0.965f, h * 0.68f, w * 0.955f, h * 0.48f)
            cubicTo(w * 0.945f, h * 0.28f, w * 0.90f, h * 0.13f, w * 0.83f, h * 0.12f)
            cubicTo(w * 0.74f, h * 0.095f, w * 0.65f, h * 0.105f, w * 0.59f, h * 0.15f)
            cubicTo(w * 0.545f, h * 0.115f, w * 0.455f, h * 0.115f, w * 0.41f, h * 0.15f)
            cubicTo(w * 0.35f, h * 0.105f, w * 0.26f, h * 0.095f, w * 0.17f, h * 0.12f)
            close()
        }

        canvas.save()
        canvas.translate(0f, 8f)
        canvas.drawPath(p, shellShadow)
        canvas.restore()
        canvas.drawPath(p, shell)
        canvas.drawPath(p, shellEdge)
    }

    private fun drawShoulders(canvas: Canvas, w: Float, h: Float) {
        val triggerW = w * 0.145f
        val triggerH = h * 0.075f
        val bumperW = w * 0.155f
        val bumperH = h * 0.064f

        val lt = RectButton("lt", RectF(w * 0.065f, h * 0.035f, w * 0.065f + triggerW, h * 0.035f + triggerH), "LT")
        val lb = RectButton("lb", RectF(w * 0.073f, h * 0.125f, w * 0.073f + bumperW, h * 0.125f + bumperH), "LB")
        val rt = RectButton("rt", RectF(w * 0.79f, h * 0.035f, w * 0.79f + triggerW, h * 0.035f + triggerH), "RT")
        val rb = RectButton("rb", RectF(w * 0.772f, h * 0.125f, w * 0.772f + bumperW, h * 0.125f + bumperH), "RB")

        listOf(lt, lb, rt, rb).forEach { b ->
            val active = getButton(b.key)
            val fill = if (active) Color.rgb(55, 185, 70) else Color.rgb(38, 41, 47)
            dark.color = fill
            canvas.drawRoundRect(b.rect, 22f, 22f, dark)
            ring.color = if (active) Color.rgb(113, 235, 123) else Color.rgb(90, 95, 104)
            ring.strokeWidth = 3f
            canvas.drawRoundRect(b.rect, 22f, 22f, ring)
            whiteText.textSize = h * 0.034f
            canvas.drawText(b.label, b.rect.centerX(), b.rect.centerY() + whiteText.textSize * 0.34f, whiteText)
        }
    }

    private fun drawStick(canvas: Canvas, cx: Float, cy: Float, r: Float, vx: Float, vy: Float) {
        canvas.drawCircle(cx, cy, r * 1.08f, shell)
        ring.color = Color.rgb(183, 187, 194)
        ring.strokeWidth = 4f
        canvas.drawCircle(cx, cy, r * 1.08f, ring)
        canvas.drawCircle(cx, cy, r * 0.95f, black)
        canvas.drawCircle(cx, cy, r * 0.77f, dark2)

        val kx = cx + vx * r * 0.46f
        val ky = cy + vy * r * 0.46f
        canvas.drawCircle(kx, ky, r * 0.50f, black)
        ring.color = Color.rgb(77, 81, 90)
        ring.strokeWidth = 4f
        canvas.drawCircle(kx, ky, r * 0.50f, ring)
        ring.color = Color.rgb(48, 51, 58)
        ring.strokeWidth = 2f
        canvas.drawCircle(kx, ky, r * 0.37f, ring)
    }

    private fun drawFaceButtons(canvas: Canvas, w: Float, h: Float, m: Float) {
        val r = m * 0.052f
        val fx = w * 0.835f
        val fy = h * 0.455f
        val gap = r * 1.55f
        val buttons = listOf(
            CircleButton("y", fx, fy - gap, r, "Y"),
            CircleButton("a", fx, fy + gap, r, "A"),
            CircleButton("x", fx - gap, fy, r, "X"),
            CircleButton("b", fx + gap, fy, r, "B"),
        )
        buttons.forEach { drawFaceButton(canvas, it) }
    }

    private fun faceColor(key: String): Int = when (key) {
        "a" -> Color.rgb(80, 194, 62)
        "b" -> Color.rgb(229, 64, 64)
        "x" -> Color.rgb(49, 157, 219)
        "y" -> Color.rgb(245, 210, 53)
        else -> Color.WHITE
    }

    private fun drawFaceButton(canvas: Canvas, b: CircleButton) {
        val active = getButton(b.key)
        canvas.drawCircle(b.x, b.y, b.r * 1.10f, if (active) shell else dark2)
        canvas.drawCircle(b.x, b.y, b.r, black)
        ring.color = if (active) Color.rgb(120, 236, 132) else Color.rgb(87, 91, 99)
        ring.strokeWidth = if (active) 6f else 3f
        canvas.drawCircle(b.x, b.y, b.r, ring)

        whiteText.color = faceColor(b.key)
        whiteText.textSize = b.r * 0.95f
        canvas.drawText(b.label, b.x, b.y + whiteText.textSize * 0.34f, whiteText)
        whiteText.color = Color.WHITE
    }

    private fun drawCenterButtons(canvas: Canvas, w: Float, h: Float, m: Float) {
        val smallR = m * 0.031f
        val guideR = m * 0.050f

        drawCenterButton(canvas, CircleButton("view", w * 0.445f, h * 0.455f, smallR, "▣"), false)
        drawCenterButton(canvas, CircleButton("menu", w * 0.555f, h * 0.455f, smallR, "≡"), false)
        drawCenterButton(canvas, CircleButton("guide", w * 0.500f, h * 0.325f, guideR, "X"), true)
    }

    private fun drawCenterButton(canvas: Canvas, b: CircleButton, guide: Boolean) {
        val active = getButton(b.key)
        val fill = if (guide) Color.rgb(20, 21, 24) else Color.rgb(223, 226, 231)
        dark.color = if (active) Color.rgb(76, 193, 84) else fill
        canvas.drawCircle(b.x, b.y, b.r, dark)
        ring.color = if (active) Color.rgb(132, 244, 140) else Color.rgb(174, 179, 187)
        ring.strokeWidth = 3f
        canvas.drawCircle(b.x, b.y, b.r, ring)

        val p = if (guide) whiteText else blackText
        p.textSize = if (guide) b.r * 0.78f else b.r * 0.82f
        canvas.drawText(b.label, b.x, b.y + p.textSize * 0.34f, p)
    }

    private fun drawDpad(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val arm = s * 0.62f
        val length = s * 1.55f
        val p = Path().apply {
            moveTo(cx - arm, cy - arm)
            lineTo(cx - arm, cy - length)
            lineTo(cx + arm, cy - length)
            lineTo(cx + arm, cy - arm)
            lineTo(cx + length, cy - arm)
            lineTo(cx + length, cy + arm)
            lineTo(cx + arm, cy + arm)
            lineTo(cx + arm, cy + length)
            lineTo(cx - arm, cy + length)
            lineTo(cx - arm, cy + arm)
            lineTo(cx - length, cy + arm)
            lineTo(cx - length, cy - arm)
            close()
        }
        canvas.drawPath(p, black)
        ring.color = Color.rgb(86, 90, 98)
        ring.strokeWidth = 3f
        canvas.drawPath(p, ring)

        if (state.up) drawDpadHighlight(canvas, RectF(cx - arm, cy - length, cx + arm, cy - arm))
        if (state.down) drawDpadHighlight(canvas, RectF(cx - arm, cy + arm, cx + arm, cy + length))
        if (state.left) drawDpadHighlight(canvas, RectF(cx - length, cy - arm, cx - arm, cy + arm))
        if (state.right) drawDpadHighlight(canvas, RectF(cx + arm, cy - arm, cx + length, cy + arm))

        canvas.drawCircle(cx, cy, s * 0.46f, dark2)
    }

    private fun drawDpadHighlight(canvas: Canvas, rect: RectF) {
        dark.color = Color.rgb(64, 181, 78)
        canvas.drawRoundRect(rect, 10f, 10f, dark)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        for (i in 0 until event.pointerCount) {
            val id = event.getPointerId(i)
            val x = event.getX(i)
            val y = event.getY(i)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.actionIndex != i) continue
                    assignPointer(id, x, y)
                }
                MotionEvent.ACTION_MOVE -> updatePointer(id, x, y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    if (event.actionMasked != MotionEvent.ACTION_CANCEL && event.actionIndex != i) continue
                    releasePointer(id)
                }
            }
        }
        invalidate()
        return true
    }

    private fun assignPointer(id: Int, x: Float, y: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val m = min(w, h)

        // Generous invisible hit areas make the phone feel less like a flat screen.
        if (hypot(x - leftCx, y - leftCy) <= stickRadius * 1.55f && leftStickPointer == null) {
            leftStickPointer = id
            pointerKeys[id] = "lstick"
            updatePointer(id, x, y)
            buzz()
            return
        }
        if (hypot(x - rightCx, y - rightCy) <= stickRadius * 1.55f && rightStickPointer == null) {
            rightStickPointer = id
            pointerKeys[id] = "rstick"
            updatePointer(id, x, y)
            buzz()
            return
        }

        val r = m * 0.052f
        val fx = w * 0.835f
        val fy = h * 0.455f
        val gap = r * 1.55f
        val circles = listOf(
            CircleButton("y", fx, fy - gap, r, "Y"),
            CircleButton("a", fx, fy + gap, r, "A"),
            CircleButton("x", fx - gap, fy, r, "X"),
            CircleButton("b", fx + gap, fy, r, "B"),
            CircleButton("view", w * 0.445f, h * 0.455f, m * 0.031f, "▣"),
            CircleButton("guide", w * 0.500f, h * 0.325f, m * 0.050f, "X"),
            CircleButton("menu", w * 0.555f, h * 0.455f, m * 0.031f, "≡"),
        )
        circles.firstOrNull { hypot(x - it.x, y - it.y) <= it.r * 1.50f }?.let {
            pointerKeys[id] = it.key
            setButton(it.key, true)
            buzz()
            return
        }

        val triggerW = w * 0.145f
        val triggerH = h * 0.075f
        val bumperW = w * 0.155f
        val bumperH = h * 0.064f
        val rects = listOf(
            RectButton("lt", RectF(w * 0.065f, h * 0.035f, w * 0.065f + triggerW, h * 0.035f + triggerH), "LT"),
            RectButton("lb", RectF(w * 0.073f, h * 0.125f, w * 0.073f + bumperW, h * 0.125f + bumperH), "LB"),
            RectButton("rt", RectF(w * 0.79f, h * 0.035f, w * 0.79f + triggerW, h * 0.035f + triggerH), "RT"),
            RectButton("rb", RectF(w * 0.772f, h * 0.125f, w * 0.772f + bumperW, h * 0.125f + bumperH), "RB"),
        )
        rects.firstOrNull { it.rect.contains(x, y) }?.let {
            pointerKeys[id] = it.key
            setButton(it.key, true)
            buzz()
            return
        }

        val dcx = w * 0.390f
        val dcy = h * 0.795f
        val s = m * 0.067f
        val arm = s * 0.76f
        val length = s * 1.88f
        val dpad = listOf(
            "up" to RectF(dcx - arm, dcy - length, dcx + arm, dcy - arm * 0.20f),
            "down" to RectF(dcx - arm, dcy + arm * 0.20f, dcx + arm, dcy + length),
            "left" to RectF(dcx - length, dcy - arm, dcx - arm * 0.20f, dcy + arm),
            "right" to RectF(dcx + arm * 0.20f, dcy - arm, dcx + length, dcy + arm),
        )
        dpad.firstOrNull { it.second.contains(x, y) }?.let {
            pointerKeys[id] = it.first
            setButton(it.first, true)
            buzz()
        }
    }

    private fun updatePointer(id: Int, x: Float, y: Float) {
        when (pointerKeys[id]) {
            "lstick" -> updateStick(true, x, y)
            "rstick" -> updateStick(false, x, y)
        }
    }

    private fun updateStick(left: Boolean, x: Float, y: Float) {
        val cx = if (left) leftCx else rightCx
        val cy = if (left) leftCy else rightCy
        var nx = (x - cx) / stickRadius
        var ny = (cy - y) / stickRadius
        val mag = hypot(nx, ny)
        if (mag > 1f) {
            nx /= mag
            ny /= mag
        }
        val dz = deadzone.coerceIn(0f, 0.35f)
        fun curve(v: Float): Float {
            val a = kotlin.math.abs(v)
            if (a < dz) return 0f
            val n = ((a - dz) / (1f - dz)).coerceIn(0f, 1f)
            return kotlin.math.sign(v) * (n * sensitivity).coerceIn(0f, 1f)
        }
        nx = curve(nx)
        ny = curve(ny)
        if (left) {
            state.lx = nx
            state.ly = ny
        } else {
            state.rx = nx
            state.ry = ny
        }
    }

    private fun releasePointer(id: Int) {
        when (val key = pointerKeys.remove(id)) {
            "lstick" -> {
                state.lx = 0f
                state.ly = 0f
                leftStickPointer = null
            }
            "rstick" -> {
                state.rx = 0f
                state.ry = 0f
                rightStickPointer = null
            }
            null -> Unit
            else -> setButton(key, false)
        }
    }

    private fun setButton(key: String, down: Boolean) {
        when (key) {
            "a" -> state.a = down
            "b" -> state.b = down
            "x" -> state.x = down
            "y" -> state.y = down
            "lb" -> state.lb = down
            "rb" -> state.rb = down
            "lt" -> state.lt = if (down) 1f else 0f
            "rt" -> state.rt = if (down) 1f else 0f
            "up" -> state.up = down
            "down" -> state.down = down
            "left" -> state.left = down
            "right" -> state.right = down
            "view" -> state.view = down
            "menu" -> state.menu = down
            "guide" -> state.guide = down
            "l3" -> state.l3 = down
            "r3" -> state.r3 = down
        }
    }

    private fun getButton(key: String): Boolean = when (key) {
        "a" -> state.a
        "b" -> state.b
        "x" -> state.x
        "y" -> state.y
        "lb" -> state.lb
        "rb" -> state.rb
        "lt" -> state.lt > 0.5f
        "rt" -> state.rt > 0.5f
        "up" -> state.up
        "down" -> state.down
        "left" -> state.left
        "right" -> state.right
        "view" -> state.view
        "menu" -> state.menu
        "guide" -> state.guide
        else -> false
    }

    private fun buzz() {
        if (!vibrationEnabled || vibrator == null || !vibrator.hasVibrator()) return
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(16, 70))
        } catch (_: Throwable) {
        }
    }
}
