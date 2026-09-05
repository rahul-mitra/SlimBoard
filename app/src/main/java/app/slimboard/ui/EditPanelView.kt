package app.slimboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import app.slimboard.theme.KeyboardTheme

/**
 * Text editing panel: cursor keys with hold-to-repeat, a Select toggle that turns arrows into
 * selection, select all / cut / copy / paste, home / end, undo / redo. One Canvas, no children.
 */
class EditPanelView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onMove(dx: Int, dy: Int, selecting: Boolean)
        /** Select was switched on or off; the anchor of the selection moves with it. */
        fun onSelectToggle(on: Boolean)
        fun onHome(selecting: Boolean)
        fun onEnd(selecting: Boolean)
        fun onSelectAll()
        fun onCut()
        fun onCopy()
        fun onPaste()
        fun onUndo()
        fun onRedo()
        fun onEditBackspace()
        fun onEditAbc()
    }

    var theme: KeyboardTheme = KeyboardTheme.DARK
        set(v) { field = v; invalidate() }

    private enum class Action { UNDO, UP, SELECT_ALL, CUT, LEFT, SELECT, RIGHT, COPY, HOME, DOWN, END, PASTE, REDO }

    private class Button(val action: Action, val label: String, val accent: Boolean = false, val repeat: Boolean = false) {
        val rect = RectF()
        var pressed = false
    }

    // 4 columns × 3 rows
    private val buttons = listOf(
        Button(Action.UNDO, "Undo"), Button(Action.UP, "↑", repeat = true), Button(Action.SELECT_ALL, "Select all"), Button(Action.CUT, "Cut"),
        Button(Action.LEFT, "←", repeat = true), Button(Action.SELECT, "Select"), Button(Action.RIGHT, "→", repeat = true), Button(Action.COPY, "Copy"),
        Button(Action.HOME, "Home"), Button(Action.DOWN, "↓", repeat = true), Button(Action.END, "End"), Button(Action.PASTE, "Paste", accent = true),
    )
    private val redoButton = Button(Action.REDO, "Redo")
    private var selecting = false

    private val bottomHeight = dp(48f)
    private val gap = dp(5f)
    private val corner = dp(8f)
    private val abcWidth = dp(72f)
    private val backWidth = dp(72f)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val handler = Handler(Looper.getMainLooper())
    private var pressedButton: Button? = null
    private var pressedBottom = 0 // 0 none, 1 abc, 2 backspace
    private val repeatRunnable = object : Runnable {
        override fun run() {
            val b = pressedButton ?: return
            fire(b)
            handler.postDelayed(this, REPEAT_MS)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val gridBottom = h - bottomHeight
        val cols = 4
        val rows = 3
        val cellW = (w - gap) / cols
        val cellH = (gridBottom - gap) / rows
        for ((i, b) in buttons.withIndex()) {
            val c = i % cols
            val r = i / cols
            b.rect.set(gap + c * cellW, gap + r * cellH, (c + 1) * cellW, (r + 1) * cellH)
        }
        // Redo lives in the bottom row between ABC and backspace.
        redoButton.rect.set(abcWidth + gap, gridBottom + dp(6f), w - backWidth - gap, h - dp(6f))
    }

    override fun onDraw(canvas: Canvas) {
        fill.color = theme.background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        for (b in buttons) drawButton(canvas, b)

        val top = height - bottomHeight
        fill.color = theme.keyFunction
        canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), fill)
        val cy = top + bottomHeight / 2
        text.color = theme.label
        text.typeface = Typeface.DEFAULT
        text.textSize = sp(15f)
        var baseline = cy - (text.descent() + text.ascent()) / 2
        canvas.drawText("ABC", abcWidth / 2, baseline, text)
        drawButton(canvas, redoButton)
        text.textSize = sp(20f)
        baseline = cy - (text.descent() + text.ascent()) / 2
        canvas.drawText("⌫", width - backWidth / 2, baseline, text)
    }

    private fun drawButton(canvas: Canvas, b: Button) {
        val active = b.action == Action.SELECT && selecting
        fill.color = when {
            b.pressed -> theme.keyPressed
            active || b.accent -> theme.accent
            b.action == Action.UP || b.action == Action.DOWN || b.action == Action.LEFT || b.action == Action.RIGHT -> theme.key
            else -> theme.keyFunction
        }
        canvas.drawRoundRect(b.rect, corner, corner, fill)
        text.color = if (active || b.accent) theme.labelOnAccent else theme.label
        val isArrow = b.label.length == 1
        text.textSize = if (isArrow) sp(24f) else sp(14f)
        text.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        val baseline = b.rect.centerY() - (text.descent() + text.ascent()) / 2
        canvas.drawText(b.label, b.rect.centerX(), baseline, text)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val b = buttons.firstOrNull { it.rect.contains(x, y) } ?: (if (redoButton.rect.contains(x, y)) redoButton else null)
                haptic()
                if (b != null) {
                    pressedButton = b
                    b.pressed = true
                    if (b.repeat) {
                        fire(b)
                        handler.postDelayed(repeatRunnable, INITIAL_DELAY_MS)
                    }
                } else if (y >= height - bottomHeight) {
                    pressedBottom = if (x < abcWidth) 1 else if (x > width - backWidth) 2 else 0
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(repeatRunnable)
                pressedButton?.let { b ->
                    b.pressed = false
                    if (!b.repeat) fire(b)
                }
                pressedButton = null
                when (pressedBottom) {
                    1 -> listener.onEditAbc()
                    2 -> listener.onEditBackspace()
                }
                pressedBottom = 0
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(repeatRunnable)
                pressedButton?.pressed = false
                pressedButton = null
                pressedBottom = 0
                invalidate()
            }
        }
        return true
    }

    private fun fire(b: Button) {
        when (b.action) {
            Action.UP -> listener.onMove(0, -1, selecting)
            Action.DOWN -> listener.onMove(0, 1, selecting)
            Action.LEFT -> listener.onMove(-1, 0, selecting)
            Action.RIGHT -> listener.onMove(1, 0, selecting)
            Action.HOME -> listener.onHome(selecting)
            Action.END -> listener.onEnd(selecting)
            Action.SELECT -> { selecting = !selecting; invalidate(); listener.onSelectToggle(selecting) }
            // Leaving Select on means the arrows trim the selection instead of dropping it, and it
            // lights up the button that does the trimming.
            Action.SELECT_ALL -> { selecting = true; invalidate(); listener.onSelectToggle(true); listener.onSelectAll() }
            Action.CUT -> { selecting = false; listener.onSelectToggle(false); listener.onCut() }
            Action.COPY -> { selecting = false; listener.onSelectToggle(false); listener.onCopy() }
            Action.PASTE -> { selecting = false; listener.onSelectToggle(false); listener.onPaste() }
            Action.UNDO -> listener.onUndo()
            Action.REDO -> listener.onRedo()
        }
    }

    fun resetSelection() {
        selecting = false
        invalidate()
    }

    private fun haptic() {
        @Suppress("DEPRECATION")
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(repeatRunnable)
        super.onDetachedFromWindow()
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private companion object {
        const val INITIAL_DELAY_MS = 400L
        const val REPEAT_MS = 80L
    }
}
