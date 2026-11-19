package dev.metro.launcher.actioncenter

import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.metro.launcher.notifications.ActionCenterItem
import dev.metro.launcher.physics.metroTilt
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** WP 8.1 Action Center palette (dark theme: black with near-black tiles, as on the phone). */
private class AcColors(val bed: Color, val tileOff: Color, val ink: Color, val subtle: Color) {
    companion object {
        val Dark = AcColors(Color(0xFF000000), Color(0xFF262626), Color.White, Color(0xFFA0A0A0))
        val Light = AcColors(Color(0xFFFFFFFF), Color(0xFFD6D6D6), Color.Black, Color(0xFF5A5A5A))
    }
}

private val Caps = TextStyle(fontSize = 10.sp, letterSpacing = 0.3.sp, lineHeight = 12.sp)

@Composable
internal fun ActionCenterPanel(
    state: ActionCenterState,
    quick: QuickActions,
    quickActions: List<QuickAction>,
    items: List<ActionCenterItem>,
    accent: Color,
    dark: Boolean,
    onQuickAction: (QuickAction) -> Unit,
    onQuickActionSettings: (QuickAction) -> Unit,
    onAllSettings: () -> Unit,
    onOpen: (ActionCenterItem) -> Unit,
    onDismiss: (ActionCenterItem) -> Unit,
    onDismissApp: (String) -> Unit,
    onClearAll: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
    onOutside: () -> Unit,
    onBrightnessPermission: () -> Unit,
) {
    val c = if (dark) AcColors.Dark else AcColors.Light
    val dragState = rememberDraggableState(onDrag)
    val drag = Modifier.draggable(dragState, Orientation.Vertical, onDragStopped = { v -> onDragEnd(v) })
    val noRipple = remember { MutableInteractionSource() }
    val fullyOpen = state.revealed > state.peekPx + 24f
    var expanded by remember { androidx.compose.runtime.mutableStateOf(false) }

    // Revealed from the top: laid out at full height, only the pulled-down part is drawn. The
    // sheet's edge is the WP accent bar with its little handle. A tap below the edge closes it.
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (down.position.y > state.revealed) {
                        down.consume()
                        waitForUpOrCancellation(PointerEventPass.Initial)?.consume()
                        onOutside()
                    }
                }
            }
            .drawWithContent {
                val edge = state.revealed.coerceIn(0f, size.height)
                clipRect(bottom = edge) { this@drawWithContent.drawContent() }
                val bar = 12.dp.toPx()
                if (edge > bar) {
                    drawRect(accent, Offset(0f, edge - bar), Size(size.width, bar))
                    val dash = 18.dp.toPx()
                    drawRect(Color.White, Offset((size.width - dash) / 2, edge - bar / 2 - 1.dp.toPx()), Size(dash, 2.dp.toPx()))
                }
            },
    ) {
        Column(Modifier.fillMaxSize().background(c.bed)) {
            // ---- the part a short pull shows
            Column(
                drag.onSizeChanged {
                    // Expand / collapse while only this part is out: the sheet's edge follows its new size.
                    val old = state.peekPx
                    state.peekPx = it.height.toFloat()
                    if (old > 0f && kotlin.math.abs(state.revealed - old) < 4f) state.revealed = state.peekPx
                },
            ) {
                // The same WP status bar as in every app, in exactly the same place, then the carrier.
                val band = with(androidx.compose.ui.platform.LocalDensity.current) { state.statusBarPx.toDp() }.coerceAtLeast(24.dp)
                Box(Modifier.fillMaxWidth().height(band)) {
                    WpStatusBar(quick = quick, notifications = items, ink = c.ink, bed = c.bed, height = band)
                }
                BasicText(
                    quick.carrier() ?: "",
                    style = TextStyle(color = c.ink, fontSize = 13.sp),
                    maxLines = 1,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 2.dp),
                )
                Spacer(Modifier.height(14.dp))
                QuickRow(quickActions, quick, accent, c, onQuickAction, onQuickActionSettings)
                BrightnessRow(quick, accent, c, onNeedsPermission = onBrightnessPermission)
                if (expanded) {
                    val more = remember(quickActions) { quick.offered().filter { it !in quickActions } }
                    more.chunked(ActionCenterConfig.SLOTS).forEach { row ->
                        Spacer(Modifier.height(3.dp))
                        QuickRow(row + List(ActionCenterConfig.SLOTS - row.size) { null }, quick, accent, c, onQuickAction, onQuickActionSettings)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconText(if (expanded) "COLLAPSE" else "EXPAND", c.ink, noRipple, { expanded = !expanded }) { drawChevron(it, up = expanded) }
                    Spacer(Modifier.weight(1f))
                    IconText("ALL SETTINGS", c.ink, noRipple, onAllSettings) { drawGear(it, c.bed) }
                }
                if (fullyOpen && items.any { it.clearable }) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        IconText("CLEAR ALL", c.ink, noRipple, onClearAll) { drawCross(it) }
                    }
                }
                Spacer(Modifier.height(12.dp)) // room for the accent bar when only this part is out
            }

            // ---- notifications, grouped by app (newest group first)
            val groups = remember(items) { items.groupBy { it.packageName }.entries.toList() }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (groups.isEmpty()) {
                    BasicText(
                        "no new notifications",
                        style = TextStyle(color = c.subtle, fontSize = 20.sp, fontWeight = FontWeight.Light),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    groups.forEach { (pkg, groupItems) ->
                        item(key = "h:$pkg") { AppGroupHeader(pkg, groupItems.first(), accent, c, onDismissApp) }
                        items(groupItems, key = { it.key }) { item -> NotificationRow(item, c, onOpen, onDismiss) }
                    }
                }
            }
            // Handle area at the bottom when fully open (the accent bar is drawn at the edge).
            Box(drag.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).height(28.dp))
        }
    }
}

