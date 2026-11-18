package dev.metro.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.metro.launcher.physics.metroTilt
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Shadow

/** WP8 "Background" setting: dark (black pages), light (white pages), or follow the phone's dark mode. */
enum class MetroThemeMode { Dark, Light, System }

/** Whether pages should be dark, given the setting and the phone's current dark-mode state. */
fun MetroThemeMode.isDark(phoneIsDark: Boolean): Boolean = when (this) {
    MetroThemeMode.Dark -> true
    MetroThemeMode.Light -> false
    MetroThemeMode.System -> phoneIsDark
}

/** Page colours for the current theme. Tiles keep white text on their accent in both themes. */
data class MetroColors(
    val background: Color,
    val foreground: Color,
    val subtle: Color,
    /** Raised surfaces: jump-list empties, menus, panels. */
    val chrome: Color,
) {
    companion object {
        val Dark = MetroColors(Color.Black, Color.White, Color(0xFF999999), Color(0xFF1F1F1F))
        val Light = MetroColors(Color.White, Color.Black, Color(0xFF666666), Color(0xFFDDDDDD))

        fun of(mode: MetroThemeMode, phoneIsDark: Boolean) = if (mode.isDark(phoneIsDark)) Dark else Light
    }
}

val LocalMetroColors = staticCompositionLocalOf { MetroColors.Dark }

/** Metro type ramp (Segoe WP sizes mapped to the system sans). Page text follows the theme. */
object MetroType {
    private val pageTitle = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp)
    private val appTitle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
    private val large = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Light)
    private val medium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Normal)
    private val small = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal)
    private val subtle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal)

    val PageTitle: TextStyle @Composable get() = pageTitle.copy(color = LocalMetroColors.current.foreground)
    val AppTitle: TextStyle @Composable get() = appTitle.copy(color = LocalMetroColors.current.foreground)
    val Large: TextStyle @Composable get() = large.copy(color = LocalMetroColors.current.foreground)
    val Medium: TextStyle @Composable get() = medium.copy(color = LocalMetroColors.current.foreground)
    val Small: TextStyle @Composable get() = small.copy(color = LocalMetroColors.current.foreground)
    val Subtle: TextStyle @Composable get() = subtle.copy(color = LocalMetroColors.current.subtle)

    /** Tile text: always white on the accent / artwork. */
    val TileLabel = TextStyle(
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 1f), 3f),
    )
}

enum class Glyph { Back, Start, Search, Unpin, Resize, Settings, Pencil }

/** Monochrome Metro glyphs drawn with strokes, so they stay crisp at any density. Defaults to the page text colour. */
@Composable
fun MetroIcon(
    glyph: Glyph,
    modifier: Modifier = Modifier,
    color: Color = LocalMetroColors.current.foreground,
    rotationDegrees: Float = 0f,
) {
    Canvas(modifier) {
        val s = size.minDimension
        translate((size.width - s) / 2f, (size.height - s) / 2f) {
            rotate(rotationDegrees, pivot = Offset(s / 2f, s / 2f)) {
                drawGlyph(glyph, s, color)
            }
        }
    }
}

