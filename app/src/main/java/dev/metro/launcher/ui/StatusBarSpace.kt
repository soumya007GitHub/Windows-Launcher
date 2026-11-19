@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.metro.launcher.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.metro.launcher.data.LauncherStore

/** Height Metro's Windows Phone status bar adds below Android's status bar band. */
object WpStatusBarSize {
    const val EXTRA_DP = 10

    fun extraPx(density: Float): Int = (EXTRA_DP * density).toInt()
}

/** Extra top space on Metro's own screens while the (taller) WP status bar is on. */
@Composable
fun wpStatusBarExtra(): Dp {
    val context = LocalContext.current
    val settings by LauncherStore.get(context).settings.collectAsState()
    return if (settings.wpStatusBar) WpStatusBarSize.EXTRA_DP.dp else 0.dp
}

/** Room for the status bar: Android's band (even while hidden) plus the WP bar's extra height. */
fun Modifier.statusBarSpace(): Modifier = composed {
    windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).padding(top = wpStatusBarExtra())
}
