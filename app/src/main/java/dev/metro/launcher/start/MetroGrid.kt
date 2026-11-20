package dev.metro.launcher.start

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import dev.metro.launcher.physics.TileSize
import kotlinx.coroutines.launch

/**
 * Metro packing grid: first-fit, row-major, square cells, zero corner radius.
 * 4 columns (classic) or 6 columns (WP8 GDR3 "show more tiles"); tile footprints are the same in both.
 */
@Composable
fun <T> MetroGrid(
    items: List<T>,
    keyOf: (T) -> Any,
    sizeOf: (T) -> TileSize,
    modifier: Modifier = Modifier,
    columns: Int = 4,
    gap: Dp = 6.dp,
    /** Whether a tile glides to a new slot (false for the tile under the finger: it jumps). */
    animate: (T) -> Boolean = { true },
    tile: @Composable (T) -> Unit,
) {
    require(columns >= TileSize.Wide.columns) { "Metro grid needs at least ${TileSize.Wide.columns} columns" }
    // Each tile's shown position, animated here in the grid's own placement: when the packing
    // changes (resize, reorder, unpin, 4<->6 columns) tiles glide from where they are, starting the
    // very frame the new layout is placed, so nothing jumps or flashes.
    val scope = rememberCoroutineScope()
    val shown = remember { HashMap<Any, Animatable<IntOffset, AnimationVector2D>>() }
    val requested = remember { HashMap<Any, IntOffset>() }
    val currentAnimate by rememberUpdatedState(animate)
    Layout(
        modifier = modifier,
        content = { items.forEach { item -> key(keyOf(item)) { tile(item) } } },
    ) { measurables, constraints ->
        check(constraints.hasBoundedWidth) { "MetroGrid needs a bounded width" }
        val gapPx = gap.roundToPx()
        val width = constraints.maxWidth
        val cell = (width - gapPx * (columns - 1)) / columns

        val occupied = ArrayList<BooleanArray>()
        fun free(r: Int, c: Int): Boolean = r >= occupied.size || !occupied[r][c]
        fun mark(r: Int, c: Int) {
            while (occupied.size <= r) occupied += BooleanArray(columns)
            occupied[r][c] = true
        }

        val origins = items.map { item ->
            val s = sizeOf(item)
            var row = 0
            var placed: Pair<Int, Int>? = null
            while (placed == null) {
                for (col in 0..columns - s.columns) {
                    val fits = (0 until s.rows).all { dr -> (0 until s.columns).all { dc -> free(row + dr, col + dc) } }
                    if (fits) {
                        placed = row to col
                        break
                    }
                }
                if (placed == null) row++
            }
            val origin = checkNotNull(placed)
            val (r, c) = origin
            for (dr in 0 until s.rows) for (dc in 0 until s.columns) mark(r + dr, c + dc)
            origin
        }

        val placeables = measurables.mapIndexed { i, m ->
            val s = sizeOf(items[i])
            val w = s.columns * cell + (s.columns - 1) * gapPx
            val h = s.rows * cell + (s.rows - 1) * gapPx
            m.measure(Constraints.fixed(w, h))
        }
        val rows = occupied.size
        val height = if (rows == 0) 0 else rows * cell + (rows - 1) * gapPx

        val live = items.map(keyOf).toSet()
        shown.keys.retainAll(live)
        requested.keys.retainAll(live)
        layout(width, height) {
            placeables.forEachIndexed { i, p ->
                val (r, c) = origins[i]
                val goal = IntOffset(c * (cell + gapPx), r * (cell + gapPx))
                val k = keyOf(items[i])
                val anim = shown.getOrPut(k) { Animatable(goal, IntOffset.VectorConverter) }
                val glide = currentAnimate(items[i])
                if (requested[k] != goal) {
                    requested[k] = goal
                    scope.launch {
                        if (glide) anim.animateTo(goal, spring(dampingRatio = 0.82f, stiffness = 520f)) else anim.snapTo(goal)
                    }
                }
                // Until the animation starts (next frame) the tile stays where it was shown.
                p.place(if (glide) anim.value else goal)
            }
        }
    }
}

/**
 * Springs a grid child from its old slot to its new one whenever the packing changes
 * (reorder, resize, unpin, 4↔6 columns). Disabled for the tile under the finger.
 */
fun Modifier.animatePlacement(enabled: Boolean): Modifier = composed {
    val scope = rememberCoroutineScope()
    val animate by rememberUpdatedState(enabled)
    var target by remember { mutableStateOf(IntOffset.Zero) }
    var animatable by remember { mutableStateOf<Animatable<IntOffset, AnimationVector2D>?>(null) }
    this
        .onPlaced { target = it.positionInParent().round() }
        .offset {
            val anim = animatable ?: Animatable(target, IntOffset.VectorConverter).also { animatable = it }
            if (anim.targetValue != target) {
                val goal = target
                scope.launch {
                    if (animate) anim.animateTo(goal, spring(dampingRatio = 0.82f, stiffness = 520f))
                    else anim.snapTo(goal)
                }
            }
            anim.value - target
        }
}
