package com.reitojike.thinkcanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardStateTest {
    @Test
    fun createEditMoveUndoRedoKeepsExpectedStates() {
        val board = BoardState()
        assertEquals(null, board.create("  ", TextKind.BODY, TextColor.INK, 10f, 20f))
        assertFalse(board.canUndo)

        val created = board.create("考え", TextKind.BODY, TextColor.INK, 10f, 20f)!!
        assertTrue(board.edit(created.id, "見出し", TextKind.TITLE, TextColor.VERMILION))
        assertTrue(board.move(created.id, 42f, -9f))
        assertEquals(42f, board.elements.single().x)

        assertTrue(board.undo())
        assertEquals(10f, board.elements.single().x)
        assertTrue(board.undo())
        assertEquals("考え", board.elements.single().text)
        assertTrue(board.undo())
        assertTrue(board.elements.isEmpty())
        assertFalse(board.canUndo)

        assertTrue(board.redo())
        assertTrue(board.redo())
        assertTrue(board.redo())
        assertEquals("見出し", board.elements.single().text)
        assertEquals(TextKind.TITLE, board.elements.single().kind)
        assertEquals(TextColor.VERMILION, board.elements.single().color)
        assertEquals(-9f, board.elements.single().y)
        assertFalse(board.canRedo)
    }

    @Test
    fun viewportChangesNeverChangeWorldCoordinates() {
        val element = TextElement(text = "配置", x = 173.25f, y = -88.5f)
        val board = BoardState(listOf(element))
        var viewport = Viewport()
        repeat(10) {
            viewport = viewport.pan(25f, -12f).zoomAt(120f, 80f, 1.2f)
            val (sx, sy) = viewport.worldToScreen(element.x, element.y)
            val (wx, wy) = viewport.screenToWorld(sx, sy)
            assertEquals(element.x, wx, 0.001f)
            assertEquals(element.y, wy, 0.001f)
        }
        assertEquals(element, board.elements.single())
        assertFalse(board.canUndo)
        assertEquals(3f, viewport.scale)
    }

    @Test
    fun zoomKeepsPointUnderFingersAndClamps() {
        val before = Viewport(scale = 1f, panX = 15f, panY = -20f)
        val world = before.screenToWorld(120f, 80f)
        val after = before.zoomAt(120f, 80f, 2f)
        assertEquals(120f, after.worldToScreen(world.first, world.second).first, 0.001f)
        assertEquals(80f, after.worldToScreen(world.first, world.second).second, 0.001f)
        assertEquals(0.15f, after.zoomAt(120f, 80f, 0.001f).scale)
    }

    @Test
    fun deletingAttachedTargetRemovesArrowInOneUndoStep() {
        val board = BoardState()
        val shape = board.addShape(ShapeKind.RECTANGLE, 0f, 0f, 120f, 80f)
        val arrow = board.addArrow(ArrowEnd.Attached(shape.id, 1f, .5f), ArrowEnd.Free(200f, 40f))!!
        assertTrue(board.delete(setOf(shape.id)))
        assertTrue(board.shapes.isEmpty())
        assertTrue(board.arrows.isEmpty())
        assertTrue(board.undo())
        assertEquals(shape, board.shapes.single())
        assertEquals(arrow, board.arrows.single())
    }

    @Test
    fun selectedFreeArrowEndsMoveOnceWithGroupedShapes() {
        val board = BoardState()
        val shape = board.addShape(ShapeKind.RECTANGLE, 0f, 0f, 100f, 80f)
        val arrow = board.addArrow(ArrowEnd.Attached(shape.id, 1f, .5f), ArrowEnd.Free(200f, 40f))!!
        assertTrue(board.moveSelection(setOf(shape.id, arrow.id), 30f, 10f))
        assertEquals(30f, board.shapes.single().x)
        assertEquals(ArrowEnd.Free(230f, 50f), board.arrows.single().to)
        assertTrue(board.undo())
        assertEquals(shape, board.shapes.single())
        assertEquals(arrow, board.arrows.single())
    }

    @Test
    fun historyKeepsMostRecentEightyChanges() {
        val board = BoardState()
        repeat(81) { board.addShape(ShapeKind.RECTANGLE, it.toFloat(), 0f, 40f, 30f) }
        repeat(80) { assertTrue(board.undo()) }
        assertEquals(1, board.shapes.size)
        assertFalse(board.undo())
    }
}
