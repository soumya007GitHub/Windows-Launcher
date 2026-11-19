package dev.metro.launcher.lock

import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Color as AndroidColor
import dev.metro.launcher.sound.MetroSounds
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The curtain window. Shown over the keyguard (show-when-locked) when the screen turns off.
 *
 * Lifting the curtain never bypasses security: it asks the system to dismiss the keyguard, so a
 * swipe-only lock opens directly and a PIN / pattern / biometric lock shows the system prompt.
 * If that prompt is cancelled, the curtain drops back down.
 */
class LockscreenActivity : ComponentActivity() {

    private val curtain = CurtainState()

    override fun onStart() {
        super.onStart()
        current = this
    }
    private var keyguardLocked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MetroSounds.preload(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.navigationBars())
            // With Metro's WP status bar on, it is drawn over the curtain instead of Android's.
            if (dev.metro.launcher.data.LauncherStore.get(this@LockscreenActivity).settings.value.wpStatusBar) {
                hide(WindowInsetsCompat.Type.statusBars())
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        // Back never lifts the curtain.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        setContent {
            CurtainLockscreen(
                state = curtain,
                showBackdrop = keyguardLocked,
                onUnlocked = ::unlock,
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false

    /** Something covered the curtain while it is still down (Home, Recents, a notification): come back. */
    private val reassert = Runnable {
        if (LockGate.locked && !resumed && !isFinishing &&
            getSystemService(PowerManager::class.java).isInteractive &&
            !LockGate.isCallActive(this)
        ) {
            LockGate.bringToFront(this)
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        handler.removeCallbacks(reassert)
        keyguardLocked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
    }

    override fun onPause() {
        super.onPause()
        resumed = false
    }

    /** Something tried to get past the curtain (Home, Recents, a notification) while the screen was on. */
    private var bypassAttempted = false

    override fun onStop() {
        super.onStop()
        if (LockGate.locked && !isFinishing) {
            if (getSystemService(PowerManager::class.java).isInteractive) bypassAttempted = true
            handler.postDelayed(reassert, REASSERT_DELAY_MS)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(reassert)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Re-shown for a fresh screen-off: start clean. Re-asserted after a bypass attempt: keep the flag.
        if (!getSystemService(PowerManager::class.java).isInteractive) bypassAttempted = false
        curtain.reset()
    }

    private fun unlock() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) {
            close()
            return
        }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = close()
            override fun onDismissCancelled() = curtain.reset()
            override fun onDismissError() = curtain.reset()
        })
    }

    private fun close() {
        MetroSounds.play(this, MetroSounds.Kind.Unlock)
        LockGate.release()
        handler.removeCallbacks(reassert)
        if (bypassAttempted) {
            // Whatever was opened behind the curtain (Recents, an app) is not where the user is going: go to Start.
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .setPackage(packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                )
            }
        }
        bypassAttempted = false
        finish()
        // Bring the Metro bar back over the app underneath now, not when Android sends its next window event.
        dev.metro.launcher.volume.MetroAccessibilityService.curtainClosed()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        private const val REASSERT_DELAY_MS = 250L

        @Volatile
        private var current: LockscreenActivity? = null

        /** Start on the Metro bar while the curtain is down: lift it (Back and Search do nothing). */
        fun liftFromNavBar(): Boolean {
            val activity = current ?: return false
            if (activity.isFinishing || activity.isDestroyed) return false
            activity.runOnUiThread { activity.curtain.liftRequests++ }
            return true
        }
    }
}
