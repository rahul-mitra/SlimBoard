package app.slimboard.layout

import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject

enum class KeyType { CHAR, SHIFT, BACKSPACE, SPACE, ENTER, LAYER, PAD }

/**
 * One key. Immutable definition plus the mutable rect/pressed state the view needs.
 * Keys are data drawn by a single view; they are never Android Views.
 */
class Key(
    val type: KeyType,
    /** Text committed when the key is tapped (CHAR only). */
    val text: String = "",
    /** Label drawn on the key. Defaults to [text]. */
    val label: String = text,
    /** Long-press options. The first one is the default and is drawn as the corner hint. */
    val longPress: List<String> = emptyList(),
    val weight: Float = 1f,
    /** LAYER keys: name of the layout to switch to. */
    val target: String = "",
    /** CHAR keys: whether shift changes the committed text and label. */
    val shiftable: Boolean = true,
    /** Whether the corner hint is drawn (if the layout allows hints at all). */
    val showHint: Boolean = true,
) {
    val rect = RectF()
    var pressed = false

    val hint: String? get() = longPress.firstOrNull()

    /** Single glyph labels get the big font; "?123", "ABC", "Send" get the small one. */
    val isSingleGlyph: Boolean = label.isNotEmpty() && label.codePointCount(0, label.length) == 1

    fun copy(
        longPress: List<String> = this.longPress,
        text: String = this.text,
        label: String = this.label,
    ) = Key(type, text, label, longPress, weight, target, shiftable, showHint)
}

class KeyboardLayout(
    val name: String,
    val rows: List<List<Key>>,
    val showHints: Boolean = true,
    val numberRowCompatible: Boolean = false,
) {
    val keys: List<Key> = rows.flatten().filter { it.type != KeyType.PAD }

    companion object {
        val EMPTY = KeyboardLayout("empty", emptyList())
    }
}

/** Parses the JSON layout format in assets/layouts. See qwerty.json for the reference shape. */
object LayoutParser {

    fun parse(json: String): KeyboardLayout {
        val root = JSONObject(json)
        val rowsJson = root.getJSONArray("rows")
        val rows = List(rowsJson.length()) { r ->
            val row = rowsJson.getJSONArray(r)
            List(row.length()) { i -> parseKey(row.getJSONObject(i)) }
        }
        return KeyboardLayout(
            name = root.getString("name"),
            rows = rows,
            showHints = root.optBoolean("hints", true),
            numberRowCompatible = root.optBoolean("numberRowCompatible", false),
        )
    }

    private fun parseKey(o: JSONObject): Key {
        if (o.has("pad")) return Key(KeyType.PAD, weight = o.getDouble("pad").toFloat())
        val w = o.optDouble("w", 1.0).toFloat()
        val lp = parseLongPress(o.opt("lp"))
        return when (val code = o.optString("code", "")) {
            "" -> {
                val k = o.getString("k")
                Key(
                    type = KeyType.CHAR,
                    text = k,
                    label = o.optString("label", k),
                    longPress = lp,
                    weight = w,
                    shiftable = o.optBoolean("shiftable", true),
                    showHint = o.optBoolean("hint", true),
                )
            }
            "shift" -> Key(KeyType.SHIFT, weight = w)
            "backspace" -> Key(KeyType.BACKSPACE, weight = w)
            "space" -> Key(KeyType.SPACE, weight = w, longPress = lp)
            "enter" -> Key(KeyType.ENTER, weight = w)
            "layer" -> Key(
                type = KeyType.LAYER,
                label = o.getString("label"),
                target = o.getString("target"),
                weight = w,
            )
            else -> throw IllegalArgumentException("Unknown key code '$code'")
        }
    }

    /** "lp" is either a string (one option per code point) or an array of strings. */
    private fun parseLongPress(v: Any?): List<String> = when (v) {
        null -> emptyList()
        is String -> v.codePoints().toArray().map { String(Character.toChars(it)) }
        is JSONArray -> List(v.length()) { v.getString(it) }
        else -> emptyList()
    }
}
