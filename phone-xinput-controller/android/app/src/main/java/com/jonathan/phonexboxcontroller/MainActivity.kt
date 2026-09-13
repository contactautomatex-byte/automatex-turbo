package com.jonathan.phonexboxcontroller

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), SensorEventListener {
    private lateinit var gamepadView: GamepadView
    private lateinit var client: UdpControllerClient
    private lateinit var status: TextView
    private val prefs by lazy { getSharedPreferences("controller_v2", MODE_PRIVATE) }

    private lateinit var sensorManager: SensorManager
    private var gyroSensor: Sensor? = null
    private var gyroRegistered = false
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        prepareLowLatencyWifi()
        requestHighRefreshRate()

        val root = FrameLayout(this)
        gamepadView = GamepadView(this).apply {
            moveSensitivity = prefs.getFloat("move_sensitivity", 1.0f)
            moveDeadzone = prefs.getFloat("move_deadzone", 0.08f)
            aimTurnSpeed = prefs.getFloat("aim_turn_speed", 1.0f)
            aimPrecision = prefs.getFloat("aim_precision", 1.55f)
            aimAcceleration = prefs.getFloat("aim_acceleration", 1.0f)
            aimAntiDeadzone = prefs.getFloat("aim_antideadzone", 0.27f)
            gyroEnabled = prefs.getBoolean("gyro_enabled", false)
            gyroSensitivity = prefs.getFloat("gyro_sensitivity", 0.42f)
            triggerLockEnabled = prefs.getBoolean("trigger_lock", true)
            vibrationEnabled = prefs.getBoolean("vibration", true)
            profile = runCatching {
                ControlProfile.valueOf(prefs.getString("profile", ControlProfile.UNIVERSAL.name)!!)
            }.getOrDefault(ControlProfile.UNIVERSAL)
            setThumbAnchors(
                prefs.getFloat("left_anchor_x", 0.22f),
                prefs.getFloat("left_anchor_y", 0.72f),
                prefs.getFloat("right_anchor_x", 0.78f),
                prefs.getFloat("right_anchor_y", 0.72f),
            )
        }
        root.addView(gamepadView, FrameLayout.LayoutParams(-1, -1))

        status = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            setBackgroundColor(Color.argb(145, 0, 0, 0))
            setPadding(18, 9, 18, 9)
            textSize = 11f
            setOnClickListener { showSettings() }
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = 5
        })

        setContentView(root)
        root.post { hideSystemUi() }

        client = UdpControllerClient(
            hostProvider = { prefs.getString("host", "192.168.137.1") ?: "192.168.137.1" },
            portProvider = { prefs.getInt("port", 45990) },
            stateProvider = { gamepadView.state },
        )
        client.start()
        gamepadView.onStateChanged = { client.sendNow() }
        gamepadView.onCalibrationComplete = { lx, ly, rx, ry ->
            prefs.edit()
                .putFloat("left_anchor_x", lx).putFloat("left_anchor_y", ly)
                .putFloat("right_anchor_x", rx).putFloat("right_anchor_y", ry)
                .putBoolean("thumb_calibrated", true)
                .apply()
            Toast.makeText(this, "Pegada calibrada", Toast.LENGTH_SHORT).show()
        }

        refreshStatus()
        root.post {
            when {
                !prefs.getBoolean("configured", false) -> showSettings()
                !prefs.getBoolean("thumb_calibrated", false) -> gamepadView.startCalibration()
            }
        }
    }

    private fun refreshStatus() {
        val host = prefs.getString("host", "192.168.137.1") ?: "192.168.137.1"
        val port = prefs.getInt("port", 45990)
        val p = when (gamepadView.profile) {
            ControlProfile.UNIVERSAL -> "UNIVERSAL"
            ControlProfile.FPS -> "FPS"
            ControlProfile.RACING -> "CORRIDA"
        }
        status.text = "$p   •   PC $host:$port   •   toque para configurar"
    }

    private fun label(textValue: String) = TextView(this).apply {
        text = textValue
        setTextColor(Color.DKGRAY)
        textSize = 13f
        setPadding(0, 8, 0, 0)
    }

    private fun showSettings() {
        gamepadView.resetAllInputs()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 4, 32, 12)
        }

        content.addView(TextView(this).apply {
            text = "V2: controle touch ergonômico. FPS usa uma grande área de mira; Universal usa dois sticks. USB com Ancoragem USB oferece a menor latência."
            setTextColor(Color.DKGRAY); textSize = 13f; setPadding(0, 0, 0, 8)
        })

        val hostInput = EditText(this).apply {
            hint = "IP do PC"; setText(prefs.getString("host", "192.168.137.1"))
            inputType = android.text.InputType.TYPE_CLASS_PHONE; setSingleLine(true)
        }
        val portInput = EditText(this).apply {
            hint = "Porta"; setText(prefs.getInt("port", 45990).toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER; setSingleLine(true)
        }
        content.addView(hostInput); content.addView(portInput)

        content.addView(label("Perfil"))
        val profileSpinner = Spinner(this)
        val names = listOf("Universal", "FPS / Tiro", "Corrida")
        profileSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        profileSpinner.setSelection(when (gamepadView.profile) {
            ControlProfile.UNIVERSAL -> 0; ControlProfile.FPS -> 1; ControlProfile.RACING -> 2
        })
        content.addView(profileSpinner)

        content.addView(label("Sensibilidade de movimento"))
        val move = SeekBar(this).apply {
            max = 100
            progress = (((gamepadView.moveSensitivity - 0.60f) / 0.80f) * 100f).toInt().coerceIn(0, 100)
        }
        content.addView(move)

        content.addView(label("Zona morta do movimento"))
        val moveDz = SeekBar(this).apply {
            max = 15; progress = (gamepadView.moveDeadzone * 100f).toInt().coerceIn(3, 18) - 3
        }
        content.addView(moveDz)

        content.addView(label("Precisão da mira — maior = microajuste mais suave"))
        val precision = SeekBar(this).apply {
            max = 100; progress = (((gamepadView.aimPrecision - 1.05f) / 1.55f) * 100f).toInt().coerceIn(0, 100)
        }
        content.addView(precision)

        content.addView(label("Velocidade máxima da mira"))
        val turn = SeekBar(this).apply {
            max = 100; progress = (((gamepadView.aimTurnSpeed - 0.55f) / 1.10f) * 100f).toInt().coerceIn(0, 100)
        }
        content.addView(turn)

        content.addView(label("Aceleração do swipe no modo FPS"))
        val accel = SeekBar(this).apply {
            max = 100; progress = (((gamepadView.aimAcceleration - 0.65f) / 1.15f) * 100f).toInt().coerceIn(0, 100)
        }
        content.addView(accel)

        content.addView(label("Anti-deadzone do stick direito"))
        val anti = SeekBar(this).apply {
            max = 100; progress = (((gamepadView.aimAntiDeadzone - 0.18f) / 0.18f) * 100f).toInt().coerceIn(0, 100)
        }
        content.addView(anti)

        val gyro = CheckBox(this).apply {
            text = "Giroscópio para microajuste enquanto LT estiver pressionado"
            isChecked = gamepadView.gyroEnabled
            isEnabled = gyroSensor != null
        }
        content.addView(gyro)
        content.addView(label("Sensibilidade do giroscópio"))
        val gyroSens = SeekBar(this).apply {
            max = 100; progress = (((gamepadView.gyroSensitivity - 0.15f) / 1.05f) * 100f).toInt().coerceIn(0, 100)
        }
        content.addView(gyroSens)

        val lockTriggers = CheckBox(this).apply {
            text = "FPS: duplo toque em LT/RT trava o gatilho (toque novamente para soltar)"
            isChecked = gamepadView.triggerLockEnabled
        }
        content.addView(lockTriggers)

        val vibration = CheckBox(this).apply {
            text = "Feedback tátil nos botões"; isChecked = gamepadView.vibrationEnabled
        }
        content.addView(vibration)

        content.addView(TextView(this).apply {
            text = "L3/R3 também podem ser acionados por duplo toque na região do respectivo analógico. As áreas clicáveis são maiores que o desenho dos botões e não se sobrepõem."
            setTextColor(Color.DKGRAY); textSize = 12f; setPadding(0, 10, 0, 4)
        })

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        scroll.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.58f).toInt(),
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle("Xbox Touch Controller V2")
            .setView(scroll)
            .setPositiveButton("SALVAR") { _, _ ->
                val host = hostInput.text.toString().trim().ifBlank { "192.168.137.1" }
                val port = portInput.text.toString().toIntOrNull()?.coerceIn(1, 65535) ?: 45990
                val profile = when (profileSpinner.selectedItemPosition) {
                    1 -> ControlProfile.FPS
                    2 -> ControlProfile.RACING
                    else -> ControlProfile.UNIVERSAL
                }
                val moveSensitivity = 0.60f + move.progress / 100f * 0.80f
                val moveDeadzone = (moveDz.progress + 3) / 100f
                val aimPrecision = 1.05f + precision.progress / 100f * 1.55f
                val turnSpeed = 0.55f + turn.progress / 100f * 1.10f
                val acceleration = 0.65f + accel.progress / 100f * 1.15f
                val antiDeadzone = 0.18f + anti.progress / 100f * 0.18f
                val gyroSensitivity = 0.15f + gyroSens.progress / 100f * 1.05f

                prefs.edit()
                    .putString("host", host).putInt("port", port)
                    .putString("profile", profile.name)
                    .putFloat("move_sensitivity", moveSensitivity).putFloat("move_deadzone", moveDeadzone)
                    .putFloat("aim_precision", aimPrecision).putFloat("aim_turn_speed", turnSpeed)
                    .putFloat("aim_acceleration", acceleration).putFloat("aim_antideadzone", antiDeadzone)
                    .putBoolean("gyro_enabled", gyro.isChecked).putFloat("gyro_sensitivity", gyroSensitivity)
                    .putBoolean("trigger_lock", lockTriggers.isChecked).putBoolean("vibration", vibration.isChecked)
                    .putBoolean("configured", true).apply()

                gamepadView.profile = profile
                gamepadView.moveSensitivity = moveSensitivity
                gamepadView.moveDeadzone = moveDeadzone
                gamepadView.aimPrecision = aimPrecision
                gamepadView.aimTurnSpeed = turnSpeed
                gamepadView.aimAcceleration = acceleration
                gamepadView.aimAntiDeadzone = antiDeadzone
                gamepadView.gyroEnabled = gyro.isChecked
                gamepadView.gyroSensitivity = gyroSensitivity
                gamepadView.triggerLockEnabled = lockTriggers.isChecked
                gamepadView.vibrationEnabled = vibration.isChecked
                refreshStatus(); updateGyroRegistration(); client.sendNow()
                if (!prefs.getBoolean("thumb_calibrated", false)) gamepadView.post { gamepadView.startCalibration() }
            }
            .setNegativeButton("CANCELAR", null)
            .setNeutralButton("CALIBRAR MÃOS") { _, _ -> gamepadView.post { gamepadView.startCalibration() } }
            .create()

        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.show()
    }

    private fun prepareLowLatencyWifi() {
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "XboxTouchController:LowLatency").apply { setReferenceCounted(false) }
        } catch (_: Throwable) { wifiLock = null }
    }

    private fun requestHighRefreshRate() {
        try {
            val displayObj = if (Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
            val best = displayObj?.supportedModes?.maxByOrNull { it.refreshRate }
            if (best != null) {
                val lp = window.attributes
                lp.preferredDisplayModeId = best.modeId
                window.attributes = lp
            }
        } catch (_: Throwable) { }
    }

    private fun updateGyroRegistration() {
        val want = ::gamepadView.isInitialized && gamepadView.gyroEnabled && gamepadView.profile == ControlProfile.FPS && gyroSensor != null
        if (want && !gyroRegistered) {
            gyroRegistered = sensorManager.registerListener(this, gyroSensor, 5000)
        } else if (!want && gyroRegistered) {
            sensorManager.unregisterListener(this)
            gyroRegistered = false
            if (::gamepadView.isInitialized) gamepadView.setGyroInput(0f, 0f)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE || !::gamepadView.isInitialized) return
        val gx = event.values[0]; val gy = event.values[1]
        val rotation = try {
            if (Build.VERSION.SDK_INT >= 30) display?.rotation ?: Surface.ROTATION_90
            else @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
        } catch (_: Throwable) { Surface.ROTATION_90 }

        val yaw: Float
        val pitch: Float
        when (rotation) {
            Surface.ROTATION_270 -> { yaw = gx; pitch = -gy }
            Surface.ROTATION_90 -> { yaw = -gx; pitch = gy }
            Surface.ROTATION_180 -> { yaw = -gy; pitch = -gx }
            else -> { yaw = gy; pitch = gx }
        }
        gamepadView.setGyroInput(yaw, pitch)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onResume() {
        super.onResume()
        try { if (wifiLock?.isHeld != true) wifiLock?.acquire() } catch (_: Throwable) { }
        if (::gamepadView.isInitialized) updateGyroRegistration()
    }

    override fun onPause() {
        if (::gamepadView.isInitialized) gamepadView.resetAllInputs()
        if (gyroRegistered) { sensorManager.unregisterListener(this); gyroRegistered = false }
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Throwable) { }
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
        if (!decor.isAttachedToWindow) { decor.post { hideSystemUi() }; return }
        if (Build.VERSION.SDK_INT >= 30) {
            decor.windowInsetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    override fun onDestroy() {
        if (::gamepadView.isInitialized) gamepadView.resetAllInputs()
        if (::client.isInitialized) client.stop()
        if (gyroRegistered) sensorManager.unregisterListener(this)
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Throwable) { }
        super.onDestroy()
    }
}
