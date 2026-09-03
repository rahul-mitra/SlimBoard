package app.slimboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import app.slimboard.theme.KeyboardTheme

/** The strip beside a one-handed keyboard: switch side, or expand back to full width. */
class SideBarView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onSwitchSide()
        fun onExitOneHanded()
    }

    var theme: KeyboardTheme = KeyboardTheme.DARK
        set(v) { field = v; invalidate() }

    /** True when the keyboard is on the right, so the bar is on the left and arrows should point right. */
    var keyboardOnRight = true
        set(v) { field = v; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val switchRect = RectF()
    private val expandRect = RectF()
    private val u = dp(1f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val size = minOf(w - dp(12f), dp(48f))
        val cx = w / 2f
        switchRect.set(cx - size / 2, h * 0.30f - size / 2, cx + size / 2, h * 0.30f + size / 2)
        expandRect.set(cx - size / 2, h * 0.62f - size / 2, cx + size / 2, h * 0.62f + size / 2)
    }

    override fun onDraw(canvas: Canvas) {
        fill.color = theme.background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        fill.color = theme.keyFunction
        canvas.drawRoundRect(switchRect, dp(10f), dp(10f), fill)
        canvas.drawRoundRect(expandRect, dp(10f), dp(10f), fill)
        stroke.color = theme.label

        // Switch side: an arrow pointing toward where the keyboard would go.
        val dir = if (keyboardOnRight) -1f else 1f
        var cx = switchRect.centerX()
        var cy = switchRect.centerY()
        canvas.drawLine(cx - 8 * u, cy, cx + 8 * u, cy, stroke)
        canvas.drawLine(cx + dir * 8 * u, cy, cx + dir * 3 * u, cy - 5 * u, stroke)
        canvas.drawLine(cx + dir * 8 * u, cy, cx + dir * 3 * u, cy + 5 * u, stroke)

        // Expand: two outward corner arrows.
        cx = expandRect.centerX()
        cy = expandRect.centerY()
        canvas.drawLine(cx - 7 * u, cy - 7 * u, cx + 7 * u, cy + 7 * u, stroke)
        canvas.drawLine(cx - 7 * u, cy - 7 * u, cx - 7 * u, cy - 1 * u, stroke)
        canvas.drawLine(cx - 7 * u, cy - 7 * u, cx - 1 * u, cy - 7 * u, stroke)
        canvas.drawLine(cx + 7 * u, cy + 7 * u, cx + 7 * u, cy + 1 * u, stroke)
        canvas.drawLine(cx + 7 * u, cy + 7 * u, cx + 1 * u, cy + 7 * u, stroke)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            when {
                switchRect.contains(event.x, event.y) -> { haptic(); listener.onSwitchSide() }
                expandRect.contains(event.x, event.y) -> { haptic(); listener.onExitOneHanded() }
            }
        }
        return true
    }

    private fun haptic() {
        @Suppress("DEPRECATION")
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
