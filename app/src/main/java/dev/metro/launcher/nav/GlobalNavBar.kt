package dev.metro.launcher.nav

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import dev.metro.launcher.MainActivity
import dev.metro.launcher.data.LauncherStore
import dev.metro.launcher.ui.OverlayLifecycleOwner
import dev.metro.launcher.ui.LocalMetroColors
import dev.metro.launcher.ui.MetroColors
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.isSystemInDarkTheme
import dev.metro.launcher.volume.MetroAccessibilityService
import kotlin.math.max

/**
 * Metro's search / start / back bar drawn over the bottom of every app, from the accessibility
 * service (the only window type a third-party app may place over other apps' navigation area).
 *
 * With Android's 3-button navigation the bar sits exactly on top of Android's bar, so apps already
 * leave room for it. With gesture navigation Android reserves only a thin strip, so the bar
 * (48 dp minimum, to stay tappable) overlaps the bottom of apps slightly.
 */
class GlobalNavBar(private val service: AccessibilityService) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null
    private var currentHeight = 0

    /** True while the lock curtain / keyguard is in front: Start lifts the curtain, the rest do nothing. */
    @Volatile
    private var locked = false

    /** Shows, resizes or hides the bar. Over the lock screen it stays, with lock-safe buttons. */
    fun update(locked: Boolean) {
        this.locked = locked
        val enabled = LauncherStore.get(service).settings.value.globalNavBar
        val height = barHeightPx()
        if (!enabled || height == null) {
            remove()
            return
        }
        if (view != null && height == currentHeight) return
        remove()
        add(height)
    }

    fun release() = remove()

    /** Bottom navigation height in px, or null when Android puts navigation on the side (landscape). */
    private fun barHeightPx(): Int? {
        val density = service.resources.displayMetrics.density
        val minimum = (MIN_HEIGHT_DP * density).toInt()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = windowManager.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
            if (insets.bottom == 0 && (insets.left > 0 || insets.right > 0)) return null
            return max(insets.bottom, minimum)
        }
        if (service.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) return null
        return minimum
    }

    private fun add(heightPx: Int) {
        val lifecycleOwner = OverlayLifecycleOwner().also { it.start() }
        val compose = ComposeView(service).apply {
            lifecycleOwner.attachTo(this)
            setContent {
                val settings by LauncherStore.get(service).settings.collectAsState()
                CompositionLocalProvider(LocalMetroColors provides MetroColors.of(settings.theme, isSystemInDarkTheme())) {
                MetroNavBar(
                    accent = settings.accent.color,
                    onBack = { if (!locked) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) },
                    onBackLongPress = { if (!locked) openMetro(ACTION_SWITCHER) },
                    onStart = {
                        if (!locked) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                        else dev.metro.launcher.lock.LockscreenActivity.liftFromNavBar()
                    },
                    onSearch = { if (!locked) openMetro(ACTION_SEARCH) },
                    height = (heightPx / service.resources.displayMetrics.density).dp,
                    modifier = Modifier.fillMaxSize(),
                )
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            heightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.BOTTOM
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0 // sit on top of Android's bar
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            title = "Metro navigation bar"
        }
        runCatching { windowManager.addView(compose, params) }
            .onSuccess {
                view = compose
                owner = lifecycleOwner
                currentHeight = heightPx
            }
            .onFailure { lifecycleOwner.destroy() }
    }

    private fun remove() {
        view?.let { runCatching { windowManager.removeView(it) } }
        owner?.destroy()
        view = null
        owner = null
        currentHeight = 0
    }

    private fun openMetro(action: String) {
        runCatching {
            service.startActivity(
                Intent(service, MainActivity::class.java)
                    .setAction(action)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    companion object {
        const val ACTION_SEARCH = "dev.metro.launcher.action.SEARCH"
        const val ACTION_SWITCHER = "dev.metro.launcher.action.SWITCHER"
        const val MIN_HEIGHT_DP = 48

        /** True when the bar is drawn system-wide (setting on and the accessibility service running). */
        fun isActive(context: Context): Boolean =
            LauncherStore.get(context).settings.value.globalNavBar && MetroAccessibilityService.isEnabled(context)
    }
}
