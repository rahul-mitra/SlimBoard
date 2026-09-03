package app.slimboard.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import app.slimboard.ui.keyboard.KeyboardView

/**
 * Root of the IME input view. Stacks, bottom to top: toolbar, clipboard panel, emoji panel, keyboard.
 *
 * The keyboard is a full-height view whose top part (headroom) is transparent and only used for
 * popups. The toolbar sits inside that headroom, just above the keys; the keyboard is drawn on top
 * so popups can overlap the toolbar, and it hands touches in the headroom back to the toolbar.
 * Panels replace the keys area exactly, so the app behind never resizes when switching.
 */
class InputViewContainer(
    context: Context,
    val keyboard: KeyboardView,
    val toolbar: ToolbarView,
    private val clipboardPanel: View,
    private val emojiPanel: View,
) : ViewGroup(context) {

    var toolbarVisible = true
        set(v) {
            field = v
            toolbar.visibility = if (v) VISIBLE else GONE
            requestLayout()
        }

    var panel = ToolbarView.Panel.NONE
        private set

    init {
        addView(toolbar)
        addView(clipboardPanel)
        addView(emojiPanel)
        addView(keyboard)
        clipboardPanel.visibility = INVISIBLE
        emojiPanel.visibility = INVISIBLE
    }

    /** Top of what the user perceives as the keyboard, in this view's coordinates. */
    val contentTop: Int
        get() = keyboard.keyboardTop - if (toolbarVisible) toolbar.heightPx.toInt() else 0

    fun showPanel(which: ToolbarView.Panel) {
        panel = which
        keyboard.visibility = if (which == ToolbarView.Panel.NONE) VISIBLE else INVISIBLE
        clipboardPanel.visibility = if (which == ToolbarView.Panel.CLIPBOARD) VISIBLE else INVISIBLE
        emojiPanel.visibility = if (which == ToolbarView.Panel.EMOJI) VISIBLE else INVISIBLE
        toolbar.activePanel = which
        if (which != ToolbarView.Panel.NONE) keyboard.cancelTouches()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        keyboard.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val height = keyboard.measuredHeight
        val keysTop = keyboard.keyboardTop
        toolbar.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(toolbar.heightPx.toInt(), MeasureSpec.EXACTLY))
        val panelSpec = MeasureSpec.makeMeasureSpec(height - keysTop, MeasureSpec.EXACTLY)
        clipboardPanel.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), panelSpec)
        emojiPanel.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), panelSpec)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val keysTop = keyboard.keyboardTop
        keyboard.layout(0, 0, w, h)
        toolbar.layout(0, keysTop - toolbar.heightPx.toInt(), w, keysTop)
        clipboardPanel.layout(0, keysTop, w, h)
        emojiPanel.layout(0, keysTop, w, h)
    }
}
