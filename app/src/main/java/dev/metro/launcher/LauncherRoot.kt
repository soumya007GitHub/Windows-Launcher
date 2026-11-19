package dev.metro.launcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.metro.launcher.lock.LockGate
import dev.metro.launcher.lock.LockscreenService
import dev.metro.launcher.recents.RecentApps
import dev.metro.launcher.recents.TaskSwitcher
import dev.metro.launcher.sound.MetroSounds
import dev.metro.launcher.volume.MetroAccessibilityService
import dev.metro.launcher.nav.GlobalNavBar
import dev.metro.launcher.start.CutoutWallpaper
import dev.metro.launcher.start.StartArranger
import dev.metro.launcher.start.IconPicker
import dev.metro.launcher.ui.LocalMetroColors
import dev.metro.launcher.ui.MetroColors
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.isSystemInDarkTheme
import dev.metro.launcher.start.cropToSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.LocalDensity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.metro.launcher.allapps.AllAppsScreen
import dev.metro.launcher.apps.AppEntry
import dev.metro.launcher.apps.AppRepository
import dev.metro.launcher.apps.IconPackInfo
import dev.metro.launcher.apps.IconPacks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.metro.launcher.data.LauncherStore
import dev.metro.launcher.data.PinnedTile
import dev.metro.launcher.home.DefaultHome
import dev.metro.launcher.home.HomeIntents
import dev.metro.launcher.home.MetroSwitches
import dev.metro.launcher.nav.MetroNavBar
import dev.metro.launcher.notifications.NotificationRepository
import dev.metro.launcher.physics.LiveTileEngineEffect
import dev.metro.launcher.physics.TurnstileMode
import dev.metro.launcher.physics.TurnstileState
import dev.metro.launcher.physics.metroTilt
import dev.metro.launcher.physics.rememberLiveTileEngine
import dev.metro.launcher.physics.turnstileItem
import dev.metro.launcher.search.SearchScreen
import dev.metro.launcher.settings.StartThemeScreen
import dev.metro.launcher.start.StartEditState
import dev.metro.launcher.start.StartScreen
import dev.metro.launcher.start.StartTileModel
import dev.metro.launcher.ui.MetroType
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class Page { Start, AllApps, Search, Settings }

