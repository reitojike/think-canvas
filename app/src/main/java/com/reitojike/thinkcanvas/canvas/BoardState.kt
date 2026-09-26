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

class BoardState(
    initial: List<TextElement> = emptyList(),
    initialShapes: List<ShapeElement> = emptyList(),
    initialArrows: List<ArrowElement> = emptyList(),
) {
    var elements by mutableStateOf(initial)
        private set
    var shapes by mutableStateOf(initialShapes)
        private set
    var arrows by mutableStateOf(initialArrows)
        private set

    private data class Change(val before: BoardSnapshot, val after: BoardSnapshot)
    private val undoStack = ArrayDeque<Change>()
    private val redoStack = ArrayDeque<Change>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun snapshot(): BoardSnapshot = BoardSnapshot(elements, shapes, arrows)

    fun create(text: String, kind: TextKind, color: TextColor, x: Float, y: Float): TextElement? {
        if (text.isBlank()) return null
        val element = TextElement(text = text, x = x, y = y, kind = kind, color = color)
        record(snapshot().copy(texts = elements + element))
        return element
    }

    fun edit(id: String, text: String, kind: TextKind, color: TextColor): Boolean {
        if (text.isBlank()) return false
        val before = elements.firstOrNull { it.id == id } ?: return false
        val after = before.copy(text = text, kind = kind, color = color)
        return record(snapshot().copy(texts = elements.map { if (it.id == id) after else it }))
    }

    fun move(id: String, x: Float, y: Float): Boolean {
        val before = elements.firstOrNull { it.id == id } ?: return false
        val after = before.copy(x = x, y = y)
        return record(snapshot().copy(texts = elements.map { if (it.id == id) after else it }))
    }

    fun apply(next: BoardSnapshot): Boolean = record(next)

    fun addShape(kind: ShapeKind, x: Float, y: Float, width: Float, height: Float,
                 color: TextColor = TextColor.INK, name: String = ""): ShapeElement {
        val shape = ShapeElement(kind = kind, x = x, y = y,
            width = width.coerceAtLeast(40f), height = height.coerceAtLeast(30f), color = color, name = name)
        record(snapshot().copy(shapes = shapes + shape))
        return shape
    }

    fun updateShape(id: String, name: String? = null, color: TextColor? = null): Boolean {
        val current = shapes.firstOrNull { it.id == id } ?: return false
        val updated = current.copy(name = name ?: current.name, color = color ?: current.color)
        return record(snapshot().copy(shapes = shapes.map { if (it.id == id) updated else it }))
    }

    fun resizeShape(id: String, width: Float, height: Float): Boolean {
        val current = shapes.firstOrNull { it.id == id } ?: return false
        val updated = current.copy(width = width.coerceAtLeast(40f), height = height.coerceAtLeast(30f))
        return record(snapshot().copy(shapes = shapes.map { if (it.id == id) updated else it }))
    }

    fun addArrow(from: ArrowEnd, to: ArrowEnd): ArrowElement? {
        val ids = (elements.map { it.id } + shapes.map { it.id }).toSet()
        if (listOf(from, to).any { it is ArrowEnd.Attached && it.targetId !in ids }) return null
        val arrow = ArrowElement(from = from, to = to)
        record(snapshot().copy(arrows = arrows + arrow))
        return arrow
    }

    fun updateArrow(id: String, from: ArrowEnd? = null, to: ArrowEnd? = null,
                    bend: Float? = null, reverse: Boolean = false): Boolean {
        val current = arrows.firstOrNull { it.id == id } ?: return false
        val updated = current.copy(from = from ?: current.from, to = to ?: current.to,
            bend = bend ?: current.bend).let { if (reverse) it.reversed() else it }
        val ids = (elements.map { it.id } + shapes.map { it.id }).toSet()
        if (listOf(updated.from, updated.to).any { it is ArrowEnd.Attached && it.targetId !in ids }) return false
        return record(snapshot().copy(arrows = arrows.map { if (it.id == id) updated else it }))
    }

    fun delete(ids: Set<String>): Boolean {
        if (ids.isEmpty()) return false
        val removedTargets = ids.intersect((elements.map { it.id } + shapes.map { it.id }).toSet())
        return record(BoardSnapshot(
            texts = elements.filterNot { it.id in ids },
            shapes = shapes.filterNot { it.id in ids },
            arrows = arrows.filterNot { arrow ->
                arrow.id in ids || listOf(arrow.from, arrow.to).any {
                    it is ArrowEnd.Attached && it.targetId in removedTargets
                }
            },
        ))
    }

    fun moveSelection(ids: Set<String>, dx: Float, dy: Float): Boolean {
        if (ids.isEmpty() || (dx == 0f && dy == 0f)) return false
        return record(snapshot().translatedSelection(ids, dx, dy))
    }

    fun insertGap(origin: WorldPoint, horizontal: Boolean, amount: Float): Boolean {
        if (amount == 0f || !amount.isFinite()) return false
        val source = snapshot()
        val scope = source.smallestRegionAt(origin)
        val boundary = if (horizontal) origin.x else origin.y
        val movePositive = amount > 0f
        fun inScope(id: String): Boolean {
            if (scope == null) return true
            val shape = source.shapes.firstOrNull { it.id == id }
            if (shape != null && shape.kind == ShapeKind.REGION &&
                shape.bounds().area >= scope.bounds().area) return false
            return source.centerOf(id)?.let { scope.bounds().contains(it) } == true
        }
        fun shift(value: Float) = if ((value >= boundary) == movePositive) amount else 0f
        val updatedTexts = elements.map {
            if (inScope(it.id)) {
                val center = source.centerOf(it.id)!!
                val delta = shift(if (horizontal) center.x else center.y)
                if (horizontal) it.copy(x = it.x + delta) else it.copy(y = it.y + delta)
            } else it
        }
        val updatedShapes = shapes.map { shape ->
            if (shape.id == scope?.id) {
                if (horizontal) {
                    if (amount > 0f) shape.copy(width = (shape.width + amount).coerceAtLeast(40f))
                    else shape.copy(x = shape.x + amount, width = (shape.width - amount).coerceAtLeast(40f))
                } else {
                    if (amount > 0f) shape.copy(height = (shape.height + amount).coerceAtLeast(30f))
                    else shape.copy(y = shape.y + amount, height = (shape.height - amount).coerceAtLeast(30f))
                }
            } else if (!inScope(shape.id)) shape else {
                val low = if (horizontal) shape.x else shape.y
                val high = low + if (horizontal) shape.width else shape.height
                when {
                    low < boundary && high > boundary -> if (horizontal)
                        shape.copy(x = shape.x + minOf(amount, 0f),
                            width = (shape.width + kotlin.math.abs(amount)).coerceAtLeast(40f))
                    else shape.copy(y = shape.y + minOf(amount, 0f),
                        height = (shape.height + kotlin.math.abs(amount)).coerceAtLeast(30f))
                    else -> {
                        val delta = shift(if (horizontal) shape.bounds().center.x else shape.bounds().center.y)
                        if (horizontal) shape.copy(x = shape.x + delta) else shape.copy(y = shape.y + delta)
                    }
                }
            }
        }
        val updatedArrows = arrows.map { arrow ->
            fun move(end: ArrowEnd): ArrowEnd = if (end is ArrowEnd.Free &&
                (scope == null || scope.bounds().contains(WorldPoint(end.x, end.y)))) {
                val delta = shift(if (horizontal) end.x else end.y)
                if (horizontal) end.copy(x = end.x + delta) else end.copy(y = end.y + delta)
            } else end
            arrow.copy(from = move(arrow.from), to = move(arrow.to))
        }
        return record(BoardSnapshot(updatedTexts, updatedShapes, updatedArrows))
    }

    fun undo(): Boolean {
        val change = undoStack.removeLastOrNull() ?: return false
        restore(change.before)
        redoStack.addLast(change)
        return true
    }

    fun redo(): Boolean {
        val change = redoStack.removeLastOrNull() ?: return false
        restore(change.after)
        undoStack.addLast(change)
        return true
    }

    private fun record(after: BoardSnapshot): Boolean {
        val before = snapshot()
        if (before == after) return false
        restore(after)
        undoStack.addLast(Change(before, after))
        if (undoStack.size > 80) undoStack.removeFirst()
        redoStack.clear()
        return true
    }

    private fun restore(value: BoardSnapshot) {
        elements = value.texts
        shapes = value.shapes
        arrows = value.arrows
    }
}
