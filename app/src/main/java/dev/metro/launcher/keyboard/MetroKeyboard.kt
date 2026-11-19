package dev.metro.launcher.keyboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Windows Phone keyboard palette: flat keys on a darker bed, function keys a shade apart. */
private class KeyColors(val bed: Color, val key: Color, val function: Color, val ink: Color, val subtle: Color) {
    companion object {
        val Dark = KeyColors(Color(0xFF1F1F1F), Color(0xFF3B3B3B), Color(0xFF2A2A2A), Color.White, Color(0xFF9A9A9A))
        val Light = KeyColors(Color(0xFFDADADA), Color.White, Color(0xFFC6C6C6), Color.Black, Color(0xFF666666))
    }
}

private val KeyHeight = 52.dp
private val Gap = 5.dp
private val LetterStyle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Light, textAlign = TextAlign.Center)

@Composable
fun MetroKeyboard(state: KeyboardState, accent: Color, dark: Boolean, actions: KeyboardActions) {
    val colors = if (dark) KeyColors.Dark else KeyColors.Light
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.bed)
            .navigationBarsPadding()
            .padding(horizontal = 3.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(Gap),
    ) {
        when (state.page) {
            KeyboardPage.Letters -> LettersPage(state, accent, colors, actions)
            KeyboardPage.Symbols1 -> SymbolsPage(state, accent, colors, actions, SYMBOLS_1, "1/2", KeyboardPage.Symbols2)
            KeyboardPage.Symbols2 -> SymbolsPage(state, accent, colors, actions, SYMBOLS_2, "2/2", KeyboardPage.Symbols1)
            KeyboardPage.Emoji -> EmojiPage(state, accent, colors, actions)
        }
    }
}

// ------------------------------------------------------------------ pages

@Composable
private fun LettersPage(state: KeyboardState, accent: Color, colors: KeyColors, actions: KeyboardActions) {
    val upper = state.shift || state.capsLock
    fun label(c: Char) = if (upper) c.uppercaseChar().toString() else c.toString()

    KeyRow {
        "qwertyuiop".forEach { c -> TextKey(label(c), accent, colors) { actions.type(c.toString()) } }
    }
    KeyRow {
        Spacer(Modifier.weight(0.5f))
        "asdfghjkl".forEach { c -> TextKey(label(c), accent, colors) { actions.type(c.toString()) } }
        Spacer(Modifier.weight(0.5f))
    }
    KeyRow {
        Key(accent, colors, weight = 1.5f, function = true, lit = state.shift || state.capsLock, onPress = actions::shiftTapped) { ink ->
            Canvas(Modifier.fillMaxSize()) { drawShift(ink, filled = state.shift || state.capsLock, locked = state.capsLock) }
        }
        "zxcvbnm".forEach { c -> TextKey(label(c), accent, colors) { actions.type(c.toString()) } }
        BackspaceKey(accent, colors, actions)
    }
    BottomRow(state, accent, colors, actions, switchLabel = "&123", switchTo = KeyboardPage.Symbols1)
}

@Composable
private fun SymbolsPage(
    state: KeyboardState,
    accent: Color,
    colors: KeyColors,
    actions: KeyboardActions,
    rows: List<String>,
    pageLabel: String,
    otherPage: KeyboardPage,
) {
    KeyRow { rows[0].split(' ').forEach { s -> TextKey(s, accent, colors) { actions.type(s) } } }
    KeyRow { rows[1].split(' ').forEach { s -> TextKey(s, accent, colors) { actions.type(s) } } }
    KeyRow {
        TextKey(pageLabel, accent, colors, weight = 1.5f, function = true, small = true) { state.page = otherPage }
        rows[2].split(' ').forEach { s -> TextKey(s, accent, colors) { actions.type(s) } }
        BackspaceKey(accent, colors, actions)
    }
    BottomRow(state, accent, colors, actions, switchLabel = "abc", switchTo = KeyboardPage.Letters)
}

