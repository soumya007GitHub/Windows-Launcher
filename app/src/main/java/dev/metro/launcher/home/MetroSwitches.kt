package dev.metro.launcher.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.inputmethod.InputMethodManager
import dev.metro.launcher.data.LauncherStore
import dev.metro.launcher.keyboard.MetroKeyboardService
import dev.metro.launcher.lock.LockscreenService
import dev.metro.launcher.notifications.TileNotificationListener
import dev.metro.launcher.volume.AccessibilityKeeper
import dev.metro.launcher.volume.MetroAccessibilityService

/**
 * The on/off switches in Settings > system.
 *
 * Android lets an app switch its own services and keyboard on or off only with WRITE_SECURE_SETTINGS
 * (granted once over USB). Without it, the switches open the matching system page instead.
 * Choosing the home app is always confirmed by the user in Android's own dialog.
 */
object MetroSwitches {

    private const val PREFS = "metro_switches"
    private const val KEY_METRO_ACTIVE = "metro_active"
    private const val KEY_SAVED_LOCK = "saved_lockscreen"
    private const val KEY_SAVED_BAR = "saved_bar"
    private const val KEY_SAVED_VOLUME = "saved_volume"
    private const val KEY_SAVED_ACTION_CENTER = "saved_action_center"
    private const val KEY_PREVIOUS_IME = "previous_ime"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun canSwitchDirectly(context: Context): Boolean = AccessibilityKeeper.canSelfEnable(context)

    /** False after the user switched back to Android's home screen from Metro's settings. */
    fun isMetroActive(context: Context): Boolean = prefs(context).getBoolean(KEY_METRO_ACTIVE, true)

    // ------------------------------------------------------------------ Metro as a whole

    /**
     * Switching to Android's home screen: remembers which Metro features were on, then turns off the
     * lock screen, Metro bar, volume panel and live-tile listener. The caller then opens Android's
     * home-app chooser.
     */
    fun deactivateMetro(context: Context) {
        val store = LauncherStore.get(context)
        val s = store.settings.value
        prefs(context).edit()
            .putBoolean(KEY_METRO_ACTIVE, false)
            .putBoolean(KEY_SAVED_LOCK, s.lockscreenEnabled)
            .putBoolean(KEY_SAVED_BAR, s.globalNavBar)
            .putBoolean(KEY_SAVED_VOLUME, s.volumePanel)
            .putBoolean(KEY_SAVED_ACTION_CENTER, s.actionCenter)
            .apply()
        store.updateSettings { it.copy(lockscreenEnabled = false, globalNavBar = false, volumePanel = false, actionCenter = false) }
        LockscreenService.stop(context)
        setAccessibility(context, false)
        setNotificationListener(context, false)
    }

    /** Coming back to Metro: restores the features that were on before. The caller asks for the home role. */
    fun activateMetro(context: Context) {
        val p = prefs(context)
        val wasInactive = !p.getBoolean(KEY_METRO_ACTIVE, true)
        p.edit().putBoolean(KEY_METRO_ACTIVE, true).apply()
        val store = LauncherStore.get(context)
        if (wasInactive) {
            store.updateSettings {
                it.copy(
                    lockscreenEnabled = p.getBoolean(KEY_SAVED_LOCK, true),
                    globalNavBar = p.getBoolean(KEY_SAVED_BAR, true),
                    volumePanel = p.getBoolean(KEY_SAVED_VOLUME, true),
                    actionCenter = p.getBoolean(KEY_SAVED_ACTION_CENTER, true),
                )
            }
        }
        val s = store.settings.value
        if (s.lockscreenEnabled) LockscreenService.start(context)
        if (s.globalNavBar || s.volumePanel || s.actionCenter) setAccessibility(context, true)
        setNotificationListener(context, true)
    }

    /** Android's "default home app" chooser, where the user picks another launcher. */
    fun homeChooserIntent(): Intent =
        Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ------------------------------------------------------------------ accessibility (bar + volume)

    /** Returns false when Android needs the user to do it in Accessibility settings. */
    fun setAccessibility(context: Context, on: Boolean): Boolean {
        if (!on) {
            // The service can always switch itself off.
            if (MetroAccessibilityService.disableRunning()) return true
        }
        if (!canSwitchDirectly(context)) return !on && !MetroAccessibilityService.isEnabled(context)
        return runCatching {
            val resolver = context.contentResolver
            val me = ComponentName(context, MetroAccessibilityService::class.java)
            val services = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != me }
                .toMutableList()
            if (on) services += me.flattenToString()
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services.joinToString(":"))
            if (on) Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ live tiles

    /** Stops or restarts the live-tile listener. Notification access itself stays granted. */
    fun setNotificationListener(context: Context, on: Boolean) {
        val component = ComponentName(context, TileNotificationListener::class.java)
        runCatching {
            if (on) {
                NotificationListenerService.requestRebind(component)
            } else {
                TileNotificationListener.unbindRunning()
            }
        }
    }

    // ------------------------------------------------------------------ keyboard

    fun isKeyboardOn(context: Context): Boolean =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.let { ComponentName.unflattenFromString(it) } == ComponentName(context, MetroKeyboardService::class.java)

    /**
     * On: adds Metro keyboard to the enabled keyboards and makes it the one in use (remembering the
     * previous one). Off: switches back to the previous keyboard and removes Metro's from the list.
     * Returns false when Android needs the user to do it (no WRITE_SECURE_SETTINGS).
     */
    fun setKeyboard(context: Context, on: Boolean): Boolean {
        if (!canSwitchDirectly(context)) return false
        return runCatching {
            val resolver = context.contentResolver
            val me = ComponentName(context, MetroKeyboardService::class.java).flattenToShortString()
            val meLong = ComponentName(context, MetroKeyboardService::class.java).flattenToString()
            val isMe = { id: String -> id.substringBefore(';') == me || id.substringBefore(';') == meLong }
            val enabled = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_INPUT_METHODS)
                .orEmpty().split(':').filter { it.isNotBlank() }
            val current = Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
            if (on) {
                if (!isMe(current) && current.isNotBlank()) prefs(context).edit().putString(KEY_PREVIOUS_IME, current).apply()
                val list = enabled.filterNot(isMe) + me
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_INPUT_METHODS, list.joinToString(":"))
                Settings.Secure.putString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD, me)
            } else {
                val others = enabled.filterNot(isMe)
                val saved = prefs(context).getString(KEY_PREVIOUS_IME, null)
                val back = saved?.takeIf { s -> others.any { it.substringBefore(';') == s } }
                    ?: others.firstOrNull()?.substringBefore(';')
                    ?: return@runCatching false // no other keyboard to fall back to
                Settings.Secure.putString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD, back)
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_INPUT_METHODS, others.joinToString(":"))
            }
            true
        }.getOrDefault(false)
    }

    fun keyboardSettingsIntent(): Intent =
        Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun showKeyboardPicker(context: Context) {
        context.getSystemService(InputMethodManager::class.java).showInputMethodPicker()
    }
}
