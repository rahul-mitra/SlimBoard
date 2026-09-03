package app.slimboard.text

import android.view.inputmethod.InputConnection

/**
 * Undo/redo for the keyboard's own edits (committed words, autocorrections, pastes, word deletes).
 * Each action says what text it inserted at the cursor and what it replaced. Undo is only applied
 * when the text before the cursor still matches, so a moved cursor silently drops stale entries.
 */
class EditHistory(private val capacity: Int = 30) {

    class Action(val inserted: String, val replaced: String)

    private val undoStack = ArrayDeque<Action>()
    private val redoStack = ArrayDeque<Action>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun record(inserted: String, replaced: String = "") {
        if (inserted.isEmpty() && replaced.isEmpty()) return
        undoStack.addLast(Action(inserted, replaced))
        while (undoStack.size > capacity) undoStack.removeFirst()
        redoStack.clear()
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    /** Returns true if something was undone. */
    fun undo(ic: InputConnection): Boolean {
        while (undoStack.isNotEmpty()) {
            val a = undoStack.removeLast()
            if (!endsWith(ic, a.inserted)) continue   // cursor moved on; stale
            ic.beginBatchEdit()
            if (a.inserted.isNotEmpty()) ic.deleteSurroundingText(a.inserted.length, 0)
            if (a.replaced.isNotEmpty()) ic.commitText(a.replaced, 1)
            ic.endBatchEdit()
            redoStack.addLast(a)
            return true
        }
        return false
    }

    fun redo(ic: InputConnection): Boolean {
        while (redoStack.isNotEmpty()) {
            val a = redoStack.removeLast()
            if (!endsWith(ic, a.replaced)) continue
            ic.beginBatchEdit()
            if (a.replaced.isNotEmpty()) ic.deleteSurroundingText(a.replaced.length, 0)
            if (a.inserted.isNotEmpty()) ic.commitText(a.inserted, 1)
            ic.endBatchEdit()
            undoStack.addLast(a)
            return true
        }
        return false
    }

    private fun endsWith(ic: InputConnection, text: String): Boolean {
        if (text.isEmpty()) return true
        val before = ic.getTextBeforeCursor(text.length, 0) ?: return false
        return before.toString() == text
    }
}
