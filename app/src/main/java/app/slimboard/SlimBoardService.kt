package app.slimboard

import android.content.SharedPreferences
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import app.slimboard.layout.LayoutRepository
import app.slimboard.settings.Prefs
import app.slimboard.theme.KeyboardTheme
import app.slimboard.ui.keyboard.KeyboardView

/**
 * The input method. Owns the InputConnection side of typing: committing text, auto-capitalisation,
 * double-space period, layer selection per field type, enter-key action, and insets.
 */
class SlimBoardService : InputMethodService(), KeyboardView.Listener, SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: Prefs
    private lateinit var layouts: LayoutRepository
    private var keyboardView: KeyboardView? = null

    private var editor: EditorInfo? = null
    private var lettersLayer = LayoutRepository.QWERTY
    private var currentLayer = LayoutRepository.QWERTY
    private var variant = LayoutRepository.Variant.NONE
    private var isPassword = false

    /** Field asked for no personalised learning (incognito) or user forced it. Used from Phase 3. */
    var noLearning = false
        private set

    private var lastSpaceAt = 0L
    private var showRequestedAt = 0L
    private val windowLocation = IntArray(2)

    override fun onCreate() {
        val start = SystemClock.uptimeMillis()
        super.onCreate()
        prefs = Prefs(this)
        prefs.registerListener(this)
        layouts = LayoutRepository(assets)
        Log.d(TAG, "onCreate took ${SystemClock.uptimeMillis() - start} ms")
    }

    override fun onDestroy() {
        prefs.unregisterListener(this)
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val start = SystemClock.uptimeMillis()
        val view = KeyboardView(this, this)
        keyboardView = view
        applyAppearance(view)
        Log.d(TAG, "onCreateInputView took ${SystemClock.uptimeMillis() - start} ms")
        return view
    }

    private fun applyAppearance(view: KeyboardView) {
        view.setTheme(KeyboardTheme.resolve(this, prefs.themeMode, prefs.dynamicColor))
        view.setConfig(prefs.keyboardConfig())
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        keyboardView?.let { applyAppearance(it) }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        val view = keyboardView ?: return
        applyAppearance(view)
        view.setLayout(layouts.get(currentLayer, prefs.numberRow, variant))
        updateAutoShift()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        showRequestedAt = SystemClock.uptimeMillis()
        editor = info
        analyze(info)
        lastSpaceAt = 0

        val view = keyboardView ?: return
        view.keyPreviewAllowed = !isPassword
        view.enterLabel = enterLabelFor(info)
        currentLayer = lettersLayer
        view.setLayout(layouts.get(currentLayer, prefs.numberRow, variant))
        view.resetShift()
        updateAutoShift()
        view.onNextDraw = {
            Log.d(TAG, "show -> first draw: ${SystemClock.uptimeMillis() - showRequestedAt} ms (restarting=$restarting)")
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        keyboardView?.cancelTouches()
        lastSpaceAt = 0
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        updateAutoShift()
    }

    /** Never take over the whole screen in landscape. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    /**
     * The keyboard view has a transparent headroom strip for popups. Tell the system the keyboard
     * really starts below it so apps are not pushed up by it and touches there reach the app.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val view = keyboardView ?: return
        if (!isInputViewShown) return
        view.getLocationInWindow(windowLocation)
        val top = windowLocation[1] + view.keyboardTop
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(
            windowLocation[0], top,
            windowLocation[0] + view.width, windowLocation[1] + view.height,
        )
    }

    // ---- Field analysis ----

    private fun analyze(info: EditorInfo) {
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION

        isPassword = when (cls) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
        noLearning = isPassword || prefs.incognito ||
            (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

        lettersLayer = when (cls) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME -> LayoutRepository.NUMPAD
            InputType.TYPE_CLASS_PHONE -> LayoutRepository.PHONE
            else -> LayoutRepository.QWERTY
        }
        variant = when {
            cls != InputType.TYPE_CLASS_TEXT -> LayoutRepository.Variant.NONE
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> LayoutRepository.Variant.EMAIL
            variation == InputType.TYPE_TEXT_VARIATION_URI -> LayoutRepository.Variant.URL
            else -> LayoutRepository.Variant.NONE
        }
    }

    private fun enterLabelFor(info: EditorInfo): String {
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return "↵"
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_SEARCH -> "Search"
            EditorInfo.IME_ACTION_SEND -> "Send"
            EditorInfo.IME_ACTION_NEXT -> "Next"
            EditorInfo.IME_ACTION_DONE -> "Done"
            EditorInfo.IME_ACTION_PREVIOUS -> "Prev"
            else -> "↵"
        }
    }

    // ---- Auto-capitalisation ----

    private fun updateAutoShift() {
        val view = keyboardView ?: return
        if (!LayoutRepository.isLetters(currentLayer) || isPassword || !prefs.autoCap) {
            view.setAutoShift(false)
            return
        }
        val ic = currentInputConnection ?: return
        val info = editor ?: return
        // Honours the field's TYPE_TEXT_FLAG_CAP_* flags; fields without them never auto-capitalise.
        val mode = ic.getCursorCapsMode(info.inputType)
        view.setAutoShift(mode != 0)
    }

    // ---- KeyboardView.Listener ----

    override fun onText(text: String) {
        currentInputConnection?.commitText(text, 1)
        lastSpaceAt = 0
        updateAutoShift()
    }

    override fun onSpace() {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        if (prefs.doubleSpacePeriod && now - lastSpaceAt < DOUBLE_SPACE_MS && canInsertPeriod(ic)) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            ic.endBatchEdit()
            lastSpaceAt = 0
        } else {
            ic.commitText(" ", 1)
            lastSpaceAt = now
        }
        updateAutoShift()
    }

    /** "word␣" + space → "word. " but never after another space, digit-only contexts excluded. */
    private fun canInsertPeriod(ic: InputConnection): Boolean {
        val before = ic.getTextBeforeCursor(3, 0) ?: return false
        if (before.length < 2 || before[before.length - 1] != ' ') return false
        val c = before[before.length - 2]
        return c.isLetterOrDigit() || c in ")]}\"'”’"
    }

    override fun onBackspace() {
        // A DEL key event respects selections and works in apps whose InputConnection mishandles
        // deleteSurroundingText. onUpdateSelection refreshes auto-shift afterwards.
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        lastSpaceAt = 0
    }

    override fun onDeleteWord() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(64, 0)
        if (before.isNullOrEmpty()) return
        var end = before.length
        while (end > 0 && before[end - 1].isWhitespace()) end--
        var start = end
        while (start > 0 && !before[start - 1].isWhitespace()) start--
        val count = before.length - start
        if (count <= 0) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        } else {
            ic.deleteSurroundingText(count, 0)
        }
        lastSpaceAt = 0
        updateAutoShift()
    }

    override fun onEnter() {
        val ic = currentInputConnection ?: return
        val options = editor?.imeOptions ?: 0
        val action = options and EditorInfo.IME_MASK_ACTION
        val hasAction = options and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0 &&
            action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        if (hasAction) {
            ic.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        lastSpaceAt = 0
    }

    override fun onLayer(target: String) {
        currentLayer = target
        keyboardView?.setLayout(layouts.get(target, prefs.numberRow, variant))
        updateAutoShift()
    }

    override fun onCursorMove(steps: Int) {
        val keyCode = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(steps)) { sendDownUpKeyEvents(keyCode) }
    }

    private companion object {
        const val TAG = "SlimBoard"
        const val DOUBLE_SPACE_MS = 600L
    }
}
