package dev.metro.launcher.physics

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.dynamicanimation.animation.DynamicAnimation
import kotlin.math.abs
import kotlin.math.sign

/**
 * Windows Phone touch tilt.
 *
 * Port of the WP toolkit `TiltEffect` maths:
 * ```
 * n          = touch / size                       (clamped 0..1)
 * mag        = |n.x − ½| + |n.y − ½|              (0 at centre, 1 at a corner)
 * xShare     = |n.x − ½| / mag
 * angle      = mag · MaxAngle
 * rotationY  = angle ·  xShare      · dirX
 * rotationX  = angle · (1 − xShare) · dirY
 * depression = (1 − mag) · MaxDepression          (pressing the centre pushes straight in)
 * ```
 * The pressed edge always goes *away* from the viewer. Rotation tracks the finger while it moves,
 * releases with an under-damped spring, and cancels if a parent scroll/drag consumes the gesture
 * or the finger leaves the tile.
 *
 * It only observes pointer events (never consumes), so it composes with `clickable`,
 * `combinedClickable`, drag-to-reorder and scroll containers. Put it *before* clickable in the chain.
 */
fun Modifier.metroTilt(
    enabled: Boolean = true,
    maxAngleDegrees: Float = MetroMotion.TILT_MAX_ANGLE_DEG,
    maxDepression: Float = MetroMotion.TILT_MAX_DEPRESSION,
    cameraDistance: Float = MetroMotion.TILE_CAMERA_DISTANCE,
): Modifier = this then TiltElement(enabled, maxAngleDegrees, maxDepression, cameraDistance)

private data class TiltElement(
    val enabled: Boolean,
    val maxAngleDegrees: Float,
    val maxDepression: Float,
    val cameraDistance: Float,
) : ModifierNodeElement<TiltNode>() {

    override fun create() = TiltNode(enabled, maxAngleDegrees, maxDepression, cameraDistance)

    override fun update(node: TiltNode) {
        node.update(enabled, maxAngleDegrees, maxDepression, cameraDistance)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "metroTilt"
        properties["enabled"] = enabled
        properties["maxAngleDegrees"] = maxAngleDegrees
        properties["maxDepression"] = maxDepression
        properties["cameraDistance"] = cameraDistance
    }
}

private class TiltNode(
    private var enabled: Boolean,
    private var maxAngleDegrees: Float,
    private var maxDepression: Float,
    private var cameraDistanceFactor: Float,
) : DelegatingNode(), LayoutModifierNode, PointerInputModifierNode {

    private val rotationX = SpringValue(0f)
    private val rotationY = SpringValue(0f)
    private val depression = SpringValue(0f, DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE)

    private val pointerInput = delegate(SuspendingPointerInputModifierNode { trackTilt() })

    // Reads spring state => only this layer is invalidated per spring frame.
    private val layerBlock: GraphicsLayerScope.() -> Unit = {
        this.rotationX = this@TiltNode.rotationX.value
        this.rotationY = this@TiltNode.rotationY.value
        val scale = 1f - depression.value
        scaleX = scale
        scaleY = scale
        cameraDistance = cameraDistanceFactor * density
    }

    fun update(enabled: Boolean, maxAngleDegrees: Float, maxDepression: Float, cameraDistance: Float) {
        val wasEnabled = this.enabled
        this.enabled = enabled
        this.maxAngleDegrees = maxAngleDegrees
        this.maxDepression = maxDepression
        this.cameraDistanceFactor = cameraDistance
        if (wasEnabled && !enabled) {
            release()
            pointerInput.resetPointerInputHandler()
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0, layerBlock = layerBlock)
        }
    }

    // PointerInputModifierNode is implemented by the delegate; forward explicitly so the
    // node kind set is unambiguous.
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        pointerInput.onPointerEvent(pointerEvent, pass, bounds)
    }

    override fun onCancelPointerInput() {
        pointerInput.onCancelPointerInput()
        release()
    }

    override fun onDetach() {
        rotationX.snapTo(0f)
        rotationY.snapTo(0f)
        depression.snapTo(0f)
    }

    private suspend fun PointerInputScope.trackTilt() {
        awaitEachGesture {
            // Initial pass: react before children/parents can consume the down.
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!enabled) return@awaitEachGesture
            val pointerId = down.id
            val slop = viewConfiguration.touchSlop
            try {
                press(down.position, size)
                while (true) {
                    // Final pass: we see whether a scroll container or drag handler claimed the gesture.
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change: PointerInputChange =
                        event.changes.firstOrNull { it.id == pointerId } ?: break
                    if (!change.pressed) break
                    val moved = change.positionChangeIgnoreConsumed() != Offset.Zero
                    if (moved && change.isConsumed) break // a parent started scrolling
                    if (!isInside(change.position, size, slop)) break
                    if (moved) press(change.position, size)
                }
            } finally {
                release()
            }
        }
    }

    private fun isInside(p: Offset, size: IntSize, slop: Float): Boolean =
        p.x >= -slop && p.y >= -slop && p.x <= size.width + slop && p.y <= size.height + slop

    private fun press(position: Offset, size: IntSize) {
        if (size.width <= 0 || size.height <= 0) return
        val nx = (position.x / size.width).coerceIn(0f, 1f)
        val ny = (position.y / size.height).coerceIn(0f, 1f)

        val xMagnitude = abs(nx - 0.5f)
        val yMagnitude = abs(ny - 0.5f)
        val magnitude = xMagnitude + yMagnitude // 0 … 1
        val xShare = if (magnitude > 0f) xMagnitude / magnitude else 0f

        // Toolkit directions, then mapped onto Android's rotation handedness.
        val dirY = -sign(nx - 0.5f) * MetroMotion.SILVERLIGHT_TO_ANDROID_Y
        val dirX = sign(ny - 0.5f) * MetroMotion.SILVERLIGHT_TO_ANDROID_X

        val angle = magnitude * maxAngleDegrees
        val targetRotY = angle * xShare * dirY
        val targetRotX = angle * (1f - xShare) * dirX
        val targetDepression = (1f - magnitude) * maxDepression

        val k = MetroMotion.TILT_TRACK_STIFFNESS
        val z = MetroMotion.TILT_TRACK_DAMPING
        rotationX.animateTo(targetRotX, k, z)
        rotationY.animateTo(targetRotY, k, z)
        depression.animateTo(targetDepression, k, z)
    }

    private fun release() {
        val k = MetroMotion.TILT_RELEASE_STIFFNESS
        val z = MetroMotion.TILT_RELEASE_DAMPING
        rotationX.animateTo(0f, k, z)
        rotationY.animateTo(0f, k, z)
        // Depression returns critically damped: overshooting past 1.0 scale looks like a bounce, not a tilt.
        depression.animateTo(0f, k, 1f)
    }
}
