package dev.metro.launcher.search

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.metro.launcher.apps.AppEntry
import dev.metro.launcher.physics.TurnstileState
import dev.metro.launcher.physics.metroTilt
import dev.metro.launcher.physics.turnstileItem
import dev.metro.launcher.ui.Glyph
import dev.metro.launcher.ui.MetroIcon
import dev.metro.launcher.ui.MetroType
import dev.metro.launcher.ui.LocalMetroColors
import dev.metro.launcher.start.AppSquare
import androidx.compose.foundation.border

/** Ranks prefix matches first, then word-start matches, then substring matches. */
internal fun rankApps(apps: List<AppEntry>, query: String, limit: Int = 30): List<AppEntry> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    return apps.asSequence()
        .mapNotNull { app ->
            val label = app.label.lowercase()
            val score = when {
                label.startsWith(q) -> 0
                label.split(' ', '-', '_', '.').any { it.startsWith(q) } -> 1
                label.contains(q) -> 2
                else -> return@mapNotNull null
            }
            score to app
        }
        .sortedWith(compareBy({ it.first }, { it.second.label.lowercase() }))
        .take(limit)
        .map { it.second }
        .toList()
}

/** Incremental app search with a web-search fallback (Search key on the Metro nav bar). */
@Composable
fun SearchScreen(
    apps: List<AppEntry>,
    accent: Color,
    metroIcons: Boolean,
    turnstile: TurnstileState,
    onLaunch: (AppEntry) -> Unit,
    onWebSearch: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val results = remember(query, apps) { rankApps(apps, query) }
    val noRipple = remember { MutableInteractionSource() }

    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .imePadding()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        BasicText("SEARCH", style = MetroType.AppTitle, modifier = Modifier.turnstileItem(turnstile))
        Spacer(Modifier.height(12.dp))
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = MetroType.Large.copy(color = Color.Black, fontSize = 20.sp),
            cursorBrush = SolidColor(accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                when {
                    results.isNotEmpty() && results.first().label.equals(query.trim(), ignoreCase = true) -> onLaunch(results.first())
                    query.isNotBlank() -> onWebSearch(query.trim())
                }
            }),
            modifier = Modifier
                .fillMaxWidth()
                .turnstileItem(turnstile)
                .background(Color.White)
                .border(2.dp, LocalMetroColors.current.foreground)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .focusRequester(focus),
        )
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            items(results, key = { it.key }) { app ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .turnstileItem(turnstile, key = app.key)
                        .metroTilt()
                        .clickable(noRipple, indication = null) { onLaunch(app) }
                        .padding(vertical = 5.dp),
                ) {
                    AppSquare(app, accent, metroIcons, Modifier.size(48.dp))
                    Spacer(Modifier.width(14.dp))
                    BasicText(app.label, style = MetroType.Large, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (query.isNotBlank()) {
                item(key = "web") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .turnstileItem(turnstile, key = "web")
                            .metroTilt()
                            .clickable(noRipple, indication = null) { onWebSearch(query.trim()) }
                            .padding(vertical = 12.dp),
                    ) {
                        MetroIcon(Glyph.Search, Modifier.size(24.dp), color = accent)
                        Spacer(Modifier.width(14.dp))
                        BasicText(
                            "search the web for “${query.trim()}”",
                            style = MetroType.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
