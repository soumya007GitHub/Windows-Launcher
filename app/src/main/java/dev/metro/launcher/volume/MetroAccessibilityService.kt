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
    private var curtainInFront = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onServiceConnected() {
        running = this
        volume = VolumeOverlay(this)
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
        curtainInFront = event.packageName == packageName && className == LockscreenActivity::class.java.name
        refreshNavBar()
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
            // Holding a key repeats every ~50 ms; step at a readable pace instead of racing to 0 / 30.
            val isRepeat = event.repeatCount > 0
            if (!isRepeat || event.eventTime - lastStepAt >= REPEAT_STEP_MS) {
                lastStepAt = event.eventTime
                volume?.onVolumeKey(up)
            }
        }
        return true // consume both down and up so Android's own dialog never appears
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
private class VolumeOverlay(private val service: AccessibilityService) {

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

    fun onVolumeKey(up: Boolean) {
        val stream = if (audio.isMusicActive) AudioManager.STREAM_MUSIC else AudioManager.STREAM_RING
        state.primary = stream
        val current = VolumeScale.level(audio, stream)
        VolumeScale.setLevel(audio, stream, current + if (up) 1 else -1)
        show()
    }

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
        const val HIDE_AFTER_MS = 3_000L
    }
}
