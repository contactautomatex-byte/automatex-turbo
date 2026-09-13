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
import kotlin.math.pow

/**
 * Phone-first XInput gamepad.
 *
 * The screen is not treated like a physical Xbox shell. Instead, the touch layer
 * uses two real virtual thumbsticks: touch-down creates a neutral origin, drag
 * creates a persistent stick deflection, and release returns immediately to 0.
 * This matches XInput semantics and is much more reliable for FPS camera control
 * than sending short relative swipe pulses.
 */
class GamepadView(context: Context) : View(context) {
    val state = GamepadState()

    var moveSensitivity = 1.0f
    var aimSensitivity = 0.90f
    var moveDeadzone = 0.08f
    var vibrationEnabled = true

    private val vibrator = context.getSystemService(Vibrator::class.java)

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(10, 12, 17) }
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(18, 22, 30) }
    private val panelEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.rgb(52, 60, 74)
    }
    private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(31, 35, 43) }
    private val dark2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(48, 54, 66) }
    private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(11, 13, 17) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.rgb(104, 113, 132)
    }
    private val zone = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(72, 122, 150, 190)
    }
    private val whiteText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val subtleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 160, 178)
        textAlign = Paint.Align.CENTER
    }

    private data class CircleButton(
        val key: String,
        val x: Float,
        val y: Float,
        val r: Float,
        val label: String,
    )

    private data class RectButton(
        val key: String,
        val rect: RectF,
        val label: String,
    )

    private val pointerKeys = mutableMapOf<Int, String>()
    private var leftStickPointer: Int? = null
    private var rightStickPointer: Int? = null

    private var leftHomeX = 0f
    private var leftHomeY = 0f
    private var rightHomeX = 0f
    private var rightHomeY = 0f
    private var leftBaseX = 0f
    private var leftBaseY = 0f
    private var rightBaseX = 0f
    private var rightBaseY = 0f
    private var stickRadius = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val m = min(w, h)

        canvas.drawRect(0f, 0f, w, h, bg)

        // Large functional grip areas instead of a decorative controller silhouette.
        val leftPanel = RectF(w * 0.025f, h * 0.15f, w * 0.485f, h * 0.965f)
        val rightPanel = RectF(w * 0.515f, h * 0.15f, w * 0.975f, h * 0.965f)
        canvas.drawRoundRect(leftPanel, 42f, 42f, panel)
        canvas.drawRoundRect(rightPanel, 42f, 42f, panel)
        canvas.drawRoundRect(leftPanel, 42f, 42f, panelEdge)
        canvas.drawRoundRect(rightPanel, 42f, 42f, panelEdge)

        leftHomeX = w * 0.225f
        leftHomeY = h * 0.705f
        rightHomeX = w * 0.705f
        rightHomeY = h * 0.720f
        stickRadius = m * 0.135f

        if (leftStickPointer == null) {
            leftBaseX = leftHomeX
            leftBaseY = leftHomeY
        }
        if (rightStickPointer == null) {
            rightBaseX = rightHomeX
            rightBaseY = rightHomeY
        }

        drawAnalogZone(canvas, leftHomeX, leftHomeY, stickRadius * 1.52f, "MOVIMENTO")
        drawAnalogZone(canvas, rightHomeX, rightHomeY, stickRadius * 1.52f, "CÂMERA")

        drawStick(canvas, leftBaseX, leftBaseY, stickRadius, state.lx, -state.ly, leftStickPointer != null)
        drawStick(canvas, rightBaseX, rightBaseY, stickRadius, state.rx, -state.ry, rightStickPointer != null)

        drawDpad(canvas, w * 0.400f, h * 0.710f, m * 0.073f)
        drawFaceButtons(canvas, w, h, m)
        drawCenterButtons(canvas, w, h, m)
        drawStickClickButtons(canvas, w, h, m)
        drawShoulders(canvas, w, h)
    }

    private fun drawAnalogZone(canvas: Canvas, cx: Float, cy: Float, r: Float, label: String) {
        canvas.drawCircle(cx, cy, r, zone)
        subtleText.textSize = min(width, height) * 0.024f
        canvas.drawText(label, cx, cy - r - subtleText.textSize * 0.55f, subtleText)
    }

    private fun drawStick(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        vx: Float,
        vy: Float,
        active: Boolean,
    ) {
        val outer = if (active) Color.rgb(66, 118, 82) else Color.rgb(42, 47, 58)
        dark.color = outer
        canvas.drawCircle(cx, cy, r, dark)
        ring.color = if (active) Color.rgb(102, 218, 123) else Color.rgb(91, 101, 120)
        ring.strokeWidth = if (active) 5f else 3f
        canvas.drawCircle(cx, cy, r, ring)

        val knobTravel = r * 0.57f
        val kx = cx + vx.coerceIn(-1f, 1f) * knobTravel
        val ky = cy + vy.coerceIn(-1f, 1f) * knobTravel
        canvas.drawCircle(kx, ky, r * 0.48f, black)
        canvas.drawCircle(kx, ky, r * 0.38f, dark2)
        ring.color = Color.rgb(103, 111, 129)
        ring.strokeWidth = 3f
        canvas.drawCircle(kx, ky, r * 0.48f, ring)
    }

    private fun shoulderButtons(w: Float, h: Float): List<RectButton> {
        val triggerW = w * 0.125f
        val bumperW = w * 0.125f
        val top = h * 0.025f
        val triggerH = h * 0.075f
        val bumperH = h * 0.064f
        return listOf(
            RectButton("lt", RectF(w * 0.035f, top, w * 0.035f + triggerW, top + triggerH), "LT"),
            RectButton("lb", RectF(w * 0.170f, top, w * 0.170f + bumperW, top + bumperH), "LB"),
            RectButton("rb", RectF(w * 0.705f, top, w * 0.705f + bumperW, top + bumperH), "RB"),
            RectButton("rt", RectF(w * 0.840f, top, w * 0.840f + triggerW, top + triggerH), "RT"),
        )
    }

    private fun drawShoulders(canvas: Canvas, w: Float, h: Float) {
        shoulderButtons(w, h).forEach { b ->
            val active = getButton(b.key)
            dark.color = if (active) Color.rgb(48, 170, 73) else Color.rgb(31, 35, 43)
            canvas.drawRoundRect(b.rect, 18f, 18f, dark)
            ring.color = if (active) Color.rgb(111, 237, 132) else Color.rgb(82, 91, 108)
            ring.strokeWidth = if (active) 4f else 2.5f
            canvas.drawRoundRect(b.rect, 18f, 18f, ring)
            whiteText.textSize = h * 0.034f
            canvas.drawText(b.label, b.rect.centerX(), b.rect.centerY() + whiteText.textSize * 0.34f, whiteText)
        }
    }

    private fun faceButtons(w: Float, h: Float, m: Float): List<CircleButton> {
        val r = m * 0.058f
        val fx = w * 0.865f
        val fy = h * 0.430f
        val gap = r * 1.62f
        return listOf(
            CircleButton("y", fx, fy - gap, r, "Y"),
            CircleButton("a", fx, fy + gap, r, "A"),
            CircleButton("x", fx - gap, fy, r, "X"),
            CircleButton("b", fx + gap, fy, r, "B"),
        )
    }

    private fun drawFaceButtons(canvas: Canvas, w: Float, h: Float, m: Float) {
        faceButtons(w, h, m).forEach { b ->
            val active = getButton(b.key)
            canvas.drawCircle(b.x, b.y, b.r * 1.08f, if (active) Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE } else dark2)
            canvas.drawCircle(b.x, b.y, b.r, black)
            ring.color = if (active) Color.rgb(126, 235, 140) else Color.rgb(89, 98, 116)
            ring.strokeWidth = if (active) 5f else 3f
            canvas.drawCircle(b.x, b.y, b.r, ring)
            whiteText.color = when (b.key) {
                "a" -> Color.rgb(87, 205, 69)
                "b" -> Color.rgb(235, 72, 72)
                "x" -> Color.rgb(59, 167, 230)
                "y" -> Color.rgb(246, 214, 60)
                else -> Color.WHITE
            }
            whiteText.textSize = b.r * 0.88f
            canvas.drawText(b.label, b.x, b.y + whiteText.textSize * 0.34f, whiteText)
            whiteText.color = Color.WHITE
        }
    }

    private fun centerButtons(w: Float, h: Float, m: Float): List<CircleButton> {
        val r = m * 0.039f
        val guideR = m * 0.047f
        return listOf(
            CircleButton("view", w * 0.455f, h * 0.330f, r, "▣"),
            CircleButton("guide", w * 0.500f, h * 0.275f, guideR, "X"),
            CircleButton("menu", w * 0.545f, h * 0.330f, r, "≡"),
        )
    }

    private fun drawCenterButtons(canvas: Canvas, w: Float, h: Float, m: Float) {
        centerButtons(w, h, m).forEach { b ->
            val active = getButton(b.key)
            dark.color = if (active) Color.rgb(49, 170, 73) else Color.rgb(31, 35, 43)
            canvas.drawCircle(b.x, b.y, b.r, dark)
            ring.color = if (active) Color.rgb(115, 236, 134) else Color.rgb(88, 96, 112)
            ring.strokeWidth = 3f
            canvas.drawCircle(b.x, b.y, b.r, ring)
            whiteText.textSize = b.r * 0.78f
            canvas.drawText(b.label, b.x, b.y + whiteText.textSize * 0.34f, whiteText)
        }
    }

    private fun stickClickButtons(w: Float, h: Float, m: Float): List<CircleButton> {
        val r = m * 0.039f
        return listOf(
            CircleButton("l3", w * 0.335f, h * 0.515f, r, "L3"),
            CircleButton("r3", w * 0.625f, h * 0.515f, r, "R3"),
        )
    }

    private fun drawStickClickButtons(canvas: Canvas, w: Float, h: Float, m: Float) {
        stickClickButtons(w, h, m).forEach { b ->
            val active = getButton(b.key)
            dark.color = if (active) Color.rgb(50, 171, 73) else Color.rgb(31, 35, 43)
            canvas.drawCircle(b.x, b.y, b.r, dark)
            ring.color = if (active) Color.rgb(117, 237, 136) else Color.rgb(84, 93, 109)
            ring.strokeWidth = 3f
            canvas.drawCircle(b.x, b.y, b.r, ring)
            whiteText.textSize = b.r * 0.60f
            canvas.drawText(b.label, b.x, b.y + whiteText.textSize * 0.34f, whiteText)
        }
    }

    private fun drawDpad(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val arm = s * 0.68f
        val length = s * 1.62f
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
        ring.color = Color.rgb(82, 91, 108)
        ring.strokeWidth = 3f
        canvas.drawPath(p, ring)

        fun highlight(rect: RectF) {
            dark.color = Color.rgb(49, 171, 73)
            canvas.drawRoundRect(rect, 9f, 9f, dark)
        }
        if (state.up) highlight(RectF(cx - arm, cy - length, cx + arm, cy - arm))
        if (state.down) highlight(RectF(cx - arm, cy + arm, cx + arm, cy + length))
        if (state.left) highlight(RectF(cx - length, cy - arm, cx - arm, cy + arm))
        if (state.right) highlight(RectF(cx + arm, cy - arm, cx + length, cy + arm))
        canvas.drawCircle(cx, cy, s * 0.43f, dark2)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // Gaming is latency sensitive; ask Android to deliver this touch stream
                // without waiting to batch MOVE events with display vsync.
                requestUnbufferedDispatch(event)
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
                releasePointer(event.getPointerId(event.actionIndex))
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

        shoulderButtons(w, h).firstOrNull { it.rect.contains(x, y) }?.let {
            pointerKeys[id] = it.key
            setButton(it.key, true)
            buzz()
            return
        }

        (faceButtons(w, h, m) + centerButtons(w, h, m) + stickClickButtons(w, h, m))
            .firstOrNull { hypot(x - it.x, y - it.y) <= it.r * 1.45f }
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

        // Left thumb: floating virtual stick. Touch-down is always neutral.
        if (x < w * 0.345f && y > h * 0.36f && leftStickPointer == null) {
            leftStickPointer = id
            pointerKeys[id] = "lstick"
            leftBaseX = x.coerceIn(w * 0.08f, w * 0.325f)
            leftBaseY = y.coerceIn(h * 0.43f, h * 0.88f)
            state.lx = 0f
            state.ly = 0f
            buzz()
            return
        }

        // Right thumb: a true XInput stick, not a trackpad/mouse. Holding the thumb
        // away from the origin keeps RX/RY deflected until release, exactly like a
        // physical Xbox right stick. This is what FPS games expect.
        if (x > w * 0.555f && y > h * 0.43f && rightStickPointer == null) {
            rightStickPointer = id
            pointerKeys[id] = "rstick"
            rightBaseX = x.coerceIn(w * 0.585f, w * 0.805f)
            rightBaseY = y.coerceIn(h * 0.49f, h * 0.88f)
            state.rx = 0f
            state.ry = 0f
            buzz()
        }
    }

    private fun updatePointer(id: Int, x: Float, y: Float) {
        when (pointerKeys[id]) {
            "lstick" -> updateLeftStick(x, y)
            "rstick" -> updateRightStick(x, y)
        }
    }

    private fun updateLeftStick(x: Float, y: Float) {
        val dx = x - leftBaseX
        val dy = leftBaseY - y
        val mag = hypot(dx, dy)
        if (mag < 0.001f) {
            state.lx = 0f
            state.ly = 0f
            return
        }

        val gate = (stickRadius * 0.92f).coerceAtLeast(1f)
        val normalized = (mag / gate).coerceIn(0f, 1f)
        val dz = moveDeadzone.coerceIn(0.03f, 0.22f)
        if (normalized <= dz) {
            state.lx = 0f
            state.ly = 0f
            return
        }

        val radial = ((normalized - dz) / (1f - dz)).coerceIn(0f, 1f)
        val strength = (radial.pow(1.10f) * moveSensitivity.coerceIn(0.55f, 1.40f)).coerceIn(0f, 1f)
        state.lx = (dx / mag * strength).coerceIn(-1f, 1f)
        state.ly = (dy / mag * strength).coerceIn(-1f, 1f)
    }

    private fun updateRightStick(x: Float, y: Float) {
        val dx = x - rightBaseX
        val dy = rightBaseY - y
        val mag = hypot(dx, dy)
        if (mag < 0.001f) {
            state.rx = 0f
            state.ry = 0f
            return
        }

        val gate = (stickRadius * 0.92f).coerceAtLeast(1f)
        val normalized = (mag / gate).coerceIn(0f, 1f)
        val touchDeadzone = (moveDeadzone * 0.70f).coerceIn(0.035f, 0.14f)
        if (normalized <= touchDeadzone) {
            state.rx = 0f
            state.ry = 0f
            return
        }

        val radial = ((normalized - touchDeadzone) / (1f - touchDeadzone)).coerceIn(0f, 1f)

        // XInput's documented right-stick deadzone is about 26.5% of full scale.
        // Map the first intentional touch movement just above that value, then use
        // a strong precision curve so small thumb travel does not jump straight to
        // a fast camera turn. The deflection remains held while the finger is held.
        val sensitivity = aimSensitivity.coerceIn(0.45f, 1.55f)
        val shaped = (radial.pow(1.85f) * sensitivity).coerceIn(0f, 1f)
        val antiDeadzone = 0.285f
        val strength = (antiDeadzone + (1f - antiDeadzone) * shaped).coerceIn(antiDeadzone, 1f)

        state.rx = (dx / mag * strength).coerceIn(-1f, 1f)
        state.ry = (dy / mag * strength).coerceIn(-1f, 1f)
    }

    private fun dpadHit(w: Float, h: Float, m: Float, x: Float, y: Float): String? {
        val cx = w * 0.400f
        val cy = h * 0.710f
        val s = m * 0.073f
        val arm = s * 0.82f
        val length = s * 1.95f
        val areas = listOf(
            "up" to RectF(cx - arm, cy - length, cx + arm, cy - arm * 0.15f),
            "down" to RectF(cx - arm, cy + arm * 0.15f, cx + arm, cy + length),
            "left" to RectF(cx - length, cy - arm, cx - arm * 0.15f, cy + arm),
            "right" to RectF(cx + arm * 0.15f, cy - arm, cx + length, cy + arm),
        )
        return areas.firstOrNull { it.second.contains(x, y) }?.first
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
                leftBaseX = leftHomeX
                leftBaseY = leftHomeY
            }
            "rstick" -> {
                state.rx = 0f
                state.ry = 0f
                rightStickPointer = null
                rightBaseX = rightHomeX
                rightBaseY = rightHomeY
            }
            null -> Unit
            else -> setButton(key, false)
        }
    }

    fun resetAllInputs() {
        pointerKeys.clear()
        leftStickPointer = null
        rightStickPointer = null
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
            "l3" -> state.l3 = down
            "r3" -> state.r3 = down
            "up" -> state.up = down
            "down" -> state.down = down
            "left" -> state.left = down
            "right" -> state.right = down
            "view" -> state.view = down
            "menu" -> state.menu = down
            "guide" -> state.guide = down
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
        "l3" -> state.l3
        "r3" -> state.r3
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
            vibrator.vibrate(VibrationEffect.createOneShot(10, 45))
        } catch (_: Throwable) {
        }
    }
}
