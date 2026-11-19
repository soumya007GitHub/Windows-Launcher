@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.metro.launcher.start

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import dev.metro.launcher.apps.AppEntry
import dev.metro.launcher.notifications.NotificationPreview
import dev.metro.launcher.physics.LiveTile
import dev.metro.launcher.physics.LiveTileEngine
import dev.metro.launcher.physics.TileSize
import dev.metro.launcher.physics.TurnstileState
import dev.metro.launcher.physics.metroTilt
import dev.metro.launcher.physics.turnstileItem
import dev.metro.launcher.ui.Glyph
import dev.metro.launcher.ui.MetroIcon
import dev.metro.launcher.ui.LocalMetroColors
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Start edit mode (WP8 "rearrange"): entered by long-pressing a tile. */
@Stable
class StartEditState {
    var editMode: Boolean by mutableStateOf(false)
        private set
    var selectedKey: String? by mutableStateOf(null)

    fun enter(key: String) {
        editMode = true
        selectedKey = key
    }

    fun exit() {
        editMode = false
        selectedKey = null
    }
}

private class DragSession(val key: String, start: Offset, var fingerRootY: Float) {
    /** Top-left of the lifted tile in grid coordinates. */
    var topLeft: Offset by mutableStateOf(start)
    var lastSwapKey: String? = null
}

/** WP8 resize cycle: medium → small → wide → medium. */
private fun TileSize.next(): TileSize = when (this) {
    TileSize.Medium -> TileSize.Small
    TileSize.Small -> TileSize.Wide
    TileSize.Wide -> TileSize.Medium
}

