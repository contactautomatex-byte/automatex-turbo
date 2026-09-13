package com.jonathan.phonexboxcontroller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

class GamepadView(context: Context) : View(context) {
    val state = GamepadState()

    // Phone-first tuning. Movement behaves like a floating joystick; aim behaves
    // like a relative touch pad so touching the screen never kicks the camera.
    var moveSensitivity = 1.0f
    var aimSensitivity = 0.78f
    var moveDeadzone = 0.10f
    var vibrationEnabled = true

    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

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
    private val aimZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(18, 80, 170, 255)
    }

    private data class CircleButton(val key: String, val x: Float, val y: Float, val r: Float, val label: String)
    private data class RectButton(val key: String, val rect: RectF, val label: String)

    private val pointerKeys = mutableMapOf<Int, String>()
    private var leftStickPointer: Int? = null
    private var rightAimPointer: Int? = null

    private var leftHomeCx = 0f
    private var leftHomeCy = 0f
    private var rightHomeCx = 0f
    private var rightHomeCy = 0f
    private var leftBaseCx = 0f
    private var leftBaseCy = 0f
    private var stickRadius = 0f

    private var rightLastX = 0f
    private var rightLastY = 0f
    private var filteredRx = 0f
    private var filteredRy = 0f
    private var lastAimMoveAt = 0L
    private var aimDecayPosted = false

    private val aimDecayRunnable = object : Runnable {
        override fun run() {
            if (rightAimPointer == null) {
                aimDecayPosted = false
                return
            }

            val idleMs = SystemClock.uptimeMillis() - lastAimMoveAt
            if (idleMs >= 42L) {
                filteredRx *= 0.34f
                filteredRy *= 0.34f
                if (abs(filteredRx) < 0.008f) filteredRx = 0f
                if (abs(filteredRy) < 0.008f) filteredRy = 0f
                state.rx = filteredRx
                state.ry = filteredRy
                invalidate()
            }
            mainHandler.postDelayed(this, 16L)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val m = min(w, h)

        canvas.drawRect(0f, 0f, w, h, background)
        drawControllerShell(canvas, w, h)

        // Positions chosen from the user's natural thumb-rest locations on a phone.
        leftHomeCx = w * 0.280f
        leftHomeCy = h * 0.600f
        rightHomeCx = w * 0.740f
        rightHomeCy = h * 0.760f
        stickRadius = m * 0.112f

        if (leftStickPointer == null) {
            leftBaseCx = leftHomeCx
            leftBaseCy = leftHomeCy
        }

        drawStick(canvas, leftBaseCx, leftBaseCy, stickRadius, state.lx, -state.ly)
        drawStick(canvas, rightHomeCx, rightHomeCy, stickRadius, state.rx, -state.ry)

        // Very subtle indication that the lower-right surface accepts relative aim.
        if (rightAimPointer != null) {
            canvas.drawRoundRect(
                RectF(w * 0.53f, h * 0.31f, w * 0.965f, h * 0.94f),
                38f,
                38f,
                aimZonePaint,
            )
        }

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
        shoulderButtons(w, h).forEach { b ->
            val active = getButton(b.key)
            dark.color = if (active) Color.rgb(55, 185, 70) else Color.rgb(38, 41, 47)
            canvas.drawRoundRect(b.rect, 22f, 22f, dark)
            ring.color = if (active) Color.rgb(113, 235, 123) else Color.rgb(90, 95, 104)
            ring.strokeWidth = 3f
            canvas.drawRoundRect(b.rect, 22f, 22f, ring)
            whiteText.textSize = h * 0.034f
            canvas.drawText(b.label, b.rect.centerX(), b.rect.centerY() + whiteText.textSize * 0.34f, whiteText)
        }
    }

    private fun shoulderButtons(w: Float, h: Float): List<RectButton> {
        val triggerW = w * 0.145f
        val triggerH = h * 0.075f
        val bumperW = w * 0.155f
        val bumperH = h * 0.064f
        return listOf(
            RectButton("lt", RectF(w * 0.065f, h * 0.035f, w * 0.065f + triggerW, h * 0.035f + triggerH), "LT"),
            RectButton("lb", RectF(w * 0.073f, h * 0.125f, w * 0.073f + bumperW, h * 0.125f + bumperH), "LB"),
            RectButton("rt", RectF(w * 0.79f, h * 0.035f, w * 0.79f + triggerW, h * 0.035f + triggerH), "RT"),
            RectButton("rb", RectF(w * 0.772f, h * 0.125f, w * 0.772f + bumperW, h * 0.125f + bumperH), "RB"),
        )
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

    private fun faceButtons(w: Float, h: Float, m: Float): List<CircleButton> {
        val r = m * 0.052f
        val fx = w * 0.835f
        val fy = h * 0.455f
        val gap = r * 1.55f
        return listOf(
            CircleButton("y", fx, fy - gap, r, "Y"),
            CircleButton("a", fx, fy + gap, r, "A"),
            CircleButton("x", fx - gap, fy, r, "X"),
            CircleButton("b", fx + gap, fy, r, "B"),
        )
    }

    private fun drawFaceButtons(canvas: Canvas, w: Float, h: Float, m: Float) {
        faceButtons(w, h, m).forEach { drawFaceButton(canvas, it) }
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

    private fun centerButtons(w: Float, h: Float, m: Float): List<CircleButton> {
        val smallR = m * 0.031f
        val guideR = m * 0.050f
        return listOf(
            CircleButton("view", w * 0.445f, h * 0.455f, smallR, "▣"),
            CircleButton("menu", w * 0.555f, h * 0.455f, smallR, "≡"),
            CircleButton("guide", w * 0.500f, h * 0.325f, guideR, "X"),
        )
    }

    private fun drawCenterButtons(canvas: Canvas, w: Float, h: Float, m: Float) {
        centerButtons(w, h, m).forEach { drawCenterButton(canvas, it, it.key == "guide") }
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
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                assignPointer(event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                reconcilePointers(event)
                for (i in 0 until event.pointerCount) {
                    updatePointer(event.getPointerId(i), event.getX(i), event.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                releasePointer(event.getPointerId(i))
            }
            MotionEvent.ACTION_CANCEL -> resetAllInputs()
        }
        invalidate()
        return true
    }

    private fun assignPointer(id: Int, x: Float, y: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val m = min(w, h)

        // Discrete buttons always win over the invisible thumb zones.
        shoulderButtons(w, h).firstOrNull { it.rect.contains(x, y) }?.let {
            pointerKeys[id] = it.key
            setButton(it.key, true)
            buzz()
            return
        }

        (faceButtons(w, h, m) + centerButtons(w, h, m))
            .firstOrNull { hypot(x - it.x, y - it.y) <= it.r * 1.50f }
            ?.let {
                pointerKeys[id] = it.key
                setButton(it.key, true)
                buzz()
                return
            }

        dpadHit(w, h, m, x, y)?.let {
            pointerKeys[id] = it
            setButton(it, true)
            buzz()
            return
        }

        // Left: floating origin. A touch is always neutral; movement only starts
        // after the finger actually drags away from the touch-down point.
        if (x < w * 0.49f && y > h * 0.29f && leftStickPointer == null) {
            leftStickPointer = id
            pointerKeys[id] = "lstick"
            leftBaseCx = x
            leftBaseCy = y
            state.lx = 0f
            state.ly = 0f
            buzz()
            return
        }

        // Right: relative aim surface. Touch-down is neutral, so tapping the screen
        // can never throw the camera up/down. Only finger movement produces RX/RY.
        if (x > w * 0.51f && y > h * 0.29f && rightAimPointer == null) {
            rightAimPointer = id
            pointerKeys[id] = "raim"
            rightLastX = x
            rightLastY = y
            filteredRx = 0f
            filteredRy = 0f
            state.rx = 0f
            state.ry = 0f
            lastAimMoveAt = SystemClock.uptimeMillis()
            ensureAimDecay()
            buzz()
        }
    }

    private fun dpadHit(w: Float, h: Float, m: Float, x: Float, y: Float): String? {
        val dcx = w * 0.390f
        val dcy = h * 0.795f
        val s = m * 0.067f
        val arm = s * 0.76f
        val length = s * 1.88f
        val areas = listOf(
            "up" to RectF(dcx - arm, dcy - length, dcx + arm, dcy - arm * 0.20f),
            "down" to RectF(dcx - arm, dcy + arm * 0.20f, dcx + arm, dcy + length),
            "left" to RectF(dcx - length, dcy - arm, dcx - arm * 0.20f, dcy + arm),
            "right" to RectF(dcx + arm * 0.20f, dcy - arm, dcx + length, dcy + arm),
        )
        return areas.firstOrNull { it.second.contains(x, y) }?.first
    }

    private fun updatePointer(id: Int, x: Float, y: Float) {
        when (pointerKeys[id]) {
            "lstick" -> updateMoveStick(x, y)
            "raim" -> updateRelativeAim(x, y)
        }
    }

    private fun updateMoveStick(x: Float, y: Float) {
        var dx = x - leftBaseCx
        var dy = leftBaseCy - y
        var mag = hypot(dx, dy)

        // If the thumb travels past the virtual gate, let the floating base follow
        // slightly. This avoids forcing the player to stretch across the glass.
        if (mag > stickRadius * 1.18f) {
            val overshoot = mag - stickRadius
            val ux = dx / mag
            val uyScreen = (y - leftBaseCy) / mag
            leftBaseCx += ux * overshoot * 0.62f
            leftBaseCy += uyScreen * overshoot * 0.62f
            dx = x - leftBaseCx
            dy = leftBaseCy - y
            mag = hypot(dx, dy)
        }

        if (mag <= 0.001f) {
            state.lx = 0f
            state.ly = 0f
            return
        }

        val normalizedMag = (mag / stickRadius).coerceIn(0f, 1f)
        val dz = moveDeadzone.coerceIn(0.03f, 0.30f)
        if (normalizedMag <= dz) {
            state.lx = 0f
            state.ly = 0f
            return
        }

        val radial = ((normalizedMag - dz) / (1f - dz)).coerceIn(0f, 1f)
        val curved = radial.pow(1.10f) * moveSensitivity.coerceIn(0.55f, 1.35f)
        val unitX = dx / mag
        val unitY = dy / mag
        state.lx = (unitX * curved).coerceIn(-1f, 1f)
        state.ly = (unitY * curved).coerceIn(-1f, 1f)
    }

    private fun updateRelativeAim(x: Float, y: Float) {
        val m = min(width.toFloat(), height.toFloat()).coerceAtLeast(1f)
        val dx = x - rightLastX
        val dy = y - rightLastY
        rightLastX = x
        rightLastY = y
        lastAimMoveAt = SystemClock.uptimeMillis()

        // Distance-per-event becomes right-stick velocity. The power curve makes
        // tiny thumb corrections precise while allowing a fast swipe to turn quickly.
        val scale = m * 0.058f
        val rawX = (dx / scale).coerceIn(-1f, 1f)
        val rawY = (-dy / scale).coerceIn(-1f, 1f)
        val targetX = aimCurve(rawX)
        val targetY = aimCurve(rawY)

        val alpha = 0.62f
        filteredRx = filteredRx * (1f - alpha) + targetX * alpha
        filteredRy = filteredRy * (1f - alpha) + targetY * alpha
        state.rx = filteredRx.coerceIn(-1f, 1f)
        state.ry = filteredRy.coerceIn(-1f, 1f)
        ensureAimDecay()
    }

    private fun aimCurve(value: Float): Float {
        val a = abs(value)
        if (a < 0.018f) return 0f
        val precision = a.pow(1.34f)
        return (sign(value) * precision * aimSensitivity.coerceIn(0.30f, 1.50f)).coerceIn(-1f, 1f)
    }

    private fun ensureAimDecay() {
        if (aimDecayPosted) return
        aimDecayPosted = true
        mainHandler.post(aimDecayRunnable)
    }

    private fun reconcilePointers(event: MotionEvent) {
        val active = HashSet<Int>()
        for (i in 0 until event.pointerCount) active.add(event.getPointerId(i))
        pointerKeys.keys.filter { it !in active }.toList().forEach { releasePointer(it) }
    }

    private fun releasePointer(id: Int) {
        when (val key = pointerKeys.remove(id)) {
            "lstick" -> {
                state.lx = 0f
                state.ly = 0f
                leftStickPointer = null
                leftBaseCx = leftHomeCx
                leftBaseCy = leftHomeCy
            }
            "raim" -> {
                state.rx = 0f
                state.ry = 0f
                filteredRx = 0f
                filteredRy = 0f
                rightAimPointer = null
                aimDecayPosted = false
                mainHandler.removeCallbacks(aimDecayRunnable)
            }
            null -> Unit
            else -> setButton(key, false)
        }
    }

    fun resetAllInputs() {
        pointerKeys.clear()
        leftStickPointer = null
        rightAimPointer = null
        aimDecayPosted = false
        mainHandler.removeCallbacks(aimDecayRunnable)
        filteredRx = 0f
        filteredRy = 0f
        state.lx = 0f
        state.ly = 0f
        state.rx = 0f
        state.ry = 0f
        state.lt = 0f
        state.rt = 0f
        state.a = false
        state.b = false
        state.x = false
        state.y = false
        state.lb = false
        state.rb = false
        state.l3 = false
        state.r3 = false
        state.up = false
        state.down = false
        state.left = false
        state.right = false
        state.view = false
        state.menu = false
        state.guide = false
        invalidate()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) resetAllInputs()
    }

    override fun onDetachedFromWindow() {
        resetAllInputs()
        super.onDetachedFromWindow()
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
            vibrator.vibrate(VibrationEffect.createOneShot(12, 55))
        } catch (_: Throwable) {
        }
    }
}
