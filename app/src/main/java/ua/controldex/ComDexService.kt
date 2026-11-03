package ua.controldex

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Foreground-сервіс (тип connectedDevice) зі сповіщенням: поки COM-DEX має бути підключений,
 * MIUI та інші оболонки не вбивають процес при згорнутому застосунку або вимкненому екрані.
 * Саме з'єднання живе в Core; сервіс лише тримає процес і показує стан.
 */
class ComDexService : Service() {

    companion object {
        private const val CHANNEL = "connection"
        private const val ID = 1
        private const val ACTION_STOP = "ua.controldex.STOP"
        @Volatile private var running = false

        fun start(c: Context) {
            val i = Intent(c, ComDexService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            } catch (e: Exception) {
                Core.log("Сервіс не запущено: ${e.javaClass.simpleName} ${e.message}")
            }
        }

        fun stop(c: Context) {
            if (running) c.stopService(Intent(c, ComDexService::class.java))
        }

        fun update(c: Context) {
            if (!running) return
            try { c.getSystemService(NotificationManager::class.java).notify(ID, build(c)) } catch (_: Exception) {}
        }

        private fun build(c: Context): Notification {
            val open = PendingIntent.getActivity(c, 0,
                Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(c, 1,
                Intent(c, ComDexService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val bat = if (Core.battery >= 0) " · батарея ${Core.battery} %" else ""
            @Suppress("DEPRECATION")
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CHANNEL) else Notification.Builder(c)
            return b.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("COM-DEX$bat")
                .setContentText(Core.status)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Від'єднатись", stop).build())
                .build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Core.init(this)
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "З'єднання з COM-DEX", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, build(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(ID, build(this))
        } catch (e: Exception) {
            Core.log("startForeground: ${e.javaClass.simpleName} ${e.message}")
            stopSelf(); return START_NOT_STICKY
        }
        running = true
        if (intent?.action == ACTION_STOP) {
            Core.log("Відключення зі сповіщення")
            Core.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        // intent == null: система перезапустила вбитий процес — підключаємось до останнього COM-DEX
        if (intent == null && !Core.gatt.wantConnected) {
            Core.log("Сервіс перезапущено системою")
            if (!Core.autoConnect()) { stopSelf(); return START_NOT_STICKY }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
