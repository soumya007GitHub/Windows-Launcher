package dev.metro.launcher.physics

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.util.lerp

/**
 * The four WP toolkit turnstile storyboards. Angles are the toolkit's XAML values, mapped to
 * Android's rotation handedness. Every item hinges on the **left edge of the screen**, not its own
 * left edge, which is what makes the Start screen look like one door swinging shut.
 *
 * | Mode        | RotationY (XAML) | Duration | Ease                     |
 * |-------------|------------------|----------|--------------------------|
 * | ForwardIn   | −80 → 0          | 350 ms   | Exponential(6) EaseOut   |
 * | ForwardOut  |   0 → 50         | 250 ms   | Exponential(6) EaseIn    |
 * | BackwardIn  |  50 → 0          | 350 ms   | Exponential(6) EaseOut   |
 * | BackwardOut |   0 → −80        | 250 ms   | Exponential(6) EaseIn    |
 */
enum class TurnstileMode(
    xamlFrom: Float,
    xamlTo: Float,
    val durationMillis: Int,
    val easing: Easing,
    val entering: Boolean,
) {
    ForwardIn(-80f, 0f, MetroMotion.TURNSTILE_IN_MS, EaseOut, entering = true),
    ForwardOut(0f, 50f, MetroMotion.TURNSTILE_OUT_MS, EaseIn, entering = false),
    BackwardIn(50f, 0f, MetroMotion.TURNSTILE_IN_MS, EaseOut, entering = true),
    BackwardOut(0f, -80f, MetroMotion.TURNSTILE_OUT_MS, EaseIn, entering = false);

    val fromDegrees: Float = xamlFrom * MetroMotion.SILVERLIGHT_TO_ANDROID_Y
    val toDegrees: Float = xamlTo * MetroMotion.SILVERLIGHT_TO_ANDROID_Y
}

private val EaseIn: Easing = ExponentialEaseIn(MetroMotion.TURNSTILE_EASE_EXPONENT)
private val EaseOut: Easing = ExponentialEaseOut(MetroMotion.TURNSTILE_EASE_EXPONENT)

/**
 * One shared timeline for a whole page. Items do not own animations; each one reads the clock
 * inside its own graphicsLayer and derives its delay from its on-screen Y position, so a page of
 * 40 tiles costs one Animatable and zero recompositions per frame.
 *
 * After an *out* mode finishes the page stays hidden (rotated out, alpha 0) until [play] is called
 * with an *in* mode or [reset] is called — so the launcher never flashes back before an app opens.
 */
@Stable
class TurnstileState {

    /** Active (or last finished out-) mode; null = page at rest. */
    var mode: TurnstileMode? by mutableStateOf(null)
        private set

    internal var anchorKey: Any? by mutableStateOf(null)
        private set

    /** Incremented per run so items recompute their cached geometry exactly once per run. */
    internal var runId by mutableIntStateOf(0)
        private set

    private val clock = Animatable(0f)
    private val mutex = MutatorMutex()

    /** Milliseconds since the current run started. Snapshot-observable. */
    internal val elapsedMillis: Float get() = clock.value

    val isRunning: Boolean get() = clock.isRunning

    /** True while the page is invisible (mid-out or finished out). */
    val isHidden: Boolean get() = mode?.entering == false

    /**
     * Plays a turnstile and suspends until every item has finished.
     *
     * @param anchorKey key of the item the user tapped (out modes only). It leaves last, after
     *                  the rest of the page has feathered away, exactly like launching from Start.
     */
    suspend fun play(mode: TurnstileMode, anchorKey: Any? = null): Unit = mutex.mutate {
        this.anchorKey = if (mode.entering) null else anchorKey
        this.mode = mode
        runId++
        clock.snapTo(0f)
        val total = totalMillis(mode, this.anchorKey != null)
        try {
            clock.animateTo(total.toFloat(), tween(durationMillis = total, easing = LinearEasing))
            Unit
        } finally {
            // Enter modes come to rest; exit modes stay hidden until the next run.
            if (mode.entering) {
                this.mode = null
                this.anchorKey = null
            } else {
                clock.snapTo(total.toFloat())
            }
        }
    }