// ------------------------------------------------------------------ WP status area

@Composable
private fun QuickRow(
    actions: List<QuickAction?>,
    quick: QuickActions,
    accent: Color,
    c: AcColors,
    onTap: (QuickAction) -> Unit,
    onLongPress: (QuickAction) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        actions.forEach { action ->
            if (action != null && quick.isAvailable(action)) QuickTile(action, quick, accent, c, onTap, onLongPress)
            else Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * Brightness: a sun, a Metro slider (thin track, accent fill, solid rectangular thumb) and an AUTO
 * switch. Dragging the slider switches auto off. Needs "Modify system settings" once.
 */
@Composable
private fun BrightnessRow(quick: QuickActions, accent: Color, c: AcColors, onNeedsPermission: () -> Unit) {
    @Suppress("UNUSED_VARIABLE")
    val revision = quick.revision
    val auto = quick.isAutoBrightness()
    var value by remember { androidx.compose.runtime.mutableFloatStateOf(quick.brightnessFraction()) }
    androidx.compose.runtime.LaunchedEffect(revision) { value = quick.brightnessFraction() }
    val noRipple = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(start = 14.dp, end = 12.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(16.dp)) { drawQuickIcon(QuickAction.Brightness, c.ink) }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .weight(1f)
                .height(36.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (!quick.canChangeBrightness()) {
                            waitForUpOrCancellation()
                            onNeedsPermission()
                            return@awaitEachGesture
                        }
                        fun set(x: Float) {
                            value = (x / size.width).coerceIn(0f, 1f)
                            quick.setBrightness(value)
                        }
                        set(down.position.x)
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            set(change.position.x)
                            change.consume()
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val track = 4.dp.toPx()
                val y = size.height / 2 - track / 2
                drawRect(c.tileOff, Offset(0f, y), Size(size.width, track))
                val x = size.width * value
                drawRect(if (auto) c.subtle else accent, Offset(0f, y), Size(x, track))
                val thumbW = 10.dp.toPx()
                val thumbH = 22.dp.toPx()
                drawRect(c.ink, Offset((x - thumbW / 2).coerceIn(0f, size.width - thumbW), size.height / 2 - thumbH / 2), Size(thumbW, thumbH))
            }
        }
        Spacer(Modifier.width(12.dp))
        BasicText(
            "AUTO",
            style = TextStyle(color = if (auto) accent else c.subtle, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
            modifier = Modifier
                .metroTilt()
                .clickable(noRipple, indication = null) {
                    if (quick.canChangeBrightness()) quick.setAutoBrightness(!auto) else onNeedsPermission()
                }
                .padding(vertical = 10.dp, horizontal = 4.dp),
        )
    }
}

private fun DrawScope.drawChevron(ink: Color, up: Boolean) {
    val y1 = if (up) 0.68f else 0.32f
    val y2 = if (up) 0.32f else 0.68f
    ln(ink, 0.12f, y1, 0.5f, y2)
    ln(ink, 0.5f, y2, 0.88f, y1)
}

@Composable
private fun StatusGlyph(draw: DrawScope.() -> Unit) {
    Canvas(Modifier.size(15.dp), onDraw = draw)
}

// ------------------------------------------------------------------ quick action tiles

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RowScope.QuickTile(
    action: QuickAction,
    quick: QuickActions,
    accent: Color,
    c: AcColors,
    onTap: (QuickAction) -> Unit,
    onLongPress: (QuickAction) -> Unit,
) {
    @Suppress("UNUSED_VARIABLE")
    val revision = quick.revision // re-read states after a change
    val on = quick.isOn(action)
    val noRipple = remember { MutableInteractionSource() }
    Box(
        Modifier
            .weight(1f)
            .aspectRatio(1.5f)
            .metroTilt()
            .background(if (on) accent else c.tileOff)
            .combinedClickable(noRipple, indication = null, onLongClick = { onLongPress(action) }) { onTap(action) }
            .padding(start = 7.dp, top = 7.dp, end = 4.dp, bottom = 5.dp),
    ) {
        Canvas(Modifier.size(18.dp).align(Alignment.TopStart)) { drawQuickIcon(action, Color.White) }
        BasicText(
            quick.label(action).uppercase(),
            style = Caps.copy(color = Color.White),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

@Composable
private fun IconText(text: String, ink: Color, noRipple: MutableInteractionSource, onClick: () -> Unit, icon: DrawScope.(Color) -> Unit) {
    Row(
        Modifier.metroTilt().clickable(noRipple, indication = null, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(14.dp)) { icon(ink) }
        Spacer(Modifier.width(8.dp))
        BasicText(text, style = TextStyle(color = ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp))
    }
}

// ------------------------------------------------------------------ notifications

@Composable
private fun AppGroupHeader(packageName: String, first: ActionCenterItem, accent: Color, c: AcColors, onDismissApp: (String) -> Unit) {
    val context = LocalContext.current
    val label = remember(packageName) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
    }
    val glyph = rememberGlyph(first)
    var dx by remember(packageName) { mutableFloatStateOf(0f) }
    val swipe = rememberDraggableState { delta -> dx = (dx + delta).coerceAtLeast(0f) }
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { translationX = dx; alpha = (1f - dx / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f) }
            .draggable(swipe, Orientation.Horizontal, onDragStopped = { v -> if (dx > 220f || v > 1500f) onDismissApp(packageName) else dx = 0f })
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).background(accent), contentAlignment = Alignment.Center) {
            glyph?.let { Image(it, null, colorFilter = ColorFilter.tint(Color.White), modifier = Modifier.size(20.dp)) }
        }
        Spacer(Modifier.width(12.dp))
        BasicText(label, style = TextStyle(color = c.ink, fontSize = 22.sp, fontWeight = FontWeight.Light), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Tap to open; swipe right to dismiss (clearable notifications only). */
@Composable
private fun NotificationRow(item: ActionCenterItem, c: AcColors, onOpen: (ActionCenterItem) -> Unit, onDismiss: (ActionCenterItem) -> Unit) {
    var dx by remember(item.key) { mutableFloatStateOf(0f) }
    val noRipple = remember { MutableInteractionSource() }
    val swipe = rememberDraggableState { delta -> if (item.clearable) dx = (dx + delta).coerceAtLeast(0f) }
    val context = LocalContext.current
    val time = remember(item.postedAt) {
        SimpleDateFormat(if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm", Locale.getDefault()).format(Date(item.postedAt))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { translationX = dx; alpha = (1f - dx / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f) }
            .draggable(swipe, Orientation.Horizontal, onDragStopped = { v -> if (dx > 220f || v > 1500f) onDismiss(item) else dx = 0f })
            .clickable(noRipple, indication = null) { onOpen(item) }
            .padding(start = 16.dp, end = 16.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(
                item.title.ifBlank { item.text },
                style = TextStyle(color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.title.isNotBlank() && item.text.isNotBlank()) {
                BasicText(item.text, style = TextStyle(color = c.subtle, fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(10.dp))
        BasicText(time, style = TextStyle(color = c.subtle, fontSize = 14.sp))
    }
}

@Composable
private fun rememberGlyph(item: ActionCenterItem): ImageBitmap? {
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

// ------------------------------------------------------------------ Metro glyphs (thin, Segoe-style lines)

internal fun DrawScope.sw() = size.width * 0.085f
private fun DrawScope.ln(ink: Color, x1: Float, y1: Float, x2: Float, y2: Float) =
    drawLine(ink, Offset(size.width * x1, size.height * y1), Offset(size.width * x2, size.height * y2), strokeWidth = sw(), cap = StrokeCap.Round)
internal fun DrawScope.stroke() = Stroke(width = sw(), cap = StrokeCap.Round, join = StrokeJoin.Round)

internal fun DrawScope.drawSignal(ink: Color, level: Int?) {
    // WP: five solid bars rising left to right; bars above the level are dimmed.
    val w = size.width
    val h = size.height
    val bars = 5
    val gap = w * 0.06f
    val bw = (w - gap * (bars - 1)) / bars
    val filled = when (level) { null -> -1; else -> (level.coerceIn(0, 4) + 1) }
    for (i in 0 until bars) {
        val top = h * (1f - (i + 1) / bars.toFloat())
        drawRect(if (i < filled) ink else ink.copy(alpha = 0.3f), Offset(i * (bw + gap), top), Size(bw, h - top))
    }
}

/** WP Wi-Fi: a quarter fan of arcs from the bottom-left corner. */
internal fun DrawScope.drawWifi(ink: Color) {
    val w = size.width
    val h = size.height
    val origin = Offset(w * 0.08f, h * 0.92f)
    drawCircle(ink, w * 0.09f, origin)
    for (i in 1..3) {
        val r = w * 0.27f * i
        drawArc(ink, 270f, 90f, false, Offset(origin.x - r, origin.y - r), Size(r * 2, r * 2), style = stroke())
    }
}

internal fun DrawScope.drawBluetooth(ink: Color) {
    val w = size.width
    val h = size.height
    val p = Path().apply {
        moveTo(w * 0.24f, h * 0.28f); lineTo(w * 0.74f, h * 0.68f); lineTo(w * 0.5f, h * 0.92f)
        lineTo(w * 0.5f, h * 0.08f); lineTo(w * 0.74f, h * 0.32f); lineTo(w * 0.24f, h * 0.72f)
    }
    drawPath(p, ink, style = stroke())
}

internal fun DrawScope.drawMoon(ink: Color) {
    val w = size.width
    val h = size.height
    val p = Path().apply {
        moveTo(w * 0.58f, h * 0.06f)
        cubicTo(w * 0.16f, h * 0.1f, w * 0.06f, h * 0.66f, w * 0.4f, h * 0.88f)
        cubicTo(w * 0.62f, h * 1.0f, w * 0.9f, h * 0.86f, w * 0.96f, h * 0.66f)
        cubicTo(w * 0.62f, h * 0.76f, w * 0.36f, h * 0.42f, w * 0.58f, h * 0.06f)
        close()
    }
    drawPath(p, ink)
}

private fun DrawScope.drawBattery(ink: Color, level: Float) {
    val stroke = 1.2.dp.toPx()
    val nub = 2.dp.toPx()
    drawRect(ink, Offset(stroke / 2, stroke / 2), Size(size.width - nub - stroke, size.height - stroke), style = Stroke(stroke))
    drawRect(ink, Offset(size.width - nub, size.height * 0.3f), Size(nub, size.height * 0.4f))
    val inner = (size.width - nub - stroke * 4) * level.coerceIn(0f, 1f)
    drawRect(ink, Offset(stroke * 2, stroke * 2), Size(inner, size.height - stroke * 4))
}

private fun DrawScope.drawCross(ink: Color) {
    ln(ink, 0.12f, 0.12f, 0.88f, 0.88f)
    ln(ink, 0.88f, 0.12f, 0.12f, 0.88f)
}

private fun DrawScope.drawGear(ink: Color, hole: Color) {
    val c = Offset(size.width / 2, size.height / 2)
    val r = size.width * 0.3f
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0)
        drawLine(
            ink,
            Offset(c.x + (r * kotlin.math.cos(a)).toFloat(), c.y + (r * kotlin.math.sin(a)).toFloat()),
            Offset(c.x + (size.width * 0.48f * kotlin.math.cos(a)).toFloat(), c.y + (size.width * 0.48f * kotlin.math.sin(a)).toFloat()),
            strokeWidth = size.width * 0.17f,
        )
    }
    drawCircle(ink, r, c)
    drawCircle(hole, r * 0.42f, c)
}

private fun DrawScope.drawQuickIcon(action: QuickAction, ink: Color) {
    val w = size.width
    val h = size.height
    when (action) {
        QuickAction.Wifi -> drawWifi(ink)
        QuickAction.Bluetooth -> drawBluetooth(ink)
        QuickAction.Cellular -> drawSignal(ink, 4)
        QuickAction.Airplane -> {
            val p = Path().apply {
                moveTo(w * 0.5f, h * 0.04f)
                cubicTo(w * 0.57f, h * 0.04f, w * 0.57f, h * 0.14f, w * 0.57f, h * 0.36f)
                lineTo(w * 0.96f, h * 0.58f); lineTo(w * 0.96f, h * 0.68f); lineTo(w * 0.57f, h * 0.55f)
                lineTo(w * 0.57f, h * 0.78f); lineTo(w * 0.7f, h * 0.88f); lineTo(w * 0.7f, h * 0.96f)
                lineTo(w * 0.5f, h * 0.9f); lineTo(w * 0.3f, h * 0.96f); lineTo(w * 0.3f, h * 0.88f)
                lineTo(w * 0.43f, h * 0.78f); lineTo(w * 0.43f, h * 0.55f); lineTo(w * 0.04f, h * 0.68f)
                lineTo(w * 0.04f, h * 0.58f); lineTo(w * 0.43f, h * 0.36f)
                cubicTo(w * 0.43f, h * 0.14f, w * 0.43f, h * 0.04f, w * 0.5f, h * 0.04f)
                close()
            }
            drawPath(p, ink)
        }
        QuickAction.Rotation -> {
            // WP rotation lock: a tilted page with a padlock.
            val p = Path().apply {
                moveTo(w * 0.1f, h * 0.45f); lineTo(w * 0.45f, h * 0.1f); lineTo(w * 0.9f, h * 0.55f); lineTo(w * 0.55f, h * 0.9f); close()
            }
            drawPath(p, ink, style = stroke())
            drawRect(ink, Offset(w * 0.38f, h * 0.5f), Size(w * 0.26f, h * 0.2f))
            drawArc(ink, 180f, 180f, false, Offset(w * 0.42f, h * 0.36f), Size(w * 0.18f, h * 0.24f), style = Stroke(sw() * 0.8f))
        }
        QuickAction.Flashlight -> {
            val p = Path().apply {
                moveTo(w * 0.22f, h * 0.06f); lineTo(w * 0.78f, h * 0.06f); lineTo(w * 0.78f, h * 0.2f)
                lineTo(w * 0.63f, h * 0.42f); lineTo(w * 0.63f, h * 0.94f); lineTo(w * 0.37f, h * 0.94f)
                lineTo(w * 0.37f, h * 0.42f); lineTo(w * 0.22f, h * 0.2f); close()
            }
            drawPath(p, ink, style = stroke())
        }
        QuickAction.QuietHours -> drawMoon(ink)
        QuickAction.Sound -> {
            val p = Path().apply {
                moveTo(w * 0.08f, h * 0.36f); lineTo(w * 0.3f, h * 0.36f); lineTo(w * 0.56f, h * 0.1f)
                lineTo(w * 0.56f, h * 0.9f); lineTo(w * 0.3f, h * 0.64f); lineTo(w * 0.08f, h * 0.64f); close()
            }
            drawPath(p, ink)
            drawArc(ink, -45f, 90f, false, Offset(w * 0.46f, h * 0.26f), Size(w * 0.48f, h * 0.48f), style = stroke())
        }
        QuickAction.Location -> {
            val p = Path().apply {
                moveTo(w * 0.5f, h * 0.96f)
                cubicTo(w * 0.18f, h * 0.58f, w * 0.16f, h * 0.48f, w * 0.16f, h * 0.36f)
                cubicTo(w * 0.16f, h * 0.16f, w * 0.32f, h * 0.04f, w * 0.5f, h * 0.04f)
                cubicTo(w * 0.68f, h * 0.04f, w * 0.84f, h * 0.16f, w * 0.84f, h * 0.36f)
                cubicTo(w * 0.84f, h * 0.48f, w * 0.82f, h * 0.58f, w * 0.5f, h * 0.96f)
                close()
            }
            drawPath(p, ink, style = stroke())
            drawCircle(ink, w * 0.1f, Offset(w * 0.5f, h * 0.36f))
        }
        QuickAction.Hotspot -> {
            drawCircle(ink, w * 0.09f, Offset(w * 0.5f, h * 0.5f))
            drawArc(ink, 135f, 90f, false, Offset(w * 0.22f, h * 0.22f), Size(w * 0.56f, h * 0.56f), style = stroke())
            drawArc(ink, -45f, 90f, false, Offset(w * 0.22f, h * 0.22f), Size(w * 0.56f, h * 0.56f), style = stroke())
            drawArc(ink, 135f, 90f, false, Offset(w * 0.02f, h * 0.02f), Size(w * 0.96f, h * 0.96f), style = stroke())
            drawArc(ink, -45f, 90f, false, Offset(w * 0.02f, h * 0.02f), Size(w * 0.96f, h * 0.96f), style = stroke())
        }
        QuickAction.BatterySaver -> {
            drawRect(ink, Offset(w * 0.04f, h * 0.28f), Size(w * 0.82f, h * 0.44f), style = stroke())
            drawRect(ink, Offset(w * 0.86f, h * 0.4f), Size(w * 0.1f, h * 0.2f))
            val p = Path().apply {
                moveTo(w * 0.26f, h * 0.62f)
                cubicTo(w * 0.28f, h * 0.36f, w * 0.5f, h * 0.36f, w * 0.66f, h * 0.38f)
                cubicTo(w * 0.62f, h * 0.58f, w * 0.46f, h * 0.64f, w * 0.26f, h * 0.62f)
                close()
            }
            drawPath(p, ink)
        }
        QuickAction.DarkMode -> {
            drawCircle(ink, w * 0.42f, Offset(w / 2, h / 2), style = stroke())
            drawArc(ink, 90f, 180f, true, Offset(w * 0.08f, h * 0.08f), Size(w * 0.84f, h * 0.84f))
        }
        QuickAction.EyeComfort -> {
            val p = Path().apply {
                moveTo(w * 0.04f, h * 0.5f)
                quadraticTo(w * 0.5f, h * 0.0f, w * 0.96f, h * 0.5f)
                quadraticTo(w * 0.5f, h * 1.0f, w * 0.04f, h * 0.5f)
                close()
            }
            drawPath(p, ink, style = stroke())
            drawCircle(ink, w * 0.14f, Offset(w / 2, h / 2))
        }
        QuickAction.Brightness, QuickAction.ReduceBrightness -> {
            val c = Offset(w / 2, h / 2)
            drawCircle(ink, w * 0.18f, c, style = stroke())
            if (action == QuickAction.ReduceBrightness) {
                drawArc(ink, 90f, 180f, true, Offset(c.x - w * 0.18f, c.y - w * 0.18f), Size(w * 0.36f, w * 0.36f))
            }
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                drawLine(
                    ink,
                    Offset(c.x + (w * 0.3f * kotlin.math.cos(a)).toFloat(), c.y + (w * 0.3f * kotlin.math.sin(a)).toFloat()),
                    Offset(c.x + (w * 0.45f * kotlin.math.cos(a)).toFloat(), c.y + (w * 0.45f * kotlin.math.sin(a)).toFloat()),
                    strokeWidth = sw(), cap = StrokeCap.Round,
                )
            }
        }
        QuickAction.FontSize -> {
            val p = Path().apply { moveTo(w * 0.04f, h * 0.9f); lineTo(w * 0.32f, h * 0.12f); lineTo(w * 0.6f, h * 0.9f) }
            drawPath(p, ink, style = stroke())
            ln(ink, 0.14f, 0.62f, 0.5f, 0.62f)
            val q = Path().apply { moveTo(w * 0.58f, h * 0.9f); lineTo(w * 0.77f, h * 0.42f); lineTo(w * 0.96f, h * 0.9f) }
            drawPath(q, ink, style = stroke())
        }
        QuickAction.ScreenRecord -> {
            drawCircle(ink, w * 0.44f, Offset(w / 2, h / 2), style = stroke())
            drawCircle(ink, w * 0.22f, Offset(w / 2, h / 2))
        }
        QuickAction.Screenshot -> {
            ln(ink, 0.06f, 0.06f, 0.36f, 0.06f); ln(ink, 0.06f, 0.06f, 0.06f, 0.36f)
            ln(ink, 0.94f, 0.06f, 0.64f, 0.06f); ln(ink, 0.94f, 0.06f, 0.94f, 0.36f)
            ln(ink, 0.06f, 0.94f, 0.36f, 0.94f); ln(ink, 0.06f, 0.94f, 0.06f, 0.64f)
            ln(ink, 0.94f, 0.94f, 0.64f, 0.94f); ln(ink, 0.94f, 0.94f, 0.94f, 0.64f)
        }
        QuickAction.SplitScreen -> {
            drawRect(ink, Offset(w * 0.12f, h * 0.06f), Size(w * 0.76f, h * 0.38f), style = stroke())
            drawRect(ink, Offset(w * 0.12f, h * 0.56f), Size(w * 0.76f, h * 0.38f), style = stroke())
        }
        QuickAction.Scanner -> {
            ln(ink, 0.06f, 0.3f, 0.06f, 0.06f); ln(ink, 0.06f, 0.06f, 0.3f, 0.06f)
            ln(ink, 0.7f, 0.06f, 0.94f, 0.06f); ln(ink, 0.94f, 0.06f, 0.94f, 0.3f)
            ln(ink, 0.06f, 0.7f, 0.06f, 0.94f); ln(ink, 0.06f, 0.94f, 0.3f, 0.94f)
            ln(ink, 0.7f, 0.94f, 0.94f, 0.94f); ln(ink, 0.94f, 0.94f, 0.94f, 0.7f)
            ln(ink, 0.2f, 0.5f, 0.8f, 0.5f)
        }
        QuickAction.Camera -> {
            drawRoundRect(ink, Offset(w * 0.04f, h * 0.26f), Size(w * 0.92f, h * 0.6f), CornerRadius(w * 0.05f), style = stroke())
            drawRect(ink, Offset(w * 0.32f, h * 0.14f), Size(w * 0.36f, h * 0.12f))
            drawCircle(ink, w * 0.17f, Offset(w * 0.5f, h * 0.56f), style = stroke())
        }
        QuickAction.Nfc -> {
            drawRoundRect(ink, Offset(w * 0.08f, h * 0.08f), Size(w * 0.84f, h * 0.84f), CornerRadius(w * 0.12f), style = stroke())
            val p = Path().apply { moveTo(w * 0.34f, h * 0.72f); lineTo(w * 0.34f, h * 0.28f); lineTo(w * 0.66f, h * 0.72f); lineTo(w * 0.66f, h * 0.28f) }
            drawPath(p, ink, style = stroke())
        }
        QuickAction.Cast -> {
            // Screen outline with its bottom-left corner open, and cast waves rising from that corner.
            val frame = Path().apply {
                moveTo(w * 0.06f, h * 0.36f); lineTo(w * 0.06f, h * 0.14f); lineTo(w * 0.94f, h * 0.14f)
                lineTo(w * 0.94f, h * 0.86f); lineTo(w * 0.6f, h * 0.86f)
            }
            drawPath(frame, ink, style = stroke())
            val corner = Offset(w * 0.06f, h * 0.86f)
            drawCircle(ink, w * 0.06f, corner)
            for (r in listOf(0.2f, 0.36f)) {
                val rad = w * r
                drawArc(ink, 270f, 90f, false, Offset(corner.x - rad, corner.y - rad), Size(rad * 2, rad * 2), style = stroke())
            }
        }
        QuickAction.PowerOff -> {
            drawArc(ink, -60f, 300f, false, Offset(w * 0.1f, h * 0.12f), Size(w * 0.8f, h * 0.8f), style = stroke())
            ln(ink, 0.5f, 0.04f, 0.5f, 0.46f)
        }
        QuickAction.Lock -> {
            drawRect(ink, Offset(w * 0.18f, h * 0.44f), Size(w * 0.64f, h * 0.5f))
            drawArc(ink, 180f, 180f, false, Offset(w * 0.28f, h * 0.08f), Size(w * 0.44f, h * 0.7f), style = stroke())
        }
    }
}
