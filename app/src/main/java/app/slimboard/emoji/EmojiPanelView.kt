package app.slimboard.emoji

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import app.slimboard.settings.Prefs
import app.slimboard.theme.KeyboardTheme
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Emoji picker drawn on one Canvas: category tabs + search button on top, a scrollable grid in the
 * middle, ABC / space / backspace at the bottom. Long-press on an emoji with skin tones opens a
 * variant popup; the chosen tone is remembered per emoji.
 */
class EmojiPanelView(context: Context, private val prefs: Prefs, private val listener: Listener) : View(context) {

    interface Listener {
        fun onEmoji(emoji: String)
        fun onSpace()
        fun onBackspace()
        fun onAbc()
        fun onSearch()
    }

    var theme: KeyboardTheme = KeyboardTheme.DARK
        set(v) { field = v; invalidate() }

    private var groups: List<EmojiGroup> = emptyList()
    private var loading = true
    private var unavailable = false
    private var current = 0 // 0 = recents, then groups
    private val recents = ArrayList<String>()
    private val skin = HashMap<String, String>()

    private val tabHeight = dp(40f)
    private val bottomHeight = dp(48f)
    private val searchSlot = dp(48f)
    private var cell = dp(48f)
    private var cols = 8
    private var scrollY = 0f
    private var maxScroll = 0f

    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private val handler = Handler(Looper.getMainLooper())

    // Variant popup
    private var popupEmoji: Emoji? = null
    private var popupOptions: List<String> = emptyList()
    private var popupSelected = 0
    private val popupRect = RectF()
    private var popupCell = 0f
    private val longPress = Runnable { openPopupAt(downX, downY) }