@Composable
fun StartScreen(
    tiles: List<StartTileModel>,
    columns: Int,
    accent: Color,
    cutout: CutoutWallpaper?,
    previews: Map<String, NotificationPreview>,
    engine: LiveTileEngine,
    turnstile: TurnstileState,
    scrollState: ScrollState,
    edit: StartEditState,
    onCommit: (List<StartTileModel>) -> Unit,
    onUnpin: (StartTileModel) -> Unit,
    onChangeIcon: (StartTileModel) -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onOpenAllApps: () -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable ColumnScope.() -> Unit = {},
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val currentTiles by rememberUpdatedState(tiles)
    val commit by rememberUpdatedState(onCommit)
    val openAllApps by rememberUpdatedState(onOpenAllApps)
    val launch by rememberUpdatedState(onLaunch)

    // `working` is what is drawn. It follows `tiles`, except while a drag is reordering it.
    var working by remember { mutableStateOf(tiles) }
    var lastTiles by remember { mutableStateOf(tiles) }
    var drag by remember { mutableStateOf<DragSession?>(null) }
    if (tiles !== lastTiles) {
        lastTiles = tiles
        if (drag == null) working = tiles
    }

    var dragSlotTopLeft by remember { mutableStateOf(Offset.Zero) }
    val slots = remember { HashMap<String, Rect>() }
    val tileCoords = remember { HashMap<String, LayoutCoordinates>() }
    val viewport = remember { floatArrayOf(0f, 0f) } // top, bottom (root px)
    val lastDragEnd = remember { longArrayOf(0L) }
    val settle = remember { mutableStateMapOf<String, Offset>() }

    fun commitList(list: List<StartTileModel>) {
        working = list
        commit(list)
    }

    fun reorderUnderFinger(s: DragSession) {
        val own = slots[s.key] ?: return
        val center = s.topLeft + Offset(own.width / 2f, own.height / 2f)
        val target = working.firstOrNull { it.key != s.key && slots[it.key]?.contains(center) == true }?.key
        if (target == null) {
            if (own.contains(center)) s.lastSwapKey = null
            return
        }
        if (target == s.lastSwapKey) return
        val from = working.indexOfFirst { it.key == s.key }
        val to = working.indexOfFirst { it.key == target }
        if (from < 0 || to < 0) return
        working = working.toMutableList().apply { add(to, removeAt(from)) }
        s.lastSwapKey = target
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun startDrag(key: String, local: Offset) {
        val rect = slots[key] ?: return
        working = currentTiles
        dragSlotTopLeft = rect.topLeft
        val rootY = tileCoords[key]?.takeIf { it.isAttached }?.localToRoot(local)?.y ?: 0f
        drag = DragSession(key, rect.topLeft, rootY)
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    fun dragBy(delta: Offset) {
        val s = drag ?: return
        s.topLeft += delta
        s.fingerRootY += delta.y
        reorderUnderFinger(s)
    }

    fun endDrag() {
        val s = drag ?: return
        settle[s.key] = s.topLeft - dragSlotTopLeft
        drag = null
        lastDragEnd[0] = SystemClock.uptimeMillis()
        if (working.map { it.key } != currentTiles.map { it.key }) commitList(working)
    }

    // Auto-scroll while a lifted tile is held near the top/bottom edge.
    val edgePx = with(density) { 72.dp.toPx() }
    val maxStepPx = with(density) { 14.dp.toPx() }
    LaunchedEffect(drag) {
        val s = drag ?: return@LaunchedEffect
        while (isActive) {
            withFrameNanos { }
            val top = viewport[0]
            val bottom = viewport[1]
            val y = s.fingerRootY
            val step = when {
                y < top + edgePx -> -maxStepPx * ((top + edgePx - y) / edgePx).coerceIn(0f, 1f)
                y > bottom - edgePx -> maxStepPx * ((y - (bottom - edgePx)) / edgePx).coerceIn(0f, 1f)
                else -> 0f
            }
            if (step != 0f) {
                val consumed = scrollState.scrollBy(step)
                if (consumed != 0f) {
                    s.topLeft += Offset(0f, consumed)
                    reorderUnderFinger(s)
                }
            }
        }
    }

    BackHandler(enabled = edit.editMode) { edit.exit() }

    val floatPhase: State<Float>? = if (edit.editMode) {
        rememberInfiniteTransition(label = "edit-float").animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(5200, easing = LinearEasing)),
            label = "phase",
        )
    } else {
        null
    }

    val swipePx = with(density) { 64.dp.toPx() }
    val noRipple = remember { MutableInteractionSource() }

    // Sideways panning to the app list is handled by the shared Start / app-list pager.
    Box(
        modifier
            .fillMaxSize()
            .background(LocalMetroColors.current.background),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                // Outside the scroll: tiles clip below Android's status bar instead of sliding under it.
                .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                .onGloballyPositioned {
                    val b = it.boundsInRoot()
                    viewport[0] = b.top
                    viewport[1] = b.bottom
                }
                .pointerInput(edit.editMode) {
                    if (edit.editMode) detectTapGestures { edit.exit() }
                }
                .verticalScroll(scrollState)
                .padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 24.dp),
        ) {
            header()

            MetroGrid(
                items = working,
                keyOf = { it.key },
                sizeOf = { it.size },
                columns = columns,
                gap = if (columns == 6) 4.dp else 6.dp,
                modifier = Modifier.fillMaxWidth(),
            ) { tile ->
                val key = tile.key
                val session = drag
                val isDragging = session?.key == key
                val gestures = Modifier.pointerInput(key) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val immediate = edit.editMode && edit.selectedKey == key
                        val start: PointerInputChange = (if (immediate) {
                            awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        } else {
                            awaitLongPressOrCancellation(down.id)
                        }) ?: return@awaitEachGesture
                        if (!edit.editMode) edit.enter(key) else edit.selectedKey = key
                        startDrag(key, start.position)
                        try {
                            drag(start.id) { change ->
                                dragBy(change.positionChange())
                                change.consume()
                            }
                        } finally {
                            endDrag()
                        }
                    }
                }
                StartTileSlot(
                    tile = tile,
                    accent = accent,
                    cutout = cutout,
                    preview = previews[tile.app.packageName],
                    engine = engine,
                    turnstile = turnstile,
                    editMode = edit.editMode,
                    selected = edit.selectedKey == key,
                    dragging = isDragging,
                    dragTranslation = { (drag?.topLeft ?: dragSlotTopLeft) - dragSlotTopLeft },
                    settleFrom = settle[key],
                    onSettled = { settle.remove(key) },
                    floatPhase = floatPhase,
                    onPlacedSlot = { coords ->
                        val pos = coords.positionInParent()
                        slots[key] = Rect(pos, coords.size.toSize())
                        tileCoords[key] = coords
                        if (drag?.key == key) dragSlotTopLeft = pos
                    },
                    gestureModifier = gestures,
                    onClick = {
                        if (SystemClock.uptimeMillis() - lastDragEnd[0] > 350) {
                            if (!edit.editMode) launch(tile.app) else edit.selectedKey = key
                        }
                    },
                    onUnpin = {
                        edit.selectedKey = null
                        working = working.filterNot { it.key == key }
                        onUnpin(tile)
                    },
                    onResize = {
                        commitList(working.map { if (it.key == key) it.copy(size = it.size.next()) else it })
                    },
                    onChangeIcon = { onChangeIcon(tile) },
                )
            }

            Spacer(Modifier.height(20.dp))

            // WP8 GDR "all apps" arrow.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.End)
                    .turnstileItem(turnstile)
                    .metroTilt()
                    .size(48.dp)
                    .border(2.dp, LocalMetroColors.current.foreground, CircleShape)
                    .clickable(noRipple, indication = null) { if (!edit.editMode) openAllApps() },
            ) {
                MetroIcon(Glyph.Back, Modifier.size(22.dp), rotationDegrees = 180f)
            }
        }
    }
}

