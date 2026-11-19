@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.metro.launcher.allapps

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.metro.launcher.apps.AppEntry
import dev.metro.launcher.physics.TurnstileMode
import dev.metro.launcher.physics.metroTilt
import dev.metro.launcher.physics.rememberTurnstileState
import dev.metro.launcher.physics.turnstileItem
import dev.metro.launcher.physics.TurnstileState
import dev.metro.launcher.ui.Glyph
import dev.metro.launcher.ui.LocalMetroColors
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onSizeChanged
import dev.metro.launcher.physics.ExponentialEaseOut
import dev.metro.launcher.start.AppSquare
import dev.metro.launcher.ui.MetroIcon
import dev.metro.launcher.ui.MetroType
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val JUMP_LETTERS = "#abcdefghijklmnopqrstuvwxyz"

private fun letterOf(label: String): Char {
    val c = label.trimStart().firstOrNull()?.lowercaseChar() ?: return '#'
    return if (c in 'a'..'z') c else '#'
}

private sealed interface ListRow {
    val key: String

    data object Settings : ListRow {
        override val key = "settings"
    }

    data class Header(val letter: Char) : ListRow {
        override val key = "h:$letter"
    }

    data class App(val app: AppEntry) : ListRow {
        override val key = "a:${app.key}"
    }
}

