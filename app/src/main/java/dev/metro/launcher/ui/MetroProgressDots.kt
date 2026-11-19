package dev.metro.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.pow

/**
 * Windows Phone indeterminate progress: five small dots fly in from the left, bunch up and drift
 * slowly through the middle, then race off to the right one after another, and the cycle repeats.
 */
@Composable
fun MetroProgressDots(color: Color, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) {
            now = withFrameMillis { it } - start
        }
    }
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val radius = 2.dp.toPx()
        val t = (now % CYCLE_MS).toFloat()
        for (i in 0 until DOTS) {
            val p = (t - i * STAGGER_MS) / TRAVEL_MS
            if (p <= 0f || p >= 1f) continue
            // Fast - slow - fast: a cubic through the centre, flattened in the middle third.
            val c = (p - 0.5f) * 2f // -1..1
            val eased = 0.5f + 0.5f * (SLOW_SHARE * c + (1f - SLOW_SHARE) * c.toDouble().pow(3.0).toFloat())
            // Dots enter and leave just beyond the edges so they never pop in.
            val x = -radius * 4 + eased * (size.width + radius * 8)
            drawCircle(color, radius, Offset(x, size.height / 2))
        }
    }
}

private const val DOTS = 5
private const val TRAVEL_MS = 2_600f
private const val STAGGER_MS = 170f
private const val CYCLE_MS = 3_800L
/** How much of the motion is linear: the rest is the cubic that makes the middle slow. */
private const val SLOW_SHARE = 0.18f
