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
)

/** Process-wide, in-memory store of the latest preview per package. */
object NotificationRepository {

    private const val MAX_TEXT_CHARS = 160

    private val _previews = MutableStateFlow<Map<String, NotificationPreview>>(emptyMap())
    val previews: StateFlow<Map<String, NotificationPreview>> = _previews.asStateFlow()

    fun isAccessGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    internal fun clear() {
        _previews.value = emptyMap()
    }

    internal fun publish(active: Array<StatusBarNotification>, ownPackage: String) {
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

    override fun onListenerConnected() = refresh()

    override fun onListenerDisconnected() {
        NotificationRepository.clear()
        // Ask the system to rebind us (e.g. after the process was killed).
        requestRebind(ComponentName(this, TileNotificationListener::class.java))
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