/**
 * WP8 app list: "start+theme" entry, then letter groups (#, aÃ¢â‚¬â€œz). Tap a letter tile for the jump grid,
 * long-press an app for pin / app info / uninstall, swipe right to return to Start.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AllAppsScreen(
    apps: List<AppEntry>,
    pinnedKeys: Set<String>,
    accent: Color,
    metroIcons: Boolean,
    turnstile: TurnstileState,
    listState: LazyListState,
    onLaunch: (AppEntry) -> Unit,
    onPin: (AppEntry) -> Unit,
    onAppInfo: (AppEntry) -> Unit,
    onUninstall: (AppEntry) -> Unit,
    onOpenSettings: () -> Unit,
    onSwipeBack: () -> Unit,
) {
    val rows = remember(apps) {
        buildList {
            add(ListRow.Settings)
            apps.groupBy { letterOf(it.label) }
                .toSortedMap()
                .forEach { (letter, group) ->
                    add(ListRow.Header(letter))
                    group.forEach { add(ListRow.App(it)) }
                }
        }
    }
    val headerIndex = remember(rows) {
        rows.withIndex().filter { it.value is ListRow.Header }.associate { (it.value as ListRow.Header).letter to it.index }
    }

    var menuFor by remember { mutableStateOf<AppEntry?>(null) }
    /** The app whose menu is drawn; outlives [menuFor] while the menu folds away. */
    var menuShown by remember { mutableStateOf<AppEntry?>(null) }
    val menuProgress = remember { Animatable(0f) }
    var menuY by remember { mutableFloatStateOf(0f) }
    var jumpOpen by remember { mutableStateOf(false) }
    val jumpTurnstile = rememberTurnstileState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val swipePx = with(density) { 64.dp.toPx() }
    val swipeBack by rememberUpdatedState(onSwipeBack)
    val noRipple = remember { MutableInteractionSource() }

    BackHandler(enabled = menuFor != null) { menuFor = null }
    BackHandler(enabled = jumpOpen) { jumpOpen = false }

    // Sideways panning back to Start is handled by the shared Start / app-list pager.
    Box(Modifier.fillMaxSize()) {
        // The list recedes (zooms back and dims) while a context menu is open, animated like WP.
        val recede by animateFloatAsState(
            targetValue = if (menuFor != null) 1f else 0f,
            animationSpec = tween(220, easing = ExponentialEaseOut(4f)),
            label = "recede",
        )
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)) {
        // Fixed header: WP-style round back button returns to Start.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                .turnstileItem(turnstile, key = "back")
                .metroTilt()
                .size(44.dp)
                .border(2.dp, LocalMetroColors.current.foreground, CircleShape)
                .clickable(noRipple, indication = null, onClick = onSwipeBack),
        ) {
            MetroIcon(Glyph.Back, Modifier.size(20.dp))
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .graphicsLayer {
                    val s = 1f - 0.06f * recede
                    scaleX = s
                    scaleY = s
                    alpha = 1f - 0.65f * recede
                }
                .padding(horizontal = 16.dp),
        ) {
            item(key = "top") { Spacer(Modifier.height(20.dp)) }
            items(rows, key = { it.key }) { row ->
                when (row) {
                    ListRow.Settings -> AppRow(
                        label = "start + theme",
                        accent = accent,
                        modifier = Modifier
                            .turnstileItem(turnstile, key = row.key)
                            .metroTilt()
                            .clickable(noRipple, indication = null, onClick = onOpenSettings),
                    ) {
                        MetroIcon(Glyph.Settings, Modifier.size(30.dp), color = Color.White)
                    }

                    is ListRow.Header -> Box(
                        Modifier
                            .padding(top = 10.dp, bottom = 6.dp)
                            .turnstileItem(turnstile, key = row.key)
                            .metroTilt()
                            .size(52.dp)
                            .border(2.dp, accent)
                            .clickable(noRipple, indication = null) {
                                jumpOpen = true
                                scope.launch { jumpTurnstile.play(TurnstileMode.ForwardIn) }
                            },
                    ) {
                        BasicText(
                            row.letter.toString(),
                            style = MetroType.Large.copy(fontSize = 26.sp, color = accent),
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 6.dp, bottom = 2.dp),
                        )
                    }

                    is ListRow.App -> {
                        var rowTop by remember { mutableFloatStateOf(0f) }
                        AppRow(
                            label = row.app.label,
                            accent = accent,
                            modifier = Modifier
                                .onGloballyPositioned { rowTop = it.boundsInRoot().bottom }
                                .turnstileItem(turnstile, key = row.app.key)
                                .metroTilt()
                                .combinedClickable(
                                    interactionSource = noRipple,
                                    indication = null,
                                    onLongClick = {
                                        menuY = rowTop
                                        menuFor = row.app
                                    },
                                    onClick = { onLaunch(row.app) },
                                ),
                        ) {
                            AppSquare(row.app, accent, metroIcons, Modifier.fillMaxSize())
                        }
                    }
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
        }
        }

        // Context menu: unfolds from the tapped app (3D flip down from its top edge), folds away on close.
        LaunchedEffect(menuFor) {
            if (menuFor != null) {
                menuShown = menuFor
                menuProgress.animateTo(1f, tween(240, easing = ExponentialEaseOut(5f)))
            } else {
                menuProgress.animateTo(0f, tween(150))
                menuShown = null
            }
        }
        val target = menuShown
        if (target != null) {
            var areaHeight by remember { mutableIntStateOf(0) }
            var menuHeight by remember { mutableIntStateOf(0) }
            Box(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { areaHeight = it.height }
                    .pointerInput(Unit) { detectTapGestures { menuFor = null } },
            ) {
                val pinned = target.key in pinnedKeys
                val colors = LocalMetroColors.current
                Column(
                    Modifier
                        .offset {
                            // Keep the whole menu on screen.
                            val y = menuY.roundToInt().coerceAtMost((areaHeight - menuHeight).coerceAtLeast(0))
                            IntOffset(0, y)
                        }
                        .padding(horizontal = 12.dp) // WP menus never touch the screen edges
                        .fillMaxWidth()
                        .onSizeChanged { menuHeight = it.height }
                        .graphicsLayer {
                            val p = menuProgress.value
                            rotationX = -90f * (1f - p)
                            transformOrigin = TransformOrigin(0.5f, 0f)
                            cameraDistance = 12f * this.density
                            alpha = p
                        }
                        .background(colors.chrome)
                        .padding(vertical = 8.dp),
                ) {
                    MenuItem(if (pinned) "already on start" else "pin to start", enabled = !pinned) {
                        menuFor = null
                        onPin(target)
                    }
                    MenuItem("app info") {
                        menuFor = null
                        onAppInfo(target)
                    }
                    MenuItem("uninstall") {
                        menuFor = null
                        onUninstall(target)
                    }
                }
            }
        }

        // Jump grid
        if (jumpOpen) {
            val available = headerIndex.keys
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .background(LocalMetroColors.current.background.copy(alpha = 0.94f))
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                    .padding(16.dp),
            ) {
                JUMP_LETTERS.toList().chunked(4).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        line.forEach { letter ->
                            val has = letter in available
                            Box(
                                Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .turnstileItem(jumpTurnstile)
                                    .metroTilt(enabled = has)
                                    .background(if (has) accent else LocalMetroColors.current.chrome)
                                    .clickable(noRipple, indication = null, enabled = has) {
                                        jumpOpen = false
                                        val index = headerIndex[letter] ?: return@clickable
                                        scope.launch { listState.scrollToItem(index + 1) } // +1: top spacer
                                    },
                            ) {
                                BasicText(
                                    letter.toString(),
                                    style = MetroType.Large.copy(
                                        fontSize = 34.sp,
                                        color = if (has) Color.White else LocalMetroColors.current.subtle,
                                    ),
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(start = 8.dp, bottom = 2.dp),
                                )
                            }
                        }
                        repeat(4 - line.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    label: String,
    accent: Color,
    modifier: Modifier,
    icon: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(52.dp)
                .background(accent),
        ) { icon() }
        Spacer(Modifier.width(14.dp))
        BasicText(label, style = MetroType.Large, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MenuItem(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val noRipple = remember { MutableInteractionSource() }
    BasicText(
        text,
        style = MetroType.Large.copy(color = if (enabled) LocalMetroColors.current.foreground else LocalMetroColors.current.subtle),
        modifier = Modifier
            .fillMaxWidth()
            .metroTilt(enabled = enabled)
            .clickable(noRipple, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    )
}
