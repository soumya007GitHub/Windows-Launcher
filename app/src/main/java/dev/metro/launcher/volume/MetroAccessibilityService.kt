package dev.metro.launcher.volume

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.WindowInsets
import android.annotation.SuppressLint
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ComposeView
import dev.metro.launcher.data.LauncherStore
import dev.metro.launcher.lock.LockscreenActivity
import dev.metro.launcher.nav.GlobalNavBar
import dev.metro.launcher.ui.OverlayLifecycleOwner
import dev.metro.launcher.ui.LocalMetroColors
import dev.metro.launcher.ui.MetroColors
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Metro's system-wide pieces:
 *  - the search / start / back bar over every app ([GlobalNavBar]);
 *  - the WP8 volume overlay, replacing Android's volume dialog (hardware keys are filtered here).
 *
 * Window-change events are used only to know when the Metro lock curtain (or the system lock
 * screen) is in front, so the bar is not drawn over it. No screen content is read.
 */
class MetroAccessibilityService : AccessibilityService() {

    private var volume: VolumeOverlay? = null
    private var navBar: GlobalNavBar? = null
    private var lastStepAt = 0L
    /** True while a key press is being handed to Android because the Metro change was refused. */
    private var passThrough = false
    private var curtainInFront = false

    /** Package of the app window in front (from window-change events), used to pick the volume stream. */
    @Volatile
    private var foregroundPackage: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onServiceConnected() {
        running = this
        volume = VolumeOverlay(this) { foregroundPackage }
        navBar = GlobalNavBar(this)
        refreshNavBar()
        scope.launch {
            LauncherStore.get(this@MetroAccessibilityService).settings
                .map { it.globalNavBar }
                .distinctUntilChanged()
                .collect { refreshNavBar() }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val className = event.className?.toString() ?: return
        trackForeground(event.packageName?.toString(), className)
        curtainInFront = event.packageName == packageName && className == LockscreenActivity::class.java.name
        refreshNavBar()
    }

    /** Ignores the notification shade, keyboards and similar windows that sit over the real app. */
    private fun trackForeground(pkg: String?, className: String) {
        if (pkg.isNullOrEmpty() || pkg == "com.android.systemui" || pkg == "android") return
        // Metro's own overlays (volume panel, nav bar) also raise window events; only its
        // activities (Start, lock curtain) count as "Metro is in front".
        if (pkg == packageName && !className.startsWith(packageName)) return
        // The lock curtain sits over the app the user was in; that app is still "in front" for volume.
        if (pkg == packageName && className == LockscreenActivity::class.java.name) return
        val ime = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        if (ime != null && ime.startsWith("$pkg/")) return
        foregroundPackage = pkg
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshNavBar() // rotation moves Android's navigation bar
    }

    private fun refreshNavBar() {
        val keyguardLocked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        navBar?.update(suppressed = curtainInFront || keyguardLocked)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val up = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> true
            KeyEvent.KEYCODE_VOLUME_DOWN -> false
            else -> return false
        }
        if (!LauncherStore.get(this).settings.value.volumePanel) return false
        val mode = getSystemService(AudioManager::class.java).mode
        if (mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION
        ) {
            return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) passThrough = false
            if (passThrough) return false
            // Holding a key repeats every ~50 ms; step at a readable pace instead of racing to 0 / 30.
            val isRepeat = event.repeatCount > 0
            if (!isRepeat || event.eventTime - lastStepAt >= REPEAT_STEP_MS) {
                lastStepAt = event.eventTime
                if (volume?.onVolumeKey(up) == false) {
                    // Android refused the change: let the key through so the volume still moves
                    // (Android's own volume dialog shows for this press).
                    passThrough = true
                    return false
                }
            }
            return true
        }
        // Key up: match whatever the key down did.
        return !passThrough
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (running === this) running = null
        scope.cancel()
        volume?.release()
        navBar?.release()
        volume = null
        navBar = null
        super.onDestroy()
    }

    companion object {
        private const val REPEAT_STEP_MS = 120L

        @Volatile
        private var running: MetroAccessibilityService? = null

        /** Called by the lock curtain the moment it is lifted, so the bar reappears without a gap. */
        fun curtainClosed() {
            val service = running ?: return
            service.curtainInFront = false
            service.refreshNavBar()
        }

        /** Switches the running service off (an accessibility service may always disable itself). */
        fun disableRunning(): Boolean {
            val service = running ?: return false
            service.disableSelf()
            return true
        }

        /** Turns the screen off and locks right away (Android 9+, needs the service running). */
        fun lockScreenNow(): Boolean {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return false
            return running?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) == true
        }

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val me = ComponentName(context, MetroAccessibilityService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}

