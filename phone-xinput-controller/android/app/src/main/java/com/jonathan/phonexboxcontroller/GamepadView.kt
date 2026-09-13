package com.jonathan.phonexboxcontroller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class GamepadView(context: Context) : View(context) {
    val state = GamepadState()

    var sensitivity = 1.0f
    var deadzone = 0.08f
    var vibrationEnabled = true

    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(8, 11, 16) }
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(24, 30, 40) }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.rgb(70, 84, 104)
    }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(16, 124, 16) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 34f
        isFakeBoldText = true
    }
    private val subText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textAlign = Paint.Align.CENTER
        textSize = 22f
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
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val w = width.toFloat()
        val h = height.toFloat()

        leftCx = w * 0.18f
        leftCy = h * 0.57f
        rightCx = w * 0.67f
        rightCy = h * 0.68f
        stickRadius = min(w, h) * 0.12f

        drawStick(canvas, leftCx, leftCy, stickRadius, state.lx, -state.ly, "L")
        drawStick(canvas, rightCx, rightCy, stickRadius, state.rx, -state.ry, "R")

        drawDpad(canvas, w * 0.36f, h * 0.70f, min(w, h) * 0.075f)

        val faceR = min(w, h) * 0.055f
        val fx = w * 0.86f
        val fy = h * 0.53f
        val gap = faceR * 1.55f
        val faces = listOf(
            CircleButton("y", fx, fy-gap, faceR, "Y"),
            CircleButton("a", fx, fy+gap, faceR, "A"),
            CircleButton("x", fx-gap, fy, faceR, "X"),
            CircleButton("b", fx+gap, fy, faceR, "B"),
        )
        for (b in faces) drawFaceButton(canvas, b)

        val shoulderH = h * 0.10f
        val shoulderW = w * 0.18f
        drawRectButton(canvas, RectButton("lt", RectF(w*0.03f,h*0.03f,w*0.03f+shoulderW,h*0.03f+shoulderH), "LT"))
        drawRectButton(canvas, RectButton("lb", RectF(w*0.03f,h*0.15f,w*0.03f+shoulderW,h*0.15f+shoulderH), "LB"))
        drawRectButton(canvas, RectButton("rt", RectF(w*0.79f,h*0.03f,w*0.79f+shoulderW,h*0.03f+shoulderH), "RT"))
        drawRectButton(canvas, RectButton("rb", RectF(w*0.79f,h*0.15f,w*0.79f+shoulderW,h*0.15f+shoulderH), "RB"))

        val smallR = min(w, h) * 0.037f
        drawFaceButton(canvas, CircleButton("view", w*0.46f, h*0.47f, smallR, "◫"))
        drawFaceButton(canvas, CircleButton("guide", w*0.52f, h*0.42f, smallR*1.15f, "X"))
        drawFaceButton(canvas, CircleButton("menu", w*0.58f, h*0.47f, smallR, "≡"))

        text.textSize = min(w,h)*0.032f
        canvas.drawText("Xbox Phone Controller", w*0.5f, h*0.09f, text)
        subText.textSize = min(w,h)*0.026f
        canvas.drawText("Segure L/R para L3/R3", w*0.5f, h*0.95f, subText)
    }

    private fun drawStick(canvas: Canvas, cx: Float, cy: Float, r: Float, vx: Float, vy: Float, label: String) {
        canvas.drawCircle(cx, cy, r, panel)
        canvas.drawCircle(cx, cy, r, outline)
        val knobR = r*0.42f
        canvas.drawCircle(cx + vx*r*0.58f, cy + vy*r*0.58f, knobR, accent)
        canvas.drawText(label, cx, cy + text.textSize*0.35f, text)
    }

    private fun drawFaceButton(canvas: Canvas, b: CircleButton) {
        val active = getButton(b.key)
        canvas.drawCircle(b.x, b.y, b.r, if (active) accent else panel)
        canvas.drawCircle(b.x, b.y, b.r, outline)
        canvas.drawText(b.label, b.x, b.y + text.textSize*0.35f, text)
    }

    private fun drawRectButton(canvas: Canvas, b: RectButton) {
        val active = getButton(b.key)
        canvas.drawRoundRect(b.rect, 20f, 20f, if (active) accent else panel)
        canvas.drawRoundRect(b.rect, 20f, 20f, outline)
        canvas.drawText(b.label, b.rect.centerX(), b.rect.centerY() + text.textSize*0.35f, text)
    }

    private fun drawDpad(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val buttons = listOf(
            RectButton("up", RectF(cx-s*0.45f, cy-s*1.45f, cx+s*0.45f, cy-s*0.35f), "↑"),
            RectButton("down", RectF(cx-s*0.45f, cy+s*0.35f, cx+s*0.45f, cy+s*1.45f), "↓"),
            RectButton("left", RectF(cx-s*1.45f, cy-s*0.45f, cx-s*0.35f, cy+s*0.45f), "←"),
            RectButton("right", RectF(cx+s*0.35f, cy-s*0.45f, cx+s*1.45f, cy+s*0.45f), "→"),
        )
        buttons.forEach { drawRectButton(canvas, it) }
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
        val w = width.toFloat(); val h = height.toFloat(); val m = min(w,h)
        if (hypot(x-leftCx, y-leftCy) <= stickRadius*1.35f && leftStickPointer == null) {
            leftStickPointer = id; pointerKeys[id] = "lstick"; updatePointer(id,x,y); buzz(); return
        }
        if (hypot(x-rightCx, y-rightCy) <= stickRadius*1.35f && rightStickPointer == null) {
            rightStickPointer = id; pointerKeys[id] = "rstick"; updatePointer(id,x,y); buzz(); return
        }

        val faceR = m*0.055f; val fx=w*0.86f; val fy=h*0.53f; val gap=faceR*1.55f
        val circles = listOf(
            CircleButton("y",fx,fy-gap,faceR,"Y"), CircleButton("a",fx,fy+gap,faceR,"A"),
            CircleButton("x",fx-gap,fy,faceR,"X"), CircleButton("b",fx+gap,fy,faceR,"B"),
            CircleButton("view",w*0.46f,h*0.47f,m*0.037f,"◫"),
            CircleButton("guide",w*0.52f,h*0.42f,m*0.043f,"X"),
            CircleButton("menu",w*0.58f,h*0.47f,m*0.037f,"≡"),
        )
        circles.firstOrNull { hypot(x-it.x,y-it.y)<=it.r*1.2f }?.let {
            pointerKeys[id]=it.key; setButton(it.key,true); buzz(); return
        }

        val sw=w*0.18f; val sh=h*0.10f
        val rects = listOf(
            RectButton("lt",RectF(w*0.03f,h*0.03f,w*0.03f+sw,h*0.03f+sh),"LT"),
            RectButton("lb",RectF(w*0.03f,h*0.15f,w*0.03f+sw,h*0.15f+sh),"LB"),
            RectButton("rt",RectF(w*0.79f,h*0.03f,w*0.79f+sw,h*0.03f+sh),"RT"),
            RectButton("rb",RectF(w*0.79f,h*0.15f,w*0.79f+sw,h*0.15f+sh),"RB"),
        )
        rects.firstOrNull { it.rect.contains(x,y) }?.let { pointerKeys[id]=it.key; setButton(it.key,true); buzz(); return }

        val dcx=w*0.36f; val dcy=h*0.70f; val s=m*0.075f
        val dpad = listOf(
            "up" to RectF(dcx-s*0.6f,dcy-s*1.65f,dcx+s*0.6f,dcy-s*0.20f),
            "down" to RectF(dcx-s*0.6f,dcy+s*0.20f,dcx+s*0.6f,dcy+s*1.65f),
            "left" to RectF(dcx-s*1.65f,dcy-s*0.6f,dcx-s*0.20f,dcy+s*0.6f),
            "right" to RectF(dcx+s*0.20f,dcy-s*0.6f,dcx+s*1.65f,dcy+s*0.6f),
        )
        dpad.firstOrNull { it.second.contains(x,y) }?.let { pointerKeys[id]=it.first; setButton(it.first,true); buzz() }
    }

    private fun updatePointer(id: Int, x: Float, y: Float) {
        when(pointerKeys[id]) {
            "lstick" -> updateStick(true,x,y)
            "rstick" -> updateStick(false,x,y)
        }
    }

    private fun updateStick(left: Boolean, x: Float, y: Float) {
        val cx = if (left) leftCx else rightCx
        val cy = if (left) leftCy else rightCy
        var nx = (x-cx)/stickRadius
        var ny = (cy-y)/stickRadius
        val mag = hypot(nx, ny)
        if (mag > 1f) { nx/=mag; ny/=mag }
        val dz = deadzone.coerceIn(0f,0.35f)
        fun curve(v: Float): Float {
            val a = kotlin.math.abs(v)
            if (a < dz) return 0f
            val n = ((a-dz)/(1f-dz)).coerceIn(0f,1f)
            return kotlin.math.sign(v) * (n*sensitivity).coerceIn(0f,1f)
        }
        nx=curve(nx); ny=curve(ny)
        if(left){ state.lx=nx; state.ly=ny } else { state.rx=nx; state.ry=ny }
    }

    private fun releasePointer(id: Int) {
        when(val key = pointerKeys.remove(id)) {
            "lstick" -> { state.lx=0f; state.ly=0f; leftStickPointer=null }
            "rstick" -> { state.rx=0f; state.ry=0f; rightStickPointer=null }
            null -> {}
            else -> setButton(key,false)
        }
    }

    private fun setButton(key: String, down: Boolean) {
        when(key){
            "a"->state.a=down; "b"->state.b=down; "x"->state.x=down; "y"->state.y=down
            "lb"->state.lb=down; "rb"->state.rb=down
            "lt"->state.lt=if(down)1f else 0f; "rt"->state.rt=if(down)1f else 0f
            "up"->state.up=down; "down"->state.down=down; "left"->state.left=down; "right"->state.right=down
            "view"->state.view=down; "menu"->state.menu=down; "guide"->state.guide=down
            "l3"->state.l3=down; "r3"->state.r3=down
        }
    }

    private fun getButton(key: String): Boolean = when(key){
        "a"->state.a; "b"->state.b; "x"->state.x; "y"->state.y; "lb"->state.lb; "rb"->state.rb
        "lt"->state.lt>0.5f; "rt"->state.rt>0.5f; "up"->state.up; "down"->state.down; "left"->state.left; "right"->state.right
        "view"->state.view; "menu"->state.menu; "guide"->state.guide; else->false
    }

    private fun buzz() {
        if (!vibrationEnabled || vibrator == null || !vibrator.hasVibrator()) return
        try { vibrator.vibrate(VibrationEffect.createOneShot(16, 70)) } catch (_: Throwable) {}
    }
}
