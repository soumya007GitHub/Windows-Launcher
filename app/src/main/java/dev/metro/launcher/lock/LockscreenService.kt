package dev.metro.launcher.lock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.metro.launcher.MainActivity
import dev.metro.launcher.data.LauncherStore
import dev.metro.launcher.sound.MetroSounds
import dev.metro.launcher.volume.AccessibilityKeeper

/**
 * Keeps an ACTION_SCREEN_OFF receiver alive (it cannot be declared in the manifest) and opens the
 * curtain the moment the screen goes off, so it is already in place when the user wakes the phone.
 *
 * Opening an activity from the background is allowed because the user granted "display over other
 * apps" (SYSTEM_ALERT_WINDOW). Without it the service does nothing.
 */
class LockscreenService : Service() {

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) {
                AccessibilityKeeper.ensure(context)
                return
            }
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                if (!LockGate.isCallActive(context)) MetroSounds.play(context, MetroSounds.Kind.Lock)
                showCurtain()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        MetroSounds.preload(this) // decode lock / unlock sounds now, so they play instantly
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!LauncherStore.get(this).settings.value.lockscreenEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SHOW) showCurtain() // e.g. right after boot
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(screenOffReceiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showCurtain() {
        if (!LauncherStore.get(this).settings.value.lockscreenEnabled) return
        if (!Settings.canDrawOverlays(this)) return
        // Screen-off during a call is the proximity sensor, not the user locking the phone.
        if (LockGate.isCallActive(this)) return
        LockGate.show(this)
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Lockscreen", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                description = "Keeps the Metro lockscreen ready while the screen is off"
            },
        )
        val openLauncher = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Metro lockscreen is on")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openLauncher)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "lockscreen"
        private const val NOTIFICATION_ID = 7
        const val ACTION_SHOW = "dev.metro.launcher.lock.SHOW"

        fun start(context: Context, showNow: Boolean = false) {
            runCatching {
                val intent = Intent(context, LockscreenService::class.java)
                if (showNow) intent.action = ACTION_SHOW
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LockscreenService::class.java))
        }
    }
}

/** Restarts the lockscreen service after a reboot or an app update, if the user turned it on. */
class LockscreenBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        AccessibilityKeeper.ensure(context)
        if (LauncherStore.get(context).settings.value.lockscreenEnabled) {
            // After a reboot the phone starts behind the curtain, like WP.
            LockscreenService.start(context, showNow = action == Intent.ACTION_BOOT_COMPLETED)
        }
    }
}
