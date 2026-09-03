package app.slimboard.theme

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build

/** Flat colour set used by KeyboardView. No drawables, no styles, so it is trivially cheap to swap. */
data class KeyboardTheme(
    val isDark: Boolean,
    val background: Int,
    val key: Int,
    val keyFunction: Int,
    val keyPressed: Int,
    val accent: Int,
    val label: Int,
    val labelOnAccent: Int,
    val hint: Int,
    val popupBackground: Int,
    val popupSelected: Int,
    val border: Int,
) {
    companion object {
        const val MODE_SYSTEM = "system"
        const val MODE_LIGHT = "light"
        const val MODE_DARK = "dark"

        val DARK = KeyboardTheme(
            isDark = true,
            background = 0xFF1B1F27.toInt(),
            key = 0xFF2E3440.toInt(),
            keyFunction = 0xFF252A34.toInt(),
            keyPressed = 0xFF4C566A.toInt(),
            accent = 0xFF3B6EA5.toInt(),
            label = 0xFFECEFF4.toInt(),
            labelOnAccent = 0xFFFFFFFF.toInt(),
            hint = 0xFF9AA3B2.toInt(),
            popupBackground = 0xFF3B4252.toInt(),
            popupSelected = 0xFF3B6EA5.toInt(),
            border = 0xFF3B4252.toInt(),
        )

        val LIGHT = KeyboardTheme(
            isDark = false,
            background = 0xFFE8EAED.toInt(),
            key = 0xFFFFFFFF.toInt(),
            keyFunction = 0xFFD2D6DB.toInt(),
            keyPressed = 0xFFB8BEC7.toInt(),
            accent = 0xFF4A7ABF.toInt(),
            label = 0xFF202124.toInt(),
            labelOnAccent = 0xFFFFFFFF.toInt(),
            hint = 0xFF5F6368.toInt(),
            popupBackground = 0xFFFFFFFF.toInt(),
            popupSelected = 0xFF4A7ABF.toInt(),
            border = 0xFFC4C8CE.toInt(),
        )

        fun isSystemDark(context: Context): Boolean =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

        fun resolve(context: Context, mode: String, dynamicColor: Boolean): KeyboardTheme {
            val dark = when (mode) {
                MODE_LIGHT -> false
                MODE_DARK -> true
                else -> isSystemDark(context)
            }
            if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return dynamic(context, dark)
            }
            return if (dark) DARK else LIGHT
        }

        /** Material You: built from the system_neutral / system_accent palettes on Android 12+. */
        private fun dynamic(context: Context, dark: Boolean): KeyboardTheme {
            fun c(id: Int) = context.getColor(id)
            return if (dark) KeyboardTheme(
                isDark = true,
                background = c(android.R.color.system_neutral1_900),
                key = c(android.R.color.system_neutral1_800),
                keyFunction = c(android.R.color.system_neutral2_800),
                keyPressed = c(android.R.color.system_neutral1_600),
                accent = c(android.R.color.system_accent1_600),
                label = c(android.R.color.system_neutral1_50),
                labelOnAccent = c(android.R.color.system_accent1_0),
                hint = c(android.R.color.system_neutral2_300),
                popupBackground = c(android.R.color.system_neutral1_700),
                popupSelected = c(android.R.color.system_accent1_600),
                border = c(android.R.color.system_neutral1_700),
            ) else KeyboardTheme(
                isDark = false,
                background = c(android.R.color.system_neutral1_100),
                key = c(android.R.color.system_neutral1_0),
                keyFunction = c(android.R.color.system_neutral2_100),
                keyPressed = c(android.R.color.system_neutral1_300),
                accent = c(android.R.color.system_accent1_600),
                label = c(android.R.color.system_neutral1_900),
                labelOnAccent = c(android.R.color.system_accent1_0),
                hint = c(android.R.color.system_neutral2_600),
                popupBackground = c(android.R.color.system_neutral1_0),
                popupSelected = c(android.R.color.system_accent1_600),
                border = c(android.R.color.system_neutral1_300),
            )
        }

        /** Utility for callers that need a translucent variant of a colour. */
        fun withAlpha(color: Int, alpha: Int): Int = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }
}
