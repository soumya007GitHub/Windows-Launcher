package dev.metro.launcher.physics

import androidx.compose.animation.core.Easing
import kotlin.math.exp

/**
 * Every tuning constant of the Metro motion system in one place.
 *
 * Values marked "WP toolkit" come from the Silverlight for Windows Phone Toolkit
 * (TiltEffect / TurnstileTransition / TurnstileFeatherEffect), which is the reference
 * implementation of WP7/WP8 motion. Everything else is tuned to match on-device feel.
 */
object MetroMotion {

    /**
     * Silverlight's PlaneProjection rotates the opposite way to Android's RenderNode for the
     * same sign of RotationY. All toolkit angles below are multiplied by this so they read the
     * same as the original XAML. If an edge moves the wrong way on your device, flip this.
     */
    const val SILVERLIGHT_TO_ANDROID_Y = -1f

    /** Same as [SILVERLIGHT_TO_ANDROID_Y] for RotationX (pressing a tile's top edge must push the top edge away). */
    const val SILVERLIGHT_TO_ANDROID_X = -1f

    // ---------------------------------------------------------------- Tilt (WP toolkit TiltEffect)

    /** TiltEffect.MaxAngle = 0.3 rad. */
    val TILT_MAX_ANGLE_DEG: Float = Math.toDegrees(0.3).toFloat()

    /**
     * TiltEffect.MaxDepression = 25 px of GlobalOffsetZ on a 480 px-wide WP screen.
     * Through PlaneProjection's ~1000 px focal length that is ~2.4 % shrink at dead centre.
     * Compose has no Z translation, so we express it as scale.
     */
    const val TILT_MAX_DEPRESSION = 0.024f

    /** Camera distance for per-tile tilt and flip, multiplied by density. */
    const val TILE_CAMERA_DISTANCE = 12f

    /** While the finger is down the tile tracks it almost rigidly (WP applied tilt instantly). */
    const val TILT_TRACK_STIFFNESS = 4000f
    const val TILT_TRACK_DAMPING = 1f

    /** Release: soft, slightly under-damped return (WP used a 100 ms ease; the spring feels identical but is interruptible). */
    const val TILT_RELEASE_STIFFNESS = 520f
    const val TILT_RELEASE_DAMPING = 0.62f

    // ---------------------------------------------------------------- Turnstile (WP toolkit TurnstileTransition)

    /** ExponentialEase Exponent=6 used by every toolkit turnstile storyboard. */
    const val TURNSTILE_EASE_EXPONENT = 6f
    const val TURNSTILE_IN_MS = 350
    const val TURNSTILE_OUT_MS = 250

    /** Feather stagger across the full screen height (top row starts first). */
    const val FEATHER_SPAN_MS = 280

    /** The tapped tile leaves this long after the last feathered row. */
    const val FEATHER_ANCHOR_GAP_MS = 70

    /** Whole-page swivel needs stronger perspective than a single tile. Multiplied by density. */
    const val TURNSTILE_CAMERA_DISTANCE = 8f

    // ---------------------------------------------------------------- Live tiles

    const val FLIP_OUT_MS = 240
    const val FLIP_IN_MS = 380

    /** Gap between two consecutive flips anywhere on the Start screen. */
    const val ENGINE_MIN_GAP_MS = 1_200L
    const val ENGINE_MAX_GAP_MS = 3_600L

    /** A single tile never flips again sooner than this. */
    const val TILE_MIN_REST_MS = 6_000L

    /** How long a back face stays up before it becomes a priority to flip home. */
    const val BACK_DWELL_MIN_MS = 4_000L
    const val BACK_DWELL_MAX_MS = 7_000L

    // ---------------------------------------------------------------- Curtain lockscreen

    /**
     * The curtain is a rigid sheet under gravity with an inelastic floor, not a spring: a spring has
     * no floor, so it would overshoot *below* the screen. WP8's curtain falls ~400 dp in ~0.4 s,
     * so g = 2h / t² ≈ 5000 dp/s².
     */
    const val CURTAIN_GRAVITY_DP = 5200f

    /** Coefficient of restitution at the bottom edge: each bounce keeps 30 % of the impact speed. */
    const val CURTAIN_RESTITUTION = 0.3f

    /** Impacts slower than this settle instead of bouncing again (dp/s). */
    const val CURTAIN_REST_VELOCITY_DP = 140f

    /** An upward release faster than this always unlocks (dp/s). */
    const val CURTAIN_FLING_DP = 1100f

    /** A slow release unlocks only when the curtain is lifted past this fraction of the screen. */
    const val CURTAIN_LIFT_FRACTION = 0.45f

    /** Tapping the lockscreen tosses the curtain up with this speed, as a hint (dp/s). */
    const val CURTAIN_TAP_HOP_DP = 1250f

    /** Spring that carries a successful flick off the top of the screen. */
    const val CURTAIN_OPEN_STIFFNESS = 380f
}

/** Silverlight ExponentialEase, EasingMode=EaseIn: f(t) = (e^(k·t) − 1) / (e^k − 1). */
class ExponentialEaseIn(private val exponent: Float) : Easing {
    private val denominator = exp(exponent.toDouble()) - 1.0

    override fun transform(fraction: Float): Float = when {
        fraction <= 0f -> 0f
        fraction >= 1f -> 1f
        else -> ((exp(exponent * fraction.toDouble()) - 1.0) / denominator).toFloat()
    }
}

/** Silverlight ExponentialEase, EasingMode=EaseOut: 1 − easeIn(1 − t). */
class ExponentialEaseOut(exponent: Float) : Easing {
    private val easeIn = ExponentialEaseIn(exponent)
    override fun transform(fraction: Float): Float = 1f - easeIn.transform(1f - fraction)
}
