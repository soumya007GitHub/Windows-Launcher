package dev.metro.launcher.actioncenter

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.content.ContextCompat
import dev.metro.launcher.data.LauncherStore
import dev.metro.launcher.notifications.ActionCenterItem
import dev.metro.launcher.notifications.NotificationRepository
import dev.metro.launcher.ui.LocalMetroColors
import dev.metro.launcher.ui.MetroColors
import dev.metro.launcher.ui.OverlayLifecycleOwner
import dev.metro.launcher.ui.isDark

/** How far the sheet is pulled down (px from the top); [peekPx] = height of the quick-actions part. */
class ActionCenterState {
    var revealed by mutableFloatStateOf(0f)
    var heightPx by mutableFloatStateOf(1f)
    var peekPx by mutableFloatStateOf(0f)
    /** Height of Android's status bar band, where WP's own status line is drawn. */
    var statusBarPx by mutableFloatStateOf(0f)
}

/**
 * WP8.1 Action Center, drawn by the Metro accessibility service:
 *  - a thin invisible strip over the status bar catches a downward swipe from the top edge
 *    (it steps aside while an app hides the status bar, e.g. full-screen video and games);
 *  - the panel follows the finger and settles open or closed;
 *  - Back, Home, opening something, or swiping it up closes it.
 * It is never available over the lock screen, so notification text isn't shown while locked.
 */