/** Hosts [VolumePanel] in an accessibility overlay window at the top of the screen. */
private class VolumeOverlay(
    private val service: AccessibilityService,
    private val foregroundPackage: () -> String?,
) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val audio = service.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val state = VolumePanelState()
    private var view: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null

    private val hide = Runnable {
        state.visible = false
        state.expanded = false
        handler.postDelayed(detach, 260)
    }
    private val detach = Runnable { removeWindow() }

    /** Steps the volume for the current context and shows the panel. Always handles the key. */
    fun onVolumeKey(up: Boolean): Boolean {
        val stream = streamForContext()
        state.primary = stream
        if (stream == AudioManager.STREAM_MUSIC) {
            // Step media exactly like apps do (one Android step per press). Raising also un-mutes,
            // and past the safe headphone level Android shows its own warning.
            val direction = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            val pastSafe = up && audio.getStreamVolume(stream) >= SAFE_MEDIA_INDEX_GUESS &&
                isHeadsetConnected()
            runCatching {
                audio.adjustStreamVolume(stream, direction, if (pastSafe) AudioManager.FLAG_SHOW_UI else 0)
            }
        } else {
            VolumeScale.setLevel(audio, stream, VolumeScale.level(audio, stream) + if (up) 1 else -1)
        }
        show()
        // Android applies volume changes asynchronously; re-read once it has.
        handler.postDelayed({ state.refresh(audio) }, 120)
        return true
    }

    private fun isHeadsetConnected(): Boolean = runCatching {
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }.getOrDefault(false)

    /**
     * Which volume the keys control, like Windows Phone / Android:
     *  - something is playing             -> media;
     *  - an app is open (YouTube, Instagram, games...), even if paused -> media;
     *  - on Start / the app list with nothing playing -> ringer.
     */
    private fun streamForContext(): Int {
        if (audio.isMusicActive) return AudioManager.STREAM_MUSIC
        val pkg = foregroundPackage() ?: return AudioManager.STREAM_RING
        if (pkg == service.packageName || pkg == homePackage()) return AudioManager.STREAM_RING
        return AudioManager.STREAM_MUSIC
    }

    private fun homePackage(): String? = runCatching {
        val home = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
        service.packageManager.resolveActivity(home, 0)?.activityInfo?.packageName
    }.getOrNull()

    private fun show() {
        addWindow()
        state.refresh(audio)
        state.visible = true
        scheduleHide()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hide)
        handler.removeCallbacks(detach)
        handler.postDelayed(hide, HIDE_AFTER_MS)
    }

    private fun addWindow() {
        if (view != null) return
        val lifecycleOwner = OverlayLifecycleOwner().also { it.start() }
        val accent = LauncherStore.get(service).settings.value.accent.color
        val compose = ComposeView(service).apply {
            lifecycleOwner.attachTo(this)
            setContent {
                val settings by LauncherStore.get(service).settings.collectAsState()
                val phoneDark = (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
                CompositionLocalProvider(LocalMetroColors provides MetroColors.of(settings.theme, phoneDark)) {
                VolumePanel(
                    state = state,
                    accent = accent,
                    onInteract = ::scheduleHide,
                    onSetLevel = { stream, level ->
                        VolumeScale.setLevel(audio, stream, level)
                        state.refresh(audio)
                        handler.postDelayed({ state.refresh(audio) }, 120)
                    },
                    onToggleSilent = {
                        val notifications = service.getSystemService(NotificationManager::class.java)
                        if (notifications.isNotificationPolicyAccessGranted) {
                            val silentNow = audio.ringerMode != AudioManager.RINGER_MODE_NORMAL
                            runCatching {
                                audio.ringerMode = if (silentNow) AudioManager.RINGER_MODE_NORMAL else AudioManager.RINGER_MODE_SILENT
                            }
                            state.refresh(audio)
                        } else {
                            // Android only lets apps with Do Not Disturb access switch to silent.
                            hideNow()
                            openSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                        }
                    },
                    onSoundSettings = {
                        hideNow()
                        openSettings(Settings.ACTION_SOUND_SETTINGS)
                    },
                    topInset = topInsetDp(),
                )
                }
            }
            // Touching anywhere else on screen closes the panel, like WP.
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) hideNow()
                false
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        runCatching { windowManager.addView(compose, params) }
            .onSuccess {
                view = compose
                owner = lifecycleOwner
            }
            .onFailure { lifecycleOwner.destroy() }
    }

    /**
     * Status bar + camera cutout height, plus a little air, so the panel starts below the camera.
     * Read from the display directly: overlay windows don't reliably receive these insets.
     */
    private fun topInsetDp(): Dp {
        val density = service.resources.displayMetrics.density
        val topPx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())
                .top
        } else {
            @SuppressLint("DiscouragedApi", "InternalInsetResource")
            val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id != 0) service.resources.getDimensionPixelSize(id) else (24 * density).toInt()
        }
        return (topPx / density).dp + 6.dp
    }

    private fun hideNow() {
        handler.removeCallbacks(hide)
        hide.run()
    }

    private fun openSettings(action: String) {
        runCatching { service.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun removeWindow() {
        view?.let { runCatching { windowManager.removeView(it) } }
        owner?.destroy()
        view = null
        owner = null
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        removeWindow()
    }

    private companion object {
        /** Android's default safe headphone index is about 60 % of max; above it a warning may be needed. */
        const val SAFE_MEDIA_INDEX_GUESS = 9
        const val HIDE_AFTER_MS = 3_000L
    }
}
