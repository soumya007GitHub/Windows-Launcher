package dev.metro.launcher.lock

import android.text.format.DateFormat
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.metro.launcher.apps.AppRepository
import dev.metro.launcher.data.LauncherStore
import dev.metro.launcher.notifications.NotificationRepository
import dev.metro.launcher.physics.MetroMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Vertical position of the curtain in px: 0 = closed, −height = fully lifted. */
@Stable
class CurtainState {
    var offset: Float by mutableFloatStateOf(0f)
        internal set

    internal var job: Job? = null

    /** Drops the curtain back to closed instantly (shown again, or PIN prompt cancelled). */
    fun reset() {
        job?.cancel()
        offset = 0f
    }
}

/**
 * WP8 curtain. The whole sheet (photo + clock) follows the finger 1:1. On release:
 *  - fast upward flick, or slow release past 45 % → spring carries it off the top → [onUnlocked];
 *  - otherwise it is released with the finger's velocity under gravity and bounces off the bottom
 *    edge with restitution 0.3 until the impact speed is below the rest threshold;
 *  - a tap tosses it up a little (the WP hint) and it falls back the same way;
 *  - grabbing it mid-fall catches it.
 */
@Composable
fun CurtainLockscreen(
    state: CurtainState,
    showBackdrop: Boolean,
    onUnlocked: () -> Unit,
) {
    val density = LocalDensity.current.density
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val unlocked by rememberUpdatedState(onUnlocked)
    var heightPx by remember { mutableFloatStateOf(1f) }

    fun dp(value: Float) = value * density

    /** Free fall from the current offset with initial velocity (px/s, + = down), bouncing on the floor. */
    suspend fun fall(initialVelocity: Float) {
        val g = dp(MetroMotion.CURTAIN_GRAVITY_DP)
        val restSpeed = dp(MetroMotion.CURTAIN_REST_VELOCITY_DP)
        var v = initialVelocity
        var y = state.offset
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 1f / 30f)
            last = now
            v += g * dt
            y += v * dt
            if (y >= 0f) {
                y = 0f
                if (v < restSpeed) {
                    state.offset = 0f
                    return
                }
                v = -v * MetroMotion.CURTAIN_RESTITUTION
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            state.offset = y.coerceAtLeast(-heightPx)
        }
    }

    suspend fun open(initialVelocity: Float) {
        val target = -heightPx
        animate(
            initialValue = state.offset,
            targetValue = target,
            initialVelocity = initialVelocity,
            animationSpec = spring(dampingRatio = 1f, stiffness = MetroMotion.CURTAIN_OPEN_STIFFNESS),
        ) { value, _ -> state.offset = value.coerceAtLeast(target) }
        state.offset = target
        unlocked()
    }

    fun release(velocity: Float?) {
        state.job = scope.launch {
            val fling = dp(MetroMotion.CURTAIN_FLING_DP)
            val lifted = -state.offset / heightPx
            when {
                velocity == null ->
                    if (state.offset > -1f) fall(-dp(MetroMotion.CURTAIN_TAP_HOP_DP)) else fall(0f)
                velocity < -fling -> open(velocity)
                lifted > MetroMotion.CURTAIN_LIFT_FRACTION && velocity < fling * 0.5f -> open(minOf(velocity, 0f))
                else -> fall(velocity) // a slow upward release keeps rising briefly, then falls
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { heightPx = it.height.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                val tracker = VelocityTracker()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    state.job?.cancel() // catch it mid-fall
                    tracker.resetTracking()
                    tracker.addPointerInputChange(down)
                    var moved = 0f
                    var dragging = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        tracker.addPointerInputChange(change)
                        val dy = change.positionChange().y
                        moved += dy
                        if (!dragging && abs(moved) > viewConfiguration.touchSlop) {
                            dragging = true
                            state.offset = (state.offset + moved - dy).coerceIn(-heightPx, 0f)
                        }
                        if (dragging) {
                            state.offset = (state.offset + dy).coerceIn(-heightPx, 0f)
                            change.consume()
                        }
                    }
                    release(if (dragging) tracker.calculateVelocity().y else null)
                }
            },
    ) {
        // When a keyguard is underneath, never reveal it while lifting.
        if (showBackdrop) Box(Modifier.fillMaxSize().background(Color.Black))
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = state.offset },
        ) {
            LockFace()
        }
    }
}

private val ClockShadow = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 2f), 10f)

@Composable
private fun LockFace() {
    val context = LocalContext.current
    val store = remember { LauncherStore.get(context) }
    val repo = remember { AppRepository.get(context) }
    val settings by store.settings.collectAsStateWithLifecycle()
    val wallpaperVersion by store.wallpaperVersion.collectAsStateWithLifecycle()
    val lockWallpaperVersion by store.lockWallpaperVersion.collectAsStateWithLifecycle()
    // Its own lock-screen photo if one is set, otherwise the home-screen photo.
    val photo by produceState<ImageBitmap?>(null, wallpaperVersion, lockWallpaperVersion) { value = store.loadLockWallpaper() }
    val previews by NotificationRepository.previews.collectAsStateWithLifecycle()
    val apps by repo.apps.collectAsStateWithLifecycle()
    val iconsByPackage = remember(apps) { apps.orEmpty().associate { it.packageName to it.icon } }

    // Ticks on the minute boundary.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000L - value % 60_000L + 50L)
        }
    }
    val is24h = DateFormat.is24HourFormat(context)
    val locale = Locale.getDefault()
    val time = remember(now, is24h) { SimpleDateFormat(if (is24h) "H:mm" else "h:mm", locale).format(Date(now)) }
    val day = remember(now) { SimpleDateFormat("EEEE", locale).format(Date(now)) }
    val date = remember(now) { SimpleDateFormat("MMMM d", locale).format(Date(now)) }

    Box(Modifier.fillMaxSize()) {
        val image = photo
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            val accent = settings.accent.color
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.55f), Color.Black))),
            )
        }

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 24.dp, end = 24.dp, bottom = 56.dp),
        ) {
            BasicText(
                time,
                style = TextStyle(color = Color.White, fontSize = 104.sp, fontWeight = FontWeight.Light, letterSpacing = (-3).sp, shadow = ClockShadow),
            )
            BasicText(day, style = TextStyle(color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Light, shadow = ClockShadow))
            BasicText(date, style = TextStyle(color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Light, shadow = ClockShadow))
            Spacer(Modifier.height(20.dp))
            // Counts only, never notification text: the phone may be locked.
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                previews.values
                    .sortedByDescending { it.postedAt }
                    .take(5)
                    .forEach { preview ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val icon = iconsByPackage[preview.packageName]
                            if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(6.dp))
                            BasicText(
                                preview.count.toString(),
                                style = TextStyle(color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Light, shadow = ClockShadow),
                            )
                        }
                    }
            }
        }
    }
}