class ActionCenter(private val service: AccessibilityService) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private val quick = QuickActions(service, service)
    private val state = ActionCenterState()

    private var strip: View? = null
    private var stripParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var panelOwner: OverlayLifecycleOwner? = null
    private var animator: android.animation.ValueAnimator? = null

    val isOpen: Boolean get() = panel != null

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Home or Recents pressed.
            if (panel != null) close(animated = false)
        }
    }

    init {
        ContextCompat.registerReceiver(
            service, closeReceiver, IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS), ContextCompat.RECEIVER_EXPORTED,
        )
    }

    /** Shows or hides the top-edge strip. [suppressed] = lock screen in front. */
    fun update(suppressed: Boolean) {
        val settings = LauncherStore.get(service).settings.value
        val acOn = settings.actionCenter
        val barOn = settings.wpStatusBar
        locked = suppressed
        // Over the lock screen the WP status bar stays (it replaces Android's there too), but the
        // Action Center can't be pulled down, so notification text never shows while locked.
        if (suppressed && panel != null) close(animated = false)
        if (!acOn && !barOn) {
            removeStrip()
            if (panel != null) close(animated = false)
            return
        }
        if (!acOn && panel != null) close(animated = false)
        if (strip == null) addStrip()
        applyStripVisibility()
        applyStripTouch()
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideIfStillHidden = Runnable {
        if (!statusBarVisible && !locked) strip?.visibility = View.INVISIBLE
        applyStripTouch()
    }

    /**
     * Hidden while a full-screen app hides the status bar; always shown over the lock screen.
     * App switches flip Android's status bar off and on for a moment: the WP bar only hides if it
     * stays hidden, so Android's bar never gets a chance to show through underneath it.
     */
    private fun applyStripVisibility() {
        handler.removeCallbacks(hideIfStillHidden)
        if (statusBarVisible || locked) {
            strip?.visibility = View.VISIBLE
        } else {
            handler.postDelayed(hideIfStillHidden, 450)
        }
    }

    /** Phone locked (Metro's curtain or Android's keyguard in front). */
    private val lockedState = androidx.compose.runtime.mutableStateOf(false)
    private var locked: Boolean
        get() = lockedState.value
        set(value) { lockedState.value = value }

    fun release() {
        removeStrip()
        removePanel()
        quick.release()
        runCatching { service.unregisterReceiver(closeReceiver) }
    }

    /** Another app came to the front (something was opened, or Home): put the panel away. */
    fun onOtherWindow() {
        if (panel != null) close(animated = false)
    }

    // ------------------------------------------------------------------ top-edge strip

    private fun statusBarHeightPx(): Int {
        val minimum = (24 * density).toInt()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val top = windowManager.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
            return maxOf(top, minimum) + dev.metro.launcher.ui.WpStatusBarSize.extraPx(density)
        }
        return minimum + dev.metro.launcher.ui.WpStatusBarSize.extraPx(density)
    }

    private var statusBarVisible = true
    private var stripOwner: OverlayLifecycleOwner? = null

    private fun addStrip() {
        val owner = OverlayLifecycleOwner().also { it.start() }
        var downY = 0f
        var dragging = false
        val tracker = VelocityTracker.obtain()
        val heightPx = statusBarHeightPx()
        val compose = ComposeView(service).apply {
            owner.attachTo(this)
            setContent {
                val settings by LauncherStore.get(service).settings.collectAsState()
                val items by NotificationRepository.items.collectAsState()
                val phoneDark = (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
                if (settings.wpStatusBar) {
                    val dark = settings.theme.isDark(phoneDark)
                    // Over the lock screen: transparent over the photo, white, and no app icons.
                    val isLocked = lockedState.value
                    WpStatusBar(
                        quick = quick,
                        notifications = if (isLocked) emptyList() else items,
                        ink = if (dark || isLocked) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Black,
                        bed = when {
                            isLocked -> androidx.compose.ui.graphics.Color.Transparent
                            dark -> androidx.compose.ui.graphics.Color.Black
                            else -> androidx.compose.ui.graphics.Color.White
                        },
                        height = with(androidx.compose.ui.platform.LocalDensity.current) { heightPx.toDp() },
                    )
                }
            }
        }
        // The pull-down gets every touch first (the drawn bar never handles touches itself).
        val handle: (MotionEvent) -> Boolean = { event ->
                tracker.addMovement(event)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downY = event.rawY
                        dragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = event.rawY - downY
                        if (!dragging && dy > 8 * density) {
                            dragging = true
                            openPanelWindow()
                        }
                        if (dragging) state.revealed = dy.coerceIn(0f, state.heightPx)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        tracker.computeCurrentVelocity(1000)
                        val velocity = tracker.yVelocity
                        tracker.clear()
                        if (dragging) snap(velocity)
                        dragging = false
                    }
                }
                true
        }
        val view = object : FrameLayout(service) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean = handle(event)
        }.apply { addView(compose) }
        owner.attachTo(view)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            heightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            title = "Metro action center edge"
        }
        // Full-screen apps (video, games) hide the status bar: then the WP bar hides too and lets
        // touches through to them.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.setOnApplyWindowInsetsListener { v, insets ->
                statusBarVisible = insets.isVisible(WindowInsets.Type.statusBars())
                applyStripVisibility()
                applyStripTouch()
                v.onApplyWindowInsets(insets)
            }
        }
        runCatching { windowManager.addView(view, params) }
            .onSuccess {
                strip = view
                stripParams = params
                stripOwner = owner
            }
            .onFailure { owner.destroy() }
    }

    /** Pull-down works only with the Action Center on and the status bar showing. */
    private fun applyStripTouch() {
        val acOn = LauncherStore.get(service).settings.value.actionCenter
        setStripTouchable(acOn && statusBarVisible && !locked)
    }

    private fun setStripTouchable(touchable: Boolean) {
        val view = strip ?: return
        val params = stripParams ?: return
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val wanted = if (touchable) params.flags and flag.inv() else params.flags or flag
        if (wanted == params.flags) return
        params.flags = wanted
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun removeStrip() {
        strip?.let { runCatching { windowManager.removeView(it) } }
        stripOwner?.destroy()
        stripOwner = null
        strip = null
        stripParams = null
    }

    // ------------------------------------------------------------------ panel window

    private fun openPanelWindow() {
        if (panel != null) return
        animator?.cancel()
        quick.refresh()
        val heightPx = service.resources.displayMetrics.heightPixels.toFloat()
        state.heightPx = heightPx
        state.revealed = 0f
        state.statusBarPx = statusBarHeightPx().toFloat()
        val owner = OverlayLifecycleOwner().also { it.start() }
        val host = object : FrameLayout(service) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) close(animated = true)
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        val compose = ComposeView(service).apply {
            owner.attachTo(this)
            setContent {
                val settings by LauncherStore.get(service).settings.collectAsState()
                val allItems by NotificationRepository.items.collectAsState()
                val config = ActionCenterConfig.get(service)
                val quickActions by config.quickActions.collectAsState()
                val items = allItems
                val phoneDark = (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
                CompositionLocalProvider(LocalMetroColors provides MetroColors.of(settings.theme, phoneDark)) {
                    ActionCenterPanel(
                        state = state,
                        quick = quick,
                        quickActions = quickActions,
                        items = items,
                        accent = settings.accent.color,
                        dark = settings.theme.isDark(phoneDark),
                        onQuickAction = { action ->
                            if (quick.closesPanel(action)) close(animated = false)
                            quick.toggle(action)?.let { openOutside(it) }
                        },
                        onQuickActionSettings = { action -> openOutside(quick.settingsIntent(action)) },
                        onAllSettings = { openOutside(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                        onOpen = ::openNotification,
                        onDismiss = { NotificationRepository.dismiss(it.key) },
                        onDismissApp = { pkg -> items.filter { it.packageName == pkg && it.clearable }.forEach { NotificationRepository.dismiss(it.key) } },
                        onClearAll = { NotificationRepository.dismissAll() },
                        onDrag = { delta -> state.revealed = (state.revealed + delta).coerceIn(0f, state.heightPx) },
                        onDragEnd = ::snap,
                        onOutside = { close(animated = true) },
                        onBrightnessPermission = { openOutside(quick.writeSettingsIntent()) },
                    )
                }
            }
        }
        host.addView(compose)
        owner.attachTo(host)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            title = "Metro action center"
        }
        runCatching { windowManager.addView(host, params) }
            .onSuccess {
                panel = host
                panelOwner = owner
                host.requestFocus()
            }
            .onFailure { owner.destroy() }
    }

    /**
     * WP 8.1 behaviour: a short pull stops with just the quick actions showing; a longer pull (or a
     * flick down) opens the whole sheet; pushing it up or a flick up puts it away.
     */
    private fun snap(velocity: Float) {
        val full = state.heightPx
        val peek = state.peekPx.takeIf { it > 0f } ?: (full * 0.3f)
        val r = state.revealed
        val fast = 1200 * density
        val target = when {
            velocity > fast -> full
            velocity < -fast -> 0f
            r < peek * 0.3f -> 0f
            r < peek + (full - peek) * 0.3f -> peek
            else -> full
        }
        if (target == 0f) close(animated = true) else animate(to = target) {}
    }

    fun close(animated: Boolean) {
        if (panel == null) return
        if (!animated) {
            animator?.cancel()
            removePanel()
            return
        }
        animate(to = 0f) { removePanel() }
    }

    private fun animate(to: Float, end: () -> Unit) {
        animator?.cancel()
        animator = android.animation.ValueAnimator.ofFloat(state.revealed, to).apply {
            duration = 260
            interpolator = android.view.animation.DecelerateInterpolator(2f)
            addUpdateListener { state.revealed = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) end() }
            })
            start()
        }
    }

    private fun removePanel() {
        panel?.let { runCatching { windowManager.removeView(it) } }
        panelOwner?.destroy()
        panel = null
        panelOwner = null
        state.revealed = 0f
    }

    // ------------------------------------------------------------------ actions

    private fun openOutside(intent: Intent) {
        close(animated = false)
        runCatching { service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun openNotification(item: ActionCenterItem) {
        close(animated = false)
        val sent = item.contentIntent?.let { pending ->
            runCatching {
                val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                        .toBundle()
                } else {
                    null
                }
                pending.send(service, 0, null, null, null, null, options)
            }.isSuccess
        } ?: false
        if (!sent) {
            service.packageManager.getLaunchIntentForPackage(item.packageName)?.let {
                runCatching { service.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        if (item.autoCancel && item.clearable) NotificationRepository.dismiss(item.key)
    }
}
