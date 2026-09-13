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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

enum class ControlProfile { UNIVERSAL, FPS, RACING }

/**
 * V2 phone-first gamepad.
 *
 * The layout is designed around two thumb reach envelopes instead of the geometry
 * of a plastic controller. It still emits the complete Xbox/XInput control set.
 */
class GamepadView(context: Context) : View(context) {
    val state = GamepadState()

    var profile: ControlProfile = ControlProfile.UNIVERSAL
        set(value) { field = value; resetAllInputs(); invalidate() }

    var moveSensitivity = 1.0f
    var moveDeadzone = 0.08f
    var aimTurnSpeed = 1.0f
    var aimPrecision = 1.55f
    var aimAcceleration = 1.0f
    var aimAntiDeadzone = 0.27f
    var gyroEnabled = false
    var gyroSensitivity = 0.42f
    var triggerLockEnabled = true
    var vibrationEnabled = true

    /** Natural thumb-rest positions, as fractions of the screen. */
    var leftAnchorX = 0.22f
    var leftAnchorY = 0.72f
    var rightAnchorX = 0.78f
    var rightAnchorY = 0.72f

    var onStateChanged: (() -> Unit)? = null
    var onCalibrationComplete: ((Float, Float, Float, Float) -> Unit)? = null

    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(7, 9, 13) }
    private val zoneFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 20, 28) }
    private val zoneStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f; color = Color.rgb(45, 56, 73)
    }
    private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(28, 33, 42) }
    private val dark2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(45, 53, 66) }
    private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(8, 10, 14) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.rgb(91, 104, 126)
    }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(56, 190, 88) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val subtle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(142, 153, 173); textAlign = Paint.Align.CENTER
    }
    private val overlay = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(218, 5, 7, 11) }

    private data class CircleButton(
        val key: String,
        val x: Float,
        val y: Float,
        val visualR: Float,
        val hitR: Float,
        val label: String,
    )

    private data class RectButton(
        val key: String,
        val rect: RectF,
        val hit: RectF,
        val label: String,
        val duplicate: Boolean = false,
    )

    private val pointerKeys = mutableMapOf<Int, String>()
    private val dpadDirByPointer = mutableMapOf<Int, String>()

    private var leftPointer: Int? = null
    private var rightPointer: Int? = null
    private var leftBaseX = 0f
    private var leftBaseY = 0f
    private var rightBaseX = 0f
    private var rightBaseY = 0f
    private var rightLastX = 0f
    private var rightLastY = 0f
    private var rightLastEventMs = 0L
    private var rightTouchX = 0f
    private var rightTouchY = 0f
    private var touchRx = 0f
    private var touchRy = 0f
    private var gyroRx = 0f
    private var gyroRy = 0f
    private var lastLookMoveAt = 0L
    private var lookDecayPosted = false

    private var latchedLt = false
    private var latchedRt = false
    private var lastLtTap = 0L
    private var lastRtTap = 0L
    private var lastLeftStickTap = 0L
    private var lastRightStickTap = 0L

    private var calibrating = false
    private var calLeft: Pair<Float, Float>? = null
    private var calRight: Pair<Float, Float>? = null

    private val lookDecay = object : Runnable {
        override fun run() {
            if (profile != ControlProfile.FPS || rightPointer == null) {
                lookDecayPosted = false
                return
            }
            if (SystemClock.uptimeMillis() - lastLookMoveAt >= 52L) {
                if (abs(touchRx) > 0.0001f || abs(touchRy) > 0.0001f) {
                    touchRx = 0f; touchRy = 0f
                    publishRightAxis()
                    changed()
                    invalidate()
                }
            }
            handler.postDelayed(this, 10L)
        }
    }

    fun setThumbAnchors(lx: Float, ly: Float, rx: Float, ry: Float) {
        leftAnchorX = lx.coerceIn(0.14f, 0.33f)
        leftAnchorY = ly.coerceIn(0.52f, 0.86f)
        rightAnchorX = rx.coerceIn(0.67f, 0.86f)
        rightAnchorY = ry.coerceIn(0.52f, 0.86f)
        invalidate()
    }

    fun startCalibration() {
        resetAllInputs()
        calibrating = true
        calLeft = null
        calRight = null
        invalidate()
    }

    fun setGyroInput(yawRate: Float, pitchRate: Float) {
        if (!gyroEnabled || profile != ControlProfile.FPS || state.lt < 0.5f) {
            if (gyroRx != 0f || gyroRy != 0f) {
                gyroRx = 0f; gyroRy = 0f
                publishRightAxis(); changed()
            }
            return
        }

        val mag = hypot(yawRate, pitchRate)
        if (mag < 0.025f) {
            gyroRx = 0f; gyroRy = 0f
        } else {
            val norm = (mag / 2.6f).coerceIn(0f, 1f)
            val minOut = (aimAntiDeadzone * 0.72f).coerceIn(0.12f, 0.24f)
            val strength = (minOut + (1f - minOut) * norm * gyroSensitivity.coerceIn(0.15f, 1.2f))
                .coerceIn(0f, 0.55f)
            gyroRx = (yawRate / mag * strength).coerceIn(-0.55f, 0.55f)
            gyroRy = (-pitchRate / mag * strength).coerceIn(-0.55f, 0.55f)
        }
        publishRightAxis(); changed(); invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat(); val m = min(w, h)
        canvas.drawRect(0f, 0f, w, h, bg)

        val leftRestX = w * leftAnchorX
        val leftRestY = h * leftAnchorY
        val rightRestX = w * rightAnchorX
        val rightRestY = h * rightAnchorY

        // Thumb reach envelopes: large, forgiving zones with no overlapping button hitboxes.
        val reachR = h * 0.34f
        canvas.drawCircle(leftRestX, leftRestY, reachR, zoneFill)
        canvas.drawCircle(rightRestX, rightRestY, reachR, zoneFill)
        canvas.drawCircle(leftRestX, leftRestY, reachR, zoneStroke)
        canvas.drawCircle(rightRestX, rightRestY, reachR, zoneStroke)

        if (leftPointer == null) { leftBaseX = leftRestX; leftBaseY = leftRestY }
        if (rightPointer == null && profile != ControlProfile.FPS) {
            rightBaseX = rightRestX; rightBaseY = rightRestY
        }

        val stickR = stickRadius(h, w)
        drawStick(canvas, leftBaseX, leftBaseY, stickR, state.lx, -state.ly, leftPointer != null, "MOVE")

        if (profile == ControlProfile.FPS) {
            drawLookSurface(canvas, w, h, rightRestX, rightRestY)
        } else {
            drawStick(canvas, rightBaseX, rightBaseY, stickR, state.rx, -state.ry, rightPointer != null, "LOOK")
        }

        drawDpad(canvas, dpadCx(w), dpadCy(h), h * 0.063f)
        faceButtons(w, h).forEach { drawCircleButton(canvas, it) }
        centerButtons(w, h).forEach { drawCircleButton(canvas, it) }
        stickClickButtons(w, h).forEach { drawCircleButton(canvas, it) }
        shoulderButtons(w, h).forEach { drawRectButton(canvas, it) }
        if (profile == ControlProfile.FPS) thumbTriggerButtons(w, h).forEach { drawRectButton(canvas, it) }

        subtle.textSize = h * 0.028f
        canvas.drawText(profile.name, w * 0.5f, h * 0.975f, subtle)

        if (latchedLt || latchedRt) {
            subtle.textSize = h * 0.024f
            val msg = buildString {
                if (latchedLt) append("LT TRAVADO")
                if (latchedLt && latchedRt) append("  •  ")
                if (latchedRt) append("RT TRAVADO")
            }
            canvas.drawText(msg, w * 0.5f, h * 0.91f, subtle)
        }

        if (calibrating) drawCalibrationOverlay(canvas, w, h, m)
    }

    private fun drawLookSurface(canvas: Canvas, w: Float, h: Float, cx: Float, cy: Float) {
        val left = w * 0.525f; val top = h * 0.205f; val right = w * 0.975f; val bottom = h * 0.96f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(22, 80, 160, 255) }
        canvas.drawRoundRect(RectF(left, top, right, bottom), 38f, 38f, p)
        ring.color = Color.argb(90, 85, 145, 218); ring.strokeWidth = 2.5f
        canvas.drawRoundRect(RectF(left, top, right, bottom), 38f, 38f, ring)
        subtle.textSize = h * 0.026f
        canvas.drawText("ÁREA DE MIRA", cx, min(cy + h * 0.20f, bottom - h * 0.04f), subtle)
        if (rightPointer != null) {
            ring.color = Color.rgb(91, 204, 119); ring.strokeWidth = 4f
            canvas.drawCircle(rightTouchX, rightTouchY, h * 0.045f, ring)
            canvas.drawCircle(rightTouchX, rightTouchY, h * 0.012f, accent)
        }
    }

    private fun drawCalibrationOverlay(canvas: Canvas, w: Float, h: Float, m: Float) {
        canvas.drawRect(0f, 0f, w, h, overlay)
        text.textSize = h * 0.065f
        canvas.drawText("CALIBRAR PEGADA", w / 2f, h * 0.28f, text)
        subtle.textSize = h * 0.040f
        canvas.drawText("Segure o celular como você joga e apoie os DOIS polegares naturalmente", w / 2f, h * 0.40f, subtle)
        canvas.drawText("Não procure círculos: apenas descanse os dedos onde eles caem sozinhos", w / 2f, h * 0.47f, subtle)

        val r = m * 0.07f
        calLeft?.let {
            ring.color = Color.rgb(89, 223, 116); ring.strokeWidth = 6f
            canvas.drawCircle(it.first, it.second, r, ring)
        }
        calRight?.let {
            ring.color = Color.rgb(89, 223, 116); ring.strokeWidth = 6f
            canvas.drawCircle(it.first, it.second, r, ring)
        }
        subtle.textSize = h * 0.028f
        canvas.drawText("esquerdo", w * 0.23f, h * 0.82f, subtle)
        canvas.drawText("direito", w * 0.77f, h * 0.82f, subtle)
    }

    private fun stickRadius(h: Float, w: Float) = min(h * 0.155f, w * 0.080f)

    private fun drawStick(canvas: Canvas, cx: Float, cy: Float, r: Float, vx: Float, vy: Float, active: Boolean, label: String) {
        dark.color = if (active) Color.rgb(39, 88, 55) else Color.rgb(28, 34, 44)
        canvas.drawCircle(cx, cy, r, dark)
        ring.color = if (active) Color.rgb(85, 220, 113) else Color.rgb(83, 96, 116)
        ring.strokeWidth = if (active) 5f else 3f
        canvas.drawCircle(cx, cy, r, ring)
        val travel = r * 0.58f
        val kx = cx + vx.coerceIn(-1f, 1f) * travel
        val ky = cy + vy.coerceIn(-1f, 1f) * travel
        canvas.drawCircle(kx, ky, r * 0.48f, black)
        canvas.drawCircle(kx, ky, r * 0.36f, dark2)
        subtle.textSize = r * 0.19f
        canvas.drawText(label, cx, cy + r * 1.28f, subtle)
    }

    private fun faceButtons(w: Float, h: Float): List<CircleButton> {
        val r = h * 0.066f
        val hit = r * 1.17f
        val fx = (w * rightAnchorX + w * 0.105f).coerceIn(w * 0.82f, w * 0.90f)
        val fy = (h * rightAnchorY - h * 0.25f).coerceIn(h * 0.39f, h * 0.52f)
        val gap = r * 2.48f
        return listOf(
            CircleButton("y", fx, fy - gap, r, hit, "Y"),
            CircleButton("a", fx, fy + gap, r, hit, "A"),
            CircleButton("x", fx - gap, fy, r, hit, "X"),
            CircleButton("b", fx + gap, fy, r, hit, "B"),
        )
    }

    private fun centerButtons(w: Float, h: Float): List<CircleButton> {
        val r = h * 0.042f; val hit = h * 0.058f
        return listOf(
            CircleButton("view", w * 0.455f, h * 0.205f, r, hit, "▣"),
            CircleButton("guide", w * 0.500f, h * 0.175f, r * 1.12f, hit * 1.05f, "X"),
            CircleButton("menu", w * 0.545f, h * 0.205f, r, hit, "≡"),
        )
    }

    private fun stickClickButtons(w: Float, h: Float): List<CircleButton> {
        val r = h * 0.040f; val hit = h * 0.057f
        return listOf(
            CircleButton("l3", w * 0.455f, h * 0.61f, r, hit, "L3"),
            CircleButton("r3", w * 0.545f, h * 0.61f, r, hit, "R3"),
        )
    }

    private fun shoulderButtons(w: Float, h: Float): List<RectButton> {
        val top = h * 0.025f
        val hh = h * 0.105f
        fun rb(key: String, l: Float, r: Float, label: String): RectButton {
            val rect = RectF(w * l, top, w * r, top + hh)
            return RectButton(key, rect, RectF(rect.left, rect.top, rect.right, rect.bottom + h * 0.015f), label)
        }
        return listOf(
            rb("lt", 0.025f, 0.145f, "LT"),
            rb("lb", 0.155f, 0.285f, "LB"),
            rb("rb", 0.715f, 0.845f, "RB"),
            rb("rt", 0.855f, 0.975f, "RT"),
        )
    }

    /** Duplicate trigger targets for a two-thumb FPS grip. */
    private fun thumbTriggerButtons(w: Float, h: Float): List<RectButton> {
        val lt = RectF(w * 0.535f, h * 0.285f, w * 0.610f, h * 0.425f)
        val rt = RectF(w * 0.915f, h * 0.700f, w * 0.982f, h * 0.885f)
        return listOf(
            RectButton("lt", lt, RectF(lt.left - w * 0.006f, lt.top, lt.right + w * 0.006f, lt.bottom), "LT", true),
            RectButton("rt", rt, RectF(rt.left - w * 0.006f, rt.top, rt.right, rt.bottom), "RT", true),
        )
    }

    private fun drawCircleButton(canvas: Canvas, b: CircleButton) {
        val active = getButton(b.key)
        dark.color = if (active) Color.rgb(44, 156, 68) else Color.rgb(27, 32, 41)
        canvas.drawCircle(b.x, b.y, b.visualR, dark)
        ring.color = if (active) Color.rgb(100, 235, 126) else Color.rgb(85, 98, 118)
        ring.strokeWidth = if (active) 5f else 3f
        canvas.drawCircle(b.x, b.y, b.visualR, ring)
        text.textSize = b.visualR * 0.78f
        text.color = when (b.key) {
            "a" -> Color.rgb(82, 209, 72)
            "b" -> Color.rgb(239, 76, 76)
            "x" -> Color.rgb(61, 170, 238)
            "y" -> Color.rgb(249, 214, 58)
            else -> Color.WHITE
        }
        canvas.drawText(b.label, b.x, b.y + text.textSize * 0.34f, text)
        text.color = Color.WHITE
    }

    private fun drawRectButton(canvas: Canvas, b: RectButton) {
        val active = getButton(b.key)
        dark.color = if (active) Color.rgb(44, 157, 68) else if (b.duplicate) Color.rgb(24, 31, 40) else Color.rgb(28, 33, 42)
        canvas.drawRoundRect(b.rect, 20f, 20f, dark)
        ring.color = if (active) Color.rgb(101, 234, 126) else Color.rgb(80, 94, 114)
        ring.strokeWidth = if (active) 4f else 2.5f
        canvas.drawRoundRect(b.rect, 20f, 20f, ring)
        text.textSize = b.rect.height() * 0.38f
        canvas.drawText(b.label, b.rect.centerX(), b.rect.centerY() + text.textSize * 0.34f, text)
    }

    private fun dpadCx(w: Float): Float = (w * leftAnchorX + w * 0.145f).coerceIn(w * 0.34f, w * 0.42f)
    private fun dpadCy(h: Float): Float = (h * leftAnchorY + h * 0.015f).coerceIn(h * 0.62f, h * 0.80f)

    private fun drawDpad(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val arm = s * 0.72f; val length = s * 1.75f
        val p = Path().apply {
            moveTo(cx - arm, cy - arm); lineTo(cx - arm, cy - length); lineTo(cx + arm, cy - length)
            lineTo(cx + arm, cy - arm); lineTo(cx + length, cy - arm); lineTo(cx + length, cy + arm)
            lineTo(cx + arm, cy + arm); lineTo(cx + arm, cy + length); lineTo(cx - arm, cy + length)
            lineTo(cx - arm, cy + arm); lineTo(cx - length, cy + arm); lineTo(cx - length, cy - arm); close()
        }
        canvas.drawPath(p, black)
        ring.color = Color.rgb(79, 92, 111); ring.strokeWidth = 3f; canvas.drawPath(p, ring)
        fun hi(rect: RectF) { canvas.drawRoundRect(rect, 8f, 8f, accent) }
        if (state.up) hi(RectF(cx - arm, cy - length, cx + arm, cy - arm * 0.12f))
        if (state.down) hi(RectF(cx - arm, cy + arm * 0.12f, cx + arm, cy + length))
        if (state.left) hi(RectF(cx - length, cy - arm, cx - arm * 0.12f, cy + arm))
        if (state.right) hi(RectF(cx + arm * 0.12f, cy - arm, cx + length, cy + arm))
        canvas.drawCircle(cx, cy, s * 0.42f, dark2)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (calibrating) return handleCalibration(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                requestUnbufferedDispatch(event)
                val i = event.actionIndex
                assignPointer(event.getPointerId(i), event.getX(i), event.getY(i), event.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                reconcilePointers(event)
                // Consume historical samples first for smoother aim/movement.
                for (history in 0 until event.historySize) {
                    val t = event.getHistoricalEventTime(history)
                    for (i in 0 until event.pointerCount) {
                        updatePointer(
                            event.getPointerId(i),
                            event.getHistoricalX(i, history),
                            event.getHistoricalY(i, history),
                            t,
                        )
                    }
                }
                for (i in 0 until event.pointerCount) {
                    updatePointer(event.getPointerId(i), event.getX(i), event.getY(i), event.eventTime)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> releasePointer(event.getPointerId(event.actionIndex))
            MotionEvent.ACTION_CANCEL -> resetAllInputs()
        }
        changed(); invalidate(); return true
    }

    private fun handleCalibration(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val x = event.getX(i); val y = event.getY(i)
                if (x < width / 2f && calLeft == null) calLeft = x to y
                else if (x >= width / 2f && calRight == null) calRight = x to y
                buzz()
                if (calLeft != null && calRight != null) {
                    val l = calLeft!!; val r = calRight!!
                    val lx = (l.first / width).coerceIn(0.14f, 0.33f)
                    val ly = (l.second / height).coerceIn(0.52f, 0.86f)
                    val rx = (r.first / width).coerceIn(0.67f, 0.86f)
                    val ry = (r.second / height).coerceIn(0.52f, 0.86f)
                    setThumbAnchors(lx, ly, rx, ry)
                    calibrating = false
                    onCalibrationComplete?.invoke(lx, ly, rx, ry)
                }
            }
        }
        invalidate(); return true
    }

    private fun assignPointer(id: Int, x: Float, y: Float, eventTime: Long) {
        val w = width.toFloat(); val h = height.toFloat()

        (shoulderButtons(w, h) + if (profile == ControlProfile.FPS) thumbTriggerButtons(w, h) else emptyList())
            .firstOrNull { it.hit.contains(x, y) }?.let {
                pointerKeys[id] = it.key
                pressButton(it.key, eventTime)
                buzz(); return
            }

        (faceButtons(w, h) + centerButtons(w, h) + stickClickButtons(w, h))
            .firstOrNull { hypot(x - it.x, y - it.y) <= it.hitR }?.let {
                pointerKeys[id] = it.key
                setButton(it.key, true)
                buzz(); return
            }

        dpadDirectionAt(w, h, x, y)?.let { dir ->
            pointerKeys[id] = "dpad"
            dpadDirByPointer[id] = dir
            setButton(dir, true)
            buzz(); return
        }

        val now = eventTime
        val dpadLeft = dpadCx(w) - h * 0.14f
        if (x < min(w * 0.35f, dpadLeft) && y > h * 0.27f && leftPointer == null) {
            leftPointer = id; pointerKeys[id] = "lstick"
            leftBaseX = x; leftBaseY = y
            state.lx = 0f; state.ly = 0f
            if (now - lastLeftStickTap <= 300L) pulse("l3")
            lastLeftStickTap = now
            buzz(); return
        }

        if (x > w * 0.52f && y > h * 0.20f && rightPointer == null) {
            rightPointer = id
            rightTouchX = x; rightTouchY = y
            if (profile == ControlProfile.FPS) {
                pointerKeys[id] = "rlook"
                rightLastX = x; rightLastY = y; rightLastEventMs = eventTime
                touchRx = 0f; touchRy = 0f; publishRightAxis()
            } else {
                pointerKeys[id] = "rstick"
                rightBaseX = x; rightBaseY = y
                touchRx = 0f; touchRy = 0f; publishRightAxis()
            }
            if (now - lastRightStickTap <= 300L) pulse("r3")
            lastRightStickTap = now
            buzz()
        }
    }

    private fun updatePointer(id: Int, x: Float, y: Float, eventTime: Long) {
        when (pointerKeys[id]) {
            "lstick" -> updateLeftStick(x, y)
            "rstick" -> updateRightStick(x, y)
            "rlook" -> updateRightLook(x, y, eventTime)
            "dpad" -> updateDpad(id, x, y)
        }
    }

    private fun updateLeftStick(x: Float, y: Float) {
        val r = stickRadius(height.toFloat(), width.toFloat())
        val (ox, oy) = radialStick(x - leftBaseX, leftBaseY - y, r, moveDeadzone, 1.08f, moveSensitivity, 0f)
        state.lx = ox; state.ly = oy
    }

    private fun updateRightStick(x: Float, y: Float) {
        rightTouchX = x; rightTouchY = y
        val r = stickRadius(height.toFloat(), width.toFloat())
        val (ox, oy) = radialStick(
            x - rightBaseX,
            rightBaseY - y,
            r,
            (moveDeadzone * 0.75f).coerceIn(0.035f, 0.13f),
            aimPrecision.coerceIn(1.05f, 2.40f),
            aimTurnSpeed.coerceIn(0.55f, 1.65f),
            aimAntiDeadzone.coerceIn(0.15f, 0.34f),
        )
        touchRx = ox; touchRy = oy; publishRightAxis()
    }

    /** FPS touchpad: finger velocity -> XInput right-stick velocity. */
    private fun updateRightLook(x: Float, y: Float, eventTime: Long) {
        val dx = x - rightLastX; val dy = y - rightLastY
        val dt = ((eventTime - rightLastEventMs).coerceAtLeast(1L) / 1000f).coerceAtMost(0.050f)
        rightLastX = x; rightLastY = y; rightLastEventMs = eventTime
        rightTouchX = x; rightTouchY = y

        val dist = hypot(dx, dy)
        if (dist < 0.18f) return
        val speed = dist / dt
        val fullSpeed = height.coerceAtLeast(1) * 4.0f
        val n = (speed / fullSpeed).coerceIn(0f, 1f)
        val shaped = (n.pow(aimPrecision.coerceIn(1.05f, 2.60f)) *
            aimAcceleration.coerceIn(0.65f, 1.80f) * aimTurnSpeed.coerceIn(0.55f, 1.65f)).coerceIn(0f, 1f)
        val anti = aimAntiDeadzone.coerceIn(0.18f, 0.36f)
        val strength = (anti + (1f - anti) * shaped).coerceIn(anti, 1f)
        val targetX = dx / dist * strength
        val targetY = -dy / dist * strength

        // Fast response with a little smoothing against touch sensor noise.
        val alpha = if (n < 0.16f) 0.58f else 0.78f
        touchRx = touchRx * (1f - alpha) + targetX * alpha
        touchRy = touchRy * (1f - alpha) + targetY * alpha
        publishRightAxis()
        lastLookMoveAt = SystemClock.uptimeMillis()
        ensureLookDecay()
    }

    private fun radialStick(
        dx: Float,
        dy: Float,
        radius: Float,
        deadzone: Float,
        exponent: Float,
        gain: Float,
        antiDeadzone: Float,
    ): Pair<Float, Float> {
        val mag = hypot(dx, dy)
        if (mag < 0.001f) return 0f to 0f
        val n = (mag / (radius * 0.95f).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val dz = deadzone.coerceIn(0f, 0.25f)
        if (n <= dz) return 0f to 0f
        val radial = ((n - dz) / (1f - dz)).coerceIn(0f, 1f)
        var strength = (radial.pow(exponent) * gain).coerceIn(0f, 1f)
        if (strength > 0f && antiDeadzone > 0f) {
            strength = antiDeadzone + (1f - antiDeadzone) * strength
        }
        return (dx / mag * strength).coerceIn(-1f, 1f) to (dy / mag * strength).coerceIn(-1f, 1f)
    }

    private fun publishRightAxis() {
        state.rx = (touchRx + gyroRx).coerceIn(-1f, 1f)
        state.ry = (touchRy + gyroRy).coerceIn(-1f, 1f)
    }

    private fun ensureLookDecay() {
        if (lookDecayPosted) return
        lookDecayPosted = true
        handler.post(lookDecay)
    }

    private fun dpadDirectionAt(w: Float, h: Float, x: Float, y: Float): String? {
        val cx = dpadCx(w); val cy = dpadCy(h); val radius = h * 0.135f
        val dx = x - cx; val dy = y - cy
        if (hypot(dx, dy) > radius || hypot(dx, dy) < h * 0.020f) return null
        return if (abs(dx) > abs(dy)) if (dx < 0f) "left" else "right" else if (dy < 0f) "up" else "down"
    }

    private fun updateDpad(id: Int, x: Float, y: Float) {
        val old = dpadDirByPointer[id]
        val next = dpadDirectionAt(width.toFloat(), height.toFloat(), x, y)
        if (old == next) return
        old?.let { setButton(it, false) }
        if (next != null) { setButton(next, true); dpadDirByPointer[id] = next } else dpadDirByPointer.remove(id)
    }

    private fun pressButton(key: String, eventTime: Long) {
        if ((key == "lt" || key == "rt") && triggerLockEnabled && profile == ControlProfile.FPS) {
            if (key == "lt") {
                if (latchedLt) { latchedLt = false; state.lt = 0f; gyroRx = 0f; gyroRy = 0f; publishRightAxis(); return }
                if (eventTime - lastLtTap <= 300L) latchedLt = true
                lastLtTap = eventTime; state.lt = 1f
            } else {
                if (latchedRt) { latchedRt = false; state.rt = 0f; return }
                if (eventTime - lastRtTap <= 300L) latchedRt = true
                lastRtTap = eventTime; state.rt = 1f
            }
        } else setButton(key, true)
    }

    private fun releasePointer(id: Int) {
        when (val key = pointerKeys.remove(id)) {
            "lstick" -> { state.lx = 0f; state.ly = 0f; leftPointer = null }
            "rstick", "rlook" -> {
                touchRx = 0f; touchRy = 0f; publishRightAxis(); rightPointer = null
                lookDecayPosted = false; handler.removeCallbacks(lookDecay)
            }
            "dpad" -> {
                dpadDirByPointer.remove(id)?.let { setButton(it, false) }
            }
            "lt" -> if (!latchedLt) { state.lt = 0f; gyroRx = 0f; gyroRy = 0f; publishRightAxis() }
            "rt" -> if (!latchedRt) state.rt = 0f
            null -> Unit
            else -> setButton(key, false)
        }
        changed(); invalidate()
    }

    private fun reconcilePointers(event: MotionEvent) {
        val active = HashSet<Int>()
        for (i in 0 until event.pointerCount) active.add(event.getPointerId(i))
        pointerKeys.keys.filter { it !in active }.toList().forEach { releasePointer(it) }
    }

    fun resetAllInputs() {
        pointerKeys.clear(); dpadDirByPointer.clear()
        leftPointer = null; rightPointer = null
        lookDecayPosted = false; handler.removeCallbacks(lookDecay)
        touchRx = 0f; touchRy = 0f; gyroRx = 0f; gyroRy = 0f
        latchedLt = false; latchedRt = false
        state.lx = 0f; state.ly = 0f; state.rx = 0f; state.ry = 0f
        state.lt = 0f; state.rt = 0f
        state.a = false; state.b = false; state.x = false; state.y = false
        state.lb = false; state.rb = false; state.l3 = false; state.r3 = false
        state.up = false; state.down = false; state.left = false; state.right = false
        state.view = false; state.menu = false; state.guide = false
        changed(); invalidate()
    }

    private fun pulse(key: String) {
        setButton(key, true); changed(); invalidate()
        handler.postDelayed({ setButton(key, false); changed(); invalidate() }, 85L)
    }

    private fun setButton(key: String, down: Boolean) {
        when (key) {
            "a" -> state.a = down; "b" -> state.b = down; "x" -> state.x = down; "y" -> state.y = down
            "lb" -> state.lb = down; "rb" -> state.rb = down
            "lt" -> state.lt = if (down) 1f else 0f; "rt" -> state.rt = if (down) 1f else 0f
            "l3" -> state.l3 = down; "r3" -> state.r3 = down
            "up" -> state.up = down; "down" -> state.down = down; "left" -> state.left = down; "right" -> state.right = down
            "view" -> state.view = down; "menu" -> state.menu = down; "guide" -> state.guide = down
        }
    }

    private fun getButton(key: String): Boolean = when (key) {
        "a" -> state.a; "b" -> state.b; "x" -> state.x; "y" -> state.y
        "lb" -> state.lb; "rb" -> state.rb; "lt" -> state.lt > 0.5f; "rt" -> state.rt > 0.5f
        "l3" -> state.l3; "r3" -> state.r3
        "up" -> state.up; "down" -> state.down; "left" -> state.left; "right" -> state.right
        "view" -> state.view; "menu" -> state.menu; "guide" -> state.guide
        else -> false
    }

    private fun changed() { onStateChanged?.invoke() }

    private fun buzz() {
        if (!vibrationEnabled || vibrator == null || !vibrator.hasVibrator()) return
        try { vibrator.vibrate(VibrationEffect.createOneShot(8, 38)) } catch (_: Throwable) { }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) resetAllInputs()
    }

    override fun onDetachedFromWindow() {
        resetAllInputs(); handler.removeCallbacksAndMessages(null); super.onDetachedFromWindow()
    }
}
