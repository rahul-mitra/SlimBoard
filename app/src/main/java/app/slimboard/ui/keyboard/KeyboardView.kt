package app.slimboard.ui.keyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import app.slimboard.layout.Key
import app.slimboard.layout.KeyType
import app.slimboard.layout.KeyboardLayout
import app.slimboard.theme.KeyboardTheme
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * The keyboard. One View, one Canvas, no children.
 *
 * Vertical structure (top to bottom):
 *   headroom  – transparent strip where key previews and long-press popups are drawn. The service
 *               excludes it from the IME insets and touchable region, so it never pushes the app
 *               up and touches there fall through to the app.
 *   keys      – rows from the current KeyboardLayout
 *   padding   – user bottom padding + navigation bar inset
 */
class KeyboardView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onText(text: String)
        fun onSpace()
        fun onBackspace()
        fun onDeleteWord()
        fun onEnter()
        fun onLayer(target: String)
        fun onCursorMove(steps: Int)
    }

    enum class ShiftState { OFF, SHIFTED, CAPS_LOCK }

    /** Invoked once after the next onDraw; the service uses it for show-to-draw timing. */
    var onNextDraw: (() -> Unit)? = null

    var shiftState: ShiftState = ShiftState.OFF
        private set
    private var shiftIsAuto = false
    private var lastShiftTapAt = 0L

    var enterLabel: String = DEFAULT_ENTER_LABEL
        set(v) { field = v; invalidate() }

    /** The service switches this off for password fields. Independent of the user setting. */
    var keyPreviewAllowed = true

    private var layout: KeyboardLayout = KeyboardLayout.EMPTY
    private var theme: KeyboardTheme = KeyboardTheme.DARK
    private var config: KeyboardConfig = KeyboardConfig()

    // ---- Geometry (px) ----
    private var rowHeight = 0f
    private var headroom = 0f
    private var topPad = 0f
    private var extraBottom = 0f
    private var bottomInset = 0
    private val keyGapH = dp(3f)
    private val keyGapV = dp(4f)
    private val keyCorner = dp(6f)
    private val popupCorner = dp(8f)
    private val hintInset = dp(5f)
    private val touchSlop = dp(8f)

    /** Where the keys start, in view coordinates. The service uses this for onComputeInsets. */
    val keyboardTop: Int get() = headroom.toInt()

    // ---- Paints, created once ----
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f) }
    private val shiftPath = android.graphics.Path()
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        textSize = sp(11f)
    }
    private val bigLabelSize = sp(22f)
    private val smallLabelSize = sp(15f)
    private val previewLabelSize = sp(28f)
    private val popupLabelSize = sp(22f)

    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    // ---- Touch state ----
    private inner class Pointer {
        var key: Key? = null
        var downX = 0f
        var downY = 0f
        var cursorMode = false
        var lastStepX = 0f
        var swipeDeletes = 0
        var longPressed = false

        val repeatRunnable = object : Runnable {
            override fun run() {
                listener.onBackspace()
                handler.postDelayed(this, BACKSPACE_REPEAT_MS)
            }
        }
        val longPressRunnable = Runnable {
            val k = key
            if (k != null && !cursorMode && k.longPress.isNotEmpty()) {
                longPressed = true
                openPopup(k, this)
            }
        }

        fun reset() {
            handler.removeCallbacks(repeatRunnable)
            handler.removeCallbacks(longPressRunnable)
            key?.pressed = false
            key = null
            cursorMode = false
            swipeDeletes = 0
            longPressed = false
        }
    }

    private val pointers = Array(MAX_POINTERS) { Pointer() }

    // Long-press popup. popupOptions is in display order: the default option sits in the cell
    // directly above the finger, the rest extend right, or left when there is no room.
    private var popupPointer: Pointer? = null
    private var popupOptions: List<String> = emptyList()
    private var popupSelected = 0
    private val popupRect = RectF()
    private var popupCellWidth = 0f

    // Key preview
    private var previewPointer: Pointer? = null
    private val previewRect = RectF()

    init {
        isClickable = true
        isFocusable = false
        computeMetrics()
    }

    // ---- Public API for the service ----

    fun setLayout(newLayout: KeyboardLayout) {
        cancelTouches()
        layout = newLayout
        if (width > 0) layoutKeys(width.toFloat())
        requestLayout()
        invalidate()
        touchHelper.invalidateRoot()
    }

    fun setTheme(newTheme: KeyboardTheme) {
        theme = newTheme
        invalidate()
    }

    fun setConfig(newConfig: KeyboardConfig) {
        config = newConfig
        computeMetrics()
        requestLayout()
        invalidate()
    }

    fun resetShift() {
        shiftState = ShiftState.OFF
        shiftIsAuto = false
        invalidate()
    }

    /** Auto-capitalisation hook. Only ever moves between OFF and an automatic SHIFTED. */
    fun setAutoShift(on: Boolean) {
        if (on && shiftState == ShiftState.OFF) {
            shiftState = ShiftState.SHIFTED
            shiftIsAuto = true
            invalidate()
        } else if (!on && shiftState == ShiftState.SHIFTED && shiftIsAuto) {
            shiftState = ShiftState.OFF
            shiftIsAuto = false
            invalidate()
        }
    }

    fun cancelTouches() {
        for (p in pointers) p.reset()
        closePopup()
        previewPointer = null
        invalidate()
    }

    // ---- Measurement and layout ----

    private fun computeMetrics() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val baseRowDp = if (landscape) 44f else 54f
        rowHeight = dp(baseRowDp) * config.heightScale / 100f
        topPad = dp(6f)
        headroom = rowHeight + dp(10f)
        extraBottom = dp(config.bottomPaddingDp.toFloat())
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val bottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetBottom
        }
        if (bottom != bottomInset) {
            bottomInset = bottom
            requestLayout()
        }
        return insets
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (headroom + topPad + layout.rows.size * rowHeight + extraBottom).toInt() + bottomInset
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutKeys(w.toFloat())
    }

    private fun layoutKeys(width: Float) {
        var y = headroom + topPad
        for ((index, row) in layout.rows.withIndex()) {
            var totalWeight = 0f
            for (k in row) totalWeight += k.weight
            val unit = width / totalWeight
            var x = 0f
            for (key in row) {
                val w = unit * key.weight
                key.rect.set(x + keyGapH, y + keyGapV / 2, x + w - keyGapH, y + rowHeight - keyGapV / 2)
                key.hitRect.set(x, y, x + w, y + rowHeight)
                x += w
            }
            // The top row also owns the small pad above it; the headroom above that is not ours.
            expandHitRects(row, if (index == 0) headroom else y, y + rowHeight, width)
            y += rowHeight
        }
    }

    /**
     * Grows the touch areas of a row until they tile it completely. Each gap between two keys is
     * split down the middle, spacers are shared the same way, and the first and last key reach the
     * edges of the keyboard. Without this the half-key spacers around "a" and "l" swallow touches
     * that clearly meant those keys.
     */
    private fun expandHitRects(row: List<Key>, top: Float, bottom: Float, width: Float) {
        val keys = ArrayList<Key>(row.size)
        for (k in row) if (k.type != KeyType.PAD) keys.add(k)
        val n = keys.size
        if (n == 0) return
        // edges[i] is the left edge of key i, edges[n] the right edge of the last key. Computed
        // from the untouched slot bounds before any hitRect is overwritten.
        val edges = FloatArray(n + 1)
        edges[0] = 0f
        edges[n] = width
        for (i in 1 until n) edges[i] = (keys[i - 1].hitRect.right + keys[i].hitRect.left) / 2f
        for (i in 0 until n) keys[i].hitRect.set(edges[i], top, edges[i + 1], bottom)
    }

    // ---- Drawing ----

    override fun onDraw(canvas: Canvas) {
        // The headroom stays transparent; only the keyboard body gets the background colour.
        keyPaint.color = theme.background
        canvas.drawRect(0f, headroom, width.toFloat(), height.toFloat(), keyPaint)
        for (key in layout.keys) drawKey(canvas, key)
        previewPointer?.key?.let { drawPreview(canvas, it) }
        if (popupPointer != null) drawPopup(canvas)
        onNextDraw?.let {
            onNextDraw = null
            it()
        }
    }

    private fun drawKey(canvas: Canvas, key: Key) {
        val isCaps = key.type == KeyType.SHIFT && shiftState == ShiftState.CAPS_LOCK
        val isShifted = key.type == KeyType.SHIFT && shiftState == ShiftState.SHIFTED
        keyPaint.color = when {
            key.pressed -> theme.keyPressed
            key.type == KeyType.ENTER || isCaps -> theme.accent
            isShifted -> theme.keyPressed
            key.type == KeyType.CHAR || key.type == KeyType.SPACE -> theme.key
            else -> theme.keyFunction
        }
        canvas.drawRoundRect(key.rect, keyCorner, keyCorner, keyPaint)
        if (config.keyBorders) {
            borderPaint.color = theme.border
            canvas.drawRoundRect(key.rect, keyCorner, keyCorner, borderPaint)
        }

        val label = labelFor(key)
        if (key.type == KeyType.ENTER && label == DEFAULT_ENTER_LABEL) {
            drawEnterIcon(canvas, key)
        } else if (key.type == KeyType.SHIFT) {
            drawShiftIcon(canvas, key, isCaps)
        } else if (label.isNotEmpty()) {
            labelPaint.color = if (key.type == KeyType.ENTER || isCaps) theme.labelOnAccent else theme.label
            labelPaint.textSize = when (key.type) {
                KeyType.CHAR -> if (key.isSingleGlyph) bigLabelSize else smallLabelSize
                KeyType.LAYER -> smallLabelSize
                KeyType.ENTER -> if (label.codePointCount(0, label.length) == 1) bigLabelSize else smallLabelSize
                else -> bigLabelSize
            }
            val baseline = key.rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2
            canvas.drawText(label, key.rect.centerX(), baseline, labelPaint)
        }

        if (layout.showHints && key.showHint && key.type == KeyType.CHAR) {
            val hint = key.hint
            if (hint != null) {
                hintPaint.color = theme.hint
                canvas.drawText(hint, key.rect.right - hintInset, key.rect.top + hintInset + hintPaint.textSize, hintPaint)
            }
        }
    }

    /**
     * Shift arrow drawn as a path so it is big and legible on every device: outline when off,
     * filled when shifted, filled with a bar underneath for caps lock.
     */
    private fun drawShiftIcon(canvas: Canvas, key: Key, isCaps: Boolean) {
        val s = dp(9f)
        val cx = key.rect.centerX()
        val cy = key.rect.centerY() - (if (isCaps) dp(2f) else 0f)
        shiftPath.reset()
        shiftPath.moveTo(cx, cy - s * 1.1f)
        shiftPath.lineTo(cx + s, cy + s * 0.05f)
        shiftPath.lineTo(cx + s * 0.5f, cy + s * 0.05f)
        shiftPath.lineTo(cx + s * 0.5f, cy + s * 0.9f)
        shiftPath.lineTo(cx - s * 0.5f, cy + s * 0.9f)
        shiftPath.lineTo(cx - s * 0.5f, cy + s * 0.05f)
        shiftPath.lineTo(cx - s, cy + s * 0.05f)
        shiftPath.close()
        val color = if (isCaps) theme.labelOnAccent else theme.label
        if (shiftState == ShiftState.OFF) {
            iconPaint.color = color
            canvas.drawPath(shiftPath, iconPaint)
        } else {
            keyPaint.color = color
            canvas.drawPath(shiftPath, keyPaint)
            iconPaint.color = color
            canvas.drawPath(shiftPath, iconPaint)   // stroke on top keeps the corners crisp
        }
        if (isCaps) {
            keyPaint.color = color
            val y = cy + s * 1.35f
            canvas.drawRoundRect(cx - s * 0.5f, y, cx + s * 0.5f, y + dp(2.5f), dp(1f), dp(1f), keyPaint)
        }
    }

    /** Font-independent return arrow: down from the top right, left along the bottom, arrowhead. */
    private fun drawEnterIcon(canvas: Canvas, key: Key) {
        val s = bigLabelSize * 0.42f
        val cx = key.rect.centerX()
        val cy = key.rect.centerY()
        iconPaint.color = theme.labelOnAccent
        canvas.drawLine(cx + s, cy - s * 0.8f, cx + s, cy + s * 0.4f, iconPaint)
        canvas.drawLine(cx + s, cy + s * 0.4f, cx - s, cy + s * 0.4f, iconPaint)
        canvas.drawLine(cx - s, cy + s * 0.4f, cx - s * 0.35f, cy - s * 0.25f, iconPaint)
        canvas.drawLine(cx - s, cy + s * 0.4f, cx - s * 0.35f, cy + s * 1.05f, iconPaint)
    }

    private fun labelFor(key: Key): String = when (key.type) {
        KeyType.CHAR -> if (key.shiftable && shiftState != ShiftState.OFF) key.label.uppercase(Locale.ROOT) else key.label
        KeyType.SHIFT -> "shift"   // drawn as an icon; the label is only used for accessibility
        KeyType.BACKSPACE -> "⌫"
        KeyType.ENTER -> enterLabel
        KeyType.LAYER -> key.label
        KeyType.SPACE, KeyType.PAD -> ""
    }

    private fun drawPreview(canvas: Canvas, key: Key) {
        val text = applyShift(key.label)
        labelPaint.textSize = previewLabelSize
        val w = max(key.rect.width() * 1.1f, labelPaint.measureText(text) + dp(24f))
        val h = rowHeight * 0.95f
        var left = key.rect.centerX() - w / 2
        left = left.coerceIn(keyGapH, width - w - keyGapH)
        val bottom = key.rect.top - dp(4f)
        previewRect.set(left, bottom - h, left + w, bottom)
        keyPaint.color = theme.popupBackground
        canvas.drawRoundRect(previewRect, popupCorner, popupCorner, keyPaint)
        labelPaint.color = theme.label
        val baseline = previewRect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2
        canvas.drawText(text, previewRect.centerX(), baseline, labelPaint)
    }

    private fun drawPopup(canvas: Canvas) {
        keyPaint.color = theme.popupBackground
        canvas.drawRoundRect(popupRect, popupCorner, popupCorner, keyPaint)
        labelPaint.textSize = popupLabelSize
        var x = popupRect.left
        for ((i, option) in popupOptions.withIndex()) {
            if (i == popupSelected) {
                keyPaint.color = theme.popupSelected
                canvas.drawRoundRect(x + dp(2f), popupRect.top + dp(3f), x + popupCellWidth - dp(2f), popupRect.bottom - dp(3f), keyCorner, keyCorner, keyPaint)
            }
            labelPaint.color = if (i == popupSelected) theme.labelOnAccent else theme.label
            val text = applyShift(option)
            labelPaint.textSize = if (text.codePointCount(0, text.length) > 2) smallLabelSize else popupLabelSize
            val baseline = popupRect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2
            canvas.drawText(text, x + popupCellWidth / 2, baseline, labelPaint)
            x += popupCellWidth
        }
    }

    // ---- Touch ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // A gesture starting in the headroom belongs to whatever sits under us there (the toolbar),
        // unless a popup is open and the finger is choosing an option.
        if (event.actionMasked == MotionEvent.ACTION_DOWN && event.y < headroom && popupPointer == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> onDown(event, event.actionIndex)
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) onMove(event, i)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> onUp(event, event.actionIndex)
            MotionEvent.ACTION_CANCEL -> cancelTouches()
        }
        return true
    }

    private fun pointerFor(event: MotionEvent, index: Int): Pointer? {
        val id = event.getPointerId(index)
        return if (id in 0 until MAX_POINTERS) pointers[id] else null
    }

    private fun onDown(event: MotionEvent, index: Int) {
        val p = pointerFor(event, index) ?: return
        val x = event.getX(index)
        val y = event.getY(index)
        if (y < headroom) return
        val key = keyAt(x, y) ?: return

        // A second finger while a popup is open dismisses the popup without committing.
        if (popupPointer != null && popupPointer !== p) closePopup()

        p.reset()
        p.key = key
        p.downX = x
        p.downY = y
        key.pressed = true
        feedback(key)

        when (key.type) {
            KeyType.BACKSPACE -> {
                listener.onBackspace()
                handler.postDelayed(p.repeatRunnable, BACKSPACE_INITIAL_DELAY_MS)
            }
            else -> if (key.longPress.isNotEmpty()) {
                handler.postDelayed(p.longPressRunnable, config.longPressMs.toLong())
            }
        }
        if (key.type == KeyType.CHAR && config.keyPreview && keyPreviewAllowed) previewPointer = p
        invalidate()
    }

    private fun onMove(event: MotionEvent, index: Int) {
        val p = pointerFor(event, index) ?: return
        val key = p.key ?: return
        val x = event.getX(index)
        val y = event.getY(index)

        if (popupPointer === p) {
            val n = popupOptions.size
            val idx = ((x - popupRect.left) / popupCellWidth).toInt().coerceIn(0, n - 1)
            if (idx != popupSelected) {
                popupSelected = idx
                invalidate()
            }
            return
        }

        when {
            key.type == KeyType.SPACE && config.spaceCursor -> {
                val dx = x - p.downX
                if (!p.cursorMode && abs(dx) > touchSlop * 2) {
                    p.cursorMode = true
                    p.lastStepX = x
                    handler.removeCallbacks(p.longPressRunnable)
                }
                if (p.cursorMode) {
                    val stepPx = dp(CURSOR_STEP_DP)
                    val steps = ((x - p.lastStepX) / stepPx).toInt()
                    if (steps != 0) {
                        listener.onCursorMove(steps)
                        p.lastStepX += steps * stepPx
                    }
                }
            }
            key.type == KeyType.BACKSPACE && config.backspaceSwipe -> {
                val dx = p.downX - x
                val words = (dx / max(key.rect.width(), dp(48f))).toInt()
                if (words > p.swipeDeletes) {
                    if (p.swipeDeletes == 0) handler.removeCallbacks(p.repeatRunnable)
                    repeat(words - p.swipeDeletes) { listener.onDeleteWord() }
                    p.swipeDeletes = words
                }
            }
            key.type == KeyType.CHAR && !p.longPressed -> {
                // Slide-to-correct: the key under the finger is the one that gets committed.
                val other = keyAt(x, y)
                if (other != null && other !== key && other.type == KeyType.CHAR) {
                    key.pressed = false
                    other.pressed = true
                    p.key = other
                    handler.removeCallbacks(p.longPressRunnable)
                    if (other.longPress.isNotEmpty()) {
                        handler.postDelayed(p.longPressRunnable, config.longPressMs.toLong())
                    }
                    invalidate()
                }
            }
        }
    }

    private fun onUp(event: MotionEvent, index: Int) {
        val p = pointerFor(event, index) ?: return
        val key = p.key ?: return
        handler.removeCallbacks(p.repeatRunnable)
        handler.removeCallbacks(p.longPressRunnable)
        key.pressed = false

        if (popupPointer === p) {
            val option = popupOptions.getOrNull(popupSelected)
            closePopup()
            if (option != null) {
                listener.onText(applyShift(option))
                afterCharCommit()
            }
        } else when (key.type) {
            KeyType.BACKSPACE -> Unit                      // already handled on press / repeat
            KeyType.SPACE -> if (!p.cursorMode) listener.onSpace()
            else -> activate(key)
        }

        if (previewPointer === p) previewPointer = null
        p.key = null
        p.cursorMode = false
        p.swipeDeletes = 0
        p.longPressed = false
        invalidate()
    }

    /** Performs a key's action. Shared by touch release and accessibility click. */
    private fun activate(key: Key) {
        when (key.type) {
            KeyType.CHAR -> {
                listener.onText(if (key.shiftable) applyShift(key.text) else key.text)
                afterCharCommit()
            }
            KeyType.SPACE -> listener.onSpace()
            KeyType.ENTER -> listener.onEnter()
            KeyType.SHIFT -> { toggleShift(); invalidate() }
            KeyType.LAYER -> listener.onLayer(key.target)
            KeyType.BACKSPACE -> listener.onBackspace()
            KeyType.PAD -> Unit
        }
    }

    /** Spoken name of a key for TalkBack. */
    private fun describe(key: Key): String = when (key.type) {
        KeyType.CHAR -> {
            val text = if (key.shiftable) applyShift(key.text) else key.text
            if (text.length == 1 && text[0].isLetter()) (if (text[0].isUpperCase()) "capital $text" else text) else text
        }
        KeyType.SPACE -> "space"
        KeyType.BACKSPACE -> "delete"
        KeyType.ENTER -> if (enterLabel == DEFAULT_ENTER_LABEL) "enter" else enterLabel
        KeyType.SHIFT -> when (shiftState) {
            ShiftState.OFF -> "shift, off"
            ShiftState.SHIFTED -> "shift, on"
            ShiftState.CAPS_LOCK -> "shift, caps lock"
        }
        KeyType.LAYER -> when (key.label) {
            "?123" -> "symbols"
            "ABC" -> "letters"
            "=\\<" -> "more symbols"
            "1234" -> "number pad"
            else -> key.label
        }
        KeyType.PAD -> ""
    }

    // ---- Accessibility: every key is a virtual view ----

    private val touchHelper = object : androidx.customview.widget.ExploreByTouchHelper(this) {
        private val bounds = android.graphics.Rect()

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val key = keyAt(x, y) ?: return INVALID_ID
            return layout.keys.indexOf(key)
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            for (i in layout.keys.indices) virtualViewIds.add(i)
        }

        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
            val key = layout.keys.getOrNull(virtualViewId)
            if (key == null) {
                node.contentDescription = ""
                node.setBoundsInParent(android.graphics.Rect(0, 0, 1, 1))
                return
            }
            node.contentDescription = describe(key)
            node.className = "android.widget.Button"
            node.addAction(androidx.core.view.accessibility.AccessibilityNodeInfoCompat.ACTION_CLICK)
            key.rect.round(bounds)
            @Suppress("DEPRECATION")
            node.setBoundsInParent(bounds)
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: android.os.Bundle?): Boolean {
            if (action != androidx.core.view.accessibility.AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            val key = layout.keys.getOrNull(virtualViewId) ?: return false
            feedback(key)
            activate(key)
            return true
        }
    }

    init {
        // Declared after touchHelper on purpose: init blocks run in declaration order.
        androidx.core.view.ViewCompat.setAccessibilityDelegate(this, touchHelper)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        touchHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    private fun keyAt(x: Float, y: Float): Key? {
        for (key in layout.keys) if (key.hitRect.contains(x, y)) return key
        return null
    }

    private fun applyShift(s: String): String =
        if (shiftState != ShiftState.OFF) s.uppercase(Locale.ROOT) else s

    private fun afterCharCommit() {
        if (shiftState == ShiftState.SHIFTED) {
            shiftState = ShiftState.OFF
            shiftIsAuto = false
        }
    }

    private fun toggleShift() {
        val now = SystemClock.uptimeMillis()
        shiftState = if (now - lastShiftTapAt < DOUBLE_TAP_MS) {
            ShiftState.CAPS_LOCK
        } else when (shiftState) {
            ShiftState.OFF -> ShiftState.SHIFTED
            ShiftState.SHIFTED, ShiftState.CAPS_LOCK -> ShiftState.OFF
        }
        shiftIsAuto = false
        lastShiftTapAt = now
    }

    // ---- Popup ----

    private fun openPopup(key: Key, p: Pointer) {
        val options = key.longPress
        popupPointer = p
        if (previewPointer === p) previewPointer = null

        labelPaint.textSize = popupLabelSize
        var widest = 0f
        for (o in options) widest = max(widest, labelPaint.measureText(o))
        val n = options.size
        val available = width - 2 * keyGapH
        popupCellWidth = max(key.rect.width(), widest + dp(20f)).coerceAtMost(available / n)
        val total = popupCellWidth * n

        // Default option directly above the finger, others to the right...
        var left = key.rect.centerX() - popupCellWidth / 2
        var reversed = false
        if (left + total > width - keyGapH) {
            val leftReversed = key.rect.centerX() + popupCellWidth / 2 - total
            if (leftReversed >= keyGapH) {
                // ...or to the left when the key is near the right edge.
                reversed = true
                left = leftReversed
            } else {
                // Too wide for either side: centre it and select whatever is under the finger.
                left = ((width - total) / 2).coerceAtLeast(keyGapH)
            }
        }
        if (left < keyGapH) left = keyGapH

        popupOptions = if (reversed) options.asReversed() else options
        popupSelected = ((key.rect.centerX() - left) / popupCellWidth).toInt().coerceIn(0, n - 1)

        val h = rowHeight * 0.95f
        var top = key.rect.top - h - dp(4f)
        if (top < 0f) top = 0f
        popupRect.set(left, top, left + total, top + h)
        invalidate()
    }

    private fun closePopup() {
        popupPointer = null
        popupOptions = emptyList()
    }

    // ---- Feedback ----

    private fun feedback(key: Key) {
        if (config.haptics) {
            @Suppress("DEPRECATION")
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
        }
        if (config.sound) {
            val fx = when (key.type) {
                KeyType.BACKSPACE -> AudioManager.FX_KEYPRESS_DELETE
                KeyType.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                KeyType.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            audio?.playSoundEffect(fx, -1f)
        }
    }

    override fun onDetachedFromWindow() {
        cancelTouches()
        super.onDetachedFromWindow()
    }

    // ---- Helpers ----

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    companion object {
        const val DEFAULT_ENTER_LABEL = "↵"
        private const val MAX_POINTERS = 16
        private const val BACKSPACE_INITIAL_DELAY_MS = 400L
        private const val BACKSPACE_REPEAT_MS = 50L
        private const val DOUBLE_TAP_MS = 300L
        private const val CURSOR_STEP_DP = 18f
    }
}