@Composable
private fun StartTileSlot(
    tile: StartTileModel,
    accent: Color,
    cutout: CutoutWallpaper?,
    preview: NotificationPreview?,
    engine: LiveTileEngine,
    turnstile: TurnstileState,
    editMode: Boolean,
    selected: Boolean,
    dragging: Boolean,
    dragTranslation: () -> Offset,
    settleFrom: Offset?,
    onSettled: () -> Unit,
    floatPhase: State<Float>?,
    onPlacedSlot: (LayoutCoordinates) -> Unit,
    gestureModifier: Modifier,
    onClick: () -> Unit,
    onUnpin: () -> Unit,
    onResize: () -> Unit,
    onChangeIcon: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = when {
            !editMode -> 1f
            dragging -> 1.06f
            selected -> 1f
            else -> 0.86f
        },
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
        label = "edit-scale",
    )
    val settleAnim = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    LaunchedEffect(settleFrom) {
        if (settleFrom != null) {
            settleAnim.snapTo(settleFrom)
            settleAnim.animateTo(Offset.Zero, spring(dampingRatio = 0.8f, stiffness = 500f))
            onSettled()
        }
    }
    val floatAmp = with(LocalDensity.current) { 2.5.dp.toPx() }
    val seed = remember(tile.key) { (tile.key.hashCode() and 0x3FF) / 1024f * (2 * PI).toFloat() }
    val noRipple = remember { MutableInteractionSource() }
    val buttonSize: Dp = if (tile.size == TileSize.Small) 22.dp else 28.dp

    Box(
        Modifier
            .onPlaced(onPlacedSlot)
            .animatePlacement(enabled = !dragging)
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                val t = if (dragging) dragTranslation() else settleAnim.value
                var tx = t.x
                var ty = t.y
                val phase = floatPhase?.value
                if (phase != null && !selected && !dragging) {
                    tx += sin(phase + seed) * floatAmp
                    ty += cos(phase * 2f + seed) * floatAmp
                }
                translationX = tx
                translationY = ty
                scaleX = scale
                scaleY = scale
            }
            .turnstileItem(turnstile, key = tile.key)
            .metroTilt(enabled = !editMode)
            .then(gestureModifier)
            .clickable(noRipple, indication = null, onClick = onClick),
    ) {
        LiveTile(
            key = tile.key,
            engine = engine,
            size = tile.size,
            hasBack = preview != null,
            modifier = Modifier.fillMaxSize(),
            front = { TileFront(tile, preview, accent, cutout) },
            back = {
                if (preview != null) TileBack(tile, preview, accent, cutout) else TileFront(tile, null, accent, cutout)
            },
        )
        if (editMode && !selected) {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.4f)))
        }
        if (selected && !dragging) {
            CornerButton(Glyph.Unpin, buttonSize, Modifier.align(Alignment.TopEnd), onClick = onUnpin)
            val grows = tile.size.next().let { it.columns * it.rows > tile.size.columns * tile.size.rows }
            CornerButton(
                Glyph.Resize,
                buttonSize,
                Modifier.align(Alignment.BottomEnd),
                rotation = if (grows) 180f else 0f,
                onClick = onResize,
            )
            CornerButton(Glyph.Pencil, buttonSize, Modifier.align(Alignment.BottomStart), onClick = onChangeIcon)
        }
    }
}

@Composable
private fun CornerButton(
    glyph: Glyph,
    size: Dp,
    modifier: Modifier,
    rotation: Float = 0f,
    onClick: () -> Unit,
) {
    val noRipple = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .padding(3.dp)
            .size(size)
            .background(LocalMetroColors.current.foreground, CircleShape)
            .clickable(noRipple, indication = null, onClick = onClick),
    ) {
        MetroIcon(glyph, Modifier.size(size * 0.6f), color = LocalMetroColors.current.background, rotationDegrees = rotation)
    }
}
