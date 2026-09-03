package app.slimboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import app.slimboard.theme.KeyboardTheme
import kotlin.math.abs
import kotlin.math.max

/**
 * The strip above the keys: back, clipboard, emoji and settings buttons, a "just copied" chip, and
 * an emoji-search mode that shows the typed query with matching emoji. Canvas-drawn icons so the
 * look does not depend on the device font.
 */
class ToolbarView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onBack()
        fun onClipboard()
        fun onEmoji()
        fun onSettings()
        fun onChip()
        fun onSearchResult(emoji: String)
    }

    enum class Panel { NONE, CLIPBOARD, EMOJI }

    private enum class Item { BACK, CLIPBOARD, EMOJI, SETTINGS }

    var theme: KeyboardTheme = KeyboardTheme.DARK
        set(v) { field = v; invalidate() }

    var activePanel = Panel.NONE
        set(v) { field = v; invalidate() }

    var chipText: String? = null
        set(v) { field = v; invalidate() }

    var searchMode = false
        private set
    private var searchQuery = ""
    private var searchResults: List<String> = emptyList()
    private var resultsScroll = 0f

    val heightPx = dp(40f)
    private val slot = dp(48f)
    private val u = dp(1f)
    private val resultCell = dp(42f)

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(14f) }
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = sp(22f) }

    private val itemRects = Array(Item.values().size) { RectF() }
    private val visibleItems = ArrayList<Item>(4)
    private val chipRect = RectF()
    private val resultsRect = RectF()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false

    fun setSearch(active: Boolean, query: String = "", results: List<String> = emptyList()) {
        searchMode = active
        searchQuery = query
        searchResults = results
        resultsScroll = 0f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), heightPx.toInt())
    }

    // ---- Drawing ----

    override fun onDraw(canvas: Canvas) {
        fill.color = theme.background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        if (searchMode) drawSearch(canvas) else drawNormal(canvas)
    }

    private fun drawNormal(canvas: Canvas) {
        visibleItems.clear()
        if (activePanel != Panel.NONE) visibleItems.add(Item.BACK)
        visibleItems.add(Item.CLIPBOARD)
        visibleItems.add(Item.EMOJI)
        visibleItems.add(Item.SETTINGS)

        var x = dp(4f)
        for (item in visibleItems) {
            val r = itemRects[item.ordinal]
            r.set(x, 0f, x + slot, height.toFloat())
            val active = (item == Item.CLIPBOARD && activePanel == Panel.CLIPBOARD) ||
                (item == Item.EMOJI && activePanel == Panel.EMOJI)
            if (active) {
                fill.color = theme.keyPressed
                canvas.drawCircle(r.centerX(), r.centerY(), dp(17f), fill)
            }
            drawIcon(canvas, item, r.centerX(), r.centerY())
            x += slot
        }

        val chip = chipText
        if (chip != null && activePanel == Panel.NONE) {
            val left = x + dp(8f)
            val right = width - dp(8f)
            if (right - left > dp(80f)) {
                chipRect.set(left, dp(6f), right, height - dp(6f))
                fill.color = theme.key
                canvas.drawRoundRect(chipRect, dp(14f), dp(14f), fill)
                drawClipboardIcon(canvas, left + dp(18f), chipRect.centerY(), 0.75f)
                text.color = theme.label
                val avail = chipRect.width() - dp(44f)
                val shown = TextUtils.ellipsize(chip, text, avail, TextUtils.TruncateAt.END)
                val baseline = chipRect.centerY() - (text.descent() + text.ascent()) / 2
                canvas.drawText(shown, 0, shown.length, left + dp(34f), baseline, text)
            } else {
                chipRect.setEmpty()
            }
        } else {
            chipRect.setEmpty()
        }
    }

    private fun drawSearch(canvas: Canvas) {
        visibleItems.clear()
        visibleItems.add(Item.BACK)
        val r = itemRects[Item.BACK.ordinal]
        r.set(dp(4f), 0f, dp(4f) + slot, height.toFloat())
        drawIcon(canvas, Item.BACK, r.centerX(), r.centerY())

        val queryLeft = r.right + dp(4f)
        val queryRight = queryLeft + max(dp(120f), width * 0.34f)
        val baseline = height / 2f - (text.descent() + text.ascent()) / 2
        if (searchQuery.isEmpty()) {
            text.color = theme.hint
            canvas.drawText("Search emoji", queryLeft, baseline, text)
        } else {
            text.color = theme.label
            val shown = TextUtils.ellipsize(searchQuery, text, queryRight - queryLeft - dp(6f), TextUtils.TruncateAt.START)
            canvas.drawText(shown, 0, shown.length, queryLeft, baseline, text)
            val w = text.measureText(shown, 0, shown.length)
            fill.color = theme.accent
            canvas.drawRect(queryLeft + w + dp(2f), height * 0.25f, queryLeft + w + dp(4f), height * 0.75f, fill)
        }

        resultsRect.set(queryRight + dp(6f), 0f, width.toFloat(), height.toFloat())
        if (searchResults.isEmpty()) {
            if (searchQuery.isNotEmpty()) {
                text.color = theme.hint
                canvas.drawText("No match", resultsRect.left, baseline, text)
            }
            return
        }
        canvas.save()
        canvas.clipRect(resultsRect)
        val offset = -(emojiPaint.descent() + emojiPaint.ascent()) / 2
        var x = resultsRect.left - resultsScroll
        for (e in searchResults) {
            if (x + resultCell > resultsRect.left && x < resultsRect.right) {
                canvas.drawText(e, x + resultCell / 2, height / 2f + offset, emojiPaint)
            }
            x += resultCell
        }
        canvas.restore()
    }

    private fun drawIcon(canvas: Canvas, item: Item, cx: Float, cy: Float) {
        stroke.color = theme.label
        fill.color = theme.label
        when (item) {
            Item.BACK -> {
                canvas.drawLine(cx + 4 * u, cy - 7 * u, cx - 3 * u, cy, stroke)
                canvas.drawLine(cx - 3 * u, cy, cx + 4 * u, cy + 7 * u, stroke)
            }
            Item.CLIPBOARD -> drawClipboardIcon(canvas, cx, cy, 1f)
            Item.EMOJI -> {
                canvas.drawCircle(cx, cy, 9 * u, stroke)
                canvas.drawCircle(cx - 3.3f * u, cy - 2.8f * u, 1.4f * u, fill)
                canvas.drawCircle(cx + 3.3f * u, cy - 2.8f * u, 1.4f * u, fill)
                canvas.drawArc(cx - 5.2f * u, cy - 4 * u, cx + 5.2f * u, cy + 5.2f * u, 25f, 130f, false, stroke)
            }
            Item.SETTINGS -> {
                canvas.drawCircle(cx, cy, 3.6f * u, stroke)
                val inner = 6.5f * u
                val outer = 9.5f * u
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val dx = Math.cos(a).toFloat()
                    val dy = Math.sin(a).toFloat()
                    canvas.drawLine(cx + dx * inner, cy + dy * inner, cx + dx * outer, cy + dy * outer, stroke)
                }
            }
        }
    }

    private fun drawClipboardIcon(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        stroke.color = theme.label
        fill.color = theme.label
        canvas.drawRoundRect(cx - 7 * u * s, cy - 7.5f * u * s, cx + 7 * u * s, cy + 9 * u * s, 2 * u, 2 * u, stroke)
        canvas.drawRoundRect(cx - 3.5f * u * s, cy - 10 * u * s, cx + 3.5f * u * s, cy - 5.5f * u * s, 1.5f * u, 1.5f * u, fill)
        canvas.drawLine(cx - 3.5f * u * s, cy + 0.5f * u * s, cx + 3.5f * u * s, cy + 0.5f * u * s, stroke)
        canvas.drawLine(cx - 3.5f * u * s, cy + 4.5f * u * s, cx + 1.5f * u * s, cy + 4.5f * u * s, stroke)
    }

    // ---- Touch ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; lastX = event.x
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (searchMode && resultsRect.contains(downX, downY)) {
                    if (!dragging && abs(event.x - downX) > touchSlop) dragging = true
                    if (dragging) {
                        val maxScroll = max(0f, searchResults.size * resultCell - resultsRect.width())
                        resultsScroll = (resultsScroll - (event.x - lastX)).coerceIn(0f, maxScroll)
                        invalidate()
                    }
                }
                lastX = event.x
            }
            MotionEvent.ACTION_UP -> if (!dragging) tap(event.x, event.y)
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        for (item in visibleItems) {
            if (itemRects[item.ordinal].contains(x, y)) {
                haptic()
                when (item) {
                    Item.BACK -> listener.onBack()
                    Item.CLIPBOARD -> listener.onClipboard()
                    Item.EMOJI -> listener.onEmoji()
                    Item.SETTINGS -> listener.onSettings()
                }
                return
            }
        }
        if (searchMode) {
            if (resultsRect.contains(x, y)) {
                val idx = ((x - resultsRect.left + resultsScroll) / resultCell).toInt()
                searchResults.getOrNull(idx)?.let { haptic(); listener.onSearchResult(it) }
            }
            return
        }
        if (!chipRect.isEmpty && chipRect.contains(x, y)) {
            haptic()
            listener.onChip()
        }
    }

    private fun haptic() {
        @Suppress("DEPRECATION")
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
