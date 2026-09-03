package app.slimboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.widget.Toast
import androidx.core.content.FileProvider
import app.slimboard.clipboard.ClipItem
import app.slimboard.clipboard.ClipboardPanel
import app.slimboard.clipboard.ClipboardStore
import app.slimboard.emoji.EmojiData
import app.slimboard.emoji.EmojiPanelView
import app.slimboard.layout.LayoutRepository
import app.slimboard.settings.Prefs
import app.slimboard.settings.SettingsActivity
import app.slimboard.text.EditHistory
import app.slimboard.text.SuggestionEngine
import app.slimboard.theme.KeyboardTheme
import app.slimboard.ui.EditPanelView
import app.slimboard.ui.InputViewContainer
import app.slimboard.ui.SideBarView
import app.slimboard.ui.ToolbarView
import app.slimboard.ui.keyboard.KeyboardView

/**
 * The input method. Owns the InputConnection side of typing (composing word, suggestions,
 * autocorrect with one-tap revert, shortcuts, undo, auto-cap, double-space period, layer per field
 * type, enter action, insets), the toolbar and its panels, clipboard capture and paste, emoji
 * search, and one-handed mode.
 */
class SlimBoardService :
    InputMethodService(),
    KeyboardView.Listener,
    ToolbarView.Listener,
    EmojiPanelView.Listener,
    ClipboardPanel.Listener,
    EditPanelView.Listener,
    SideBarView.Listener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: Prefs
    private lateinit var layouts: LayoutRepository
    private lateinit var store: ClipboardStore
    private lateinit var clipboard: ClipboardManager
    private lateinit var engine: SuggestionEngine
    private val handler = Handler(Looper.getMainLooper())
    private val history = EditHistory()

    private var container: InputViewContainer? = null
    private var keyboardView: KeyboardView? = null
    private var toolbar: ToolbarView? = null
    private var clipboardPanel: ClipboardPanel? = null
    private var emojiPanel: EmojiPanelView? = null
    private var editPanel: EditPanelView? = null

    private var editor: EditorInfo? = null
    private var lettersLayer = LayoutRepository.QWERTY
    private var currentLayer = LayoutRepository.QWERTY
    private var variant = LayoutRepository.Variant.NONE
    private var isPassword = false
    private var suggestionsForField = false
    private var shortcuts: Map<String, String> = emptyMap()

    /** Field asked for no personalised learning (incognito), user forced it, or the app is excluded. */
    var noLearning = false
        private set

    private var lastSpaceAt = 0L
    private var showRequestedAt = 0L
    private val windowLocation = IntArray(2)

    // Word being composed (underlined in the app) and its suggestions.
    private val composing = StringBuilder()
    private var suggestionGeneration = 0
    private var latestResult: SuggestionEngine.Result? = null
    private class AutoCorrection(val typed: String, val applied: String, val separator: String)
    private var lastAutoCorrection: AutoCorrection? = null

    // Emoji search: non-null while the keyboard is typing into the search strip instead of the app.
    private var emojiQuery: StringBuilder? = null

    // Clipboard capture
    private var lastClipTimestamp = 0L
    private var ignoreNextClipChange = false
    private var chipItem: ClipItem? = null
    private val hideChip = Runnable { chipItem = null; toolbar?.chipText = null }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { ingestPrimaryClip() }

    // ---- Lifecycle ----

    override fun onCreate() {
        val start = SystemClock.uptimeMillis()
        super.onCreate()
        prefs = Prefs(this)
        prefs.registerListener(this)
        layouts = LayoutRepository(assets)
        store = ClipboardStore.get(this)
        clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.addPrimaryClipChangedListener(clipListener)
        engine = SuggestionEngine(this)
        shortcuts = prefs.shortcuts
        Log.d(TAG, "onCreate took ${SystemClock.uptimeMillis() - start} ms")
    }

    override fun onDestroy() {
        clipboard.removePrimaryClipChangedListener(clipListener)
        prefs.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val start = SystemClock.uptimeMillis()
        val keyboard = KeyboardView(this, this)
        val bar = ToolbarView(this, this)
        val clips = ClipboardPanel(this, store, this)
        val emoji = EmojiPanelView(this, prefs, this)
        val edit = EditPanelView(this, this)
        val side = SideBarView(this, this)
        val root = InputViewContainer(
            this, keyboard, bar,
            mapOf(ToolbarView.Panel.CLIPBOARD to clips, ToolbarView.Panel.EMOJI to emoji, ToolbarView.Panel.EDIT to edit),
            side,
        )
        keyboardView = keyboard
        toolbar = bar
        clipboardPanel = clips
        emojiPanel = emoji
        editPanel = edit
        container = root
        applyAppearance()
        Log.d(TAG, "onCreateInputView took ${SystemClock.uptimeMillis() - start} ms")
        return root
    }

    private fun applyAppearance() {
        val theme = KeyboardTheme.resolve(this, prefs.themeMode, prefs.dynamicColor)
        keyboardView?.setTheme(theme)
        keyboardView?.setConfig(prefs.keyboardConfig())
        toolbar?.theme = theme
        clipboardPanel?.theme = theme
        clipboardPanel?.expiryHours = prefs.clipboardExpiryHours
        emojiPanel?.theme = theme
        editPanel?.theme = theme
        container?.let {
            // The strip is needed for suggestions even when the user hides the toolbar buttons.
            it.toolbarVisible = prefs.toolbar || prefs.suggestions
            it.oneHandedRight = prefs.oneHandedRight
            it.oneHanded = prefs.oneHanded
            it.sideBarTheme(theme)
        }
    }

    private fun InputViewContainer.sideBarTheme(theme: KeyboardTheme) {
        // SideBarView is owned by the container; theme it through the child list.
        for (i in 0 until childCount) (getChildAt(i) as? SideBarView)?.theme = theme
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyAppearance()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key in Prefs.INTERNAL_KEYS) return
        if (key == Prefs.SHORTCUTS) { shortcuts = prefs.shortcuts; return }
        applyAppearance()
        keyboardView?.setLayout(layouts.get(currentLayer, prefs.numberRow, variant))
        updateAutoShift()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        showRequestedAt = SystemClock.uptimeMillis()
        editor = info
        analyze(info)
        rememberApp(info)
        lastSpaceAt = 0
        if (!restarting) history.clear()
        resetComposing()

        val view = keyboardView ?: return
        view.keyPreviewAllowed = !isPassword
        view.enterLabel = enterLabelFor(info)
        // On a restart the same field continues with changed text (e.g. after a paste): keep the
        // open panel, the current layer and any emoji search. Only a new field resets them.
        if (!restarting) {
            exitEmojiSearch()
            showPanel(ToolbarView.Panel.NONE)
            currentLayer = lettersLayer
            view.resetShift()
        }
        view.setLayout(layouts.get(currentLayer, prefs.numberRow, variant))
        updateAutoShift()
        view.onNextDraw = {
            Log.d(TAG, "show -> first draw: ${SystemClock.uptimeMillis() - showRequestedAt} ms (restarting=$restarting)")
        }

        if (suggestionsForField) {
            engine.ensureLoaded()
            // Emoji names double as suggestions ("fire" → 🔥), so the list is needed here too.
            EmojiData.ensureLoaded(this) { }
        }

        if (prefs.clipboardEnabled) {
            store.expire(prefs.clipboardExpiryHours * 3_600_000L)
            ingestPrimaryClip()
            refreshChip()
        } else {
            hideChip.run()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        keyboardView?.cancelTouches()
        finishComposingInApp()
        resetComposing()
        lastSpaceAt = 0
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // The user moved the cursor away from the word we are composing: stop composing it.
        if (composing.isNotEmpty() && (candidatesEnd < 0 || newSelEnd != candidatesEnd || newSelStart != newSelEnd)) {
            resetComposing()
        }
        updateAutoShift()
    }

    /** Never take over the whole screen in landscape. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    /**
     * The input view has a transparent headroom strip for popups above the toolbar. Tell the system
     * where the visible keyboard really starts so apps are not pushed up by it and touches there
     * reach the app.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val root = container ?: return
        if (!isInputViewShown) return
        root.getLocationInWindow(windowLocation)
        val top = windowLocation[1] + root.contentTop
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(
            windowLocation[0], top,
            windowLocation[0] + root.width, windowLocation[1] + root.height,
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
            (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0 ||
            (info.packageName != null && info.packageName in prefs.noLearnApps)

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

        val noSuggestionsFlag = info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
        val plainText = cls == InputType.TYPE_CLASS_TEXT && variant == LayoutRepository.Variant.NONE &&
            variation != InputType.TYPE_TEXT_VARIATION_FILTER
        suggestionsForField = prefs.suggestions && plainText && !isPassword && !noSuggestionsFlag
    }

    /** Keeps a short list of apps the keyboard has been used in, so settings can offer per-app toggles. */
    private fun rememberApp(info: EditorInfo) {
        val pkg = info.packageName ?: return
        if (pkg == packageName) return
        val seen = prefs.appsSeen
        if (seen.containsKey(pkg)) return
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
        val updated = LinkedHashMap(seen)
        updated[pkg] = label
        while (updated.size > 30) updated.remove(updated.keys.first())
        prefs.appsSeen = updated
    }

    private fun enterLabelFor(info: EditorInfo): String {
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return KeyboardView.DEFAULT_ENTER_LABEL
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_SEARCH -> "Search"
            EditorInfo.IME_ACTION_SEND -> "Send"
            EditorInfo.IME_ACTION_NEXT -> "Next"
            EditorInfo.IME_ACTION_DONE -> "Done"
            EditorInfo.IME_ACTION_PREVIOUS -> "Prev"
            else -> KeyboardView.DEFAULT_ENTER_LABEL
        }
    }

    // ---- Auto-capitalisation ----

    private fun updateAutoShift() {
        val view = keyboardView ?: return
        if (emojiQuery != null || !LayoutRepository.isLetters(currentLayer) || isPassword || !prefs.autoCap) {
            view.setAutoShift(false)
            return
        }
        // While composing, the field's caps mode is stale (it describes the position before the word).
        if (composing.isNotEmpty()) {
            view.setAutoShift(false)
            return
        }
        val ic = currentInputConnection ?: return
        val info = editor ?: return
        // Honours the field's TYPE_TEXT_FLAG_CAP_* flags; fields without them never auto-capitalise.
        val mode = ic.getCursorCapsMode(info.inputType)
        view.setAutoShift(mode != 0)
    }

    // ---- Composing, suggestions, autocorrect, shortcuts ----

    private fun isWordChar(text: String): Boolean {
        if (text.length != 1) return false
        val c = text[0]
        return c.isLetter() || (c == '\'' && composing.isNotEmpty())
    }

    private fun resetComposing() {
        composing.setLength(0)
        latestResult = null
        toolbar?.setSuggestions("", emptyList(), -1)
    }

    /** Ends the composing region in the app without changing its text. */
    private fun finishComposingInApp() {
        if (composing.isNotEmpty()) currentInputConnection?.finishComposingText()
    }

    private fun appendToComposing(text: String) {
        val ic = currentInputConnection ?: return
        composing.append(text)
        ic.setComposingText(composing, 1)
        requestSuggestions()
    }

    private fun requestSuggestions() {
        val typed = composing.toString()
        val gen = ++suggestionGeneration
        engine.suggestAsync(typed, gen) { g, result ->
            if (g != suggestionGeneration || composing.toString() != result.typed) return@suggestAsync
            latestResult = result
            showSuggestions(result)
        }
    }

    private fun shortcutFor(typed: String): String? = shortcuts[typed.lowercase()]

    /** Items shown in the strip: shortcut expansion first if any, then engine suggestions, then emoji. */
    private fun stripItems(result: SuggestionEngine.Result): Pair<List<String>, Int> {
        val items = ArrayList<String>(4)
        var highlight: Int
        val expansion = shortcutFor(result.typed)
        if (expansion != null) {
            items.add(result.typed)
            items.add(expansion)
            result.suggestions.firstOrNull { it != result.typed }?.let { items.add(it) }
            highlight = 1
        } else {
            items.addAll(result.suggestions)
            highlight = when {
                items.isEmpty() -> -1
                result.autoCorrect != null && prefs.autocorrect -> items.indexOf(result.autoCorrect).takeIf { it >= 0 } ?: 1
                items.size >= 2 -> 1
                else -> 0
            }
            if (highlight >= items.size) highlight = items.size - 1
        }
        EmojiData.exact(result.typed)?.let { if (items.size < 4) items.add(it) }
        return items to highlight
    }

    private fun showSuggestions(result: SuggestionEngine.Result) {
        val (items, highlight) = stripItems(result)
        toolbar?.setSuggestions(result.typed, items, highlight)
    }

    /**
     * Commits the composed word followed by [separator]. A text shortcut always expands; otherwise
     * autocorrect applies when enabled and confident. Both are undoable, and a correction is also
     * reverted by one backspace.
     */
    private fun commitComposing(separator: String) {
        val ic = currentInputConnection ?: return
        val typed = composing.toString()
        if (typed.isEmpty()) return
        val expansion = shortcutFor(typed)
        val result = if (expansion == null) latestResult?.takeIf { it.typed == typed } ?: engine.suggestSync(typed) else null
        val auto = if (prefs.autocorrect) result?.autoCorrect else null
        ic.beginBatchEdit()
        when {
            expansion != null -> {
                ic.commitText(expansion + separator, 1)
                history.record(expansion + separator, typed)
                lastAutoCorrection = AutoCorrection(typed, expansion, separator)
            }
            auto != null && auto != typed -> {
                ic.commitText(auto + separator, 1)
                history.record(auto + separator, typed)
                lastAutoCorrection = AutoCorrection(typed, auto, separator)
            }
            else -> {
                ic.commitText(typed + separator, 1)
                history.record(typed + separator)
                lastAutoCorrection = null
                learn(typed)
            }
        }
        ic.endBatchEdit()
        resetComposing()
    }

    private fun learn(word: String) {
        if (!prefs.learnWords || noLearning) return
        if (word.length < 2 || !word.all { it.isLetter() || it == '\'' }) return
        if (engine.isWord(word)) return
        engine.learn(word)
    }

    /** Backspace right after an autocorrect or shortcut restores what was typed. */
    private fun revertAutoCorrection(ac: AutoCorrection) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(ac.applied.length + ac.separator.length, 0)
        composing.setLength(0)
        composing.append(ac.typed)
        ic.setComposingText(composing, 1)
        ic.endBatchEdit()
        lastAutoCorrection = null
        if (shortcutFor(ac.typed) == null && prefs.learnWords && !noLearning) engine.learn(ac.typed, force = true)
        requestSuggestions()
    }

    // ---- Panels ----

    private fun showPanel(which: ToolbarView.Panel) {
        val root = container ?: return
        if (which != ToolbarView.Panel.NONE) exitEmojiSearch()
        root.showPanel(which)
        when (which) {
            ToolbarView.Panel.CLIPBOARD -> {
                store.expire(prefs.clipboardExpiryHours * 3_600_000L)
                clipboardPanel?.refresh()
            }
            ToolbarView.Panel.EMOJI -> {
                emojiPanel?.onShown()
                EmojiData.ensureLoaded(this) { groups -> emojiPanel?.setData(groups) }
            }
            ToolbarView.Panel.EDIT -> {
                finishComposingInApp()
                resetComposing()
                editPanel?.resetSelection()
            }
            ToolbarView.Panel.NONE -> Unit
        }
    }

    private fun togglePanel(which: ToolbarView.Panel) {
        val current = container?.panel ?: return
        showPanel(if (current == which) ToolbarView.Panel.NONE else which)
    }

    // ---- Emoji search (typing goes to the toolbar strip) ----

    private fun enterEmojiSearch() {
        finishComposingInApp()
        resetComposing()
        showPanel(ToolbarView.Panel.NONE)
        emojiQuery = StringBuilder()
        toolbar?.setSearch(true)
        EmojiData.ensureLoaded(this) { updateEmojiSearch() }
        keyboardView?.setAutoShift(false)
    }

    private fun exitEmojiSearch() {
        if (emojiQuery == null) return
        emojiQuery = null
        toolbar?.setSearch(false)
        updateAutoShift()
    }

    private fun updateEmojiSearch() {
        val q = emojiQuery?.toString() ?: return
        toolbar?.setSearch(true, q, EmojiData.search(q).map { it.chars })
    }

    private fun commitEmoji(emoji: String) {
        finishComposingInApp()
        resetComposing()
        currentInputConnection?.commitText(emoji, 1)
        history.record(emoji)
        lastSpaceAt = 0
        lastAutoCorrection = null
    }

    // ---- Clipboard capture ----

    private fun ingestPrimaryClip() {
        if (!prefs.clipboardEnabled) return
        if (ignoreNextClipChange) { ignoreNextClipChange = false; return }
        try {
            val desc = clipboard.primaryClipDescription ?: return
            val ts = desc.timestamp
            if (ts != 0L && ts == lastClipTimestamp) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                desc.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
            ) { lastClipTimestamp = ts; return }
            val clip = clipboard.primaryClip ?: return
            if (clip.itemCount == 0) return
            lastClipTimestamp = ts
            val item = clip.getItemAt(0)
            val createdAt = if (ts != 0L) ts else System.currentTimeMillis()

            val uri = item.uri
            val mime = uri?.let { contentResolver.getType(it) }
            if (uri != null && mime != null && mime.startsWith("image/")) {
                if (!prefs.clipboardImages) return
                val maxBytes = prefs.clipboardMaxImageMb * 1024L * 1024L
                store.addImageAsync(contentResolver, uri, mime, createdAt, maxBytes) { added ->
                    if (added != null) showChip(added)
                }
                return
            }
            val text = item.coerceToText(this)?.toString() ?: return
            store.addText(text, createdAt)?.let { showChip(it) }
        } catch (e: SecurityException) {
            // Not the focused/default IME right now; the clip will be picked up on the next show.
        }
    }

    private fun showChip(item: ClipItem) {
        chipItem = item
        refreshChip()
    }

    private fun refreshChip() {
        val item = chipItem
        val age = if (item != null) System.currentTimeMillis() - item.createdAt else Long.MAX_VALUE
        handler.removeCallbacks(hideChip)
        if (item == null || age > CHIP_LIFETIME_MS) {
            hideChip.run()
            return
        }
        toolbar?.chipText = item.preview()
        handler.postDelayed(hideChip, CHIP_LIFETIME_MS - age)
    }

    private fun pasteImage(item: ClipItem) {
        val ic = currentInputConnection ?: return
        val info = editor ?: return
        val file = item.imageFile ?: return
        finishComposingInApp()
        resetComposing()
        val uri = FileProvider.getUriForFile(this, "$packageName.clips", file)
        val description = ClipDescription("SlimBoard image", arrayOf(item.mime))

        val accepted = info.contentMimeTypes?.any { ClipDescription.compareMimeTypes(item.mime, it) } == true
        if (accepted) {
            val target = info.packageName
            if (target != null) grantUriPermission(target, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val ok = try {
                ic.commitContent(InputContentInfo(uri, description), InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
            } catch (e: Exception) { false }
            if (ok) return
        }
        // Fallback: hand it to the system clipboard so a long-press → Paste in the app works.
        ignoreNextClipChange = true
        clipboard.setPrimaryClip(ClipData.newUri(contentResolver, "SlimBoard image", uri))
        Toast.makeText(this, "This app doesn't accept images here. Long-press the field and choose Paste.", Toast.LENGTH_LONG).show()
    }

    // ---- Key events with modifiers (editing panel) ----

    private fun sendKey(keyCode: Int, meta: Int = 0) {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    private fun shiftMeta(selecting: Boolean) =
        if (selecting) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0

    // ---- KeyboardView.Listener ----

    override fun onText(text: String) {
        emojiQuery?.let { it.append(text); updateEmojiSearch(); return }
        lastAutoCorrection = null
        lastSpaceAt = 0
        if (suggestionsForField && isWordChar(text)) {
            appendToComposing(text)
            keyboardView?.setAutoShift(false)
            return
        }
        if (composing.isNotEmpty()) {
            commitComposing(text)
        } else {
            currentInputConnection?.commitText(text, 1)
        }
        updateAutoShift()
    }

    override fun onSpace() {
        emojiQuery?.let { if (it.isNotEmpty() && it.last() != ' ') it.append(' '); updateEmojiSearch(); return }
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        if (composing.isNotEmpty()) {
            commitComposing(" ")
            lastSpaceAt = now
            updateAutoShift()
            return
        }
        lastAutoCorrection = null
        if (prefs.doubleSpacePeriod && now - lastSpaceAt < DOUBLE_SPACE_MS && canInsertPeriod(ic)) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            ic.endBatchEdit()
            history.record(". ", " ")
            lastSpaceAt = 0
        } else {
            ic.commitText(" ", 1)
            lastSpaceAt = now
        }
        updateAutoShift()
    }

    /** "word␣" + space → "word. " but never after another space. */
    private fun canInsertPeriod(ic: InputConnection): Boolean {
        val before = ic.getTextBeforeCursor(3, 0) ?: return false
        if (before.length < 2 || before[before.length - 1] != ' ') return false
        val c = before[before.length - 2]
        return c.isLetterOrDigit() || c in ")]}\"'”’"
    }

    override fun onBackspace() {
        emojiQuery?.let { q ->
            if (q.isEmpty()) exitEmojiSearch() else { q.setLength(q.offsetByCodePoints(q.length, -1)); updateEmojiSearch() }
            return
        }
        lastSpaceAt = 0
        lastAutoCorrection?.let { revertAutoCorrection(it); return }
        if (composing.isNotEmpty()) {
            val ic = currentInputConnection ?: return
            composing.setLength(composing.length - 1)
            if (composing.isEmpty()) {
                ic.commitText("", 1)
                resetComposing()
                updateAutoShift()
            } else {
                ic.setComposingText(composing, 1)
                requestSuggestions()
            }
            return
        }
        // A DEL key event respects selections and works in apps whose InputConnection mishandles
        // deleteSurroundingText. onUpdateSelection refreshes auto-shift afterwards.
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    override fun onDeleteWord() {
        emojiQuery?.let { it.setLength(0); updateEmojiSearch(); return }
        val ic = currentInputConnection ?: return
        lastAutoCorrection = null
        if (composing.isNotEmpty()) {
            ic.commitText("", 1)
            resetComposing()
            updateAutoShift()
            return
        }
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
            history.record("", before.substring(start))
        }
        lastSpaceAt = 0
        updateAutoShift()
    }

    override fun onEnter() {
        emojiQuery?.let {
            EmojiData.search(it.toString(), 1).firstOrNull()?.let { e -> commitEmoji(e.chars) }
            exitEmojiSearch()
            return
        }
        if (composing.isNotEmpty()) commitComposing("")
        lastAutoCorrection = null
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
        if (emojiQuery != null) return
        finishComposingInApp()
        resetComposing()
        lastAutoCorrection = null
        val keyCode = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(steps)) { sendDownUpKeyEvents(keyCode) }
    }

    // ---- ToolbarView.Listener ----

    override fun onBack() {
        if (emojiQuery != null) exitEmojiSearch() else showPanel(ToolbarView.Panel.NONE)
    }

    override fun onClipboard() = togglePanel(ToolbarView.Panel.CLIPBOARD)

    override fun onEmoji() = togglePanel(ToolbarView.Panel.EMOJI)

    override fun onEdit() = togglePanel(ToolbarView.Panel.EDIT)

    override fun onOneHanded() {
        prefs.oneHanded = !prefs.oneHanded   // the prefs listener re-applies appearance
    }

    override fun onSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        requestHideSelf(0)
    }

    override fun onChip() {
        val item = chipItem ?: return
        when (item.type) {
            ClipItem.Type.TEXT -> onPasteText(item.text)
            ClipItem.Type.IMAGE -> pasteImage(item)
        }
        hideChip.run()
    }

    override fun onSearchResult(emoji: String) {
        commitEmoji(emoji)
        exitEmojiSearch()
    }

    override fun onSuggestion(index: Int) {
        val ic = currentInputConnection ?: return
        val result = latestResult ?: return
        val (items, _) = stripItems(result)
        val chosen = items.getOrNull(index) ?: return
        val isEmoji = EmojiData.exact(result.typed) == chosen && index == items.lastIndex && chosen != result.typed
        val text = if (isEmoji) chosen else "$chosen "
        ic.commitText(text, 1)
        history.record(text, result.typed)
        if (!isEmoji && shortcutFor(result.typed) == null) learn(chosen)
        lastAutoCorrection = null
        lastSpaceAt = if (isEmoji) 0 else SystemClock.uptimeMillis()
        resetComposing()
        updateAutoShift()
    }

    // ---- EmojiPanelView.Listener ----

    override fun onEmoji(emoji: String) = commitEmoji(emoji)

    override fun onAbc() = showPanel(ToolbarView.Panel.NONE)

    override fun onSearch() = enterEmojiSearch()

    // ---- ClipboardPanel.Listener ----

    override fun onPasteText(text: String) {
        finishComposingInApp()
        resetComposing()
        currentInputConnection?.commitText(text, 1)
        history.record(text)
        lastSpaceAt = 0
        lastAutoCorrection = null
        updateAutoShift()
    }

    override fun onPasteImage(item: ClipItem) = pasteImage(item)

    // ---- EditPanelView.Listener ----

    override fun onMove(dx: Int, dy: Int, selecting: Boolean) {
        val code = when {
            dx < 0 -> KeyEvent.KEYCODE_DPAD_LEFT
            dx > 0 -> KeyEvent.KEYCODE_DPAD_RIGHT
            dy < 0 -> KeyEvent.KEYCODE_DPAD_UP
            else -> KeyEvent.KEYCODE_DPAD_DOWN
        }
        sendKey(code, shiftMeta(selecting))
    }

    override fun onHome(selecting: Boolean) = sendKey(KeyEvent.KEYCODE_MOVE_HOME, shiftMeta(selecting))

    override fun onEnd(selecting: Boolean) = sendKey(KeyEvent.KEYCODE_MOVE_END, shiftMeta(selecting))

    /** Context-menu actions first (EditText), Ctrl shortcuts as fallback (Compose, WebView). */
    private fun contextAction(id: Int, keyCode: Int) {
        val ic = currentInputConnection ?: return
        if (!ic.performContextMenuAction(id)) sendKey(keyCode, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
    }

    override fun onSelectAll() = contextAction(android.R.id.selectAll, KeyEvent.KEYCODE_A)

    override fun onCut() = contextAction(android.R.id.cut, KeyEvent.KEYCODE_X)

    override fun onCopy() = contextAction(android.R.id.copy, KeyEvent.KEYCODE_C)

    override fun onPaste() = contextAction(android.R.id.paste, KeyEvent.KEYCODE_V)

    override fun onUndo() {
        val ic = currentInputConnection ?: return
        if (!history.undo(ic)) Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show()
        lastAutoCorrection = null
    }

    override fun onRedo() {
        val ic = currentInputConnection ?: return
        if (!history.redo(ic)) Toast.makeText(this, "Nothing to redo", Toast.LENGTH_SHORT).show()
    }

    override fun onEditBackspace() = sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)

    override fun onEditAbc() = showPanel(ToolbarView.Panel.NONE)

    // ---- SideBarView.Listener ----

    override fun onSwitchSide() { prefs.oneHandedRight = !prefs.oneHandedRight }

    override fun onExitOneHanded() { prefs.oneHanded = false }

    private companion object {
        const val TAG = "SlimBoard"
        const val DOUBLE_SPACE_MS = 600L
        const val CHIP_LIFETIME_MS = 60_000L
    }
}
