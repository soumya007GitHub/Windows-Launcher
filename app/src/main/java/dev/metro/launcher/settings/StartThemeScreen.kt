@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.metro.launcher.settings

import dev.metro.launcher.ui.statusBarSpace
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.metro.launcher.apps.IconPackInfo
import dev.metro.launcher.data.LauncherSettings
import dev.metro.launcher.physics.TurnstileState
import dev.metro.launcher.physics.metroTilt
import dev.metro.launcher.physics.turnstileItem
import dev.metro.launcher.ui.LocalMetroColors
import dev.metro.launcher.ui.MetroAccent
import dev.metro.launcher.ui.MetroListPicker
import dev.metro.launcher.ui.MetroThemeMode
import dev.metro.launcher.ui.MetroToggle
import dev.metro.launcher.ui.MetroType
import dev.metro.launcher.ui.isDark
import kotlinx.coroutines.launch

/** Pivot sections, in WP order. */
private val Sections = listOf("theme", "start", "sounds", "notifications", "system", "about")

/**
 * WP8 settings as a Pivot: "SETTINGS" on top, large section headings that you swipe through
 * (current heading bright, the others dimmed and wrapping round after it, like WP), and a
 * scrolling page per section. Multi-choice options are WP list pickers (drop-downs).
 */
@Composable
fun StartThemeScreen(
    settings: LauncherSettings,
    turnstile: TurnstileState,
    hasBackgroundPhoto: Boolean,
    isDefaultHome: Boolean,
    notificationAccess: Boolean,
    onAccent: (MetroAccent) -> Unit,
    onColumns: (Int) -> Unit,
    onCutout: (Boolean) -> Unit,
    onPickPhoto: () -> Unit,
    onHideNavigation: (Boolean) -> Unit,
    onSetDefaultHome: (Boolean) -> Unit,
    onNotificationAccess: () -> Unit,
    overlayGranted: Boolean,
    onLockscreen: (Boolean) -> Unit,
    onOverlayPermission: () -> Unit,
    onPreviewLockscreen: () -> Unit,
    usageAccess: Boolean,
    onUsageAccess: () -> Unit,
    onMetroIcons: (Boolean) -> Unit,
    onTheme: (MetroThemeMode) -> Unit,
    onPhoneDisplaySettings: () -> Unit,
    onArrangeStart: () -> Unit,
    iconPacks: List<IconPackInfo>,
    onIconPack: (String?) -> Unit,
    onFindIconPacks: () -> Unit,
    volumeServiceOn: Boolean,
    onVolumePanel: (Boolean) -> Unit,
    onAccessibilitySettings: () -> Unit,
    onGlobalNavBar: (Boolean) -> Unit,
    onLockSounds: (Boolean) -> Unit,
    customSounds: Boolean,
    onPickLockSound: () -> Unit,
    onPickUnlockSound: () -> Unit,
    onPreviewLockSound: () -> Unit,
    onPreviewUnlockSound: () -> Unit,
    onResetSounds: () -> Unit,
    hasLockPhoto: Boolean,
    onPickLockPhoto: () -> Unit,
    onClearLockPhoto: () -> Unit,
) {
    val accent = settings.accent.color
    val pager = rememberPagerState { Sections.size }
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .statusBarSpace()
            .padding(top = 20.dp),
    ) {
        BasicText(
            "SETTINGS",
            style = MetroType.AppTitle,
            modifier = Modifier.padding(horizontal = 20.dp).turnstileItem(turnstile),
        )
        PivotHeaders(
            current = pager.currentPage,
            offset = pager.currentPageOffsetFraction,
            onSelect = { scope.launch { pager.animateScrollToPage(it) } },
            modifier = Modifier.turnstileItem(turnstile),
        )
        HorizontalPager(state = pager, beyondViewportPageCount = 1, modifier = Modifier.fillMaxSize()) { page ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp, bottom = 32.dp),
            ) {
                when (Sections[page]) {
                    "theme" -> ThemePage(settings, accent, turnstile, iconPacks, onTheme, onPhoneDisplaySettings, onAccent, onMetroIcons, onIconPack, onFindIconPacks)
                    "start" -> StartPage(settings, accent, turnstile, hasBackgroundPhoto, onColumns, onCutout, onPickPhoto, onArrangeStart)
                    "sounds" -> SoundsPage(
                        settings, accent, turnstile, customSounds, onLockSounds, onPreviewLockSound, onPreviewUnlockSound,
                        onPickLockSound, onPickUnlockSound, onResetSounds, onLockscreen, onPreviewLockscreen,
                        hasLockPhoto, onPickLockPhoto, onClearLockPhoto,
                    )
                    "notifications" -> NotificationsPage(settings, accent, turnstile)
                    "system" -> SystemPage(
                        settings, accent, turnstile, isDefaultHome, notificationAccess, usageAccess, overlayGranted, volumeServiceOn,
                        onGlobalNavBar, onVolumePanel, onHideNavigation, onSetDefaultHome, onNotificationAccess, onUsageAccess,
                        onOverlayPermission, onAccessibilitySettings,
                    )
                    else -> AboutPage(accent, turnstile)
                }
            }
        }
    }
}

