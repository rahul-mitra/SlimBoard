package app.slimboard.clipboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.slimboard.theme.KeyboardTheme

/**
 * Clipboard history panel shown in place of the keys. Plain platform Views, no library widgets.
 * Tap pastes, long-press flips a card into Pin / Delete actions.
 */
class ClipboardPanel(context: Context, private val store: ClipboardStore, private val listener: Listener) : LinearLayout(context) {

    interface Listener {
        fun onPasteText(text: String)
        fun onPasteImage(item: ClipItem)
    }

    var theme: KeyboardTheme = KeyboardTheme.DARK
        set(v) { field = v; restyle(); rebuild() }

    /** Hours until unpinned items expire, 0 = never. Shown in the header. */
    var expiryHours: Int = 24
        set(v) { field = v; updateSubtitle() }

    private val title = TextView(context)
    private val subtitle = TextView(context)
    private val clearButton = TextView(context)
    private val scroll = ScrollView(context)
    private val grid = GridLayout(context)
    private val empty = TextView(context)
    private val thumbs = LruCache<Long, Bitmap>(40)
    private var actionCardId = -1L
    private val storeListener: () -> Unit = { rebuild() }

    init {
        orientation = VERTICAL

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(12), dp(6))
        }
        val titles = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        title.text = "Clipboard"
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        title.typeface = Typeface.DEFAULT_BOLD
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        titles.addView(title)
        titles.addView(subtitle)
        header.addView(titles)

        clearButton.text = "Clear"
        clearButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        clearButton.setPadding(dp(14), dp(8), dp(14), dp(8))
        clearButton.setOnClickListener { store.clearUnpinned() }
        header.addView(clearButton)
        addView(header)

        grid.columnCount = 2
        grid.setPadding(dp(8), 0, dp(8), dp(8))
        scroll.addView(grid)
        scroll.isVerticalScrollBarEnabled = false
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        empty.gravity = Gravity.CENTER
        empty.setPadding(dp(32), dp(24), dp(32), dp(24))
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)

        restyle()
        updateSubtitle()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        store.addListener(storeListener)
        rebuild()
    }

    override fun onDetachedFromWindow() {
        store.removeListener(storeListener)
        super.onDetachedFromWindow()
    }

    fun refresh() = rebuild()

    private fun updateSubtitle() {
        subtitle.text = when {
            expiryHours <= 0 -> "Pinned and unpinned items are kept until you delete them"
            expiryHours < 24 -> "Unpinned items clear after $expiryHours h · pinned stay"
            expiryHours == 24 -> "Unpinned items clear after 24 h · pinned stay"
            else -> "Unpinned items clear after ${expiryHours / 24} days · pinned stay"
        }
    }

    private fun restyle() {
        setBackgroundColor(theme.background)
        title.setTextColor(theme.label)
        subtitle.setTextColor(theme.hint)
        clearButton.setTextColor(theme.label)
        clearButton.background = pill(theme.keyFunction)
        empty.setTextColor(theme.hint)
    }

    private fun rebuild() {
        grid.removeAllViews()
        actionCardId = -1L
        val items = store.items()
        if (items.isEmpty()) {
            empty.text = "Nothing here yet.\nText and images you copy will show up here."
            grid.addView(empty, GridLayout.LayoutParams(GridLayout.spec(0), GridLayout.spec(0, 2, 1f)).apply {
                width = 0
            })
            return
        }
        for (item in items) grid.addView(card(item), cellParams())
    }

    private fun cellParams() = GridLayout.LayoutParams(
        GridLayout.spec(GridLayout.UNDEFINED, 1f),
        GridLayout.spec(GridLayout.UNDEFINED, 1f),
    ).apply {
        width = 0
        setMargins(dp(4), dp(4), dp(4), dp(4))
    }

    private fun card(item: ClipItem): View {
        val card = FrameLayout(context)
        card.background = rounded(if (item.pinned) blend(theme.key, theme.accent, 0.15f) else theme.key)
        card.minimumHeight = dp(72)
        card.isClickable = true
        card.isFocusable = true

        val content: View = when (item.type) {
            ClipItem.Type.TEXT -> TextView(context).apply {
                text = item.text.trim()
                maxLines = 4
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(theme.label)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            ClipItem.Type.IMAGE -> ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110))
                setImageBitmap(thumb(item))
                clipToOutline = true
                background = rounded(theme.keyFunction)
            }
        }
        card.addView(content)

        if (item.pinned) {
            card.addView(TextView(context).apply {
                text = "PINNED"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(theme.labelOnAccent)
                background = pill(theme.accent)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
                    .apply { setMargins(0, dp(6), dp(6), 0) }
            })
        }

        card.setOnClickListener {
            if (actionCardId != -1L) { rebuild(); return@setOnClickListener }
            when (item.type) {
                ClipItem.Type.TEXT -> listener.onPasteText(item.text)
                ClipItem.Type.IMAGE -> listener.onPasteImage(item)
            }
        }
        card.setOnLongClickListener {
            showActions(card, item)
            true
        }
        return card
    }

    private fun showActions(card: FrameLayout, item: ClipItem) {
        actionCardId = item.id
        card.removeAllViews()
        card.background = rounded(theme.keyFunction)
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }
        row.addView(actionButton(if (item.pinned) "Unpin" else "Pin", theme.accent, theme.labelOnAccent) {
            store.setPinned(item.id, !item.pinned)
        })
        row.addView(actionButton("Delete", theme.keyPressed, theme.label) {
            store.delete(item.id)
        })
        card.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        card.setOnClickListener { rebuild() }
    }

    private fun actionButton(label: String, bg: Int, fg: Int, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextColor(fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        background = pill(bg)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(6), 0, dp(6), 0) }
        setOnClickListener { onClick() }
    }

    private fun thumb(item: ClipItem): Bitmap? {
        thumbs.get(item.id)?.let { return it }
        val f = item.thumbFile ?: return null
        val bmp = BitmapFactory.decodeFile(f.path) ?: return null
        thumbs.put(item.id, bmp)
        return bmp
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(color)
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(20).toFloat()
        setColor(color)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt() and 0xFF
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
