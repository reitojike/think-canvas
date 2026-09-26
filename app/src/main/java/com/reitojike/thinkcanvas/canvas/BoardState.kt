package com.reitojike.thinkcanvas.canvas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID

enum class TextKind { BODY, TITLE }
enum class TextColor { INK, VERMILION }

data class TextElement(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val kind: TextKind = TextKind.BODY,
    val color: TextColor = TextColor.INK,
    val x: Float,
    val y: Float,
)

class BoardState(initial: List<TextElement> = emptyList()) {
    var elements by mutableStateOf(initial)
        private set

    private data class Change(val before: TextElement?, val after: TextElement?)
    private val undoStack = ArrayDeque<Change>()
    private val redoStack = ArrayDeque<Change>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun create(text: String, kind: TextKind, color: TextColor, x: Float, y: Float): TextElement? {
        if (text.isBlank()) return null
        val element = TextElement(text = text, kind = kind, color = color, x = x, y = y)
        record(Change(null, element))
        return element
    }

    fun edit(id: String, text: String, kind: TextKind, color: TextColor): Boolean {
        if (text.isBlank()) return false
        val before = elements.firstOrNull { it.id == id } ?: return false
        val after = before.copy(text = text, kind = kind, color = color)
        if (before == after) return false
        record(Change(before, after))
        return true
    }

    fun move(id: String, x: Float, y: Float): Boolean {
        val before = elements.firstOrNull { it.id == id } ?: return false
        val after = before.copy(x = x, y = y)
        if (before == after) return false
        record(Change(before, after))
        return true
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        val change = undoStack.removeLast()
        apply(change.after, change.before)
        redoStack.addLast(change)
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        val change = redoStack.removeLast()
        apply(change.before, change.after)
        undoStack.addLast(change)
        return true
    }

    private fun record(change: Change) {
        apply(change.before, change.after)
        undoStack.addLast(change)
        if (undoStack.size > 80) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun apply(before: TextElement?, after: TextElement?) {
        elements = when {
            before == null && after != null -> elements + after
            before != null && after == null -> elements.filterNot { it.id == before.id }
            before != null && after != null -> elements.map { if (it.id == before.id) after else it }
            else -> elements
        }
    }
}
