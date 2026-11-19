package dev.metro.launcher.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.metro.launcher.actioncenter.ActionCenterConfig
import dev.metro.launcher.actioncenter.QuickActions
import dev.metro.launcher.data.LauncherSettings
import dev.metro.launcher.physics.TurnstileState
import dev.metro.launcher.physics.metroTilt
import dev.metro.launcher.physics.turnstileItem
import dev.metro.launcher.ui.MetroListPicker
import dev.metro.launcher.ui.MetroType

/**
 * WP 8.1 "notifications + actions": the Action Center switch and its four quick actions. The
 * choices are the tiles Android's own quick settings offer on this phone (listed first), plus a
 * few Windows Phone ones.
 */
@Composable
internal fun NotificationsPage(settings: LauncherSettings, accent: Color, turnstile: TurnstileState) {
    val context = LocalContext.current
    val config = remember { ActionCenterConfig.get(context) }
    val quickActions by config.quickActions.collectAsState()
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    val probe = remember(resumes) { QuickActions(context) }
    val offered = remember(probe) { probe.offered().also { probe.release() } }
    val canWifiName = remember(resumes) { probe.canReadWifiName() }
    val canBluetooth = remember(resumes) { probe.canReadBluetooth() }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resumes++ }

    ActionCenterToggle(settings.actionCenter, accent, turnstile)
    dev.metro.launcher.ui.MetroToggle(
        title = "Windows Phone status bar",
        subtitle = "signal, Wi-Fi, notifications, battery and time in WP style at the top of every app",
        checked = settings.wpStatusBar,
        accent = accent,
        onCheckedChange = { on ->
            val store = dev.metro.launcher.data.LauncherStore.get(context)
            store.updateSettings { it.copy(wpStatusBar = on) }
            if (on && !dev.metro.launcher.volume.MetroAccessibilityService.isEnabled(context)) {
                dev.metro.launcher.home.MetroSwitches.setAccessibility(context, true)
            }
        },
        modifier = Modifier.turnstileItem(turnstile),
    )

    Spacer(Modifier.height(8.dp))
    BasicText("Quick actions", style = MetroType.Subtle, modifier = Modifier.turnstileItem(turnstile))
    BasicText(
        "choose the four at the top of the action center",
        style = MetroType.Subtle,
        modifier = Modifier.padding(bottom = 4.dp).turnstileItem(turnstile),
    )
    for (slot in 0 until ActionCenterConfig.SLOTS) {
        MetroListPicker(
            title = listOf("first", "second", "third", "fourth")[slot],
            options = offered,
            selected = quickActions.getOrNull(slot) ?: offered.first(),
            label = { it.label },
            accent = accent,
            onSelect = { config.setQuickAction(slot, it) },
            modifier = Modifier.turnstileItem(turnstile),
        )
    }

    Spacer(Modifier.height(16.dp))
    BasicText("Network names on the tiles", style = MetroType.Subtle, modifier = Modifier.turnstileItem(turnstile))
    PermissionLink(
        title = "Wi-Fi network name",
        granted = canWifiName,
        action = "allow (Android asks for location to share it)",
        accent = accent,
        turnstile = turnstile,
    ) {
        permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        PermissionLink(
            title = "Bluetooth device name",
            granted = canBluetooth,
            action = "allow nearby devices",
            accent = accent,
            turnstile = turnstile,
        ) {
            permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
        }
    }
}

@Composable
private fun PermissionLink(title: String, granted: Boolean, action: String, accent: Color, turnstile: TurnstileState, onClick: () -> Unit) {
    val noRipple = remember { MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxWidth()
            .turnstileItem(turnstile)
            .metroTilt(enabled = !granted)
            .clickable(noRipple, indication = null, enabled = !granted, onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        BasicText(title, style = MetroType.Medium)
        BasicText(if (granted) "on" else action, style = if (granted) MetroType.Subtle else MetroType.Subtle.copy(color = accent))
    }
}
