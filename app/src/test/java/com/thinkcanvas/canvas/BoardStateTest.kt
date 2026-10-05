package com.thinkcanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardStateTest {
    private fun image() = ImageElement(assetId = "8c0a9b01-94fd-404c-944d-62c1249b4d01",
        x = 10f, y = 20f, width = 200f, height = 100f, intrinsicWidth = 400, intrinsicHeight = 200)

    @Test fun imageDeleteRestoresAttachedArrowAndDescriptionInOneUndo() {
        val image = image().copy(altText = "図")
        val board = BoardState(initialImages = listOf(image))
        val arrow = board.addArrow(ArrowEnd.Attached(image.id, 1f, .5f), ArrowEnd.Free(400f, 70f))!!
        assertTrue(board.delete(setOf(image.id)))
        assertTrue(board.images.isEmpty())
        assertTrue(board.arrows.isEmpty())
        assertTrue(image.assetId in board.retainedImageAssetIds)
        assertTrue(board.undo())
        assertEquals(listOf(image), board.images)
        assertEquals(listOf(arrow), board.arrows)
        assertTrue(board.redo())
        assertTrue(board.images.isEmpty())
    }

    @Test fun deletedAssetBecomesUnreferencedOnlyAfterAllRetainingEditsExpire() {
        val image = image()
        val board = BoardState(initialImages = listOf(image))
        board.delete(setOf(image.id))
        repeat(79) { board.create("$it", TextKind.BODY, TextColor.INK, it.toFloat(), 0f) }
        assertTrue(image.assetId in board.retainedImageAssetIds)
        board.create("最後", TextKind.BODY, TextColor.INK, 0f, 0f)
        assertFalse(image.assetId in board.retainedImageAssetIds)
    }

    @Test fun imageMoveResizeAndEmptyDescriptionCanEachBeUndone() {
        val image = image()
        val board = BoardState(initialImages = listOf(image))
        assertTrue(board.moveSelection(setOf(image.id), 30f, -10f))
        assertEquals(40f, board.images.single().x, 0f)
        assertTrue(board.resizeImage(image.id, 300f, 150f))
        assertTrue(board.describeImage(image.id, "図の説明"))
        assertTrue(board.describeImage(image.id, ""))
        assertTrue(board.undo())
        assertEquals("図の説明", board.images.single().altText)
        assertTrue(board.undo())
        assertTrue(board.undo())
        assertEquals(200f, board.images.single().width, 0f)
        assertTrue(board.undo())
        assertEquals(image, board.images.single())
    }

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
    fun textEditUndoRedoReDerivesLogicalMembershipWhileKeepingAnchor() {
        val region = ShapeElement(id = "region", kind = ShapeKind.REGION,
            x = 0f, y = 0f, width = 100f, height = 100f)
        val short = TextElement(id = "text", text = "猫", x = 40f, y = 20f)
        val board = BoardState(listOf(short), listOf(region))
        assertEquals(WorldPoint(45f, 30f), board.snapshot().centerOf(short.id))
        assertTrue(region.bounds().contains(board.snapshot().centerOf(short.id)!!))

        val long = "映画を見たあとに残った違和感と好きだった場面をあとで整理する"
        assertTrue(board.edit(short.id, long, TextKind.BODY, TextColor.INK))
        assertEquals(40f, board.elements.single().x)
        assertEquals(WorldPoint(120f, 40f), board.snapshot().centerOf(short.id))
        assertFalse(region.bounds().contains(board.snapshot().centerOf(short.id)!!))

        assertTrue(board.undo())
        assertEquals(WorldPoint(45f, 30f), board.snapshot().centerOf(short.id))
        assertTrue(board.redo())
        assertEquals(WorldPoint(120f, 40f), board.snapshot().centerOf(short.id))
        assertEquals(40f, board.elements.single().x)
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
    fun viewportChangesNeverMoveShapesOrArrowEnds() {
        val board = BoardState()
        val shape = board.addShape(ShapeKind.RECTANGLE, 12f, -30f, 120f, 80f)
        val arrow = board.addArrow(ArrowEnd.Attached(shape.id, 1f, .5f),
            ArrowEnd.Free(300f, 25f))!!
        val before = board.snapshot()
        var viewport = Viewport()
        repeat(10) { viewport = viewport.pan(22f, -13f).zoomAt(100f, 90f, 1.15f) }
        assertEquals(before, board.snapshot())
        assertEquals(shape, board.shapes.single())
        assertEquals(arrow, board.arrows.single())
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
    fun arrowBendSnapsToStraightNearCenter() {
        val board = BoardState()
        val arrow = board.addArrow(ArrowEnd.Free(0f, 0f), ArrowEnd.Free(100f, 0f))!!
        assertTrue(board.updateArrow(arrow.id, bend = 20f))
        assertTrue(board.updateArrow(arrow.id, bend = 4f))
        assertEquals(0f, board.arrows.single().bend)
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
