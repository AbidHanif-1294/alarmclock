package com.example.alarmlock

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.*
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AlarmService : Service() {

    companion object {
        const val ACTION_ARM     = "com.example.alarmlock.ARM"
        const val ACTION_TRIGGER = "com.example.alarmlock.TRIGGER"
        const val ACTION_STOP    = "com.example.alarmlock.STOP"

        const val CH_ID    = "alarm_ch"
        const val NOTIF_ID = 9911
        const val POLL_MS     = 10_000L
        const val WATCHDOG_MS = 500L
    }

    private lateinit var prefs: SharedPreferences
    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var volumeSebelumnya = -1

    @Volatile private var polling = false
    private var pollThread: Thread? = null
    @Volatile private var alarming = false

    private val watchdog = object : Runnable {
        override fun run() {
            paksaVolumeMaks()
            if (alarming) handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("alarm_prefs", MODE_PRIVATE)
        createChannel()
        startForeground(NOTIF_ID, buildNotif("Standby — menunggu trigger"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ARM -> {
                prefs.edit().putBoolean("armed", true).apply()
                startPolling()
            }
            ACTION_TRIGGER -> switchToAlarming()
            ACTION_STOP -> {
                prefs.edit().putBoolean("armed", false)
                    .putBoolean("alarming", false).apply()
                kirimAckStop()           // ← patch: beri tahu server saat stop dari HP
                shutdown()
                return START_NOT_STICKY
            }
            else -> {
                // restart oleh OS
                if (prefs.getBoolean("armed", false)) {
                    startPolling()
                    if (prefs.getBoolean("alarming", false)) switchToAlarming()
                } else {
                    stopSelf(); return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    // ---------- polling ----------
    private fun startPolling() {
        if (polling) return
        val serverUrl = prefs.getString("server_url", null)
        val token     = prefs.getString("token", null)
        if (serverUrl.isNullOrEmpty() || token.isNullOrEmpty()) return

        polling = true
        pollThread = Thread {
            while (polling) {
                val res = try { pollServer(serverUrl, token) } catch (_: Exception) { null }
                if (res != null) {
                    handler.post {
                        // ← patch: stop diprioritaskan kalau keduanya ter-set
                        if (res.stop) {
                            switchToIdle()
                            Thread { ack(serverUrl, token, "stop") }.start()
                        } else if (res.trigger && !alarming) {
                            switchToAlarming()
                            Thread { ack(serverUrl, token, "trigger") }.start()
                        }
                    }
                }
                try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
    }

    private data class PollResult(val trigger: Boolean, val stop: Boolean)

    private fun pollServer(base: String, token: String): PollResult? {
        val conn = (URL("$base/poll").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("X-Token", token)
            connectTimeout = 5000; readTimeout = 5000
        }
        return try {
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            PollResult(json.optBoolean("trigger"), json.optBoolean("stop"))
        } catch (_: Exception) { null } finally { conn.disconnect() }
    }

    private fun ack(base: String, token: String, type: String) {
        try {
            val conn = (URL("$base/ack?type=$type").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("X-Token", token)
                connectTimeout = 5000; readTimeout = 5000
                doOutput = true
                outputStream.write(ByteArray(0))
            }
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) { }
    }

    // ---------- patch: ack stop saat dimatikan dari HP ----------
    private fun kirimAckStop() {
        val url = prefs.getString("server_url", null) ?: return
        val tok = prefs.getString("token", null) ?: return
        Thread {
            try { ack(url, tok, "stop") } catch (_: Exception) { }
        }.start()
    }

    // ---------- state switch ----------
    private fun switchToAlarming() {
        if (alarming) return
        alarming = true
        prefs.edit().putBoolean("alarming", true).apply()
        nyalakan()
        handler.removeCallbacks(watchdog)
        handler.post(watchdog)
        notifUpdate("ALARM BERBUNYI — Masukkan PIN")
    }

    private fun switchToIdle() {
        if (!alarming) return
        alarming = false
        prefs.edit().putBoolean("alarming", false).apply()
        handler.removeCallbacks(watchdog)
        matikan()
        notifUpdate("Standby — menunggu trigger")
    }

    private fun shutdown() {
        polling = false
        pollThread?.interrupt()
        handler.removeCallbacks(watchdog)
        kembalikanVolume()
        matikan()
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------- audio ----------
    private fun nyalakan() {
        val am = getSystemService(AudioManager::class.java)
        volumeSebelumnya = am.getStreamVolume(AudioManager.STREAM_ALARM)
        paksaVolumeMaks()

        player = MediaPlayer.create(this, R.raw.alarm)?.apply {
            isLooping = true
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setVolume(1f, 1f)
            start()
        }

        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "AlarmLock::alarm"
        ).apply { acquire(12 * 60 * 60 * 1000L) }
    }

    private fun paksaVolumeMaks() {
        val am = getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        if (am.getStreamVolume(AudioManager.STREAM_ALARM) != max) {
            try { am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0) }
            catch (_: SecurityException) { }
        }
        player?.setVolume(1f, 1f)
    }

    private fun kembalikanVolume() {
        if (volumeSebelumnya < 0) return
        try {
            getSystemService(AudioManager::class.java)
                .setStreamVolume(AudioManager.STREAM_ALARM, volumeSebelumnya, 0)
        } catch (_: SecurityException) { }
        volumeSebelumnya = -1
    }

    private fun matikan() {
        player?.stop()
        player?.release()
        player = null
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    // ---------- notifikasi ----------
    private fun buildNotif(msg: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, StopAlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("Alarm Anti-Hilang")
            .setContentText(msg)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(open, alarming)
            .setContentIntent(open)
            .build()
    }

    private fun notifUpdate(msg: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(msg))
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CH_ID, "Alarm", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        nm.createNotificationChannel(ch)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (prefs.getBoolean("armed", false)) {
            val pi = PendingIntent.getService(
                this, 1,
                Intent(applicationContext, AlarmService::class.java),
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000, pi
            )
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        polling = false
        handler.removeCallbacks(watchdog)
        matikan()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}