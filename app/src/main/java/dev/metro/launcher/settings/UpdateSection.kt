package dev.metro.launcher.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.metro.launcher.physics.TurnstileState
import dev.metro.launcher.physics.metroTilt
import dev.metro.launcher.physics.turnstileItem
import dev.metro.launcher.ui.MetroProgressDots
import dev.metro.launcher.ui.MetroType
import dev.metro.launcher.update.Release
import dev.metro.launcher.update.UpdateStatus
import dev.metro.launcher.update.Updater
import kotlinx.coroutines.launch
import java.io.File

private sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data object UpToDate : UpdateUi
    data class Available(val release: Release) : UpdateUi
    data class Downloading(val release: Release, val progress: Float) : UpdateUi
    data class NeedsPermission(val apk: File) : UpdateUi
    data object Installing : UpdateUi
    data class Failed(val message: String) : UpdateUi
}

/** Settings > about: check GitHub for a newer Metro, download it and hand it to Android's installer. */
@Composable
internal fun UpdateSection(accent: Color, turnstile: TurnstileState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ui by remember { mutableStateOf<UpdateUi>(UpdateUi.Idle) }
    val installError by UpdateStatus.lastError.collectAsState()

    fun install(apk: File) {
        if (!Updater.canInstall(context)) {
            ui = UpdateUi.NeedsPermission(apk)
            return
        }
        ui = runCatching { Updater.install(context, apk) }
            .fold({ UpdateUi.Installing }, { UpdateUi.Failed("couldn't start the installer") })
    }

    fun check() {
        UpdateStatus.lastError.value = null
        ui = UpdateUi.Checking
        scope.launch {
            ui = runCatching { Updater.check(context) }.fold(
                onSuccess = { release -> if (release == null) UpdateUi.UpToDate else UpdateUi.Available(release) },
                onFailure = { UpdateUi.Failed("couldn't reach GitHub. Check your internet connection.") },
            )
        }
    }

    fun download(release: Release) {
        ui = UpdateUi.Downloading(release, 0f)
        scope.launch {
            runCatching {
                Updater.download(context, release) { p -> ui = UpdateUi.Downloading(release, p) }
            }.fold(
                onSuccess = { apk -> install(apk) },
                onFailure = { e -> ui = UpdateUi.Failed("download failed: ${e.message ?: "unknown error"}") },
            )
        }
    }

    // Back from "Install unknown apps": carry on if it was allowed.
    LifecycleResumeEffect(ui) {
        val waiting = ui
        if (waiting is UpdateUi.NeedsPermission && Updater.canInstall(context)) install(waiting.apk)
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxWidth().turnstileItem(turnstile)) {
        val shown = if (installError != null && ui == UpdateUi.Installing) UpdateUi.Failed(installError!!) else ui
        when (shown) {
            UpdateUi.Idle -> Link("check for updates", accent) { check() }
            UpdateUi.Checking -> Working("checking for updates", accent)
            UpdateUi.UpToDate -> {
                Status("Metro is up to date")
                Link("check again", accent) { check() }
            }
            is UpdateUi.Available -> {
                Status("version ${shown.release.version} is available")
                Link("download and install", accent) { download(shown.release) }
            }
            is UpdateUi.Downloading -> Working(
                if (shown.progress > 0f) "downloading ${(shown.progress * 100).toInt()}%" else "downloading",
                accent,
            )
            is UpdateUi.NeedsPermission -> {
                Status("Android needs your OK for Metro to install its own update")
                Link("allow installing updates", accent) {
                    runCatching { context.startActivity(Updater.unknownSourcesIntent(context)) }
                }
            }
            UpdateUi.Installing -> Status("confirm the update in Android's window")
            is UpdateUi.Failed -> {
                Status(shown.message)
                Link("try again", accent) { check() }
            }
        }
    }
}

@Composable
private fun Working(text: String, accent: Color) {
    Spacer(Modifier.height(8.dp))
    MetroProgressDots(accent)
    Spacer(Modifier.height(6.dp))
    BasicText(text, style = MetroType.Subtle)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Status(text: String) {
    BasicText(text, style = MetroType.Medium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Link(text: String, accent: Color, onClick: () -> Unit) {
    val noRipple = remember { MutableInteractionSource() }
    BasicText(
        text,
        style = MetroType.Medium.copy(color = accent),
        modifier = Modifier
            .fillMaxWidth()
            .metroTilt()
            .clickable(noRipple, indication = null, onClick = onClick)
            .padding(vertical = 12.dp),
    )
}
