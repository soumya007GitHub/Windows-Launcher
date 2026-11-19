package dev.metro.launcher.onboarding

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.metro.launcher.data.LauncherStore
import dev.metro.launcher.home.DefaultHome
import dev.metro.launcher.lock.LockscreenService
import dev.metro.launcher.notifications.NotificationRepository
import dev.metro.launcher.physics.metroTilt
import dev.metro.launcher.recents.RecentApps
import dev.metro.launcher.ui.MetroProgressDots
import dev.metro.launcher.volume.MetroAccessibilityService
import kotlinx.coroutines.delay

/** First-run setup: shown once per install, then never again (unless run from settings). */
object Onboarding {
    private const val PREFS = "onboarding"
    private const val KEY_DONE = "done"

    fun isDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)

    fun markDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
    }
}

/** One permission screen. [done] is re-checked every time the user comes back to Metro. */
private class Step(
    val id: String,
    val title: String,
    val body: String,
    val done: (Context) -> Boolean,
    val extraLink: Pair<String, (Context) -> Intent>? = null,
)

private const val WP_BLUE = 0xFF1BA1E2

/**
 * Windows Phone style first-run: a black screen with a large Windows logo and the WP loading dots,
 * then each permission as a full-screen WP alert (big title, text, "permit" and "skip"). Steps that
 * are already granted are passed over. Android always shows its own page or dialog to confirm.
 */
@Composable
fun OnboardingFlow(onFinished: () -> Unit) {
    val context = LocalContext.current
    val store = remember { LauncherStore.get(context) }
    var splash by remember { mutableStateOf(true) }
    var index by remember { mutableIntStateOf(0) }
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    BackHandler { /* setup can't be backed out of; use skip */ }

    val steps = remember { buildSteps() }
    val roleRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { resumes++ }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resumes++ }

    LaunchedEffect(Unit) {
        delay(2600)
        splash = false
    }

    // Skip over anything already allowed (also after coming back from a settings page).
    LaunchedEffect(index, resumes, splash) {
        if (splash) return@LaunchedEffect
        var i = index
        while (i < steps.size && steps[i].done(context)) i++
        if (i >= steps.size) {
            Onboarding.markDone(context)
            onFinished()
        } else if (i != index) {
            index = i
        }
    }

    fun permit(step: Step) {
        when (step.id) {
            "home" -> runCatching { roleRequest.launch(DefaultHome.requestIntent(context)) }
            "post" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runtime.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
            "names" -> runtime.launch(
                buildList {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
                }.toTypedArray(),
            )
            "lock" -> {
                store.updateSettings { it.copy(lockscreenEnabled = true) }
                if (Settings.canDrawOverlays(context)) {
                    LockscreenService.start(context)
                    resumes++
                } else {
                    open(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                }
            }
            else -> intentFor(step.id, context)?.let { open(context, it) }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).clickable(remember { MutableInteractionSource() }, null) {}) {
        if (splash) {
            Splash()
        } else if (index < steps.size) {
            StepScreen(
                step = steps[index],
                number = index + 1,
                total = steps.size,
                accent = store.settings.value.accent.color,
                onPermit = { permit(steps[index]) },
                onSkip = {
                    if (index + 1 >= steps.size) {
                        Onboarding.markDone(context)
                        onFinished()
                    } else {
                        index++
                    }
                },
            )
        }
    }
}

// ------------------------------------------------------------------ splash

@Composable
private fun Splash() {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Canvas(Modifier.size(150.dp)) { drawWindowsLogo(Color(WP_BLUE)) }
        Spacer(Modifier.height(56.dp))
        MetroProgressDots(Color.White, Modifier.fillMaxWidth())
    }
}

