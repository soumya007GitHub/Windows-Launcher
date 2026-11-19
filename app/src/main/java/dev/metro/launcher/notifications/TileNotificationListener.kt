package dev.metro.launcher.notifications

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a live tile's back face shows for one app. Held in memory only; never persisted or logged. */
data class NotificationPreview(
    val packageName: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    /** Number of active, user-visible notifications for the package (tile badge). */
    val count: Int,
    /** The app's monochrome status-bar icon (drawn white on the lock screen, Metro style). */
    val smallIcon: android.graphics.drawable.Icon? = null,
)

/** One notification as the Action Center lists it. Held in memory only; never persisted or logged. */
data class ActionCenterItem(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val clearable: Boolean,
    val contentIntent: android.app.PendingIntent?,
    val autoCancel: Boolean,
    /** The app's monochrome status-bar icon, drawn white on an accent square (WP style). */
    val smallIcon: android.graphics.drawable.Icon? = null,
)

/** Process-wide, in-memory store of the latest preview per package. */
object NotificationRepository {

    private const val MAX_TEXT_CHARS = 160

    private val _previews = MutableStateFlow<Map<String, NotificationPreview>>(emptyMap())
    val previews: StateFlow<Map<String, NotificationPreview>> = _previews.asStateFlow()

    private val _items = MutableStateFlow<List<ActionCenterItem>>(emptyList())
    /** Every visible notification, newest first, for the Action Center. */
    val items: StateFlow<List<ActionCenterItem>> = _items.asStateFlow()

    /** Removes one notification (only clearable ones), as swiping it away in Android's shade would. */
    fun dismiss(key: String) {
        TileNotificationListener.cancel(key)
    }

    /** "clear all": removes every clearable notification. */
    fun dismissAll() {
        TileNotificationListener.cancelAll()
    }

    fun isAccessGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    internal fun clear() {
        _previews.value = emptyMap()
        _items.value = emptyList()
    }

    internal fun publish(active: Array<StatusBarNotification>, ownPackage: String) {
        _items.value = active
            .filter { it.packageName != ownPackage && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .mapNotNull { sbn ->
                val extras = sbn.notification.extras
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString().orEmpty()
                if (title.isBlank() && text.isBlank()) return@mapNotNull null
                ActionCenterItem(
                    key = sbn.key,
                    packageName = sbn.packageName,
                    title = title.take(MAX_TEXT_CHARS),
                    text = text.take(MAX_TEXT_CHARS),
                    postedAt = sbn.postTime,
                    clearable = sbn.isClearable,
                    contentIntent = sbn.notification.contentIntent,
                    autoCancel = sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0,
                    smallIcon = sbn.notification.smallIcon,
                )
            }
            .sortedByDescending { it.postedAt }
        val byPackage = HashMap<String, MutableList<StatusBarNotification>>()
        for (sbn in active) {
            if (!isTileWorthy(sbn, ownPackage)) continue
            byPackage.getOrPut(sbn.packageName) { ArrayList() } += sbn
        }
        _previews.value = byPackage.mapValues { (pkg, list) ->
            val latest = list.maxBy { it.postTime }
            val extras = latest.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            NotificationPreview(
                packageName = pkg,
                title = title.take(MAX_TEXT_CHARS),
                text = text.take(MAX_TEXT_CHARS),
                postedAt = latest.postTime,
                count = list.size,
                smallIcon = latest.notification.smallIcon,
            )
        }.filterValues { it.title.isNotBlank() || it.text.isNotBlank() }
    }

    private fun isTileWorthy(sbn: StatusBarNotification, ownPackage: String): Boolean {
        val n = sbn.notification
        if (sbn.packageName == ownPackage) return false
        if (sbn.isOngoing) return false // music, navigation, foreground services
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return false
        if (n.visibility == Notification.VISIBILITY_SECRET) return false
        return true
    }
}

/**
 * Mirrors active notifications into [NotificationRepository]. The user must grant
 * "Notification access" in system settings; nothing is read until they do.
 */
class TileNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        if (!dev.metro.launcher.home.MetroSwitches.isMetroActive(this)) {
            // Metro is switched off in its settings: stay unbound until it is turned back on.
            runCatching { requestUnbind() }
            return
        }
        running = this
        refresh()
    }

    override fun onListenerDisconnected() {
        if (running === this) running = null
        NotificationRepository.clear()
        // Ask the system to rebind us (e.g. after the process was killed), unless Metro is switched off.
        if (dev.metro.launcher.home.MetroSwitches.isMetroActive(this)) {
            requestRebind(ComponentName(this, TileNotificationListener::class.java))
        }
    }

    companion object {
        @Volatile
        private var running: TileNotificationListener? = null

        fun cancel(key: String) {
            running?.let { runCatching { it.cancelNotification(key) } }
        }

        fun cancelAll() {
            running?.let { runCatching { it.cancelAllNotifications() } }
        }

        /** Stops delivering notifications to Metro (access stays granted, so it can be turned back on). */
        fun unbindRunning() {
            running?.let { runCatching { it.requestUnbind() } }
            NotificationRepository.clear()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    private fun refresh() {
        val active = try {
            activeNotifications ?: emptyArray()
        } catch (_: SecurityException) {
            emptyArray() // listener not (yet) bound
        }
        NotificationRepository.publish(active, packageName)
    }
}
