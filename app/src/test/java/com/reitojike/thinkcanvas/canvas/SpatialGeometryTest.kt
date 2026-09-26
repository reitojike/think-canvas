package com.reitojike.thinkcanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialGeometryTest {
    @Test
    fun overlappingRegionsChooseSmallestAndNestedMoveOnlyOnce() {
        val board = BoardState(listOf(TextElement(id = "text", text = "内側", x = 42f, y = 42f)))
        val outer = board.addShape(ShapeKind.REGION, 0f, 0f, 300f, 300f)
        val inner = board.addShape(ShapeKind.REGION, 20f, 20f, 150f, 150f)
        assertEquals(inner.id, board.snapshot().smallestRegionAt(WorldPoint(70f, 70f))?.id)
        assertTrue(board.moveSelection(setOf(outer.id, inner.id), 10f, 20f))
        assertEquals(52f, board.elements.single().x)
        assertEquals(30f, board.shapes.first { it.id == inner.id }.x)
        assertTrue(board.undo())
        assertEquals(42f, board.elements.single().x)
        assertEquals(20f, board.shapes.first { it.id == inner.id }.x)
    }

    @Test
    fun ellipseAndRectangleHitOnlyNearOutlineUnlessSmall() {
        val rectangle = ShapeElement(kind = ShapeKind.RECTANGLE, x = 0f, y = 0f, width = 120f, height = 80f)
        val ellipse = ShapeElement(kind = ShapeKind.ELLIPSE, x = 0f, y = 0f, width = 120f, height = 80f)
        assertTrue(rectangle.hitStroke(WorldPoint(2f, 40f), 12f))
        assertFalse(rectangle.hitStroke(WorldPoint(60f, 40f), 12f))
        assertTrue(rectangle.hitStroke(WorldPoint(60f, 40f), 80f))
        assertTrue(ellipse.hitStroke(WorldPoint(60f, 2f), 12f))
        assertFalse(ellipse.hitStroke(WorldPoint(60f, 40f), 12f))
        assertTrue(ellipse.containsInterior(WorldPoint(60f, 40f)))
        assertFalse(ellipse.containsInterior(WorldPoint(0f, 0f)))
    }

    @Test
    fun attachedArrowFollowsMovedShapeAndFreeEndStaysPut() {
        val board = BoardState()
        val shape = board.addShape(ShapeKind.ELLIPSE, 0f, 0f, 100f, 80f)
        val arrow = board.addArrow(ArrowEnd.Attached(shape.id, 1f, .5f), ArrowEnd.Free(250f, 40f))!!
        val before = board.snapshot().arrowPoints(arrow)!!
        assertEquals(106f, before.first.x, .001f)
        board.moveSelection(setOf(shape.id), 20f, 0f)
        val after = board.snapshot().arrowPoints(arrow)!!
        assertEquals(126f, after.first.x, .001f)
        assertEquals(250f, after.second.x, .001f)
        board.updateArrow(arrow.id, bend = 25f)
        val control = board.snapshot().arrowControl(board.arrows.single())!!
        board.updateArrow(arrow.id, reverse = true)
        assertEquals(control.x, board.snapshot().arrowControl(board.arrows.single())!!.x, .001f)
        assertEquals(control.y, board.snapshot().arrowControl(board.arrows.single())!!.y, .001f)
    }

    @Test
    fun polygonSelectsCentersAndArrowsOnlyWithBothEndsInside() {
        val board = BoardState(listOf(TextElement(id = "inside", text = "中", x = 10f, y = 10f),
            TextElement(id = "outside", text = "外", x = 300f, y = 300f)))
        val shape = board.addShape(ShapeKind.RECTANGLE, 20f, 20f, 40f, 30f)
        val insideArrow = board.addArrow(ArrowEnd.Free(20f, 20f), ArrowEnd.Free(80f, 80f))!!
        val crossingArrow = board.addArrow(ArrowEnd.Free(20f, 20f), ArrowEnd.Free(400f, 400f))!!
        val polygon = listOf(WorldPoint(0f, 0f), WorldPoint(200f, 0f),
            WorldPoint(200f, 200f), WorldPoint(0f, 200f))
        val selected = board.snapshot().lassoSelection(polygon)
        assertTrue("inside" in selected)
        assertTrue(shape.id in selected)
        assertTrue(insideArrow.id in selected)
        assertFalse("outside" in selected)
        assertFalse(crossingArrow.id in selected)
    }

    @Test
    fun gapStretchesCrossingShapeInsideSmallestScope() {
        val board = BoardState(listOf(
            TextElement(id = "inside", text = "中", x = 130f, y = 70f),
            TextElement(id = "outside", text = "外", x = 400f, y = 70f),
        ))
        val outer = board.addShape(ShapeKind.REGION, 0f, 0f, 500f, 300f)
        val inner = board.addShape(ShapeKind.REGION, 20f, 20f, 250f, 200f)
        val crossing = board.addShape(ShapeKind.RECTANGLE, 60f, 80f, 100f, 50f)
        val arrow = board.addArrow(ArrowEnd.Attached(crossing.id, 1f, .5f),
            ArrowEnd.Free(220f, 100f))!!
        assertTrue(board.insertGap(WorldPoint(120f, 60f), true, 30f))
        assertEquals(160f, board.elements.first { it.id == "inside" }.x)
        assertEquals(400f, board.elements.first { it.id == "outside" }.x)
        assertEquals(130f, board.shapes.first { it.id == crossing.id }.width)
        assertEquals(280f, board.shapes.first { it.id == inner.id }.width)
        assertEquals(500f, board.shapes.first { it.id == outer.id }.width)
        assertEquals(ArrowEnd.Free(250f, 100f), board.arrows.first { it.id == arrow.id }.to)
        assertTrue(board.undo())
        assertEquals(130f, board.elements.first { it.id == "inside" }.x)
        assertEquals(ArrowEnd.Free(220f, 100f), board.arrows.first { it.id == arrow.id }.to)
    }

    @Test
    fun gapMovesOnlyFreeArrowEndsOnAffectedSide() {
        val board = BoardState()
        val arrow = board.addArrow(ArrowEnd.Free(60f, 20f), ArrowEnd.Free(160f, 20f))!!
        assertTrue(board.insertGap(WorldPoint(100f, 0f), true, 30f))
        assertEquals(ArrowEnd.Free(60f, 20f), board.arrows.single().from)
        assertEquals(ArrowEnd.Free(190f, 20f), board.arrows.single().to)
        assertTrue(board.undo())
        assertEquals(arrow, board.arrows.single())
    }

    @Test
    fun verticalNegativeGapMovesOnlyUpperSideAndStretchesCrossingShape() {
        val board = BoardState(listOf(
            TextElement(id = "upper", text = "上", x = 200f, y = 20f),
            TextElement(id = "lower", text = "下", x = 200f, y = 200f),
        ))
        val crossing = board.addShape(ShapeKind.ELLIPSE, 30f, 40f, 100f, 100f)
        assertTrue(board.insertGap(WorldPoint(150f, 100f), false, -25f))
        assertEquals(-5f, board.elements.first { it.id == "upper" }.y)
        assertEquals(200f, board.elements.first { it.id == "lower" }.y)
        val after = board.shapes.first { it.id == crossing.id }
        assertEquals(15f, after.y)
        assertEquals(125f, after.height)
        assertTrue(board.undo())
        assertEquals(40f, board.shapes.single().y)
    }
}
