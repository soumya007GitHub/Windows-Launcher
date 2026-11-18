package dev.metro.launcher.nav

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.metro.launcher.ui.Glyph
import dev.metro.launcher.ui.MetroIcon
import dev.metro.launcher.ui.LocalMetroColors

/** Metro navigation bar: Search on the left, Start in the middle, Back (hold for app switcher) on the right. */
@Composable
fun MetroNavBar(
    accent: Color,
    onBack: () -> Unit,
    onBackLongPress: () -> Unit,
    onStart: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 52.dp,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(LocalMetroColors.current.background)
            .height(height),
    ) {
        NavButton(Glyph.Search, accent, onSearch)
        NavButton(Glyph.Start, accent, onStart)
        NavButton(Glyph.Back, accent, onBack, onBackLongPress)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.NavButton(
    glyph: Glyph,
    accent: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onLongClick = onLongClick?.let { action ->
                    {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        action()
                    }
                },
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onClick()
                },
            ),
    ) {
        MetroIcon(glyph, Modifier.size(24.dp), color = if (pressed) accent else LocalMetroColors.current.foreground)
    }
}
