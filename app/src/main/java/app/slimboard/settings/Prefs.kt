package app.slimboard.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import app.slimboard.theme.KeyboardTheme
import app.slimboard.ui.keyboard.KeyboardConfig

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
    }
}
