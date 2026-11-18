package dev.metro.launcher.volume

import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.metro.launcher.ui.Glyph
import dev.metro.launcher.ui.MetroIcon
import dev.metro.launcher.ui.LocalMetroColors
import kotlin.math.roundToInt

/** WP volume scales: ringer and notifications 0–10, media + apps 0–30, mapped onto Android's index ranges. */
object VolumeScale {
    fun steps(stream: Int): Int = if (stream == AudioManager.STREAM_MUSIC) 30 else 10

    fun level(audio: AudioManager, stream: Int): Int {
        val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        return (audio.getStreamVolume(stream) * steps(stream).toFloat() / max).roundToInt()
    }

    /**
     * Sets a WP level silently (flags = 0: Android's dialog stays hidden). If rounding would leave
     * Android's index unchanged, it moves one index in the requested direction so every press counts.
     */
    fun setLevel(audio: AudioManager, stream: Int, level: Int) {
        val steps = steps(stream)
        val max = audio.getStreamMaxVolume(stream)
        val current = audio.getStreamVolume(stream)
        val wanted = level.coerceIn(0, steps)
        var index = (wanted * max / steps.toFloat()).roundToInt()
        val before = level(audio, stream)
        if (index == current && wanted != before) {
            index = (current + if (wanted > before) 1 else -1).coerceIn(0, max)
        }
        // Ring volume 0 can mean "silent", which Android only lets apps with Do Not Disturb access set.
        runCatching { audio.setStreamVolume(stream, index, 0) }
            .onFailure { runCatching { audio.setStreamVolume(stream, 1.coerceAtMost(max), 0) } }
    }
}

@Stable
class VolumePanelState {
    var visible by mutableStateOf(false)
    var expanded by mutableStateOf(false)

    /** The stream the keys change: ringer normally, media while something plays. */
    var primary by mutableIntStateOf(AudioManager.STREAM_RING)
    var ringer by mutableIntStateOf(0)
    var notifications by mutableIntStateOf(0)
    var media by mutableIntStateOf(0)
    var silent by mutableStateOf(false)

    fun refresh(audio: AudioManager) {
        ringer = VolumeScale.level(audio, AudioManager.STREAM_RING)
        notifications = VolumeScale.level(audio, AudioManager.STREAM_NOTIFICATION)
        media = VolumeScale.level(audio, AudioManager.STREAM_MUSIC)
        silent = audio.ringerMode != AudioManager.RINGER_MODE_NORMAL
    }
}

// Colours come from the theme (LocalMetroColors): dark panel + white text, or light panel + black text.
private val Number = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Light)
private val OfMax = TextStyle(fontSize = 14.sp)
private val Label = TextStyle(fontSize = 15.sp)
private val Action = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)

/**
 * Windows Phone 8.1 volume control. Collapsed: the stream the keys are changing, with a drop-down
 * chevron. Expanded: separate "Ringer" and "Notifications" (0–10) and "Media + Apps" (0–30) sliders, then
 * SILENT MODE and SOUND SETTINGS, and an up chevron to collapse.
 */
@Composable
fun VolumePanel(
    state: VolumePanelState,
    accent: Color,
    onInteract: () -> Unit,
    onSetLevel: (stream: Int, level: Int) -> Unit,
    onToggleSilent: () -> Unit,
    onSoundSettings: () -> Unit,
    /** Space for the status bar + camera cutout, measured by the overlay host. */
    topInset: Dp,
) {
    AnimatedVisibility(
        visible = state.visible,
        enter = slideInVertically(tween(160)) { -it },
        exit = slideOutVertically(tween(160)) { -it },
    ) {
        val noRipple = remember { MutableInteractionSource() }
        val colors = LocalMetroColors.current
        val ink = colors.foreground
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.chrome)
                .padding(top = topInset)
                .padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 2.dp),
        ) {
            val rows = if (state.expanded) {
                listOf(AudioManager.STREAM_RING, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_MUSIC)
            } else {
                listOf(state.primary)
            }
            rows.forEach { stream ->
                StreamRow(
                    stream = stream,
                    level = when (stream) {
                        AudioManager.STREAM_RING -> state.ringer
                        AudioManager.STREAM_NOTIFICATION -> state.notifications
                        else -> state.media
                    },
                    accent = accent,
                    onInteract = onInteract,
                    onLevel = { onSetLevel(stream, it) },
                )
                Spacer(Modifier.height(if (state.expanded) 14.dp else 2.dp))
            }
            if (state.expanded) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(noRipple, indication = null) { onInteract(); onToggleSilent() }
                            .padding(vertical = 8.dp),
                    ) {
                        Canvas(Modifier.size(18.dp)) { drawBell(ink, colors.chrome, slashed = true) }
                        Spacer(Modifier.width(10.dp))
                        BasicText(if (state.silent) "SILENT MODE ON" else "SILENT MODE OFF", style = Action.copy(color = ink))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable(noRipple, indication = null) { onSoundSettings() }
                            .padding(vertical = 8.dp),
                    ) {
                        MetroIcon(Glyph.Settings, Modifier.size(16.dp), color = ink)
                        Spacer(Modifier.width(8.dp))
                        BasicText("SOUND SETTINGS", style = Action.copy(color = ink))
                    }
                }
            }
            // Chevron: down to expand, up to collapse.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.End)
                    .size(width = 44.dp, height = 30.dp)
                    .clickable(noRipple, indication = null) {
                        onInteract()
                        state.expanded = !state.expanded
                    },
            ) {
                Canvas(Modifier.size(14.dp)) { drawChevron(ink, up = state.expanded) }
            }
        }
    }
}

