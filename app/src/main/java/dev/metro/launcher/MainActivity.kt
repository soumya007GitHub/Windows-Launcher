package dev.metro.launcher

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.metro.launcher.data.LauncherStore
import dev.metro.launcher.home.HomeIntents
import dev.metro.launcher.lock.LockGate
import dev.metro.launcher.lock.LockscreenService
import dev.metro.launcher.nav.GlobalNavBar
import dev.metro.launcher.volume.AccessibilityKeeper
import dev.metro.launcher.ui.isDark
import android.content.res.Configuration
import dev.metro.launcher.sound.MetroSounds
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val homeIntents = HomeIntents()
    private var hideNavigation = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val store = LauncherStore.get(this)
        MetroSounds.preload(this)
        if (store.settings.value.lockscreenEnabled) LockscreenService.start(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                store.settings.map { Triple(it.hideSystemNavigation, it.globalNavBar, it.theme) }.distinctUntilChanged().collect {
                    applySystemNavigation()
                }
            }
        }
        handleMetroAction(intent)
        setContent {
            // First run after install: splash + one-by-one permission setup over the launcher.
            var onboarding by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(!dev.metro.launcher.onboarding.Onboarding.isDone(this))
            }
            androidx.compose.foundation.layout.Box {
                MetroLauncherApp(homeIntents)
                if (onboarding) dev.metro.launcher.onboarding.OnboardingFlow(onFinished = { onboarding = false })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AccessibilityKeeper.ensure(this) // OriginOS may have switched the Metro bar's service off
        applySystemNavigation()
        // Home pressed while the curtain is down: the curtain wins.
        if (LockGate.locked) LockGate.bringToFront(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemNavigation()
    }

    /**
     * When the system-wide Metro bar is active it sits exactly over Android's navigation bar, so
     * Android's bar must stay (it reserves the space). Otherwise Metro may hide it on its own screens.
     */
    private fun applySystemNavigation() {
        val settings = LauncherStore.get(this).settings.value
        // Light background: dark status-bar icons, like WP's light theme.
        val phoneIsDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val barStyle = if (!settings.theme.isDark(phoneIsDark)) {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        } else {
            SystemBarStyle.dark(Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
        hideNavigation = settings.hideSystemNavigation && !GlobalNavBar.isActive(this)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hideNavigation) {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        }
        controller.show(WindowInsetsCompat.Type.statusBars())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (HomeIntents.isHomeIntent(intent)) {
            // Same test Launcher3 uses: focused and not merely brought to front => user was already on home.
            val broughtToFront = intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT != 0
            homeIntents.onHomeIntent(alreadyOnHome = hasWindowFocus() && !broughtToFront)
        }
        handleMetroAction(intent)
    }

    /** Search / app switcher requested from the system-wide Metro bar. */
    private fun handleMetroAction(intent: Intent?) {
        when (intent?.action) {
            GlobalNavBar.ACTION_SEARCH -> homeIntents.request(HomeIntents.Request.Search)
            GlobalNavBar.ACTION_SWITCHER -> homeIntents.request(HomeIntents.Request.Switcher)
        }
    }
}