@Composable
private fun EmojiPage(state: KeyboardState, accent: Color, colors: KeyColors, actions: KeyboardActions) {
    val context = LocalContext.current
    val view = LocalView.current
    val tabs = remember { listOf("🕘") + Emoji.categories.map { it.tab } }
    val emoji = remember(state.emojiTab) {
        if (state.emojiTab == 0) Emoji.recent(context) else Emoji.categories[state.emojiTab - 1].emoji
    }
    val grid = rememberLazyGridState()
    LaunchedEffect(state.emojiTab) { grid.scrollToItem(0) }

    Box(Modifier.fillMaxWidth().height(KeyHeight * 3 + Gap * 2)) {
        if (emoji.isEmpty()) {
            BasicText(
                "emoji you use will appear here",
                style = TextStyle(color = colors.subtle, fontSize = 15.sp),
                modifier = Modifier.align(Alignment.Center),
            )
        }
        LazyVerticalGrid(columns = GridCells.Fixed(8), state = grid, modifier = Modifier.fillMaxSize()) {
            items(emoji) { e ->
                var pressed by remember { mutableStateOf(false) }
                Box(
                    Modifier
                        .height(KeyHeight)
                        .background(if (pressed) accent else Color.Transparent)
                        .pressable(
                            onDown = { pressed = true; actions.keyFeedback(view) },
                            onUp = { pressed = false },
                            onTap = { actions.emojiTyped(e) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(e, style = TextStyle(fontSize = 26.sp, textAlign = TextAlign.Center))
                }
            }
        }
    }
    KeyRow {
        TextKey("abc", accent, colors, weight = 1.5f, function = true, small = true) { state.page = KeyboardPage.Letters }
        tabs.forEachIndexed { i, tab ->
            Key(
                accent, colors, weight = 0.78f, function = true, lit = i == state.emojiTab,
                onPress = { state.emojiTab = i },
            ) { _ -> BasicText(tab, style = TextStyle(fontSize = 17.sp, textAlign = TextAlign.Center)) }
        }
        BackspaceKey(accent, colors, actions)
    }
}

@Composable
private fun BottomRow(
    state: KeyboardState,
    accent: Color,
    colors: KeyColors,
    actions: KeyboardActions,
    switchLabel: String,
    switchTo: KeyboardPage,
) {
    KeyRow {
        TextKey(switchLabel, accent, colors, weight = 1.5f, function = true, small = true) {
            state.page = switchTo
        }
        Key(accent, colors, function = true, onPress = { state.page = KeyboardPage.Emoji }) { ink ->
            Canvas(Modifier.fillMaxSize()) { drawSmiley(ink) }
        }
        TextKey(state.altKey, accent, colors) { actions.type(state.altKey) }
        // Space: hold to switch keyboards.
        Key(accent, colors, weight = 4f, onPress = { actions.type(" ") }, onLongPress = actions::showPicker) { _ -> }
        TextKey(".", accent, colors) { actions.type(".") }
        Key(accent, colors, weight = 1.5f, function = true, onPress = actions::enter) { ink ->
            when (state.enter) {
                EnterKind.NewLine -> Canvas(Modifier.fillMaxSize()) { drawEnter(ink) }
                EnterKind.Search -> Canvas(Modifier.fillMaxSize()) { drawSearch(ink) }
                else -> BasicText(
                    state.enter.name.lowercase(),
                    style = TextStyle(color = ink, fontSize = 16.sp, textAlign = TextAlign.Center),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ keys

@Composable
private fun KeyRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(KeyHeight),
        horizontalArrangement = Arrangement.spacedBy(Gap),
        content = content,
    )
}

@Composable
private fun RowScope.TextKey(
    text: String,
    accent: Color,
    colors: KeyColors,
    weight: Float = 1f,
    function: Boolean = false,
    small: Boolean = false,
    onPress: () -> Unit,
) {
    Key(accent, colors, weight = weight, function = function, onPress = onPress) { ink ->
        BasicText(
            text,
            style = if (small) TextStyle(color = ink, fontSize = 16.sp, textAlign = TextAlign.Center) else LetterStyle.copy(color = ink),
            maxLines = 1,
        )
    }
}

@Composable
private fun RowScope.BackspaceKey(accent: Color, colors: KeyColors, actions: KeyboardActions) {
    Key(accent, colors, weight = 1.5f, function = true, onPress = actions::backspace, repeat = true) { ink ->
        Canvas(Modifier.fillMaxSize()) { drawBackspace(ink) }
    }
}

/**
 * One flat Metro key. It turns the accent colour while held (WP8.1). [onPress] fires on release,
 * except for repeating keys (backspace), which fire on touch and then repeat while held.
 */
@Composable
private fun RowScope.Key(
    accent: Color,
    colors: KeyColors,
    weight: Float = 1f,
    function: Boolean = false,
    lit: Boolean = false,
    repeat: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    onPress: () -> Unit,
    content: @Composable (ink: Color) -> Unit,
) {
    val view = LocalView.current
    val audio = LocalContext.current.getSystemService(android.media.AudioManager::class.java)
    var pressed by remember { mutableStateOf(false) }
    val timers = androidx.compose.runtime.rememberCoroutineScope()
    val press by rememberUpdatedState(onPress)
    val longPress by rememberUpdatedState(onLongPress)
    val actionsView = view
    val bg = when {
        pressed -> accent
        lit && function -> colors.key
        function -> colors.function
        else -> colors.key
    }
    val ink = if (pressed) Color.White else colors.ink
    Box(
        Modifier
            .weight(weight)
            .fillMaxHeight()
            .background(bg)
            .pointerInput(repeat) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    actionsView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    // Follows the phone's "touch sounds" setting.
                    audio.playSoundEffect(android.media.AudioManager.FX_KEYPRESS_STANDARD)
                    if (repeat) {
                        press()
                        val repeater = timers.launch {
                            delay(REPEAT_START_MS)
                            while (true) {
                                press()
                                delay(REPEAT_EVERY_MS)
                            }
                        }
                        waitForUpOrCancellation()
                        repeater.cancel()
                    } else {
                        var longFired = false
                        val timer = longPress?.let { action ->
                            timers.launch {
                                delay(LONG_PRESS_MS)
                                longFired = true
                                pressed = false
                                action()
                            }
                        }
                        val up = waitForUpOrCancellation()
                        timer?.cancel()
                        if (up != null && !longFired) press()
                    }
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        content(ink)
    }
}

/** Press handling for emoji cells (inside a scrolling grid, so a drag cancels the tap). */
private fun Modifier.pressable(onDown: () -> Unit, onUp: () -> Unit, onTap: () -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            onDown()
            val up = waitForUpOrCancellation()
            onUp()
            if (up != null) onTap()
        }
    }

private const val REPEAT_START_MS = 400L
private const val REPEAT_EVERY_MS = 55L
private const val LONG_PRESS_MS = 500L

private val SYMBOLS_1 = listOf(
    "1 2 3 4 5 6 7 8 9 0",
    "@ # $ % & * - + ( )",
    "! \" ' : ; / ?",
)
private val SYMBOLS_2 = listOf(
    "~ ` | • √ π ÷ × ¶ ∆",
    "£ ¢ € ¥ ^ ° = { } \\",
    "© ® ™ [ ] < >",
)

// ------------------------------------------------------------------ glyphs (thin Metro line icons)

private fun DrawScope.line(): Stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun DrawScope.drawShift(ink: Color, filled: Boolean, locked: Boolean) {
    val s = 9.dp.toPx()
    val cx = size.width / 2
    val cy = size.height / 2 - 1.dp.toPx()
    val path = Path().apply {
        moveTo(cx, cy - s)
        lineTo(cx + s, cy)
        lineTo(cx + s * 0.45f, cy)
        lineTo(cx + s * 0.45f, cy + s * 0.8f)
        lineTo(cx - s * 0.45f, cy + s * 0.8f)
        lineTo(cx - s * 0.45f, cy)
        lineTo(cx - s, cy)
        close()
    }
    if (filled) drawPath(path, ink) else drawPath(path, ink, style = line())
    if (locked) {
        drawLine(ink, Offset(cx - s * 0.45f, cy + s * 1.25f), Offset(cx + s * 0.45f, cy + s * 1.25f), strokeWidth = 1.6.dp.toPx())
    }
}

private fun DrawScope.drawBackspace(ink: Color) {
    val h = 9.dp.toPx()
    val w = 13.dp.toPx()
    val cx = size.width / 2
    val cy = size.height / 2
    val left = cx - w
    val path = Path().apply {
        moveTo(left, cy)
        lineTo(left + h, cy - h)
        lineTo(cx + w, cy - h)
        lineTo(cx + w, cy + h)
        lineTo(left + h, cy + h)
        close()
    }
    drawPath(path, ink, style = line())
    val xc = cx + w * 0.25f
    val x = 3.5.dp.toPx()
    drawLine(ink, Offset(xc - x, cy - x), Offset(xc + x, cy + x), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
    drawLine(ink, Offset(xc - x, cy + x), Offset(xc + x, cy - x), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
}

private fun DrawScope.drawEnter(ink: Color) {
    val cx = size.width / 2
    val cy = size.height / 2
    val w = 11.dp.toPx()
    val h = 7.dp.toPx()
    val a = 4.dp.toPx()
    val path = Path().apply {
        moveTo(cx + w, cy - h)
        lineTo(cx + w, cy + h * 0.5f)
        lineTo(cx - w, cy + h * 0.5f)
    }
    drawPath(path, ink, style = line())
    drawLine(ink, Offset(cx - w, cy + h * 0.5f), Offset(cx - w + a, cy + h * 0.5f - a), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
    drawLine(ink, Offset(cx - w, cy + h * 0.5f), Offset(cx - w + a, cy + h * 0.5f + a), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
}

private fun DrawScope.drawSearch(ink: Color) {
    val r = 6.dp.toPx()
    val c = Offset(size.width / 2 + 2.dp.toPx(), size.height / 2 - 2.dp.toPx())
    drawCircle(ink, r, c, style = line())
    val d = r * 0.707f
    drawLine(ink, Offset(c.x - d, c.y + d), Offset(c.x - d - 6.dp.toPx(), c.y + d + 6.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
}

private fun DrawScope.drawSmiley(ink: Color) {
    val r = 10.dp.toPx()
    val c = Offset(size.width / 2, size.height / 2)
    drawCircle(ink, r, c, style = line())
    val eye = 1.4.dp.toPx()
    drawCircle(ink, eye, Offset(c.x - r * 0.38f, c.y - r * 0.25f))
    drawCircle(ink, eye, Offset(c.x + r * 0.38f, c.y - r * 0.25f))
    drawArc(
        ink, startAngle = 20f, sweepAngle = 140f, useCenter = false,
        topLeft = Offset(c.x - r * 0.55f, c.y - r * 0.35f), size = Size(r * 1.1f, r * 1.0f), style = line(),
    )
}
