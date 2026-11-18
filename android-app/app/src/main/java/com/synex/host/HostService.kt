package com.synex.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager

/** A visible Android foreground service holds the app process while Java is running.
 * Android can still stop apps under memory pressure; this is not a 24/7 guarantee.
 */
class HostService : Service() {
    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("synex-server", "Servidor Synex Host", NotificationManager.IMPORTANCE_LOW))
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, "synex-server")
            .setContentTitle("Synex Host · Servidor encendido")
            .setContentText("Minecraft se ejecuta localmente en tu teléfono")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
        startForeground(1417, notification)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SynexHost:MinecraftServer").apply {
            setReferenceCounted(false)
            acquire()
        }
        runCatching {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SynexHost:LAN").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // When Android recreates a killed service, don't pretend that an old JVM survived.
        if (LocalEngine.get(this).servers().none { it.running }) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        wifiLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wifiLock = null
        cpuLock?.takeIf { it.isHeld }?.release()
        cpuLock = null
        super.onDestroy()
    }
}