@Composable
private fun StreamRow(stream: Int, level: Int, accent: Color, onInteract: () -> Unit, onLevel: (Int) -> Unit) {
    val steps = VolumeScale.steps(stream)
    val colors = LocalMetroColors.current
    val ink = colors.foreground
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            BasicText("%02d".format(level), style = Number.copy(color = ink))
            BasicText("/$steps", style = OfMax.copy(color = colors.subtle), modifier = Modifier.padding(bottom = 3.dp))
            Spacer(Modifier.width(10.dp))
            BasicText(
                when (stream) {
                    AudioManager.STREAM_RING -> "Ringer"
                    AudioManager.STREAM_NOTIFICATION -> "Notifications"
                    else -> "Media + Apps"
                },
                style = Label.copy(color = ink),
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(20.dp)) {
                when (stream) {
                    AudioManager.STREAM_RING -> drawBell(ink, colors.chrome, slashed = false)
                    AudioManager.STREAM_NOTIFICATION -> drawMessage(ink)
                    else -> drawNote(ink)
                }
            }
            Spacer(Modifier.width(14.dp))
            LevelSlider(level, steps, accent, onInteract, onLevel, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
        }
    }
}

/** WP slider: thin grey track, accent fill, white rectangular thumb. Tap or drag to set. */
@Composable
private fun LevelSlider(
    level: Int,
    steps: Int,
    accent: Color,
    onInteract: () -> Unit,
    onLevel: (Int) -> Unit,
    modifier: Modifier,
) {
    val colors = LocalMetroColors.current
    val ink = colors.foreground
    val track = colors.subtle.copy(alpha = 0.6f)
    fun levelAt(x: Float, width: Int) = ((x / width.coerceAtLeast(1)) * steps).roundToInt().coerceIn(0, steps)
    Canvas(
        modifier
            .height(26.dp)
            .pointerInput(steps) { detectTapGestures { onInteract(); onLevel(levelAt(it.x, size.width)) } }
            .pointerInput(steps) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    onInteract()
                    onLevel(levelAt(change.position.x, size.width))
                }
            },
    ) {
        val trackHeight = 4.dp.toPx()
        val thumbWidth = 9.dp.toPx()
        val thumbHeight = 22.dp.toPx()
        val y = (size.height - trackHeight) / 2f
        val thumbX = level / steps.toFloat() * (size.width - thumbWidth)
        drawRect(track, topLeft = Offset(0f, y), size = Size(size.width, trackHeight))
        drawRect(accent, topLeft = Offset(0f, y), size = Size(thumbX, trackHeight))
        drawRect(ink, topLeft = Offset(thumbX, (size.height - thumbHeight) / 2f), size = Size(thumbWidth, thumbHeight))
    }
}

private fun DrawScope.drawBell(ink: Color, band: Color, slashed: Boolean) {
    val s = size.minDimension
    val bell = Path().apply {
        moveTo(s * 0.5f, s * 0.08f)
        cubicTo(s * 0.8f, s * 0.1f, s * 0.78f, s * 0.42f, s * 0.8f, s * 0.62f)
        lineTo(s * 0.92f, s * 0.78f)
        lineTo(s * 0.08f, s * 0.78f)
        lineTo(s * 0.2f, s * 0.62f)
        cubicTo(s * 0.22f, s * 0.42f, s * 0.2f, s * 0.1f, s * 0.5f, s * 0.08f)
        close()
    }
    drawPath(bell, ink)
    drawCircle(ink, radius = s * 0.09f, center = Offset(s * 0.5f, s * 0.88f))
    if (slashed) {
        drawLine(band, Offset(s * 0.1f, s * 0.04f), Offset(s * 0.96f, s * 0.9f), s * 0.2f)
        drawLine(ink, Offset(s * 0.06f, s * 0.08f), Offset(s * 0.92f, s * 0.94f), s * 0.09f, StrokeCap.Square)
    }
}

/** Speech bubble (notifications). */
private fun DrawScope.drawMessage(ink: Color) {
    val s = size.minDimension
    val bubble = Path().apply {
        moveTo(s * 0.08f, s * 0.14f)
        lineTo(s * 0.92f, s * 0.14f)
        lineTo(s * 0.92f, s * 0.66f)
        lineTo(s * 0.42f, s * 0.66f)
        lineTo(s * 0.2f, s * 0.88f)
        lineTo(s * 0.22f, s * 0.66f)
        lineTo(s * 0.08f, s * 0.66f)
        close()
    }
    drawPath(bubble, ink)
}

/** Eighth note. */
private fun DrawScope.drawNote(ink: Color) {
    val s = size.minDimension
    drawCircle(ink, radius = s * 0.17f, center = Offset(s * 0.36f, s * 0.78f))
    drawLine(ink, Offset(s * 0.5f, s * 0.78f), Offset(s * 0.5f, s * 0.08f), s * 0.09f)
    val flag = Path().apply {
        moveTo(s * 0.5f, s * 0.08f)
        cubicTo(s * 0.62f, s * 0.22f, s * 0.86f, s * 0.26f, s * 0.78f, s * 0.52f)
        cubicTo(s * 0.76f, s * 0.36f, s * 0.62f, s * 0.32f, s * 0.5f, s * 0.3f)
        close()
    }
    drawPath(flag, ink)
}

private fun DrawScope.drawChevron(ink: Color, up: Boolean) {
    val s = size.minDimension
    val stroke = Stroke(width = s * 0.12f, cap = StrokeCap.Square)
    val path = Path().apply {
        if (up) {
            moveTo(s * 0.1f, s * 0.7f); lineTo(s * 0.5f, s * 0.3f); lineTo(s * 0.9f, s * 0.7f)
        } else {
            moveTo(s * 0.1f, s * 0.3f); lineTo(s * 0.5f, s * 0.7f); lineTo(s * 0.9f, s * 0.3f)
        }
    }
    drawPath(path, ink, style = stroke)
}
