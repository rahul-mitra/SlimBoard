package app.slimboard.ui.keyboard

/** Snapshot of the settings KeyboardView cares about. Built by Prefs, applied by the service. */
data class KeyboardConfig(
    val heightScale: Int = 100,
    val bottomPaddingDp: Int = 0,
    val keyBorders: Boolean = false,
    val keyPreview: Boolean = true,
    val haptics: Boolean = true,
    val sound: Boolean = false,
    val spaceCursor: Boolean = true,
    val backspaceSwipe: Boolean = true,
    val longPressMs: Int = 300,
)
