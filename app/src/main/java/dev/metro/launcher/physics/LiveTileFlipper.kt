package dev.metro.launcher.physics

import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Metro tile footprint in grid cells (columns × rows). */
enum class TileSize(val columns: Int, val rows: Int) {
    Small(1, 1),
    Medium(2, 2),
    Wide(4, 2);

    /** WP8: small tiles only show a badge; medium and wide tiles are live. */
    val canFlip: Boolean get() = this != Small
}

enum class TileFace { Front, Back }

private val FlipOutEasing: Easing = ExponentialEaseIn(3f)
private val FlipInEasing: Easing = ExponentialEaseOut(5f)

/**
 * One tile's X-axis flip: `0° → 90°` (front leaves, ease-in), swap faces while edge-on,
 * `−90° → 0°` (back arrives, ease-out). Because the face is swapped at exactly 90° and the second
 * half starts from −90°, the incoming face is never mirrored and no back-face culling is needed.
 */
@Stable
class LiveTileFlipState internal constructor(initialFace: TileFace = TileFace.Front) {

    var face: TileFace by mutableStateOf(initialFace)
        private set

    private val rotation = Animatable(0f)
    private val mutex = MutatorMutex()

    /** Current rotationX in degrees; read it inside graphicsLayer. */
    val rotationX: Float get() = rotation.value

    val isFlipping: Boolean get() = rotation.isRunning

    internal var lastFlipAt = 0L
    internal var backDwellMillis = 0L

    suspend fun flipTo(target: TileFace) {
        if (target == face && rotation.value == 0f) return
        mutex.mutate {
            lastFlipAt = SystemClock.uptimeMillis()
            if (!ValueAnimator.areAnimatorsEnabled()) {
                face = target
                rotation.snapTo(0f)
                return@mutate
            }
            try {
                rotation.animateTo(90f, tween(MetroMotion.FLIP_OUT_MS, easing = FlipOutEasing))
                face = target
                rotation.snapTo(-90f)
                rotation.animateTo(0f, tween(MetroMotion.FLIP_IN_MS, easing = FlipInEasing))
            } finally {
                // Cancelled mid-flip (lifecycle stopped, tile scrolled away): land flat on whichever
                // face is currently showing. First half => still the old face; second half => new face.
                if (rotation.value != 0f) {
                    withContext(NonCancellable) { rotation.snapTo(0f) }
                }
            }
        }
    }

    suspend fun flip() = flipTo(if (face == TileFace.Front) TileFace.Back else TileFace.Front)

    suspend fun snapTo(target: TileFace) = mutex.mutate {
        face = target
        rotation.snapTo(0f)
    }
}

/**
 * Coroutine-driven scheduler that staggers flips across all visible live tiles.
 *
 * Rules (tuned to WP8):
 *  - only one flip *starts* per gap of [MetroMotion.ENGINE_MIN_GAP_MS]…[MetroMotion.ENGINE_MAX_GAP_MS];
 *  - a tile rests at least [MetroMotion.TILE_MIN_REST_MS] between flips;
 *  - a back face stays up for a random 4–7 s, then going home has priority;
 *  - a tile only shows its back face while it has back content (e.g. a notification);
 *  - nothing flips while [paused] (edit mode, turnstile, search) or below Lifecycle.RESUMED.
 *
 * Flip state is kept per tile key for the engine's lifetime, so a tile scrolled off and back keeps
 * its face. Call [forget] when a tile is unpinned.
 */
@Stable
class LiveTileEngine(private val random: Random = Random.Default) {

    var paused: Boolean by mutableStateOf(false)

    private val states = HashMap<Any, LiveTileFlipState>()
    private val visible = LinkedHashMap<Any, () -> Boolean>()

    fun stateFor(key: Any): LiveTileFlipState = states.getOrPut(key) { LiveTileFlipState() }

    fun forget(key: Any) {
        states.remove(key)
        visible.remove(key)
    }

    internal fun register(key: Any, hasBack: () -> Boolean) {
        visible[key] = hasBack
    }

    internal fun unregister(key: Any) {
        visible.remove(key)
    }

    /** Runs until cancelled. Use [LiveTileEngineEffect] to bind it to the lifecycle. */
    suspend fun run(): Unit = coroutineScope {
        while (isActive) {
            delay(random.nextLong(MetroMotion.ENGINE_MIN_GAP_MS, MetroMotion.ENGINE_MAX_GAP_MS))
            if (paused) continue
            val (state, target) = pickNext() ?: continue
            launch { state.flipTo(target) }
        }
    }

    private fun pickNext(): Pair<LiveTileFlipState, TileFace>? {
        val now = SystemClock.uptimeMillis()
        var homeward: Pair<LiveTileFlipState, TileFace>? = null
        val outward = ArrayList<LiveTileFlipState>()

        for ((key, hasBack) in visible) {
            val state = states[key] ?: continue
            if (state.isFlipping) continue
            val restedFor = now - state.lastFlipAt
            when (state.face) {
                TileFace.Back -> {
                    // Content vanished, or dwell elapsed: go home first.
                    if (!hasBack() || restedFor >= state.backDwellMillis) {
                        if (homeward == null) homeward = state to TileFace.Front
                    }
                }
                TileFace.Front -> {
                    if (hasBack() && restedFor >= MetroMotion.TILE_MIN_REST_MS) outward += state
                }
            }
        }
        homeward?.let { return it }
        if (outward.isEmpty()) return null
        val chosen = outward[random.nextInt(outward.size)]
        chosen.backDwellMillis = random.nextLong(MetroMotion.BACK_DWELL_MIN_MS, MetroMotion.BACK_DWELL_MAX_MS)
        return chosen to TileFace.Back
    }
}

@Composable
fun rememberLiveTileEngine(): LiveTileEngine = remember { LiveTileEngine() }

/** Runs [engine] only while the host lifecycle is RESUMED (screen on, launcher in front). */
@Composable
fun LiveTileEngineEffect(engine: LiveTileEngine) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(engine, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            engine.run()
        }
    }
}

/**
 * A live tile surface. Draws [front] or [back] for the current face and applies the flip rotation
 * on its own GPU layer (pivot = tile centre, matching WP8).
 *
 * Small tiles and tiles without back content never register with the engine.
 */
@Composable
fun LiveTile(
    key: Any,
    engine: LiveTileEngine,
    size: TileSize,
    hasBack: Boolean,
    modifier: Modifier = Modifier,
    front: @Composable BoxScope.() -> Unit,
    back: @Composable BoxScope.() -> Unit,
) {
    val state = remember(engine, key) { engine.stateFor(key) }
    val live = size.canFlip
    val currentHasBack by rememberUpdatedState(hasBack)

    DisposableEffect(engine, key, live) {
        if (live) engine.register(key) { currentHasBack }
        onDispose { engine.unregister(key) }
    }

    Box(
        modifier.graphicsLayer {
            rotationX = state.rotationX
            cameraDistance = MetroMotion.TILE_CAMERA_DISTANCE * density
        },
    ) {
        if (live && hasBack && state.face == TileFace.Back) back() else front()
    }
}
