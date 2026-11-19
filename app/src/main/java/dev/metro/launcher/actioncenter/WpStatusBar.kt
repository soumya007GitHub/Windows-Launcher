package dev.metro.launcher.actioncenter

import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.metro.launcher.notifications.ActionCenterItem
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the WP status bar shows, re-read every few seconds. */
internal data class StatusSnapshot(
    val time: String,
    val battery: Int,
    val charging: Boolean,
    val signal: Int?,
    val wifi: Boolean,
    val bluetooth: Boolean,
    val quiet: Boolean,
    val carrier: String?,
)

@Composable
internal fun rememberStatus(quick: QuickActions): StatusSnapshot {
    val context = LocalContext.current
    @Suppress("UNUSED_VARIABLE")
    val revision = quick.revision
    val snapshot by produceState(read(context, quick), quick.revision) {
        while (true) {
            value = read(context, quick)
            delay(5_000L)
        }
    }
    return snapshot
}

private fun read(context: android.content.Context, quick: QuickActions): StatusSnapshot {
    val bm = context.getSystemService(BatteryManager::class.java)
    val now = System.currentTimeMillis()
    val time = SimpleDateFormat(if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm", Locale.getDefault()).format(Date(now))
    return StatusSnapshot(
        time = time,
        battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
        charging = bm.isCharging,
        signal = quick.signalLevel(),
        wifi = quick.isOn(QuickAction.Wifi),
        bluetooth = quick.isOn(QuickAction.Bluetooth),
        quiet = quick.isOn(QuickAction.QuietHours),
        carrier = quick.carrier(),
    )
}

/**
 * Windows Phone's system tray, drawn over Android's status bar in every app: signal, Wi-Fi,
 * Bluetooth, quiet hours and the apps with notifications (white glyphs) on the left; the battery
 * with its percentage inside, and the time, on the right. The middle stays clear for the camera.
 */
@Composable
internal fun WpStatusBar(quick: QuickActions, notifications: List<ActionCenterItem>, ink: Color, bed: Color, height: Dp) {
    val s = rememberStatus(quick)
    val apps = remember(notifications) { notifications.distinctBy { it.packageName }.take(4) }
    Box(Modifier.fillMaxSize().background(bed).padding(horizontal = 14.dp)) {
        Row(
            Modifier.align(Alignment.CenterStart).height(height),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusIcons(s, ink)
            apps.forEach { item -> NotificationGlyph(item, ink) }
        }
        Row(Modifier.align(Alignment.CenterEnd).height(height), verticalAlignment = Alignment.CenterVertically) {
            BatteryWithPercent(s.battery, s.charging, ink, bed)
            Spacer(Modifier.width(8.dp))
            BasicText(s.time, style = TextStyle(color = ink, fontSize = 15.sp))
        }
    }
}

@Composable
internal fun StatusIcons(s: StatusSnapshot, ink: Color) {
    Canvas(Modifier.size(width = 18.dp, height = 14.dp)) { drawSignal(ink, s.signal) }
    if (s.wifi) Canvas(Modifier.size(15.dp)) { drawWifi(ink) }
    if (s.bluetooth) Canvas(Modifier.size(14.dp)) { drawBluetooth(ink) }
    if (s.quiet) Canvas(Modifier.size(13.dp)) { drawMoon(ink) }
}

/** WP battery: a thin outline with a nub, filled to the charge level (no number), as on WP 8.1. */
@Composable
internal fun BatteryWithPercent(level: Int, charging: Boolean, ink: Color, bed: Color) {
    Canvas(Modifier.size(width = 27.dp, height = 12.dp)) {
        val stroke = 1.3.dp.toPx()
        val nub = 2.dp.toPx()
        val body = Size(size.width - nub - stroke, size.height - stroke)
        drawRect(ink, Offset(stroke / 2, stroke / 2), body, style = Stroke(stroke))
        drawRect(ink, Offset(size.width - nub, size.height * 0.32f), Size(nub, size.height * 0.36f))
        val gap = stroke * 2
        val full = size.width - nub - stroke - gap * 2 + stroke / 2
        val fill = full * (level.coerceIn(0, 100) / 100f)
        drawRect(ink, Offset(gap, gap), Size(fill, size.height - gap * 2))
    }
}

/** An app's notification icon (its monochrome status-bar icon) drawn white, as WP did. */
@Composable
internal fun NotificationGlyph(item: ActionCenterItem, ink: Color) {
    val glyph = rememberSmallIcon(item)
    if (glyph != null) Image(glyph, null, colorFilter = ColorFilter.tint(ink), modifier = Modifier.size(14.dp))
}

@Composable
internal fun rememberSmallIcon(item: ActionCenterItem): ImageBitmap? {
    val context = LocalContext.current
    return remember(item.packageName, item.smallIcon) {
        runCatching {
            val drawable = item.smallIcon?.loadDrawable(context) ?: return@runCatching null
            val px = (24 * context.resources.displayMetrics.density).toInt()
            val bitmap = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, px, px)
            drawable.draw(android.graphics.Canvas(bitmap))
            bitmap.asImageBitmap()
        }.getOrNull()
    }
}