/** The Windows 8 logo: four panes in perspective, the right side larger. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWindowsLogo(color: Color) {
    val w = size.width
    val h = size.height
    fun pane(points: List<Pair<Float, Float>>) {
        val p = Path().apply {
            moveTo(w * points[0].first, h * points[0].second)
            points.drop(1).forEach { (x, y) -> lineTo(w * x, h * y) }
            close()
        }
        drawPath(p, color)
    }
    // Same proportions as the launcher icon (scaled from its 108-unit canvas).
    fun s(x: Float, y: Float) = ((x - 30f) / 48f) to ((y - 30f) / 48f)
    pane(listOf(s(33.84f, 41.52f), s(51.38f, 37.97f), s(51.38f, 53.2f), s(33.84f, 53.38f)))
    pane(listOf(s(33.84f, 54.62f), s(51.38f, 54.8f), s(51.38f, 70.03f), s(33.84f, 66.48f)))
    pane(listOf(s(53.4f, 37.56f), s(74.16f, 33.36f), s(74.16f, 52.97f), s(53.4f, 53.18f)))
    pane(listOf(s(53.4f, 54.82f), s(74.16f, 55.03f), s(74.16f, 74.64f), s(53.4f, 70.44f)))
}

// ------------------------------------------------------------------ step screen (WP alert)

@Composable
private fun StepScreen(step: Step, number: Int, total: Int, accent: Color, onPermit: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    // WP turnstile-in for each new screen.
    val turn = remember(step) { Animatable(-80f) }
    LaunchedEffect(step) { turn.animateTo(0f, tween(320, easing = FastOutSlowInEasing)) }
    Column(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                rotationY = turn.value
                transformOrigin = TransformOrigin(0f, 0.5f)
                cameraDistance = 12f * density
                alpha = 1f - (-turn.value / 80f) * 0.6f
            }
            .statusBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(36.dp))
        BasicText("SETUP  $number OF $total", style = TextStyle(color = Color(0xFF9A9A9A), fontSize = 13.sp, letterSpacing = 0.6.sp))
        Spacer(Modifier.height(10.dp))
        BasicText(step.title, style = TextStyle(color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Light))
        Spacer(Modifier.height(14.dp))
        BasicText(step.body, style = TextStyle(color = Color.White, fontSize = 18.sp, lineHeight = 25.sp))
        step.extraLink?.let { (label, intent) ->
            Spacer(Modifier.height(16.dp))
            BasicText(
                label,
                style = TextStyle(color = accent, fontSize = 16.sp, lineHeight = 22.sp),
                modifier = Modifier.clickable(remember { MutableInteractionSource() }, null) { open(context, intent(context)) },
            )
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WpButton("skip", onSkip)
            WpButton("permit", onPermit)
        }
        // At the bottom, just above the navigation bar, as on Windows Phone.
        Spacer(Modifier.navigationBarsPadding().height(24.dp))
    }
}

@Composable
private fun RowScope.WpButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .height(52.dp)
            .metroTilt()
            .border(2.5.dp, Color.White)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = TextStyle(color = Color.White, fontSize = 19.sp, textAlign = TextAlign.Center))
    }
}

// ------------------------------------------------------------------ the steps

private fun buildSteps(): List<Step> = buildList {
    add(
        Step(
            "home", "Start",
            "Make Metro your home screen, so Start opens whenever you press Home.",
            { DefaultHome.isDefault(it) },
        ),
    )
    add(
        Step(
            "service", "Metro service",
            "Turn on \"Metro\" in Accessibility. It draws the Windows Phone status bar, the search / Start / back bar " +
                "in every app, the Action Center and the volume panel. It doesn't read what's on your screen.\n\n" +
                "On the next page tap Metro (under installed or downloaded apps) and switch it on, then come back.",
            { MetroAccessibilityService.isEnabled(it) },
            extraLink = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                "Switch greyed out? Open Metro's app info, tap ⋮ and \"Allow restricted settings\", then try again." to { c ->
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}"))
                }
            } else {
                null
            },
        ),
    )
    add(
        Step(
            "listener", "Notifications",
            "Let live tiles, the lock screen and the Action Center show your notifications. Switch Metro on in the list.",
            { NotificationRepository.isAccessGranted(it) },
        ),
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(
            Step(
                "post", "Metro alerts",
                "Allow Metro to post its own notification, which keeps the lock screen running.",
                { it.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED },
            ),
        )
    }
    add(
        Step(
            "lock", "Lock screen",
            "Use the Windows Phone curtain lock screen. Metro needs to display over other apps for it.",
            { LauncherStore.get(it).settings.value.lockscreenEnabled && Settings.canDrawOverlays(it) },
        ),
    )
    add(
        Step(
            "usage", "Recent apps",
            "Hold Back to see your recent apps. Allow usage access for Metro in the list.",
            { RecentApps.hasAccess(it) },
        ),
    )
    add(
        Step(
            "write", "Brightness",
            "Let the Action Center change brightness, auto brightness and rotation lock. Allow \"Modify system settings\".",
            { Settings.System.canWrite(it) },
        ),
    )
    add(
        Step(
            "dnd", "Quiet hours",
            "Let quiet hours and silent mode work from the Action Center and the volume panel. Allow Do Not Disturb access.",
            { it.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted },
        ),
    )
    add(
        Step(
            "names", "Network names",
            "Show your Wi-Fi network and Bluetooth device names on the Action Center tiles. Android asks for location " +
                "and nearby devices to share them.",
            { it.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED },
        ),
    )
}

private fun intentFor(id: String, context: Context): Intent? = when (id) {
    "service" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    "listener" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    "usage" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    "write" -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
    "dnd" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
    else -> null
}

private fun open(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
