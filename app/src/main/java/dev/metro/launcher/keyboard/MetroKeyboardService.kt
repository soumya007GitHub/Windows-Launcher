package dev.metro.launcher.keyboard

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.SystemClock
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import dev.metro.launcher.data.LauncherStore
import dev.metro.launcher.ui.OverlayLifecycleOwner
import dev.metro.launcher.ui.isDark

enum class KeyboardPage { Letters, Symbols1, Symbols2, Emoji }

/** What the bottom-right key does in the current text field. */
enum class EnterKind { NewLine, Go, Search, Send, Next, Done }

/** Field-specific key next to space: "," normally, "@" in e-mail fields, "/" in web addresses. */
class KeyboardState {
    var page by mutableStateOf(KeyboardPage.Letters)
    var shift by mutableStateOf(false)
    var capsLock by mutableStateOf(false)
    var enter by mutableStateOf(EnterKind.NewLine)
    var altKey by mutableStateOf(",")
    var emojiTab by mutableIntStateOf(1) // 0 = recent
}

/** Things the keyboard UI asks the service to do. */
interface KeyboardActions {
    fun type(text: String)
    fun backspace()
    fun enter()
    fun shiftTapped()
    fun emojiTyped(emoji: String)
    fun showPicker()
    fun keyFeedback(view: View)
}

/**
 * The Metro keyboard: a Windows Phone 8 style input method with letters, two symbol pages and
 * emoji. Typing goes through the standard InputConnection; nothing typed is stored, except the
 * list of recently used emoji (kept on the phone only).
 */
class MetroKeyboardService : InputMethodService(), KeyboardActions {

    val state = KeyboardState()
    private var owner: OverlayLifecycleOwner? = null
    private var lastShiftTapAt = 0L
    private var lastSpaceAt = 0L

    override fun onCreateInputView(): View {
        owner?.destroy()
        val lifecycle = OverlayLifecycleOwner().also { it.start() }
        owner = lifecycle
        window.window?.decorView?.let { lifecycle.attachTo(it) }
        return ComposeView(this).apply {
            lifecycle.attachTo(this)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val store = LauncherStore.get(this@MetroKeyboardService)
                val settings by store.settings.collectAsState()
                val phoneDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
                MetroKeyboard(
                    state = state,
                    accent = settings.accent.color,
                    dark = settings.theme.isDark(phoneDark),
                    actions = this@MetroKeyboardService,
                )
            }
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val klass = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        state.page = when (klass) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> KeyboardPage.Symbols1
            else -> if (restarting && state.page == KeyboardPage.Emoji) KeyboardPage.Emoji else KeyboardPage.Letters
        }
        state.altKey = when {
            klass == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) -> "@"
            klass == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_URI -> "/"
            else -> ","
        }
        state.enter = enterKind(info)
        state.capsLock = false
        updateAutoCaps()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        updateAutoCaps()
    }

    override fun onDestroy() {
        owner?.destroy()
        owner = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ actions

    override fun type(text: String) {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        if (text == " ") {
            // WP: double-tap space after a word ends the sentence.
            val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
            if (now - lastSpaceAt < DOUBLE_SPACE_MS && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                lastSpaceAt = 0L
                updateAutoCaps()
                return
            }
            lastSpaceAt = now
        } else {
            lastSpaceAt = 0L
        }
        val out = if (state.page == KeyboardPage.Letters && (state.shift || state.capsLock)) text.uppercase() else text
        ic.commitText(out, 1)
        if (state.shift && !state.capsLock) state.shift = false
        updateAutoCaps()
    }

    override fun emojiTyped(emoji: String) {
        currentInputConnection?.commitText(emoji, 1)
        Emoji.remember(this, emoji)
        lastSpaceAt = 0L
    }

    override fun backspace() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            // A key event (not deleteSurroundingText) so emoji and other multi-char symbols go in one press.
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
        lastSpaceAt = 0L
        updateAutoCaps()
    }

    override fun enter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (state.enter != EnterKind.NewLine && info != null) {
            ic.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        lastSpaceAt = 0L
        updateAutoCaps()
    }

    override fun shiftTapped() {
        val now = SystemClock.uptimeMillis()
        when {
            state.capsLock -> { state.capsLock = false; state.shift = false }
            state.shift && now - lastShiftTapAt < DOUBLE_TAP_MS -> state.capsLock = true
            else -> state.shift = !state.shift
        }
        lastShiftTapAt = now
    }

    override fun showPicker() {
        getSystemService(InputMethodManager::class.java).showInputMethodPicker()
    }

    override fun keyFeedback(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        // Follows the phone's "touch sounds" setting.
        getSystemService(AudioManager::class.java).playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
    }

    // ------------------------------------------------------------------ helpers

    private fun updateAutoCaps() {
        if (state.capsLock || state.page != KeyboardPage.Letters) return
        val info = currentInputEditorInfo ?: return
        val caps = currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0
        state.shift = caps != 0
    }

    private fun enterKind(info: EditorInfo): EnterKind {
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return EnterKind.NewLine
        if (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) return EnterKind.NewLine
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> EnterKind.Go
            EditorInfo.IME_ACTION_SEARCH -> EnterKind.Search
            EditorInfo.IME_ACTION_SEND -> EnterKind.Send
            EditorInfo.IME_ACTION_NEXT -> EnterKind.Next
            EditorInfo.IME_ACTION_DONE -> EnterKind.Done
            else -> EnterKind.NewLine
        }
    }

    private companion object {
        const val DOUBLE_SPACE_MS = 600L
        const val DOUBLE_TAP_MS = 350L
    }
}
