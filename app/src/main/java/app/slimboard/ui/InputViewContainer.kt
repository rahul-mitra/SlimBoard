package app.slimboard.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import app.slimboard.ui.keyboard.KeyboardView

/**
 * Root of the IME input view. Stacks, bottom to top: toolbar, panels, side bar, keyboard.
 *
 * The keyboard is a full-height view whose top part (headroom) is transparent and only used for
 * popups. The toolbar sits inside that headroom, just above the keys; the keyboard is drawn on top
 * so popups can overlap the toolbar, and it hands touches in the headroom back to the toolbar.
 * Panels replace the keys area exactly, so the app behind never resizes when switching.
 *
 * One-handed mode narrows everything to [ONE_HANDED_FRACTION] of the width, anchored left or
 * right, and fills the rest with a side bar.
 */
class InputViewContainer(
    context: Context,
    val keyboard: KeyboardView,
    val toolbar: ToolbarView,
    private val panels: Map<ToolbarView.Panel, View>,
    private val sideBar: SideBarView,
) : ViewGroup(context) {

    var toolbarVisible = true
        set(v) {
            field = v
            toolbar.visibility = if (v) VISIBLE else GONE
            requestLayout()
        }

    var oneHanded = false
        set(v) { field = v; sideBar.visibility = if (v) VISIBLE else GONE; requestLayout() }

    var oneHandedRight = true
        set(v) { field = v; sideBar.keyboardOnRight = v; requestLayout() }

    var panel = ToolbarView.Panel.NONE
        private set

    init {
        addView(toolbar)
        for (p in panels.values) { addView(p); p.visibility = INVISIBLE }
        addView(sideBar)
        sideBar.visibility = GONE
        addView(keyboard)
    }

    /** Top of what the user perceives as the keyboard, in this view's coordinates. */
    val contentTop: Int
        get() = keyboard.keyboardTop - if (toolbarVisible) toolbar.heightPx.toInt() else 0

    fun showPanel(which: ToolbarView.Panel) {
        panel = which
        keyboard.visibility = if (which == ToolbarView.Panel.NONE) VISIBLE else INVISIBLE
        for ((p, v) in panels) v.visibility = if (p == which) VISIBLE else INVISIBLE
        toolbar.activePanel = which
        if (which != ToolbarView.Panel.NONE) keyboard.cancelTouches()
    }

    private fun contentWidth(total: Int) = if (oneHanded) (total * ONE_HANDED_FRACTION).toInt() else total

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val cw = contentWidth(width)
        val exactW = MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY)
        keyboard.measure(exactW, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val height = keyboard.measuredHeight
        val keysTop = keyboard.keyboardTop
        toolbar.measure(exactW, MeasureSpec.makeMeasureSpec(toolbar.heightPx.toInt(), MeasureSpec.EXACTLY))
        val panelSpec = MeasureSpec.makeMeasureSpec(height - keysTop, MeasureSpec.EXACTLY)
        for (p in panels.values) p.measure(exactW, panelSpec)
        if (oneHanded) {
            sideBar.measure(
                MeasureSpec.makeMeasureSpec(width - cw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height - contentTop, MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val cw = contentWidth(w)
        val x0 = if (oneHanded && oneHandedRight) w - cw else 0
        val keysTop = keyboard.keyboardTop
        keyboard.layout(x0, 0, x0 + cw, h)
        toolbar.layout(x0, keysTop - toolbar.heightPx.toInt(), x0 + cw, keysTop)
        for (p in panels.values) p.layout(x0, keysTop, x0 + cw, h)
        if (oneHanded) {
            val sx = if (oneHandedRight) 0 else cw
            sideBar.layout(sx, contentTop, sx + (w - cw), h)
        }
    }

    companion object {
        const val ONE_HANDED_FRACTION = 0.78f
    }
}
