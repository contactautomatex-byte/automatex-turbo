package com.jonathan.phonexboxcontroller

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var gamepadView: GamepadView
    private lateinit var client: UdpControllerClient
    private lateinit var status: TextView

    private val prefs by lazy { getSharedPreferences("controller", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        gamepadView = GamepadView(this).apply {
            moveSensitivity = prefs.getFloat("move_sensitivity", 1.0f)
            aimSensitivity = prefs.getFloat("aim_sensitivity", 0.90f)
            moveDeadzone = prefs.getFloat("move_deadzone", 0.08f)
        }
        root.addView(gamepadView, FrameLayout.LayoutParams(-1, -1))

        status = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            setBackgroundColor(Color.argb(145, 0, 0, 0))
            setPadding(18, 10, 18, 10)
            textSize = 12f
            setOnClickListener { showSettings() }
        }
        val statusLp = FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = 8
        }
        root.addView(status, statusLp)

        setContentView(root)
        root.post { hideSystemUi() }
        refreshStatus()

        client = UdpControllerClient(
            hostProvider = { prefs.getString("host", "192.168.137.1") ?: "192.168.137.1" },
            portProvider = { prefs.getInt("port", 45990) },
            stateProvider = { gamepadView.state },
        )
        client.start()

        if (!prefs.getBoolean("configured", false)) root.post { showSettings() }
    }

    private fun refreshStatus() {
        val host = prefs.getString("host", "192.168.137.1") ?: "192.168.137.1"
        val port = prefs.getInt("port", 45990)
        status.text = "PC: $host:$port   •   toque aqui para configurar"
    }

    private fun showSettings() {
        gamepadView.resetAllInputs()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(34, 6, 34, 12)
        }

        val info = TextView(this).apply {
            text = "USB com Ancoragem USB oferece a menor latência. Wi‑Fi também funciona."
            setTextColor(Color.DKGRAY)
            textSize = 14f
            setPadding(0, 0, 0, 8)
        }

        val hostInput = EditText(this).apply {
            hint = "IP do PC"
            setText(prefs.getString("host", "192.168.137.1"))
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setSingleLine(true)
        }
        val portInput = EditText(this).apply {
            hint = "Porta"
            setText(prefs.getInt("port", 45990).toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }

        val moveLabel = TextView(this).apply {
            text = "Sensibilidade do analógico esquerdo"
            setPadding(0, 10, 0, 0)
        }
        val move = SeekBar(this).apply {
            max = 80
            progress = (((prefs.getFloat("move_sensitivity", 1.0f) - 0.60f) / 0.80f) * 80f).toInt().coerceIn(0, 80)
        }

        val aimLabel = TextView(this).apply {
            text = "Sensibilidade do analógico direito"
            setPadding(0, 10, 0, 0)
        }
        val aim = SeekBar(this).apply {
            max = 110
            progress = (((prefs.getFloat("aim_sensitivity", 0.90f) - 0.45f) / 1.10f) * 110f).toInt().coerceIn(0, 110)
        }

        val dzLabel = TextView(this).apply {
            text = "Zona morta do toque"
            setPadding(0, 10, 0, 0)
        }
        val dz = SeekBar(this).apply {
            max = 18
            progress = (prefs.getFloat("move_deadzone", 0.08f) * 100f).toInt().coerceIn(3, 18)
        }

        val hint = TextView(this).apply {
            text = "Os dois analógicos são joysticks virtuais reais: toque cria o centro, arraste e SEGURE para manter a direção; ao soltar, volta imediatamente ao centro."
            setTextColor(Color.DKGRAY)
            textSize = 13f
            setPadding(0, 12, 0, 4)
        }

        content.addView(info)
        content.addView(hostInput)
        content.addView(portInput)
        content.addView(moveLabel)
        content.addView(move)
        content.addView(aimLabel)
        content.addView(aim)
        content.addView(dzLabel)
        content.addView(dz)
        content.addView(hint)

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val maxBodyHeight = (resources.displayMetrics.heightPixels * 0.58f).toInt()
        scroll.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            maxBodyHeight,
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle("Xbox Phone Controller")
            .setView(scroll)
            .setPositiveButton("SALVAR") { _, _ ->
                val host = hostInput.text.toString().trim().ifBlank { "192.168.137.1" }
                val port = portInput.text.toString().toIntOrNull()?.coerceIn(1, 65535) ?: 45990
                val moveSensitivity = 0.60f + (move.progress / 80f) * 0.80f
                val aimSensitivity = 0.45f + (aim.progress / 110f) * 1.10f
                val deadzone = (dz.progress.coerceAtLeast(3)) / 100f

                prefs.edit()
                    .putString("host", host)
                    .putInt("port", port)
                    .putFloat("move_sensitivity", moveSensitivity)
                    .putFloat("aim_sensitivity", aimSensitivity)
                    .putFloat("move_deadzone", deadzone)
                    .putBoolean("configured", true)
                    .apply()

                gamepadView.moveSensitivity = moveSensitivity
                gamepadView.aimSensitivity = aimSensitivity
                gamepadView.moveDeadzone = deadzone
                refreshStatus()
            }
            .setNegativeButton("CANCELAR", null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.show()
    }

    override fun onPause() {
        if (::gamepadView.isInitialized) gamepadView.resetAllInputs()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.post { hideSystemUi() }
        else if (::gamepadView.isInitialized) gamepadView.resetAllInputs()
    }

    private fun hideSystemUi() {
        if (isFinishing || isDestroyed) return
        val decor = window.decorView
        if (!decor.isAttachedToWindow) {
            decor.post { hideSystemUi() }
            return
        }

        if (android.os.Build.VERSION.SDK_INT >= 30) {
            decor.windowInsetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    override fun onDestroy() {
        if (::gamepadView.isInitialized) gamepadView.resetAllInputs()
        if (::client.isInitialized) client.stop()
        super.onDestroy()
    }
}