    /** Puts the page back to rest instantly (e.g. returning from an app with animations disabled). */
    suspend fun reset(): Unit = mutex.mutate {
        mode = null
        anchorKey = null
        clock.snapTo(0f)
    }

    internal fun delayFor(normalizedY: Float, isAnchor: Boolean): Float {
        val feather = normalizedY.coerceIn(0f, 1f) * MetroMotion.FEATHER_SPAN_MS
        return if (isAnchor) MetroMotion.FEATHER_SPAN_MS + MetroMotion.FEATHER_ANCHOR_GAP_MS.toFloat() else feather
    }

    private fun totalMillis(mode: TurnstileMode, hasAnchor: Boolean): Int =
        MetroMotion.FEATHER_SPAN_MS +
            (if (hasAnchor) MetroMotion.FEATHER_ANCHOR_GAP_MS else 0) +
            mode.durationMillis
}

@Composable
fun rememberTurnstileState(): TurnstileState = remember { TurnstileState() }

/**
 * Marks a composable as one feathered element of a turnstile page.
 *
 * @param key identifies this item when it is the tapped anchor (see [TurnstileState.play]).
 */
fun Modifier.turnstileItem(
    state: TurnstileState,
    key: Any? = null,
    cameraDistance: Float = MetroMotion.TURNSTILE_CAMERA_DISTANCE,
): Modifier = this then TurnstileItemElement(state, key, cameraDistance)

private data class TurnstileItemElement(
    val state: TurnstileState,
    val key: Any?,
    val cameraDistance: Float,
) : ModifierNodeElement<TurnstileItemNode>() {
    override fun create() = TurnstileItemNode(state, key, cameraDistance)

    override fun update(node: TurnstileItemNode) {
        node.state = state
        node.key = key
        node.cameraDistanceFactor = cameraDistance
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "turnstileItem"
        properties["key"] = key
    }
}

private class TurnstileItemNode(
    var state: TurnstileState,
    var key: Any?,
    var cameraDistanceFactor: Float,
) : Modifier.Node(), LayoutModifierNode, LayoutAwareModifierNode {

    private var coordinates: LayoutCoordinates? = null

    // Geometry is sampled once per run (positions are stable while the page swivels).
    private var cachedRun = -1
    private var normalizedY = 0f
    private var pivotFractionX = 0f

    override fun onPlaced(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
    }

    override fun onDetach() {
        coordinates = null
    }

    private val layerBlock: GraphicsLayerScope.() -> Unit = {
        val mode = state.mode
        if (mode != null) {
            val run = state.runId
            if (run != cachedRun) {
                sampleGeometry()
                cachedRun = run
            }
            val isAnchor = key != null && key == state.anchorKey
            val delay = state.delayFor(normalizedY, isAnchor)
            val elapsed = state.elapsedMillis
            val t = ((elapsed - delay) / mode.durationMillis).coerceIn(0f, 1f)
            val eased = mode.easing.transform(t)

            rotationY = lerp(mode.fromDegrees, mode.toDegrees, eased)
            alpha = when {
                mode.entering && elapsed < delay -> 0f // not started yet: still "behind" the hinge
                !mode.entering && t >= 1f -> 0f // swung fully out
                else -> 1f
            }
            transformOrigin = TransformOrigin(pivotFractionX, 0.5f)
            cameraDistance = cameraDistanceFactor * density
        }
    }

    private fun sampleGeometry() {
        val coords = coordinates?.takeIf { it.isAttached } ?: return
        val root = coords.findRootCoordinates()
        val rootHeight = root.size.height.toFloat()
        val position = coords.positionInRoot()
        val width = coords.size.width.toFloat()
        val height = coords.size.height.toFloat()
        // Feather by the item's vertical centre so tall tiles start with their row.
        normalizedY = if (rootHeight > 0f) ((position.y + height / 2f) / rootHeight) else 0f
        // Hinge on the screen's left edge, expressed relative to this item's own bounds.
        pivotFractionX = if (width > 0f) -position.x / width else 0f
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0, layerBlock = layerBlock)
        }
    }
}
