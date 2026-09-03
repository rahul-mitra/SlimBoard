package app.slimboard.layout

import android.content.res.AssetManager

/**
 * Loads layouts from assets/layouts/<name>.json and applies runtime variations
 * (number row, email/URL bottom row). Parsed results are cached per variation.
 */
class LayoutRepository(private val assets: AssetManager) {

    enum class Variant { NONE, EMAIL, URL }

    private data class CacheKey(val name: String, val numberRow: Boolean, val variant: Variant)

    private val base = HashMap<String, KeyboardLayout>()
    private val cache = HashMap<CacheKey, KeyboardLayout>()

    fun get(name: String, numberRow: Boolean, variant: Variant): KeyboardLayout {
        val key = CacheKey(name, numberRow, variant)
        return cache.getOrPut(key) {
            var layout = load(name)
            if (numberRow && layout.numberRowCompatible) layout = withNumberRow(layout)
            if (variant != Variant.NONE && name == QWERTY) layout = withVariant(layout, variant)
            layout
        }
    }

    private fun load(name: String): KeyboardLayout = base.getOrPut(name) {
        val json = assets.open("layouts/$name.json").bufferedReader().use { it.readText() }
        LayoutParser.parse(json)
    }

    /** Prepends a digit row and strips the digit hints from what used to be the top row. */
    private fun withNumberRow(layout: KeyboardLayout): KeyboardLayout {
        val digits = "1234567890".map { Key(KeyType.CHAR, it.toString(), shiftable = false) }
        val rows = ArrayList<List<Key>>(layout.rows.size + 1)
        rows.add(digits)
        layout.rows.forEachIndexed { index, row ->
            rows.add(
                if (index == 0) row.map { k ->
                    if (k.type == KeyType.CHAR && k.longPress.firstOrNull()?.all { c -> c.isDigit() } == true)
                        k.copy(longPress = k.longPress.drop(1))
                    else k
                } else row,
            )
        }
        return KeyboardLayout(layout.name, rows, layout.showHints, layout.numberRowCompatible)
    }

    /** Email fields get an @ key and .com under the period; URL fields get / and .com. */
    private fun withVariant(layout: KeyboardLayout, variant: Variant): KeyboardLayout {
        val replacement = if (variant == Variant.EMAIL) "@" else "/"
        val domains = listOf(".com", ".in", ".org", ".net", ".co", ".io", ".dev")
        val rows = layout.rows.mapIndexed { index, row ->
            if (index != layout.rows.lastIndex) row else row.map { k ->
                when {
                    k.type == KeyType.CHAR && k.text == "," -> k.copy(text = replacement, label = replacement, longPress = listOf(","))
                    k.type == KeyType.CHAR && k.text == "." -> k.copy(longPress = domains + k.longPress)
                    else -> k
                }
            }
        }
        return KeyboardLayout(layout.name, rows, layout.showHints, layout.numberRowCompatible)
    }

    companion object {
        const val QWERTY = "qwerty"
        const val SYMBOLS = "symbols"
        const val SYMBOLS2 = "symbols2"
        const val NUMPAD = "numpad"
        const val PHONE = "phone"

        /** Layers on which shift and auto-capitalisation apply. */
        fun isLetters(name: String) = name == QWERTY
    }
}