    private val bgPaint = Paint()
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = sp(15f) }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }

    init {
        loadState()
    }

    fun setData(newGroups: List<EmojiGroup>) {
        groups = newGroups
        loading = false
        unavailable = newGroups.isEmpty()
        if (current == 0 && recents.isEmpty() && groups.isNotEmpty()) current = 1
        clampScroll()
        invalidate()
    }

    fun onShown() {
        loadState()
        if (current == 0 && recents.isEmpty() && groups.isNotEmpty()) current = 1
        scroller.forceFinished(true)
        clampScroll()
        invalidate()
    }

    private fun loadState() {
        recents.clear()
        prefs.emojiRecents.split(RECENTS_SEP).filter { it.isNotEmpty() }.forEach { recents.add(it) }
        skin.clear()
        try {
            val o = JSONObject(prefs.emojiSkinTones)
            for (k in o.keys()) skin[k] = o.getString(k)
        } catch (e: Exception) { /* ignore corrupt state */ }
    }

    // ---- Layout ----

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cols = max(6, (w / dp(46f)).toInt())
        cell = w.toFloat() / cols
        clampScroll()
    }

    private fun currentList(): List<String> =
        if (current == 0) recents else groups.getOrNull(current - 1)?.emojis?.map { displayFor(it) } ?: emptyList()

    private fun currentEmojis(): List<Emoji>? = if (current == 0) null else groups.getOrNull(current - 1)?.emojis

    private fun displayFor(e: Emoji): String = skin[e.chars] ?: e.chars

    private val gridTop get() = tabHeight
    private val gridBottom get() = height - bottomHeight

    private fun clampScroll() {
        val rows = (currentList().size + cols - 1) / cols
        maxScroll = max(0f, rows * cell - (gridBottom - gridTop))
        scrollY = scrollY.coerceIn(0f, maxScroll)
    }

    // ---- Drawing ----

    override fun onDraw(canvas: Canvas) {
        bgPaint.color = theme.background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        drawTabs(canvas)
        drawGrid(canvas)
        drawBottom(canvas)
        if (popupEmoji != null) drawPopup(canvas)
    }

    private fun drawTabs(canvas: Canvas) {
        // Search button
        val cx = searchSlot / 2
        val cy = tabHeight / 2
        strokePaint.color = theme.label
        canvas.drawCircle(cx - dp(2f), cy - dp(2f), dp(6f), strokePaint)
        canvas.drawLine(cx + dp(2.5f), cy + dp(2.5f), cx + dp(7f), cy + dp(7f), strokePaint)

        val count = groups.size + 1
        if (count <= 1) return
        val tabW = (width - searchSlot) / count
        emojiPaint.textSize = sp(18f)
        for (i in 0 until count) {
            val x = searchSlot + i * tabW
            val icon = if (i == 0) "🕒" else groups[i - 1].icon
            val alpha = if (i == current) 255 else 130
            emojiPaint.alpha = alpha
            val baseline = cy - (emojiPaint.descent() + emojiPaint.ascent()) / 2
            canvas.drawText(icon, x + tabW / 2, baseline, emojiPaint)
            if (i == current) {
                bgPaint.color = theme.accent
                canvas.drawRoundRect(x + tabW * 0.25f, tabHeight - dp(3f), x + tabW * 0.75f, tabHeight, dp(1.5f), dp(1.5f), bgPaint)
            }
        }
        emojiPaint.alpha = 255
    }

    private fun drawGrid(canvas: Canvas) {
        val list = currentList()
        textPaint.color = theme.hint
        if (loading) {
            canvas.drawText("Loading emoji…", width / 2f, (gridTop + gridBottom) / 2, textPaint)
            return
        }
        if (unavailable) {
            canvas.drawText("Emoji data is not bundled in this build", width / 2f, (gridTop + gridBottom) / 2, textPaint)
            return
        }
        if (list.isEmpty()) {
            canvas.drawText(if (current == 0) "No recent emoji yet" else "Nothing here", width / 2f, (gridTop + gridBottom) / 2, textPaint)
            return
        }
        canvas.save()
        canvas.clipRect(0f, gridTop, width.toFloat(), gridBottom)
        emojiPaint.textSize = cell * 0.58f
        emojiPaint.color = theme.label
        val firstRow = (scrollY / cell).toInt()
        val lastRow = ((scrollY + gridBottom - gridTop) / cell).toInt() + 1
        val offset = -(emojiPaint.descent() + emojiPaint.ascent()) / 2
        for (row in firstRow..lastRow) {
            val y = gridTop + row * cell - scrollY
            for (c in 0 until cols) {
                val idx = row * cols + c
                if (idx >= list.size) break
                canvas.drawText(list[idx], c * cell + cell / 2, y + cell / 2 + offset, emojiPaint)
            }
        }
        canvas.restore()
    }

    private fun drawBottom(canvas: Canvas) {
        val top = gridBottom
        bgPaint.color = theme.keyFunction
        canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), bgPaint)
        val cy = top + bottomHeight / 2
        textPaint.color = theme.label
        textPaint.textSize = sp(15f)
        val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText("ABC", abcWidth / 2, baseline, textPaint)
        // space bar
        bgPaint.color = theme.key
        canvas.drawRoundRect(abcWidth + dp(6f), top + dp(7f), width - backWidth - dp(6f), height - dp(7f), dp(6f), dp(6f), bgPaint)
        textPaint.textSize = sp(20f)
        val b2 = cy - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText("⌫", width - backWidth / 2, b2, textPaint)
    }

    private val abcWidth get() = dp(72f)
    private val backWidth get() = dp(72f)

    private fun drawPopup(canvas: Canvas) {
        bgPaint.color = theme.popupBackground
        canvas.drawRoundRect(popupRect, dp(8f), dp(8f), bgPaint)
        emojiPaint.textSize = popupCell * 0.6f
        val offset = -(emojiPaint.descent() + emojiPaint.ascent()) / 2
        var x = popupRect.left
        for ((i, option) in popupOptions.withIndex()) {
            if (i == popupSelected) {
                bgPaint.color = theme.popupSelected
                canvas.drawRoundRect(x + dp(2f), popupRect.top + dp(3f), x + popupCell - dp(2f), popupRect.bottom - dp(3f), dp(6f), dp(6f), bgPaint)
            }
            canvas.drawText(option, x + popupCell / 2, popupRect.centerY() + offset, emojiPaint)
            x += popupCell
        }
    }

    // ---- Touch ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                downX = x; downY = y; lastY = y
                dragging = false
                if (y in gridTop..gridBottom && emojiAt(x, y)?.variants?.isNotEmpty() == true) {
                    handler.postDelayed(longPress, LONG_PRESS_MS)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                if (popupEmoji != null) {
                    val n = popupOptions.size
                    popupSelected = ((x - popupRect.left) / popupCell).toInt().coerceIn(0, n - 1)
                    invalidate()
                    return true
                }
                if (!dragging && downY in gridTop..gridBottom && abs(y - downY) > touchSlop) {
                    dragging = true
                    handler.removeCallbacks(longPress)
                }
                if (dragging) {
                    scrollY = (scrollY - (y - lastY)).coerceIn(0f, maxScroll)
                    invalidate()
                }
                lastY = y
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                if (popupEmoji != null) {
                    val base = popupEmoji!!
                    val chosen = popupOptions.getOrNull(popupSelected) ?: base.chars
                    if (chosen == base.chars) skin.remove(base.chars) else skin[base.chars] = chosen
                    saveSkin()
                    popupEmoji = null
                    commit(chosen)
                } else if (dragging) {
                    velocity?.let {
                        it.computeCurrentVelocity(1000)
                        val vy = it.yVelocity
                        if (abs(vy) > 200f) {
                            scroller.fling(0, scrollY.toInt(), 0, (-vy).toInt(), 0, 0, 0, maxScroll.toInt())
                            postInvalidateOnAnimation()
                        }
                    }
                } else {
                    tap(x, y)
                }
                velocity?.recycle(); velocity = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                popupEmoji = null
                velocity?.recycle(); velocity = null
                invalidate()
            }
        }
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollY = scroller.currY.toFloat().coerceIn(0f, maxScroll)
            postInvalidateOnAnimation()
        }
    }

    private fun tap(x: Float, y: Float) {
        when {
            y < tabHeight -> {
                if (x < searchSlot) { feedback(); listener.onSearch(); return }
                val count = groups.size + 1
                if (count <= 1) return
                val tabW = (width - searchSlot) / count
                val i = ((x - searchSlot) / tabW).toInt().coerceIn(0, count - 1)
                if (i != current) {
                    current = i
                    scrollY = 0f
                    clampScroll()
                    feedback()
                }
            }
            y >= gridBottom -> {
                feedback()
                when {
                    x < abcWidth -> listener.onAbc()
                    x > width - backWidth -> listener.onBackspace()
                    else -> listener.onSpace()
                }
            }
            else -> {
                val idx = indexAt(x, y)
                val list = currentList()
                if (idx in list.indices) {
                    feedback()
                    commit(list[idx])
                }
            }
        }
    }

    private fun indexAt(x: Float, y: Float): Int {
        val row = ((y - gridTop + scrollY) / cell).toInt()
        val col = (x / cell).toInt()
        if (col !in 0 until cols) return -1
        return row * cols + col
    }

    private fun emojiAt(x: Float, y: Float): Emoji? {
        val emojis = currentEmojis() ?: return null
        return emojis.getOrNull(indexAt(x, y))
    }

    private fun openPopupAt(x: Float, y: Float) {
        val e = emojiAt(x, y) ?: return
        val idx = indexAt(x, y)
        popupEmoji = e
        popupOptions = listOf(e.chars) + e.variants
        popupCell = min(cell, (width - 2 * dp(4f)) / popupOptions.size)
        val total = popupCell * popupOptions.size
        val col = idx % cols
        val cellCenter = col * cell + cell / 2
        var left = cellCenter - popupCell / 2
        var reversed = false
        if (left + total > width - dp(4f)) {
            val lr = cellCenter + popupCell / 2 - total
            if (lr >= dp(4f)) { left = lr; reversed = true } else left = max(dp(4f), (width - total) / 2)
        }
        if (left < dp(4f)) left = dp(4f)
        if (reversed) popupOptions = popupOptions.asReversed()
        popupSelected = ((cellCenter - left) / popupCell).toInt().coerceIn(0, popupOptions.size - 1)
        val row = idx / cols
        val cellTop = gridTop + row * cell - scrollY
        var top = cellTop - popupCell - dp(4f)
        if (top < 0f) top = cellTop + cell + dp(4f)
        popupRect.set(left, top, left + total, top + popupCell)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    private fun commit(emoji: String) {
        listener.onEmoji(emoji)
        recents.remove(emoji)
        recents.add(0, emoji)
        while (recents.size > MAX_RECENTS) recents.removeAt(recents.size - 1)
        prefs.emojiRecents = recents.joinToString(RECENTS_SEP)
    }

    private fun saveSkin() {
        val o = JSONObject()
        for ((k, v) in skin) o.put(k, v)
        prefs.emojiSkinTones = o.toString()
    }

    private fun feedback() {
        @Suppress("DEPRECATION")
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private companion object {
        const val RECENTS_SEP = ""
        const val MAX_RECENTS = 40
        const val LONG_PRESS_MS = 350L
    }
}
