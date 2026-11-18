package dev.metro.launcher.lock

import android.content.Context
import android.content.Intent
import android.media.AudioManager

/**
 * Process-wide "curtain is down" flag. While it is set, every way out of the curtain except
 * lifting it (Home, Recents, a notification, another app) brings the curtain straight back.
 *
 * This is a usage gate for phones with no PIN, not security: the notification shade and quick
 * settings stay reachable, because Android only lets the real keyguard block those.
 */
object LockGate {

    @Volatile
    var locked: Boolean = false
        private set

    fun show(context: Context) {
        locked = true
        bringToFront(context)
    }

    fun release() {
        locked = false
    }

    fun bringToFront(context: Context) {
        runCatching {
            context.startActivity(
                Intent(context, LockscreenActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION,
                ),
            )
        }
    }

    /** Never cover a ringing or active call. */
    fun isCallActive(context: Context): Boolean {
        val mode = context.getSystemService(AudioManager::class.java).mode
        return mode == AudioManager.MODE_RINGTONE ||
            mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION
    }
}
