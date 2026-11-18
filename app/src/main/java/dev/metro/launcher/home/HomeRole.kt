package dev.metro.launcher.home

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

/**
 * Carries Home-button presses from [android.app.Activity.onNewIntent] into Compose.
 *
 * Two cases, as Launcher3 tells them apart:
 *  - Home pressed while the launcher is already in front: [inLauncherPresses] increments
 *    (Start returns from All Apps, or scrolls back to the top).
 *  - Home pressed inside another app: [returnHomePending] is set before onResume runs, so the resume
 *    handler can jump straight to the top of Start before the page swings back in.
 */
@Stable
class HomeIntents {
    val inLauncherPresses = mutableIntStateOf(0)
    var returnHomePending: Boolean = false

    fun onHomeIntent(alreadyOnHome: Boolean) {
        if (alreadyOnHome) inLauncherPresses.intValue++ else returnHomePending = true
    }

    /** Returns and clears the pending return-home flag. */
    fun consumeReturnHome(): Boolean = returnHomePending.also { returnHomePending = false }

    enum class Request { Search, Switcher }

    /** Latest request from the system-wide Metro bar, with a counter so repeats still fire. */
    val pendingRequest = mutableStateOf<Pair<Request, Int>?>(null)
    private var requestCount = 0

    fun request(request: Request) {
        pendingRequest.value = request to ++requestCount
    }

    companion object {
        fun isHomeIntent(intent: Intent?): Boolean =
            intent != null && intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)
    }
}

object DefaultHome {

    fun isDefault(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
                return roles.isRoleHeld(RoleManager.ROLE_HOME)
            }
        }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.resolveActivity(
                home,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return resolved?.activityInfo?.packageName == context.packageName
    }

    /**
     * Intent that asks the user to make Metro the home app: the system role dialog on Android 10+,
     * otherwise the "Default home app" settings page. The user always confirms; nothing is changed silently.
     */
    fun requestIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
                return roles.createRequestRoleIntent(RoleManager.ROLE_HOME)
            }
        }
        return Intent(Settings.ACTION_HOME_SETTINGS)
    }
}
