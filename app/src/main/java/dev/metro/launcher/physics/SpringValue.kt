package dev.metro.launcher.physics

import android.animation.ValueAnimator
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * Bridges an androidx.dynamicanimation [SpringAnimation] into Compose snapshot state.
 *
 * Reading [value] inside a `graphicsLayer` block subscribes only the layer, so every spring
 * frame re-renders the RenderNode without recomposition or relayout.
 *
 * Threading: SpringAnimation drives itself from the Choreographer of the thread that started it,
 * so every call must happen on the main thread (pointer input and Compose effects already do).
 */
@Stable
class SpringValue(
    initialValue: Float,
    minimumVisibleChange: Float = DynamicAnimation.MIN_VISIBLE_CHANGE_ROTATION_DEGREES,
) {
    private val state = mutableFloatStateOf(initialValue)

    /** Current animated value; snapshot-observable. */
    val value: Float get() = state.floatValue

    private val holder = FloatValueHolder(initialValue)
    private val force = SpringForce(initialValue)
    private val animation = SpringAnimation(holder).apply {
        setSpring(force)
        setMinimumVisibleChange(minimumVisibleChange)
        addUpdateListener { _, v, _ -> state.floatValue = v }
        addEndListener { _, _, v, _ -> state.floatValue = v }
    }

    val isRunning: Boolean get() = animation.isRunning

    /**
     * Retargets the spring. If it is already moving, momentum is preserved (dynamicanimation
     * carries velocity across a final-position change), which is what makes tilt feel physical.
     *
     * @param startVelocity only applied when the spring is at rest, e.g. a fling handoff.
     */
    fun animateTo(
        target: Float,
        stiffness: Float,
        dampingRatio: Float,
        startVelocity: Float? = null,
    ) {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            snapTo(target)
            return
        }
        force.setStiffness(stiffness)
        force.setDampingRatio(dampingRatio)
        if (startVelocity != null && !animation.isRunning) {
            holder.value = state.floatValue
            animation.setStartVelocity(startVelocity)
        }
        animation.animateToFinalPosition(target)
    }

    fun snapTo(target: Float) {
        if (animation.isRunning) animation.cancel()
        holder.value = target
        force.setFinalPosition(target)
        state.floatValue = target
    }

    /** Stops wherever it is. */
    fun cancel() {
        if (animation.isRunning) animation.cancel()
    }
}
