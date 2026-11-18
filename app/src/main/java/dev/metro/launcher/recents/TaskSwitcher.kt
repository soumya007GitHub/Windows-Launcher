package dev.metro.launcher.recents

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.metro.launcher.apps.AppEntry
import dev.metro.launcher.physics.ExponentialEaseOut
import dev.metro.launcher.physics.metroTilt
import dev.metro.launcher.ui.MetroType
import dev.metro.launcher.ui.LocalMetroColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Recently used apps, newest first. Android gives launchers no access to the real task list or
 * app screenshots, so this reads foreground events from UsageStatsManager, which needs the user
 * to grant "Usage access". Nothing leaves the device.
 */
object RecentApps {

    private const val PREFS = "metro_recents"
    private const val KEY_CLEARED_ALL = "cleared_all_at"
    private const val KEY_DISMISSED_PREFIX = "dismissed:"

    /**
     * An app only counts as "recent" if it was on screen since the phone last started *and*
     * within this window. Usage history has no notion of "closed", so a short window is what
     * keeps long-finished apps out.
     */
    private const val WINDOW_MS = 3 * 60 * 60 * 1000L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION") // still the supported check for usage access; no replacement on API 26–36
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Closes an app as far as a launcher is allowed to: Android only lets third-party apps stop
     * *background* processes (which every other app is while the switcher is up). The system's own
     * task list is not touched; it can only be edited by the system.
     */
    fun dismiss(context: Context, packageName: String) {
        prefs(context).edit().putLong(KEY_DISMISSED_PREFIX + packageName, System.currentTimeMillis()).apply()
        runCatching { context.getSystemService(ActivityManager::class.java).killBackgroundProcesses(packageName) }
    }

    /** "close all": everything currently listed disappears until it is opened again. */
    fun dismissAll(context: Context, packageNames: List<String>) {
        prefs(context).edit().putLong(KEY_CLEARED_ALL, System.currentTimeMillis()).apply()
        val activityManager = context.getSystemService(ActivityManager::class.java)
        packageNames.forEach { runCatching { activityManager.killBackgroundProcesses(it) } }
    }

    suspend fun load(context: Context, apps: List<AppEntry>, limit: Int = 12): List<AppEntry> =
        withContext(Dispatchers.IO) {
            if (!hasAccess(context)) return@withContext emptyList()
            val usage = context.getSystemService(UsageStatsManager::class.java)
            val prefs = prefs(context)
            val end = System.currentTimeMillis()
            val bootedAt = end - SystemClock.elapsedRealtime()
            val since = maxOf(bootedAt, end - WINDOW_MS, prefs.getLong(KEY_CLEARED_ALL, 0L))
            // Aggregated per-app stats are one cheap call, unlike walking every usage event.
            val stats = usage.queryAndAggregateUsageStats(since, end)
            val lastSeen = HashMap<String, Long>()
            for ((pkg, stat) in stats) {
                val time = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) stat.lastTimeVisible else stat.lastTimeUsed
                // Daily buckets can carry older timestamps: re-check against the window.
                if (time > since) lastSeen[pkg] = time
            }
            val byPackage = apps.groupBy { it.packageName }
            lastSeen.entries
                .asSequence()
                .filter { it.key != context.packageName }
                .filter { prefs.getLong(KEY_DISMISSED_PREFIX + it.key, 0L) < it.value }
                .sortedByDescending { it.value }
                .mapNotNull { byPackage[it.key]?.firstOrNull() }
                .take(limit)
                .toList()
        }
}

