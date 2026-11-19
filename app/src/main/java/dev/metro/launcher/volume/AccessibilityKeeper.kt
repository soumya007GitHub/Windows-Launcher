package dev.metro.launcher.volume

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import dev.metro.launcher.data.LauncherStore

/**
 * Some OEM builds (OriginOS among them) switch off accessibility services of sideloaded apps,
 * which removes the Metro bar and volume panel. If the user has granted Metro
 * WRITE_SECURE_SETTINGS (one-time, over USB: `adb shell pm grant dev.metro.launcher
 * android.permission.WRITE_SECURE_SETTINGS`), Metro switches its own service back on.
 * It never touches any other service in the list.
 */
object AccessibilityKeeper {

    fun canSelfEnable(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Re-enables Metro's service if a Metro feature needs it and it was switched off. Returns true if it is on. */
    fun ensure(context: Context): Boolean {
        if (MetroAccessibilityService.isEnabled(context)) return true
        val settings = LauncherStore.get(context).settings.value
        if (!settings.globalNavBar && !settings.volumePanel && !settings.actionCenter && !settings.wpStatusBar) return false
        if (!canSelfEnable(context)) return false
        return runCatching {
            val resolver = context.contentResolver
            val me = ComponentName(context, MetroAccessibilityService::class.java).flattenToString()
            val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val services = current.split(':').filter { it.isNotBlank() }.toMutableList()
            if (services.none { ComponentName.unflattenFromString(it) == ComponentName.unflattenFromString(me) }) {
                services += me
            }
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services.joinToString(":"))
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        }.getOrDefault(false)
    }
}
