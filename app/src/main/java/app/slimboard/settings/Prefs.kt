package app.slimboard.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import app.slimboard.theme.KeyboardTheme
import app.slimboard.ui.keyboard.KeyboardConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Typed wrapper over SharedPreferences. Single source of truth for every user setting.
 * The IME service listens for changes and re-applies them live.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences = context.getSharedPreferences("slimboard", Context.MODE_PRIVATE)

    // Appearance
    var themeMode: String
        get() = sp.getString(THEME_MODE, KeyboardTheme.MODE_SYSTEM) ?: KeyboardTheme.MODE_SYSTEM
        set(v) = sp.edit().putString(THEME_MODE, v).apply()
    var dynamicColor: Boolean by bool(DYNAMIC_COLOR, Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
    var keyBorders: Boolean by bool(KEY_BORDERS, false)
    var heightScale: Int by int(HEIGHT_SCALE, 100)      // percent, 80..130
    var bottomPadding: Int by int(BOTTOM_PADDING, 0)    // dp, 0..40
    var toolbar: Boolean by bool(TOOLBAR, true)
    var oneHanded: Boolean by bool(ONE_HANDED, false)
    var oneHandedRight: Boolean by bool(ONE_HANDED_RIGHT, true)

    // Layout
    var numberRow: Boolean by bool(NUMBER_ROW, false)

    // Typing
    var autoCap: Boolean by bool(AUTO_CAP, true)
    var doubleSpacePeriod: Boolean by bool(DOUBLE_SPACE_PERIOD, true)
    var spaceCursor: Boolean by bool(SPACE_CURSOR, true)
    var backspaceSwipe: Boolean by bool(BACKSPACE_SWIPE, true)
    var longPressMs: Int by int(LONG_PRESS_MS, 300)     // 200..500
    var incognito: Boolean by bool(INCOGNITO, false)

    // Feedback
    var keyPreview: Boolean by bool(KEY_PREVIEW, true)
    var haptics: Boolean by bool(HAPTICS, true)
    var sound: Boolean by bool(SOUND, false)

    // Suggestions
    var suggestions: Boolean by bool(SUGGESTIONS, true)
    /** Off until the dictionary quality is proven in daily use. */
    var autocorrect: Boolean by bool(AUTOCORRECT, false)
    var learnWords: Boolean by bool(LEARN_WORDS, true)

    // Clipboard
    var clipboardEnabled: Boolean by bool(CLIPBOARD_ENABLED, true)
    var clipboardImages: Boolean by bool(CLIPBOARD_IMAGES, true)
    /** Hours after which unpinned items are removed. 0 = never. */
    var clipboardExpiryHours: Int by int(CLIPBOARD_EXPIRY_HOURS, 24)
    var clipboardMaxImageMb: Int by int(CLIPBOARD_MAX_IMAGE_MB, 10)

    // Text shortcuts: typed word (lowercase) → expansion
    var shortcuts: Map<String, String>
        get() = try {
            val o = JSONObject(sp.getString(SHORTCUTS, "{}") ?: "{}")
            LinkedHashMap<String, String>().also { m -> for (k in o.keys()) m[k] = o.getString(k) }
        } catch (e: Exception) { emptyMap() }
        set(v) = sp.edit().putString(SHORTCUTS, JSONObject(v as Map<*, *>).toString()).apply()

    // Apps where SlimBoard has been used (package → label) and apps excluded from learning
    var appsSeen: Map<String, String>
        get() = try {
            val o = JSONObject(sp.getString(APPS_SEEN, "{}") ?: "{}")
            LinkedHashMap<String, String>().also { m -> for (k in o.keys()) m[k] = o.getString(k) }
        } catch (e: Exception) { emptyMap() }
        set(v) = sp.edit().putString(APPS_SEEN, JSONObject(v as Map<*, *>).toString()).apply()
    var noLearnApps: Set<String>
        get() = try {
            val a = JSONArray(sp.getString(NO_LEARN_APPS, "[]") ?: "[]")
            HashSet<String>().also { s -> for (i in 0 until a.length()) s.add(a.getString(i)) }
        } catch (e: Exception) { emptySet() }
        set(v) = sp.edit().putString(NO_LEARN_APPS, JSONArray(v.toList()).toString()).apply()

    // Emoji (internal state, not user-facing)
    var emojiRecents: String
        get() = sp.getString(EMOJI_RECENTS, "") ?: ""
        set(v) = sp.edit().putString(EMOJI_RECENTS, v).apply()
    var emojiSkinTones: String
        get() = sp.getString(EMOJI_SKIN_TONES, "{}") ?: "{}"
        set(v) = sp.edit().putString(EMOJI_SKIN_TONES, v).apply()

    fun keyboardConfig() = KeyboardConfig(
        heightScale = heightScale,
        bottomPaddingDp = bottomPadding,
        keyBorders = keyBorders,
        keyPreview = keyPreview,
        haptics = haptics,
        sound = sound,
        spaceCursor = spaceCursor,
        backspaceSwipe = backspaceSwipe,
        longPressMs = longPressMs,
    )

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)

    /** Every stored setting as JSON, for backup. */
    fun exportAll(): JSONObject {
        val o = JSONObject()
        for ((k, v) in sp.all) if (v != null) o.put(k, v)
        return o
    }

    /** Replaces stored settings with the given JSON (types inferred). Unknown keys are kept as-is. */
    fun importAll(o: JSONObject) {
        val e = sp.edit()
        for (k in o.keys()) {
            when (val v = o.get(k)) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Double -> e.putFloat(k, v.toFloat())
                is String -> e.putString(k, v)
                else -> Unit
            }
        }
        e.apply()
    }

    // ---- tiny delegates ----

    private fun bool(key: String, default: Boolean) = object : kotlin.properties.ReadWriteProperty<Prefs, Boolean> {
        override fun getValue(thisRef: Prefs, property: kotlin.reflect.KProperty<*>) = sp.getBoolean(key, default)
        override fun setValue(thisRef: Prefs, property: kotlin.reflect.KProperty<*>, value: Boolean) =
            sp.edit().putBoolean(key, value).apply()
    }

    private fun int(key: String, default: Int) = object : kotlin.properties.ReadWriteProperty<Prefs, Int> {
        override fun getValue(thisRef: Prefs, property: kotlin.reflect.KProperty<*>) = sp.getInt(key, default)
        override fun setValue(thisRef: Prefs, property: kotlin.reflect.KProperty<*>, value: Int) =
            sp.edit().putInt(key, value).apply()
    }

    companion object {
        const val THEME_MODE = "theme_mode"
        const val DYNAMIC_COLOR = "dynamic_color"
        const val KEY_BORDERS = "key_borders"
        const val HEIGHT_SCALE = "height_scale"
        const val BOTTOM_PADDING = "bottom_padding"
        const val TOOLBAR = "toolbar"
        const val ONE_HANDED = "one_handed"
        const val ONE_HANDED_RIGHT = "one_handed_right"
        const val NUMBER_ROW = "number_row"
        const val AUTO_CAP = "auto_cap"
        const val DOUBLE_SPACE_PERIOD = "double_space_period"
        const val SPACE_CURSOR = "space_cursor"
        const val BACKSPACE_SWIPE = "backspace_swipe"
        const val LONG_PRESS_MS = "long_press_ms"
        const val INCOGNITO = "incognito"
        const val KEY_PREVIEW = "key_preview"
        const val HAPTICS = "haptics"
        const val SOUND = "sound"
        const val SUGGESTIONS = "suggestions"
        const val AUTOCORRECT = "autocorrect"
        const val LEARN_WORDS = "learn_words"
        const val CLIPBOARD_ENABLED = "clipboard_enabled"
        const val CLIPBOARD_IMAGES = "clipboard_images"
        const val CLIPBOARD_EXPIRY_HOURS = "clipboard_expiry_hours"
        const val CLIPBOARD_MAX_IMAGE_MB = "clipboard_max_image_mb"
        const val SHORTCUTS = "shortcuts"
        const val APPS_SEEN = "apps_seen"
        const val NO_LEARN_APPS = "no_learn_apps"
        const val EMOJI_RECENTS = "emoji_recents"
        const val EMOJI_SKIN_TONES = "emoji_skin_tones"

        /** Keys that are internal state; changing them must not trigger a keyboard re-layout. */
        val INTERNAL_KEYS = setOf(EMOJI_RECENTS, EMOJI_SKIN_TONES, APPS_SEEN)
    }
}
