package com.jonathan.phonexboxcontroller

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var gamepadView: GamepadView
    private lateinit var client: UdpControllerClient
    private lateinit var status: TextView

    private val prefs by lazy { getSharedPreferences("controller", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUi()

        val root = FrameLayout(this)
        gamepadView = GamepadView(this)
        gamepadView.sensitivity = prefs.getFloat("sensitivity", 1.0f)
        gamepadView.deadzone = prefs.getFloat("deadzone", 0.08f)
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
        refreshStatus()

        client = UdpControllerClient(
            hostProvider = { prefs.getString("host", "192.168.137.1") ?: "192.168.137.1" },
            portProvider = { prefs.getInt("port", 45990) },
            stateProvider = { gamepadView.state },
        )
        client.start()

        if (!prefs.getBoolean("configured", false)) showSettings()
    }

    private fun refreshStatus() {
        val host = prefs.getString("host", "192.168.137.1") ?: "192.168.137.1"
        val port = prefs.getInt("port", 45990)
        status.text = "PC: $host:$port   •   toque aqui para configurar"
    }

    private fun showSettings() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 10, 40, 0)
        }
        val hostInput = EditText(this).apply {
            hint = "IP do PC"
            setText(prefs.getString("host", "192.168.137.1"))
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        val portInput = EditText(this).apply {
            hint = "Porta"
            setText(prefs.getInt("port", 45990).toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val sensLabel = TextView(this).apply { text = "Sensibilidade dos analógicos" }
        val sens = SeekBar(this).apply {
            max = 100
            progress = (((prefs.getFloat("sensitivity",1f)-0.5f)/1.0f)*100f).toInt().coerceIn(0,100)
        }
        val dzLabel = TextView(this).apply { text = "Zona morta" }
        val dz = SeekBar(this).apply {
            max = 25
            progress = (prefs.getFloat("deadzone",0.08f)*100f).toInt().coerceIn(0,25)
        }
        content.addView(hostInput)
        content.addView(portInput)
        content.addView(sensLabel)
        content.addView(sens)
        content.addView(dzLabel)
        content.addView(dz)

        AlertDialog.Builder(this)
            .setTitle("Conexão com o PC")
            .setMessage("Recomendado: USB com Ancoragem USB ativada. Também funciona pela mesma rede Wi‑Fi.")
            .setView(content)
            .setPositiveButton("SALVAR") { _, _ ->
                val host = hostInput.text.toString().trim().ifBlank { "192.168.137.1" }
                val port = portInput.text.toString().toIntOrNull()?.coerceIn(1,65535) ?: 45990
                val sensitivity = 0.5f + sens.progress/100f
                val deadzone = dz.progress/100f
                prefs.edit()
                    .putString("host", host)
                    .putInt("port", port)
                    .putFloat("sensitivity", sensitivity)
                    .putFloat("deadzone", deadzone)
                    .putBoolean("configured", true)
                    .apply()
                gamepadView.sensitivity = sensitivity
                gamepadView.deadzone = deadzone
                refreshStatus()
            }
            .setNegativeButton("CANCELAR", null)
            .show()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private fun hideSystemUi() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
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
        client.stop()
        super.onDestroy()
    }
}
