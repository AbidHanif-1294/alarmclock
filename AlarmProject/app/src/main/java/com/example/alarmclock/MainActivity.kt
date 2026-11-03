package com.example.alarmlock

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var etPin: EditText
    private lateinit var etPin2: EditText
    private lateinit var etUrl: EditText
    private lateinit var etToken: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("alarm_prefs", MODE_PRIVATE)
        etPin   = findViewById(R.id.etPin)
        etPin2  = findViewById(R.id.etPin2)
        etUrl   = findViewById(R.id.etServerUrl)
        etToken = findViewById(R.id.etToken)

        // restore config
        etUrl.setText(prefs.getString("server_url", ""))
        etToken.setText(prefs.getString("token", ""))

        mintaIzinNotif()

        findViewById<Button>(R.id.btnArm).setOnClickListener { arm() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { testTrigger() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopWithPin() }
    }

    private fun arm() {
        val p1 = etPin.text.toString()
        val p2 = etPin2.text.toString()
        val url = etUrl.text.toString().trim().trimEnd('/')
        val tok = etToken.text.toString().trim()

        if (p1.length < 4) return toast("PIN minimal 4 digit")
        if (p1 != p2)     return toast("Konfirmasi PIN tidak sama")
        if (url.isEmpty()) return toast("URL server kosong")
        if (tok.isEmpty()) return toast("Token kosong")
        if (!punyaPolicyAccess()) {
            toast("Izinkan akses Do Not Disturb dulu, lalu tekan ARM lagi")
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return
        }

        prefs.edit()
            .putString("pin", p1)
            .putString("server_url", url)
            .putString("token", tok)
            .putBoolean("armed", true)
            .putBoolean("alarming", false)
            .apply()

        ContextCompat.startForegroundService(
            this, Intent(this, AlarmService::class.java).setAction(AlarmService.ACTION_ARM)
        )
        toast("ARMED. HP standby. Trigger dari laptop kapan pun.")
    }

    private fun testTrigger() {
        if (prefs.getString("pin", null).isNullOrEmpty()) return toast("Set PIN dulu")
        ContextCompat.startForegroundService(
            this, Intent(this, AlarmService::class.java).setAction(AlarmService.ACTION_TRIGGER)
        )
        toast("Test: alarm bunyi. Masukkan PIN untuk stop.")
    }

    private fun stopWithPin() {
        val p = etPin.text.toString()
        if (p.isNotEmpty() && p == prefs.getString("pin", null)) {
            ContextCompat.startForegroundService(
                this, Intent(this, AlarmService::class.java).setAction(AlarmService.ACTION_STOP)
            )
            toast("Alarm dimatikan")
        } else {
            toast("PIN salah")
        }
    }

    private fun punyaPolicyAccess(): Boolean =
        getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    private fun mintaIzinNotif() {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (list.isNotEmpty()) requestPermissions(list.toTypedArray(), 100)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}