private fun DrawScope.drawGlyph(glyph: Glyph, s: Float, color: Color) {
    val w = s * 0.09f
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(color, Offset(x1 * s, y1 * s), Offset(x2 * s, y2 * s), strokeWidth = w, cap = StrokeCap.Square)

    when (glyph) {
        Glyph.Back -> {
            line(0.88f, 0.5f, 0.14f, 0.5f)
            line(0.14f, 0.5f, 0.44f, 0.2f)
            line(0.14f, 0.5f, 0.44f, 0.8f)
        }

        Glyph.Start -> {
            // Windows 8 style: a four-pane window seen in perspective (near edge on the right).
            // Bilinear map of the unit square: top edge (0.08,0.24)→(0.92,0.07), bottom (0.08,0.76)→(0.92,0.93).
            fun p(u: Float, v: Float): Offset {
                val x = 0.08f + 0.84f * u
                val top = 0.24f + (0.07f - 0.24f) * u
                val bottom = 0.76f + (0.93f - 0.76f) * u
                return Offset(x * s, (top + (bottom - top) * v) * s)
            }
            val uSplits = listOf(0f to 0.435f, 0.485f to 1f) // left panes narrower: perspective
            val vSplits = listOf(0f to 0.475f, 0.525f to 1f)
            for ((u0, u1) in uSplits) for ((v0, v1) in vSplits) {
                val pane = Path().apply {
                    moveTo(p(u0, v0).x, p(u0, v0).y)
                    lineTo(p(u1, v0).x, p(u1, v0).y)
                    lineTo(p(u1, v1).x, p(u1, v1).y)
                    lineTo(p(u0, v1).x, p(u0, v1).y)
                    close()
                }
                drawPath(pane, color)
            }
        }

        Glyph.Search -> {
            drawCircle(color, radius = s * 0.26f, center = Offset(s * 0.6f, s * 0.4f), style = Stroke(w))
            line(0.42f, 0.58f, 0.13f, 0.87f)
        }

        Glyph.Unpin -> {
            // push-pin head, plate, needle, then a slash through it
            drawRect(color, Offset(s * 0.38f, s * 0.14f), Size(s * 0.24f, s * 0.26f))
            line(0.26f, 0.44f, 0.74f, 0.44f)
            line(0.5f, 0.44f, 0.5f, 0.86f)
            line(0.16f, 0.16f, 0.84f, 0.84f)
        }

        Glyph.Pencil -> {
            // pencil body along the diagonal, with a tip
            drawLine(color, Offset(s * 0.72f, s * 0.14f), Offset(s * 0.24f, s * 0.62f), strokeWidth = w * 2.2f)
            line(0.86f, 0.28f, 0.38f, 0.76f)
            line(0.18f, 0.84f, 0.3f, 0.6f)
            line(0.18f, 0.84f, 0.42f, 0.72f)
        }

        Glyph.Resize -> {
            // diagonal arrow pointing to the top-left; rotate 180° for "grow"
            line(0.78f, 0.78f, 0.24f, 0.24f)
            line(0.22f, 0.22f, 0.56f, 0.22f)
            line(0.22f, 0.22f, 0.22f, 0.56f)
        }

        Glyph.Settings -> {
            val c = Offset(s / 2f, s / 2f)
            drawCircle(color, radius = s * 0.24f, center = c, style = Stroke(w))
            drawCircle(color, radius = s * 0.08f, center = c)
            for (k in 0 until 8) {
                rotate(k * 45f, pivot = c) {
                    drawLine(color, Offset(s / 2f, s * 0.08f), Offset(s / 2f, s * 0.26f), strokeWidth = w * 1.4f)
                }
            }
        }
    }
}

/**
 * WP8 ToggleSwitch: a white 2 dp bordered track, an accent fill inset 4 dp that grows with the
 * thumb, and a solid white thumb the full height of the track that slides from edge to edge.
 */
@Composable
private fun MetroSwitch(progress: Float, accent: Color) {
    val ink = LocalMetroColors.current.foreground
    Canvas(Modifier.size(width = 68.dp, height = 30.dp)) {
        val border = 2.dp.toPx()
        val inset = border + 4.dp.toPx()
        val thumbWidth = 14.dp.toPx()
        val w = size.width
        val h = size.height
        drawRect(
            ink,
            topLeft = Offset(border / 2f, border / 2f),
            size = Size(w - border, h - border),
            style = Stroke(border),
        )
        val thumbX = progress * (w - thumbWidth)
        val fillWidth = thumbX - inset - 2.dp.toPx()
        if (fillWidth > 0f) {
            drawRect(accent, topLeft = Offset(inset, inset), size = Size(fillWidth, h - inset * 2f))
        }
        drawRect(ink, topLeft = Offset(thumbX, 0f), size = Size(thumbWidth, h))
    }
}

/**
 * WP8 ListPicker (drop-down): the title above a 2 dp bordered box showing the current choice.
 * Tapping expands the box in place into the full list (current choice in the accent colour);
 * tapping an item picks it and collapses.
 */
@Composable
fun <T> MetroListPicker(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    accent: Color,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val colors = LocalMetroColors.current
    val noRipple = remember { MutableInteractionSource() }
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        BasicText(title, style = MetroType.Subtle)
        Spacer(Modifier.size(6.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .animateContentSize(tween(180))
                .border(2.dp, if (open) accent else colors.foreground)
                .background(if (open) colors.background else Color.Transparent),
        ) {
            if (!open) {
                BasicText(
                    label(selected),
                    style = MetroType.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .metroTilt()
                        .clickable(noRipple, indication = null) { open = true }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            } else {
                options.forEach { option ->
                    BasicText(
                        label(option),
                        style = MetroType.Medium.copy(color = if (option == selected) accent else colors.foreground),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(noRipple, indication = null) {
                                open = false
                                if (option != selected) onSelect(option)
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/** WP8 toggle: label + On/Off + rectangular switch. */
@Composable
fun MetroToggle(
    title: String,
    checked: Boolean,
    accent: Color,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val noRipple = remember { MutableInteractionSource() }
    val progress by animateFloatAsState(if (checked) 1f else 0f, spring(stiffness = 900f), label = "toggle")
    Column(
        modifier
            .fillMaxWidth()
            .metroTilt()
            .clickable(noRipple, indication = null) { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
    ) {
        BasicText(title, style = MetroType.Medium)
        if (subtitle != null) BasicText(subtitle, style = MetroType.Subtle.copy(fontSize = 13.sp))
        Spacer(Modifier.size(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(if (checked) "On" else "Off", style = MetroType.Large, modifier = Modifier.weight(1f))
            MetroSwitch(progress, accent)
        }
    }
}
