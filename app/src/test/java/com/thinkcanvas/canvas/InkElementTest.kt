package com.thinkcanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InkElementTest {
    private fun stroke(id: String, start: Long, end: Long, x: Float) = InkStroke(
        id = id, startedAt = start, endedAt = end, inputType = InkInputType.TOUCH,
        points = listOf(InkPoint(x, 0f, 0), InkPoint(x + 10f, 10f, end - start)),
    )

    @Test
    fun groupingAndUndoArePerStrokeWhileMoveAndDeleteArePerElement() {
        val text = TextElement(id = "text", text = "固定", x = 30f, y = 40f)
        val board = BoardState(initial = listOf(text))
        assertFalse(board.addInkStroke(InkKind.PEN, stroke("one", 1_000, 1_100, 0f)))
        assertTrue(board.addInkStroke(InkKind.PEN, stroke("two", 2_600, 2_700, 20f)))
        assertEquals(1, board.ink.size)
        assertEquals(2, board.ink.single().strokes.size)
        val id = board.ink.single().id

        assertTrue(board.undo())
        assertEquals(listOf("one"), board.ink.single().strokes.map { it.id })
        assertTrue(board.redo())
        assertEquals(listOf("one", "two"), board.ink.single().strokes.map { it.id })

        assertTrue(board.moveSelection(setOf(id), 50f, -20f))
        assertEquals(50f, board.ink.single().strokes.first().points.first().x)
        assertEquals(70f, board.ink.single().strokes.last().points.first().x)
        assertEquals(text, board.elements.single())
        assertTrue(board.delete(setOf(id)))
        assertTrue(board.ink.isEmpty())
        assertTrue(board.undo())
        assertEquals(2, board.ink.single().strokes.size)
        assertEquals(text, board.elements.single())
    }

    @Test
    fun differentKindOrLateStrokeStartsNewElementAndViewportNeverMovesPoints() {
        val board = BoardState()
        board.addInkStroke(InkKind.PEN, stroke("one", 1_000, 1_100, -20f))
        board.addInkStroke(InkKind.MARKER, stroke("two", 1_200, 1_300, 10f))
        board.addInkStroke(InkKind.MARKER, stroke("three", 2_801, 2_900, 20f))
        assertEquals(3, board.ink.size)
        val before = board.snapshot()
        var viewport = Viewport()
        repeat(10) { viewport = viewport.pan(15f, -10f).zoomAt(100f, 100f, 1.1f) }
        assertEquals(before, board.snapshot())
        assertEquals(setOf(board.ink[0].id), before.lassoSelection(listOf(
            WorldPoint(-30f, -10f), WorldPoint(0f, -10f), WorldPoint(0f, 20f),
            WorldPoint(-30f, 20f),
        )))
    }
}