/**
 * WP8 task switcher (hold Back): the screen zooms out to a strip of app cards, newest on the left.
 * The strip scrolls freely and settles with the nearest card centred. Tap a card to switch,
 * flick a card up to close it, Back or tap outside to dismiss.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskSwitcher(
    /** null while loading. */
    apps: List<AppEntry>?,
    hasAccess: Boolean,
    accent: Color,
    metroIcons: Boolean,
    onOpen: (AppEntry) -> Unit,
    onClose: (AppEntry) -> Unit,
    onCloseAll: () -> Unit,
    onGrantAccess: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }
    val dismiss by rememberUpdatedState(onDismiss)
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(260, easing = ExponentialEaseOut(5f))) }
    val noRipple = remember { MutableInteractionSource() }
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val cardWidth = screenWidth * 0.52f // leaves room for "close all" under the cards
    val cardAspect = (screenWidth.value / screenHeight.value).coerceIn(0.4f, 0.75f)

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value }
            .background(LocalMetroColors.current.background.copy(alpha = 0.94f))
            .pointerInput(Unit) {
                // Only a real tap on the background dismisses; a swipe never does.
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                    if ((up.position - down.position).getDistance() < viewConfiguration.touchSlop) dismiss()
                }
            },
    ) {
        when {
            !hasAccess -> Column(Modifier.align(Alignment.Center).padding(32.dp)) {
                BasicText("Recent apps need usage access", style = MetroType.Large)
                Spacer(Modifier.height(8.dp))
                BasicText(
                    "allow usage access",
                    style = MetroType.Medium.copy(color = accent),
                    modifier = Modifier
                        .metroTilt()
                        .clickable(noRipple, indication = null, onClick = onGrantAccess)
                        .padding(vertical = 8.dp),
                )
            }

            apps == null -> BasicText("loading…", style = MetroType.Large, modifier = Modifier.align(Alignment.Center))

            apps.isEmpty() -> BasicText("no recent apps", style = MetroType.Large, modifier = Modifier.align(Alignment.Center))

            else -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxSize()
                    // Keep everything clear of the Metro / Android navigation bar at the bottom.
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(top = 24.dp, bottom = 64.dp),
            ) {
                val listState = rememberLazyListState()
                val snapping = rememberSnapFlingBehavior(listState, SnapPosition.Center)
                LazyRow(
                    state = listState,
                    flingBehavior = snapping,
                    contentPadding = PaddingValues(horizontal = (screenWidth - cardWidth) / 2),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            // Zoom out from full screen, as WP8 does.
                            val s = 1.25f - 0.25f * appear.value
                            scaleX = s
                            scaleY = s
                        },
                ) {
                    items(apps, key = { it.key }) { app ->
                        RecentCard(
                            app = app,
                            width = cardWidth,
                            aspect = cardAspect,
                            accent = accent,
                            metroIcons = metroIcons,
                            onOpen = { onOpen(app) },
                            onClose = { onClose(app) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
                // Its own row: clear space above (from the cards) and below (from the nav bar).
                Spacer(Modifier.height(40.dp))
                BasicText(
                    "close all",
                    style = MetroType.Medium.copy(color = accent),
                    modifier = Modifier
                        .metroTilt()
                        .clickable(noRipple, indication = null, onClick = onCloseAll)
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** One card. Vertical drag lifts it 1:1; a flick or a lift past 120 dp throws it off the top. */
@Composable
private fun RecentCard(
    app: AppEntry,
    width: Dp,
    aspect: Float,
    accent: Color,
    metroIcons: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val close by rememberUpdatedState(onClose)
    val noRipple = remember { MutableInteractionSource() }
    var offset by remember { mutableFloatStateOf(0f) }
    val closeDistance = with(density) { 120.dp.toPx() }
    val flickVelocity = with(density) { 900.dp.toPx() }
    val offScreen = with(density) { -LocalConfiguration.current.screenHeightDp.dp.toPx() }

    Column(
        modifier
            .width(width)
            .graphicsLayer {
                translationY = offset
                alpha = (1f - offset / offScreen * 1.4f).coerceIn(0f, 1f)
            }
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta -> offset = (offset + delta).coerceAtMost(0f) },
                onDragStopped = { velocity ->
                    if (offset < -closeDistance || velocity < -flickVelocity) {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        animate(offset, offScreen, velocity.coerceAtMost(0f), tween(200)) { v, _ -> offset = v }
                        close()
                    } else {
                        animate(offset, 0f, velocity, spring(dampingRatio = 0.7f, stiffness = 500f)) { v, _ -> offset = v }
                    }
                },
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(app.icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            BasicText(app.label, style = MetroType.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .metroTilt()
                .background(accent)
                .clickable(noRipple, indication = null, onClick = onOpen),
        ) {
            Image(
                app.tileIcon(metroIcons),
                contentDescription = app.label,
                modifier = Modifier.fillMaxHeight(0.22f).aspectRatio(1f),
            )
        }
    }
}