/**
 * Pivot headings. They are drawn starting from the current section and wrap around (WP's endless
 * pivot), sliding left with the pager while you swipe.
 */
@Composable
private fun PivotHeaders(current: Int, offset: Float, onSelect: (Int) -> Unit, modifier: Modifier) {
    val colors = LocalMetroColors.current
    val noRipple = remember { MutableInteractionSource() }
    val order = remember(current) { Sections.indices.map { (current + it) % Sections.size } }
    Box(modifier.fillMaxWidth().height(76.dp).clipToBounds()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier
                .wrapContentWidth(unbounded = true, align = androidx.compose.ui.Alignment.Start)
                .padding(start = 20.dp)
                .graphicsLayer { translationX = -offset * 220.dp.toPx() },
        ) {
            order.forEach { index ->
                BasicText(
                    Sections[index],
                    style = MetroType.PageTitle.copy(
                        fontSize = 52.sp,
                        color = if (index == current) colors.foreground else colors.subtle.copy(alpha = 0.55f),
                    ),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.clickable(noRipple, indication = null) { onSelect(index) },
                )
            }
        }
    }
}

@Composable
private fun ThemePage(
    settings: LauncherSettings,
    accent: Color,
    turnstile: TurnstileState,
    iconPacks: List<IconPackInfo>,
    onTheme: (MetroThemeMode) -> Unit,
    onPhoneDisplaySettings: () -> Unit,
    onAccent: (MetroAccent) -> Unit,
    onMetroIcons: (Boolean) -> Unit,
    onIconPack: (String?) -> Unit,
    onFindIconPacks: () -> Unit,
) {
    val noRipple = remember { MutableInteractionSource() }
    MetroListPicker(
        title = "Background",
        options = MetroThemeMode.entries.toList(),
        selected = settings.theme,
        label = {
            when (it) {
                MetroThemeMode.Dark -> "dark"
                MetroThemeMode.Light -> "light"
                MetroThemeMode.System -> "same as phone"
            }
        },
        accent = accent,
        onSelect = onTheme,
        modifier = Modifier.turnstileItem(turnstile),
    )
    // Android only lets the system change the phone's dark mode; offer a shortcut when they differ.
    val phoneIsDark = isSystemInDarkTheme()
    if (settings.theme != MetroThemeMode.System && settings.theme.isDark(phoneIsDark) != phoneIsDark) {
        LinkRow(
            if (phoneIsDark) "phone is in dark mode: switch phone to light" else "phone is in light mode: switch phone to dark",
            accent,
            Modifier.turnstileItem(turnstile),
            onPhoneDisplaySettings,
        )
    }

    Spacer(Modifier.height(12.dp))
    BasicText("Accent color", style = MetroType.Subtle, modifier = Modifier.turnstileItem(turnstile))
    BasicText(settings.accent.name.lowercase(), style = MetroType.Large.copy(color = accent), modifier = Modifier.turnstileItem(turnstile))
    Spacer(Modifier.height(10.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MetroAccent.entries.chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.turnstileItem(turnstile)) {
                row.forEach { option ->
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .metroTilt()
                            .background(option.color)
                            .then(if (option == settings.accent) Modifier.border(3.dp, LocalMetroColors.current.foreground) else Modifier)
                            .clickable(noRipple, indication = null) { onAccent(option) },
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))

    MetroToggle(
        title = "Metro icons",
        subtitle = "white glyphs on tiles, from each app's monochrome icon where it has one",
        checked = settings.metroIcons,
        accent = accent,
        onCheckedChange = onMetroIcons,
        modifier = Modifier.turnstileItem(turnstile),
    )
    MetroListPicker(
        title = "Icon pack",
        options = listOf<IconPackInfo?>(null) + iconPacks,
        selected = iconPacks.firstOrNull { it.packageName == settings.iconPack },
        label = { it?.label ?: "none" },
        accent = accent,
        onSelect = { onIconPack(it?.packageName) },
        modifier = Modifier.turnstileItem(turnstile),
    )
    LinkRow("find icon packs in Play Store", accent, Modifier.turnstileItem(turnstile), onFindIconPacks)
}

@Composable
private fun StartPage(
    settings: LauncherSettings,
    accent: Color,
    turnstile: TurnstileState,
    hasBackgroundPhoto: Boolean,
    onColumns: (Int) -> Unit,
    onCutout: (Boolean) -> Unit,
    onPickPhoto: () -> Unit,
    onArrangeStart: () -> Unit,
) {
    MetroListPicker(
        title = "Tiles per row",
        options = listOf(4, 6),
        selected = settings.columns,
        label = { if (it == 6) "6 columns (show more tiles)" else "4 columns" },
        accent = accent,
        onSelect = onColumns,
        modifier = Modifier.turnstileItem(turnstile),
    )
    MetroToggle(
        title = "Transparent tiles",
        subtitle = if (hasBackgroundPhoto) "tiles become windows onto your background photo" else "choose a background photo first",
        checked = settings.wallpaperCutout && hasBackgroundPhoto,
        accent = accent,
        onCheckedChange = { if (hasBackgroundPhoto) onCutout(it) else onPickPhoto() },
        modifier = Modifier.turnstileItem(turnstile),
    )
    BasicText("Home screen photo", style = MetroType.Subtle, modifier = Modifier.padding(top = 8.dp).turnstileItem(turnstile))
    dev.metro.launcher.wallpapers.WallpaperStrip(accent, forLock = false, modifier = Modifier.turnstileItem(turnstile))
    LinkRow("choose from your photos", accent, Modifier.turnstileItem(turnstile), onPickPhoto)
    Spacer(Modifier.height(8.dp))
    LinkRow("arrange my Start for me", accent, Modifier.turnstileItem(turnstile), onArrangeStart)
    BasicText(
        "replaces your current tile layout with a designed one built from your most-used apps",
        style = MetroType.Subtle.copy(fontSize = 13.sp),
        modifier = Modifier.turnstileItem(turnstile),
    )
}

@Composable
private fun SoundsPage(
    settings: LauncherSettings,
    accent: Color,
    turnstile: TurnstileState,
    customSounds: Boolean,
    onLockSounds: (Boolean) -> Unit,
    onPreviewLockSound: () -> Unit,
    onPreviewUnlockSound: () -> Unit,
    onPickLockSound: () -> Unit,
    onPickUnlockSound: () -> Unit,
    onResetSounds: () -> Unit,
    onLockscreen: (Boolean) -> Unit,
    onPreviewLockscreen: () -> Unit,
    hasLockPhoto: Boolean,
    onPickLockPhoto: () -> Unit,
    onClearLockPhoto: () -> Unit,
) {
    MetroToggle(
        title = "Lock & unlock sounds",
        subtitle = "follows your ringer: silent or vibrate plays nothing",
        checked = settings.lockSounds,
        accent = accent,
        onCheckedChange = onLockSounds,
        modifier = Modifier.turnstileItem(turnstile),
    )
    LinkRow("play lock sound", accent, Modifier.turnstileItem(turnstile), onPreviewLockSound)
    LinkRow("play unlock sound", accent, Modifier.turnstileItem(turnstile), onPreviewUnlockSound)
    LinkRow("choose lock sound file", accent, Modifier.turnstileItem(turnstile), onPickLockSound)
    LinkRow("choose unlock sound file", accent, Modifier.turnstileItem(turnstile), onPickUnlockSound)
    if (customSounds) LinkRow("use built-in sounds", accent, Modifier.turnstileItem(turnstile), onResetSounds)
    Spacer(Modifier.height(12.dp))
    MetroToggle(
        title = "Lockscreen curtain",
        subtitle = "lift the photo and clock to unlock; your PIN or fingerprint still applies",
        checked = settings.lockscreenEnabled,
        accent = accent,
        onCheckedChange = onLockscreen,
        modifier = Modifier.turnstileItem(turnstile),
    )
    BasicText("Lock screen photo", style = MetroType.Subtle, modifier = Modifier.padding(top = 8.dp).turnstileItem(turnstile))
    BasicText(
        if (hasLockPhoto) "its own photo" else "same as the home screen photo",
        style = MetroType.Medium,
        modifier = Modifier.turnstileItem(turnstile),
    )
    dev.metro.launcher.wallpapers.WallpaperStrip(accent, forLock = true, modifier = Modifier.turnstileItem(turnstile))
    LinkRow("choose from your photos", accent, Modifier.turnstileItem(turnstile), onPickLockPhoto)
    if (hasLockPhoto) LinkRow("use home screen photo instead", accent, Modifier.turnstileItem(turnstile), onClearLockPhoto)
    LinkRow("preview lockscreen", accent, Modifier.turnstileItem(turnstile), onPreviewLockscreen)
}

@Composable
private fun SystemPage(
    settings: LauncherSettings,
    accent: Color,
    turnstile: TurnstileState,
    isDefaultHome: Boolean,
    notificationAccess: Boolean,
    usageAccess: Boolean,
    overlayGranted: Boolean,
    volumeServiceOn: Boolean,
    onGlobalNavBar: (Boolean) -> Unit,
    onVolumePanel: (Boolean) -> Unit,
    onHideNavigation: (Boolean) -> Unit,
    onSetDefaultHome: (Boolean) -> Unit,
    onNotificationAccess: () -> Unit,
    onUsageAccess: () -> Unit,
    onOverlayPermission: () -> Unit,
    onAccessibilitySettings: () -> Unit,
) {
    MetroToggle(
        title = "Metro bar in every app",
        subtitle = "search / start / back over every app; best with Android's 3-button navigation",
        checked = settings.globalNavBar,
        accent = accent,
        onCheckedChange = onGlobalNavBar,
        modifier = Modifier.turnstileItem(turnstile),
    )
    MetroToggle(
        title = "Windows Phone volume panel",
        subtitle = "replaces Android's volume pop-up in every app",
        checked = settings.volumePanel,
        accent = accent,
        onCheckedChange = onVolumePanel,
        modifier = Modifier.turnstileItem(turnstile),
    )
    AppSwitchAnimationToggle(accent, turnstile)
    MetroToggle(
        title = "Hide Android navigation bar",
        subtitle = "on Metro's own screens, when the Metro bar is not running everywhere",
        checked = settings.hideSystemNavigation,
        accent = accent,
        onCheckedChange = onHideNavigation,
        modifier = Modifier.turnstileItem(turnstile),
    )
    MetroToggle(
        title = "Metro home screen",
        subtitle = if (isDefaultHome) {
            "off switches back to Android's home screen and turns off Metro's lock screen, bar, volume panel and live tiles"
        } else {
            "on makes Metro your home screen again and turns its features back on"
        },
        checked = isDefaultHome,
        accent = accent,
        onCheckedChange = onSetDefaultHome,
        modifier = Modifier.turnstileItem(turnstile),
    )
    KeyboardToggle(accent, turnstile)
    Spacer(Modifier.height(16.dp))
    BasicText("Permissions", style = MetroType.Subtle, modifier = Modifier.turnstileItem(turnstile))
    PermissionRow("Live tiles", notificationAccess, "allow notification access", accent, turnstile, onNotificationAccess)
    PermissionRow("Recent apps (hold Back)", usageAccess, "allow usage access", accent, turnstile, onUsageAccess)
    PermissionRow("Lockscreen", overlayGranted, "allow display over other apps", accent, turnstile, onOverlayPermission)
    PermissionRow("Metro bar & volume panel", volumeServiceOn, "turn on \"Metro\" in Accessibility", accent, turnstile, onAccessibilitySettings)
}

/** WP8.1 Action Center on / off; runs in the Metro accessibility service, switched on when needed. */
@Composable
internal fun ActionCenterToggle(on: Boolean, accent: Color, turnstile: TurnstileState) {
    val context = LocalContext.current
    MetroToggle(
        title = "Action center",
        subtitle = "swipe down from the top edge for quick actions and notifications",
        checked = on,
        accent = accent,
        onCheckedChange = { wanted ->
            val store = dev.metro.launcher.data.LauncherStore.get(context)
            store.updateSettings { it.copy(actionCenter = wanted) }
            val s = store.settings.value
            val needsService = s.globalNavBar || s.volumePanel || s.actionCenter
            val serviceOn = dev.metro.launcher.volume.MetroAccessibilityService.isEnabled(context)
            if (needsService != serviceOn) {
                val switched = dev.metro.launcher.home.MetroSwitches.setAccessibility(context, needsService)
                if (!switched && needsService) {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
        },
        modifier = Modifier.turnstileItem(turnstile),
    )
}

/**
 * Android's own window / app-switch animations (the "jump" when apps open, close or switch, which
 * can let Android's status bar show for a moment). Off = both scales set to 0, as in Developer
 * options. Needs the one-time WRITE_SECURE_SETTINGS grant; otherwise opens Developer options.
 */
@Composable
private fun AppSwitchAnimationToggle(accent: Color, turnstile: TurnstileState) {
    val context = LocalContext.current
    var resumes by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    val resolver = context.contentResolver
    val animationsOn = remember(resumes) {
        android.provider.Settings.Global.getFloat(resolver, android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) > 0f
    }
    MetroToggle(
        title = "Android app-switch animations",
        subtitle = "off removes Android's open / close / switch animation, so apps appear instantly",
        checked = animationsOn,
        accent = accent,
        onCheckedChange = { on ->
            val scale = if (on) 1f else 0f
            val done = dev.metro.launcher.home.MetroSwitches.canSwitchDirectly(context) && runCatching {
                android.provider.Settings.Global.putFloat(resolver, android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE, scale)
                android.provider.Settings.Global.putFloat(resolver, android.provider.Settings.Global.WINDOW_ANIMATION_SCALE, scale)
            }.isSuccess
            if (done) {
                resumes++
            } else {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        },
        modifier = Modifier.turnstileItem(turnstile),
    )
}

/** Metro keyboard on / off. Switches directly when Metro may change secure settings, else opens Android's pages. */
@Composable
private fun KeyboardToggle(accent: Color, turnstile: TurnstileState) {
    val context = LocalContext.current
    var resumes by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    val on = remember(resumes) { dev.metro.launcher.home.MetroSwitches.isKeyboardOn(context) }
    MetroToggle(
        title = "Metro keyboard",
        subtitle = "Windows Phone keyboard with emoji; off switches back to your previous keyboard",
        checked = on,
        accent = accent,
        onCheckedChange = { wanted ->
            val switches = dev.metro.launcher.home.MetroSwitches
            if (switches.setKeyboard(context, wanted)) {
                resumes++
            } else if (wanted) {
                // Android needs the user: turn it on in the keyboard list, then pick it.
                runCatching { context.startActivity(switches.keyboardSettingsIntent()) }
            } else {
                switches.showKeyboardPicker(context)
            }
        },
        modifier = Modifier.turnstileItem(turnstile),
    )
}

private const val GITHUB_URL = "https://github.com/soumya007github"

@Composable
private fun AboutPage(accent: Color, turnstile: TurnstileState) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    BasicText("Metro", style = MetroType.PageTitle.copy(fontSize = 40.sp), modifier = Modifier.turnstileItem(turnstile))
    BasicText("version $version", style = MetroType.Subtle, modifier = Modifier.turnstileItem(turnstile))
    UpdateSection(accent, turnstile)
    Spacer(Modifier.height(12.dp))
    BasicText(
        "A home screen for Android in the style of Windows Phone 8: live tiles with tilt and flip, " +
            "turnstile transitions, the app list, the curtain lockscreen, the volume panel and the " +
            "search / start / back bar.",
        style = MetroType.Medium.copy(lineHeight = 24.sp),
        modifier = Modifier.turnstileItem(turnstile),
    )
    Spacer(Modifier.height(24.dp))
    BasicText("Created by", style = MetroType.Subtle, modifier = Modifier.turnstileItem(turnstile))
    BasicText(
        "soumya007github",
        style = MetroType.Large.copy(color = accent),
        modifier = Modifier
            .turnstileItem(turnstile)
            .metroTilt()
            .clickable {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(GITHUB_URL))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
    )
    BasicText("github.com/soumya007github", style = MetroType.Subtle, modifier = Modifier.turnstileItem(turnstile))
    Spacer(Modifier.height(24.dp))
    BasicText(
        "Built with Kotlin and Jetpack Compose. Not affiliated with or endorsed by Microsoft. " +
            "Windows and Windows Phone are trademarks of Microsoft Corporation.",
        style = MetroType.Subtle.copy(fontSize = 13.sp, lineHeight = 18.sp),
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.turnstileItem(turnstile),
    )
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    action: String,
    accent: Color,
    turnstile: TurnstileState,
    onClick: () -> Unit,
) {
    val noRipple = remember { MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxWidth()
            .turnstileItem(turnstile)
            .metroTilt(enabled = !granted)
            .clickable(noRipple, indication = null, enabled = !granted, onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        BasicText(title, style = MetroType.Medium)
        BasicText(
            if (granted) "on" else action,
            style = if (granted) MetroType.Subtle else MetroType.Subtle.copy(color = accent),
        )
    }
}

@Composable
private fun LinkRow(text: String, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    val noRipple = remember { MutableInteractionSource() }
    BasicText(
        text,
        style = MetroType.Medium.copy(color = accent),
        modifier = modifier
            .fillMaxWidth()
            .metroTilt()
            .clickable(noRipple, indication = null, onClick = onClick)
            .padding(vertical = 12.dp),
    )
}