@Composable
fun MetroLauncherApp(homeIntents: HomeIntents) {
    val context = LocalContext.current
    val store = remember { LauncherStore.get(context) }
    val repo = remember { AppRepository.get(context) }

    val apps by repo.apps.collectAsStateWithLifecycle()
    val pinned by store.tiles.collectAsStateWithLifecycle()
    val settings by store.settings.collectAsStateWithLifecycle()
    val wallpaperVersion by store.wallpaperVersion.collectAsStateWithLifecycle()
    val wallpaper by produceState<ImageBitmap?>(null, wallpaperVersion) { value = store.loadWallpaper() }
    val previews by NotificationRepository.previews.collectAsStateWithLifecycle()
    var accessGranted by remember { mutableStateOf(NotificationRepository.isAccessGranted(context)) }
    var isDefaultHome by remember { mutableStateOf(DefaultHome.isDefault(context)) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var usageAccess by remember { mutableStateOf(RecentApps.hasAccess(context)) }
    var switcherOpen by remember { mutableStateOf(false) }
    var recentApps by remember { mutableStateOf<List<AppEntry>?>(null) }
    // Re-scanned whenever the app list changes (an icon pack was installed or removed).
    val iconPacks by produceState(emptyList<IconPackInfo>(), apps) {
        value = withContext(Dispatchers.IO) { IconPacks.installed(context) }
    }
    // Only so the lockscreen's foreground-service notification can be shown; the service works without it.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val scope = rememberCoroutineScope()
    var metroActive by remember { mutableStateOf(MetroSwitches.isMetroActive(context)) }
    val roleRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDefaultHome = DefaultHome.isDefault(context)
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val metrics = context.resources.displayMetrics
            scope.launch {
                if (store.importWallpaper(uri, metrics.widthPixels, metrics.heightPixels)) {
                    store.updateSettings { it.copy(wallpaperCutout = true) }
                }
            }
        }
    }

    var volumeServiceOn by remember { mutableStateOf(MetroAccessibilityService.isEnabled(context)) }
    var customSounds by remember {
        mutableStateOf(MetroSounds.Kind.entries.any { MetroSounds.hasCustom(context, it) })
    }
    var pendingSound by remember { mutableStateOf(MetroSounds.Kind.Lock) }
    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val kind = pendingSound
            scope.launch {
                if (MetroSounds.importCustom(context, kind, uri)) {
                    customSounds = true
                    MetroSounds.preview(context, kind)
                }
            }
        }
    }

    val lockWallpaperVersion by store.lockWallpaperVersion.collectAsStateWithLifecycle()
    val lockPhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val metrics = context.resources.displayMetrics
            scope.launch { store.importLockWallpaper(uri, metrics.widthPixels, metrics.heightPixels) }
        }
    }

    // First run: seed Start from the phone's default apps.
    LaunchedEffect(apps, pinned) {
        val loaded = apps
        if (pinned == null && !loaded.isNullOrEmpty()) store.setTiles(repo.defaultPins(loaded))
    }

    val allApps = apps.orEmpty()
    val appsByKey = remember(allApps) { allApps.associateBy { it.key } }
    val pinnedKeys = remember(pinned) { pinned.orEmpty().map { it.key }.toSet() }
    val iconOverrides by store.iconOverrides.collectAsStateWithLifecycle()
    val startTiles = remember(pinned, appsByKey, settings.metroIcons, iconOverrides) {
        pinned.orEmpty().mapNotNull { p ->
            appsByKey[p.key]?.let { StartTileModel(it, p.size, settings.metroIcons, iconOverrides[p.key]) }
        }
    }
    var iconPickerFor by remember { mutableStateOf<AppEntry?>(null) }
    val iconImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val app = iconPickerFor
        if (uri != null && app != null) {
            scope.launch {
                store.importIconImage(app.key, uri)?.let { spec -> store.setIconOverride(app.key, spec) }
                iconPickerFor = null
            }
        }
    }

    val engine = rememberLiveTileEngine()
    LiveTileEngineEffect(engine)
    val turnstiles = remember { Page.entries.associateWith { TurnstileState() } }
    val stack = remember { mutableStateListOf(Page.Start) }
    val page = stack.last()
    val edit = remember { StartEditState() }
    val startScroll = rememberScrollState()
    val allAppsList = rememberLazyListState()
    /** WP: Start (page 0) and the app list (page 1) are one surface that pans sideways. */
    val homePager = rememberPagerState { 2 }
    var transitioning by remember { mutableStateOf(false) }
    val navMutex = remember { Mutex() }

    SideEffect {
        engine.paused = page != Page.Start || homePager.currentPage != 0 || edit.editMode || transitioning
    }

    /**
     * Navigation (Back / Start / Search / swipes) queues behind a running transition, so a tap made
     * mid-animation is never lost. App launches pass queue = false and are dropped instead,
     * so a double tap can't open two apps.
     */
    fun transition(queue: Boolean = true, block: suspend () -> Unit) {
        if (!queue && transitioning) return
        scope.launch {
            navMutex.withLock {
                transitioning = true
                try {
                    block()
                } finally {
                    transitioning = false
                }
            }
        }
    }

    fun push(target: Page) = transition {
        if (stack.last() == target) return@transition
        turnstiles.getValue(stack.last()).play(TurnstileMode.ForwardOut)
        stack.add(target)
        turnstiles.getValue(target).play(TurnstileMode.ForwardIn)
    }

    fun pop(afterPop: suspend () -> Unit = {}) = transition {
        if (stack.size <= 1) return@transition
        turnstiles.getValue(stack.last()).play(TurnstileMode.BackwardOut)
        stack.removeAt(stack.lastIndex)
        afterPop()
        turnstiles.getValue(stack.last()).play(TurnstileMode.BackwardIn)
    }

    fun goStart() {
        edit.exit()
        switcherOpen = false // Home / Start always closes the app switcher
        iconPickerFor = null // ... and the icon picker
        transition {
            if (stack.size == 1) {
                if (homePager.currentPage != 0) homePager.animateScrollToPage(0) else startScroll.animateScrollTo(0)
                return@transition
            }
            turnstiles.getValue(stack.last()).play(TurnstileMode.BackwardOut)
            stack.removeRange(1, stack.size)
            homePager.scrollToPage(0)
            startScroll.scrollTo(0)
            turnstiles.getValue(Page.Start).play(TurnstileMode.BackwardIn)
        }
    }

    fun showAllApps() {
        scope.launch { homePager.animateScrollToPage(1) }
    }

    /** WP launch: the page feathers away, tapped item last, then the app opens. */
    fun launchFrom(anchorKey: String?, action: () -> Unit) = transition(queue = false) {
        val t = turnstiles.getValue(stack.last())
        t.play(TurnstileMode.ForwardOut, anchorKey)
        if (runCatching(action).isFailure) t.play(TurnstileMode.BackwardIn)
    }

    fun launchApp(app: AppEntry) {
        // One-tap "screen off" apps: lock immediately, no turnstile and no app window.
        if (app.packageName in SCREEN_OFF_PACKAGES) {
            if (!MetroAccessibilityService.lockScreenNow()) runCatching { repo.launchInstantly(app) }
            return
        }
        launchFrom(app.key) { repo.launch(app) }
    }

    fun openSettingsIntent(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** The accessibility service runs only while the Metro bar or the volume panel needs it. */
    fun syncAccessibility(globalNavBar: Boolean, volumePanel: Boolean) {
        val wanted = globalNavBar || volumePanel
        if (wanted != MetroAccessibilityService.isEnabled(context)) {
            if (!MetroSwitches.setAccessibility(context, wanted) && wanted) {
                openSettingsIntent(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        scope.launch {
            kotlinx.coroutines.delay(300)
            volumeServiceOn = MetroAccessibilityService.isEnabled(context)
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        accessGranted = NotificationRepository.isAccessGranted(context)
        isDefaultHome = DefaultHome.isDefault(context)
        overlayGranted = Settings.canDrawOverlays(context)
        usageAccess = RecentApps.hasAccess(context)
        volumeServiceOn = MetroAccessibilityService.isEnabled(context)
        val viaHome = homeIntents.consumeReturnHome()
        scope.launch {
            if (viaHome) {
                edit.exit()
                switcherOpen = false
                iconPickerFor = null
                Page.entries.filter { it != Page.Start }.forEach { turnstiles.getValue(it).reset() }
                if (stack.size > 1) stack.removeRange(1, stack.size)
                homePager.scrollToPage(0)
                startScroll.scrollTo(0)
            }
            val t = turnstiles.getValue(stack.last())
            if (t.isHidden) t.play(TurnstileMode.BackwardIn)
        }
    }

    // Home pressed while the launcher is already in front.
    val inLauncherPresses = homeIntents.inLauncherPresses.intValue
    LaunchedEffect(inLauncherPresses) {
        if (inLauncherPresses > 0) goStart()
    }

    fun openSwitcher() {
        usageAccess = RecentApps.hasAccess(context)
        recentApps = null
        scope.launch { recentApps = RecentApps.load(context, allApps) }
        switcherOpen = true
    }

    // Search / hold-Back pressed on the system-wide bar while another app was in front.
    val metroRequest = homeIntents.pendingRequest.value
    LaunchedEffect(metroRequest) {
        when (metroRequest?.first) {
            HomeIntents.Request.Search -> if (stack.last() != Page.Search) push(Page.Search)
            HomeIntents.Request.Switcher -> openSwitcher()
            null -> Unit
        }
    }

    val globalBarActive = settings.globalNavBar && volumeServiceOn

    // A home screen never finishes on Back; deeper handlers (edit mode, menus) take precedence.
    BackHandler(enabled = true) { pop() }
    // Back on the app-list half pans back to Start.
    BackHandler(enabled = page == Page.Start && homePager.currentPage == 1) {
        scope.launch { homePager.animateScrollToPage(0) }
    }

    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val accent = settings.accent.color

    // Transparent tiles: the photo pre-scaled (centre-crop) to the window, so each tile can paint
    // exactly the slice of it that lies behind the tile.
    var windowSize by remember { mutableStateOf(IntSize.Zero) }
    val cutoutImage by produceState<ImageBitmap?>(null, wallpaper, windowSize) {
        val photo = wallpaper
        value = if (photo == null || windowSize == IntSize.Zero) null
        else withContext(Dispatchers.Default) { cropToSize(photo, windowSize) }
    }
    val cutout = remember(cutoutImage, settings.wallpaperCutout) {
        cutoutImage?.takeIf { settings.wallpaperCutout }?.let { CutoutWallpaper(it, startScroll, homePager) }
    }

    val themeColors = MetroColors.of(settings.theme, isSystemInDarkTheme())
    CompositionLocalProvider(LocalMetroColors provides themeColors) {
    Box(
        Modifier
            .fillMaxSize()
            .background(themeColors.background)
            .onSizeChanged { windowSize = it },
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (page) {
                    // Start and the app list share one turnstile, so launching or opening settings
                    // swings the whole surface.
                    Page.Start -> HorizontalPager(
                        state = homePager,
                        userScrollEnabled = !edit.editMode,
                        beyondViewportPageCount = 1,
                        modifier = Modifier.fillMaxSize(),
                    ) { half ->
                        if (half == 0) {
                            StartScreen(
                                tiles = startTiles,
                                columns = settings.columns,
                                accent = accent,
                                cutout = cutout,
                                previews = previews,
                                engine = engine,
                                turnstile = turnstiles.getValue(Page.Start),
                                scrollState = startScroll,
                                edit = edit,
                                onCommit = { models ->
                                    val shown = models.map { PinnedTile(it.key, it.size) }
                                    val hidden = pinned.orEmpty().filter { p -> models.none { it.key == p.key } && p.key !in appsByKey }
                                    store.setTiles(shown + hidden)
                                },
                                onUnpin = { tile ->
                                    store.unpin(tile.key)
                                    engine.forget(tile.key)
                                },
                                onChangeIcon = { tile -> iconPickerFor = tile.app },
                                onLaunch = ::launchApp,
                                onOpenAllApps = ::showAllApps,
                            )
                        } else {
                            AllAppsScreen(
                                apps = allApps,
                                pinnedKeys = pinnedKeys,
                                accent = accent,
                                metroIcons = settings.metroIcons,
                                turnstile = turnstiles.getValue(Page.Start),
                                listState = allAppsList,
                                onLaunch = ::launchApp,
                                onPin = { app ->
                                    store.pin(app.key)
                                    scope.launch {
                                        homePager.animateScrollToPage(0)
                                        withFrameNanos { } // let Start lay out the new tile
                                        startScroll.animateScrollTo(startScroll.maxValue)
                                    }
                                },
                                onAppInfo = { app -> runCatching { repo.openAppInfo(app) } },
                                onUninstall = { app -> runCatching { repo.uninstall(app) } },
                                onOpenSettings = { push(Page.Settings) },
                                onSwipeBack = { scope.launch { homePager.animateScrollToPage(0) } },
                            )
                        }
                    }

                    // The app list now lives inside the Start pager.
                    Page.AllApps -> Unit

                    Page.Search -> SearchScreen(
                        apps = allApps,
                        accent = accent,
                        metroIcons = settings.metroIcons,
                        turnstile = turnstiles.getValue(Page.Search),
                        onLaunch = ::launchApp,
                        onWebSearch = { q -> launchFrom("web") { repo.webSearch(q) } },
                    )

                    Page.Settings -> StartThemeScreen(
                        settings = settings,
                        turnstile = turnstiles.getValue(Page.Settings),
                        hasBackgroundPhoto = wallpaper != null,
                        isDefaultHome = isDefaultHome && metroActive,
                        notificationAccess = accessGranted,
                        onAccent = { a -> store.updateSettings { it.copy(accent = a) } },
                        onColumns = { c -> store.updateSettings { it.copy(columns = c) } },
                        onCutout = { on -> store.updateSettings { it.copy(wallpaperCutout = on) } },
                        onPickPhoto = {
                            runCatching {
                                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                        },
                        onHideNavigation = { hide -> store.updateSettings { it.copy(hideSystemNavigation = hide) } },
                        onSetDefaultHome = { on ->
                            if (on) {
                                MetroSwitches.activateMetro(context)
                                metroActive = true
                                if (!DefaultHome.isDefault(context)) {
                                    runCatching { roleRequest.launch(DefaultHome.requestIntent(context)) }
                                }
                                scope.launch {
                                    kotlinx.coroutines.delay(300)
                                    volumeServiceOn = MetroAccessibilityService.isEnabled(context)
                                }
                            } else {
                                MetroSwitches.deactivateMetro(context)
                                metroActive = false
                                // Android's chooser: the user picks the phone's own home screen there.
                                runCatching { context.startActivity(MetroSwitches.homeChooserIntent()) }
                                    .onFailure { openSettingsIntent(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
                            }
                        },
                        onNotificationAccess = { openSettingsIntent(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                        overlayGranted = overlayGranted,
                        onLockscreen = { on ->
                            store.updateSettings { it.copy(lockscreenEnabled = on) }
                            if (on) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                                    PackageManager.PERMISSION_GRANTED
                                ) {
                                    runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                                }
                                if (!Settings.canDrawOverlays(context)) {
                                    openSettingsIntent(overlaySettingsIntent(context))
                                }
                                LockscreenService.start(context)
                            } else {
                                LockscreenService.stop(context)
                            }
                        },
                        onOverlayPermission = { openSettingsIntent(overlaySettingsIntent(context)) },
                        onPreviewLockscreen = { LockGate.show(context) },
                        usageAccess = usageAccess,
                        onUsageAccess = { openSettingsIntent(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                        onMetroIcons = { on -> store.updateSettings { it.copy(metroIcons = on) } },
                        onTheme = { mode -> store.updateSettings { it.copy(theme = mode) } },
                        onPhoneDisplaySettings = { openSettingsIntent(Intent(Settings.ACTION_DISPLAY_SETTINGS)) },
                        onArrangeStart = {
                            scope.launch {
                                val tiles = StartArranger.arrange(context, allApps)
                                if (tiles.isNotEmpty()) {
                                    store.setTiles(tiles)
                                    goStart()
                                }
                            }
                        },
                        iconPacks = iconPacks,
                        onIconPack = { pkg ->
                            store.updateSettings { it.copy(iconPack = pkg) }
                            repo.setIconPack(pkg)
                        },
                        onFindIconPacks = {
                            openSettingsIntent(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=windows%20phone%20icon%20pack")))
                        },
                        volumeServiceOn = volumeServiceOn,
                        onVolumePanel = { on ->
                            store.updateSettings { it.copy(volumePanel = on) }
                            syncAccessibility(globalNavBar = store.settings.value.globalNavBar, volumePanel = on)
                        },
                        onAccessibilitySettings = { openSettingsIntent(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        onGlobalNavBar = { on ->
                            store.updateSettings { it.copy(globalNavBar = on) }
                            syncAccessibility(globalNavBar = on, volumePanel = store.settings.value.volumePanel)
                        },
                        onLockSounds = { on -> store.updateSettings { it.copy(lockSounds = on) } },
                        customSounds = customSounds,
                        onPickLockSound = {
                            pendingSound = MetroSounds.Kind.Lock
                            runCatching { soundPicker.launch(arrayOf("audio/*")) }
                        },
                        onPickUnlockSound = {
                            pendingSound = MetroSounds.Kind.Unlock
                            runCatching { soundPicker.launch(arrayOf("audio/*")) }
                        },
                        onPreviewLockSound = { MetroSounds.preview(context, MetroSounds.Kind.Lock) },
                        onPreviewUnlockSound = { MetroSounds.preview(context, MetroSounds.Kind.Unlock) },
                        hasLockPhoto = lockWallpaperVersion != 0L,
                        onPickLockPhoto = {
                            runCatching { lockPhotoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                        },
                        onClearLockPhoto = { store.clearLockWallpaper() },
                        onResetSounds = {
                            MetroSounds.resetCustom(context)
                            customSounds = false
                        },
                    )
                }
            }
            if (globalBarActive) {
                // The system-wide bar is drawn by the accessibility service over Android's bar; keep the
                // same space free here (48 dp minimum, matching the overlay under gesture navigation).
                val navBottom = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
                Spacer(Modifier.height(maxOf(navBottom, GlobalNavBar.MIN_HEIGHT_DP.dp)))
            } else {
                MetroNavBar(
                    accent = accent,
                    onBack = { backDispatcher?.onBackPressed() },
                    onBackLongPress = ::openSwitcher,
                    onStart = ::goStart,
                    onSearch = { if (page != Page.Search) push(Page.Search) },
                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                )
            }
        }

        if (switcherOpen) {
            TaskSwitcher(
                apps = recentApps,
                hasAccess = usageAccess,
                accent = accent,
                metroIcons = settings.metroIcons,
                onOpen = { app ->
                    switcherOpen = false
                    runCatching { repo.launch(app) }
                },
                onClose = { app ->
                    recentApps = recentApps?.minus(app)
                    RecentApps.dismiss(context, app.packageName)
                },
                onCloseAll = {
                    RecentApps.dismissAll(context, recentApps.orEmpty().map { it.packageName })
                    recentApps = emptyList()
                },
                onGrantAccess = {
                    switcherOpen = false
                    openSettingsIntent(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                },
                onDismiss = { switcherOpen = false },
            )
        }

        iconPickerFor?.let { app ->
            IconPicker(
                app = app,
                current = iconOverrides[app.key],
                accent = accent,
                packs = iconPacks,
                onPick = { spec ->
                    store.setIconOverride(app.key, spec)
                    iconPickerFor = null
                },
                onPickImage = {
                    runCatching { iconImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                },
                onDismiss = { iconPickerFor = null },
            )
        }
    }
    }
}

private fun overlaySettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

/** Apps whose only job is to turn the screen off; tapping them locks instantly with no animation. */
private val SCREEN_OFF_PACKAGES = setOf(
    "com.android.bbk.lockscreen3", // vivo / OriginOS one-tap lock screen
)
