package com.thinkcanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialGeometryTest {
    @Test fun storedTextHasNoAuthoritativeBoundsUntilRendererResolvesIt() {
        val text = TextElement(id = "text", text = "a long line", x = 10f, y = 20f)
        val snapshot = BoardSnapshot(texts = listOf(text))
        assertEquals(null, snapshot.boundsOf(text.id))
        val resolved = WorldBounds(10f, 20f, 210f, 62f)
        assertEquals(resolved, snapshot.boundsOf(text.id, mapOf(text.id to resolved)))
        assertEquals(text, snapshot.texts.single())
    }

    @Test fun resolvedArrowGeometryIncludesEndpointOffsetHeadAndStroke() {
        val arrow = ArrowElement(id = "arrow", from = ArrowEnd.Free(0f, 0f),
            to = ArrowEnd.Free(40f, 0f))
        val snapshot = BoardSnapshot(arrows = listOf(arrow))
        val geometry = snapshot.arrowRenderGeometry(arrow, scale = 2f, pixelsPerDp = 3f)!!
        assertEquals(0f, geometry.start.x, .001f)
        assertEquals(40f, geometry.end.x, .001f)
        assertTrue(geometry.headLeft.x < geometry.end.x)
        assertTrue(geometry.bounds.right > geometry.end.x)
        assertTrue(geometry.bounds.left < geometry.start.x)
    }

    @Test fun renderedInkBoundsIncludeBrushRadiusWithoutChangingStoredPointBounds() {
        val stroke = InkStroke(startedAt = 0, endedAt = 1, inputType = InkInputType.TOUCH,
            points = listOf(InkPoint(10f, 20f, 0)))
        val marker = InkElement(id = "marker", kind = InkKind.MARKER, strokes = listOf(stroke))
        assertEquals(WorldBounds(10f, 20f, 10f, 20f), marker.bounds())
        assertEquals(WorldBounds(2.5f, 12.5f, 17.5f, 27.5f), marker.renderedBounds())
        val pen = marker.copy(id = "pen", kind = InkKind.PEN)
        assertEquals(WorldBounds(8.75f, 18.75f, 11.25f, 21.25f), pen.renderedBounds())
    }

    @Test fun resolvedShapeAndRegionGeometryIncludesTheirDifferentStrokeExtents() {
        val shape = ShapeElement(id = "shape", kind = ShapeKind.RECTANGLE,
            x = 10f, y = 20f, width = 100f, height = 60f)
        val region = ShapeElement(id = "region", kind = ShapeKind.REGION,
            x = 200f, y = 300f, width = 100f, height = 60f)
        val geometry = BoardSnapshot(shapes = listOf(shape, region))
            .resolveRenderedGeometry(emptyMap(), scale = 2f, pixelsPerDp = 3f)

        assertEquals(WorldBounds(8.5f, 18.5f, 111.5f, 81.5f), geometry.bounds(shape.id))
        assertEquals(WorldBounds(198.875f, 298.875f, 301.125f, 361.125f), geometry.bounds(region.id))
    }

    @Test fun regionMovePreviewAndCommitUseStoredTextAnchor() {
        val region = ShapeElement(id = "region", kind = ShapeKind.REGION,
            x = 0f, y = 0f, width = 100f, height = 100f)
        val text = TextElement(id = "text", text = "membership", x = 20f, y = 30f)
        val source = BoardSnapshot(texts = listOf(text), shapes = listOf(region))
        val rendered = source.resolveRenderedGeometry(mapOf(text.id to
            WorldBounds(20f, 30f, 320f, 60f)), scale = .5f, pixelsPerDp = 3f)
        assertFalse("display center is outside although stored anchor is inside",
            region.bounds().contains(rendered.bounds(text.id)!!.center))

        val preview = source.translatedSelection(setOf(region.id), 12f, 8f)
        val committed = BoardState(source.texts, source.shapes).apply {
            moveSelection(setOf(region.id), 12f, 8f)
        }.snapshot()

        assertEquals(preview, committed)
        assertEquals(32f, preview.texts.single().x)
    }

    @Test fun gapPreviewAndCommitUseStoredTextAnchorOnEachSide() {
        val region = ShapeElement(id = "region", kind = ShapeKind.REGION,
            x = 0f, y = 0f, width = 200f, height = 100f)
        val left = TextElement(id = "left", text = "left", x = 30f, y = 20f)
        val right = TextElement(id = "right", text = "right", x = 70f, y = 20f)
        val source = BoardSnapshot(texts = listOf(left, right), shapes = listOf(region))
        val rendered = source.resolveRenderedGeometry(mapOf(
            left.id to WorldBounds(30f, 20f, 180f, 50f),
            right.id to WorldBounds(70f, 20f, 80f, 50f)), scale = 2f, pixelsPerDp = 3f)
        assertTrue("rendered center puts the stored-left text across the gap",
            rendered.bounds(left.id)!!.center.x > 50f)

        val preview = source.withGap(WorldPoint(50f, 30f), horizontal = true, amount = 20f)
        val committedBoard = BoardState(source.texts, source.shapes).apply {
            insertGap(WorldPoint(50f, 30f), horizontal = true, amount = 20f)
        }

        assertEquals(preview, committedBoard.snapshot())
        assertEquals(30f, preview.texts.first { it.id == left.id }.x)
        assertEquals(90f, preview.texts.first { it.id == right.id }.x)
    }

    @Test fun regionMembershipIsInvariantAcrossViewportScales() {
        val region = ShapeElement(id = "region", kind = ShapeKind.REGION,
            x = 0f, y = 0f, width = 100f, height = 100f)
        val text = TextElement(id = "text", text = "scale", x = 40f, y = 30f)
        val source = BoardSnapshot(texts = listOf(text), shapes = listOf(region))
        val previews = listOf(.25f to 100f, 3f to 100f).map { (scale, measuredWidth) ->
            val geometry = source.resolveRenderedGeometry(mapOf(text.id to WorldBounds(
                text.x, text.y, text.x + measuredWidth, text.y + 40f)), scale)
            assertTrue(region.bounds().contains(geometry.bounds(text.id)!!.center))
            source.translatedSelection(setOf(region.id), 5f, 0f)
        }

        assertEquals(previews.first(), previews.last())
        assertEquals(45f, previews.first().texts.single().x)
    }

    @Test fun lassoUsesTheSameScaleAwareAttachedArrowEndpointAsRendering() {
        val shape = ShapeElement(id = "target", kind = ShapeKind.RECTANGLE,
            x = 0f, y = 0f, width = 40f, height = 40f)
        val arrow = ArrowElement(id = "arrow", from = ArrowEnd.Attached(shape.id, 1f, .5f),
            to = ArrowEnd.Free(200f, 20f))
        val source = BoardSnapshot(shapes = listOf(shape), arrows = listOf(arrow))
        val display = source.resolveRenderedGeometry(emptyMap())
        val concaveLasso = listOf(
            WorldPoint(40f, 10f), WorldPoint(55f, 10f), WorldPoint(55f, 45f),
            WorldPoint(195f, 45f), WorldPoint(195f, 10f), WorldPoint(205f, 10f),
            WorldPoint(205f, 55f), WorldPoint(40f, 55f),
        )

        assertEquals(setOf(arrow.id), source.lassoSelection(concaveLasso,
            display.boundsById, arrowEndpointOffset = 6f))
        assertFalse(arrow.id in source.lassoSelection(concaveLasso,
            display.boundsById, arrowEndpointOffset = 30f))
    }

    @Test fun textAttachedArrowKeepsModelCenterAndUsesMeasuredDisplayBoundsWhenAvailable() {
        val text = TextElement(id = "target-text", text = "anchor", x = 10f, y = 20f)
        val arrow = ArrowElement(id = "attached-arrow",
            from = ArrowEnd.Attached(text.id, .5f, .5f), to = ArrowEnd.Free(110f, 20f))
        val source = BoardSnapshot(texts = listOf(text), arrows = listOf(arrow))

        assertEquals(null, source.boundsOf(text.id))
        assertTrue("model-only arrow center resolves from the saved text anchor",
            source.centerOf(arrow.id) != null)
        val display = source.resolveRenderedGeometry(mapOf(text.id to
            WorldBounds(10f, 20f, 110f, 60f)), scale = .5f, pixelsPerDp = 2f)
        assertTrue("resolved text bounds produce a display arrow extent",
            display.bounds(arrow.id) != null)
    }

    @Test fun farSemanticVisibilityMeasuresDiagonalArrowPathInsteadOfAxisBounds() {
        val arrow = ArrowElement(id = "diagonal", from = ArrowEnd.Free(0f, 0f),
            to = ArrowEnd.Free(70.71068f, 70.71068f))
        val snapshot = BoardSnapshot(arrows = listOf(arrow))
        val geometry = snapshot.arrowRenderGeometry(arrow, scale = .25f, pixelsPerDp = 1f)!!
        val projectedBounds = maxOf(geometry.bounds.right - geometry.bounds.left,
            geometry.bounds.bottom - geometry.bounds.top) * .25f

        assertTrue("axis-aligned rendered bounds under-project the diagonal", projectedBounds < 20f)
        assertTrue("the 25dp rendered chord remains visible in FAR",
            snapshot.semanticProjection(.25f, 14f).visible(arrow.id))
    }

    @Test fun regionMembershipIsInvariantAcrossMeasuredTextExtents() {
        val region = ShapeElement(id = "region", kind = ShapeKind.REGION,
            x = 0f, y = 0f, width = 100f, height = 100f)
        val text = TextElement(id = "text", text = "font", x = 40f, y = 30f)
        val source = BoardSnapshot(texts = listOf(text), shapes = listOf(region))
        val previews = listOf(10f, 500f).mapIndexed { index, measuredWidth ->
            val geometry = source.resolveRenderedGeometry(mapOf(text.id to WorldBounds(
                text.x, text.y, text.x + measuredWidth, text.y + 40f)), scale = 1f)
            assertEquals(index == 0, region.bounds().contains(geometry.bounds(text.id)!!.center))
            source.translatedSelection(setOf(region.id), 5f, 0f)
        }

        assertEquals(previews.first(), previews.last())
        assertEquals(45f, previews.first().texts.single().x)
    }

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
    fun detachingCenteredAnchorKeepsDisplayedEndpoint() {
        val board = BoardState()
        val shape = board.addShape(ShapeKind.RECTANGLE, 0f, 0f, 100f, 80f)
        val arrow = board.addArrow(ArrowEnd.Attached(shape.id, .5f, .5f),
            ArrowEnd.Free(-100f, 40f))!!
        val displayed = board.snapshot().arrowPoints(arrow)!!.first
        val free = board.snapshot().detachedEnd(arrow, from = true)!!
        assertEquals(displayed.x, free.x, .001f)
        assertEquals(displayed.y, free.y, .001f)
        assertTrue(board.updateArrow(arrow.id, from = free))
        assertEquals(displayed, board.snapshot().arrowPoints(board.arrows.single())!!.first)
    }

    @Test
    fun movingRegionDoesNotMoveContainedArrowsFreeEndpoint() {
        val board = BoardState()
        val region = board.addShape(ShapeKind.REGION, 0f, 0f, 300f, 200f)
        val shape = board.addShape(ShapeKind.RECTANGLE, 20f, 30f, 80f, 60f)
        val arrow = board.addArrow(ArrowEnd.Attached(shape.id, 1f, .5f),
            ArrowEnd.Free(200f, 60f))!!
        val before = board.snapshot().arrowPoints(arrow)!!
        assertTrue(board.moveSelection(setOf(region.id), 25f, 0f))
        val after = board.snapshot().arrowPoints(board.arrows.single())!!
        assertEquals(before.first.x + 25f, after.first.x, .001f)
        assertEquals(before.second, after.second)
        assertEquals(arrow, board.arrows.single())
        assertTrue(board.undo())
        assertEquals(before, board.snapshot().arrowPoints(board.arrows.single()))
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
        assertEquals(ArrowEnd.Free(220f, 100f), board.arrows.first { it.id == arrow.id }.to)
        assertTrue(board.undo())
        assertEquals(130f, board.elements.first { it.id == "inside" }.x)
        assertEquals(ArrowEnd.Free(220f, 100f), board.arrows.first { it.id == arrow.id }.to)
    }

    @Test
    fun gapKeepsFreeArrowEndsFixed() {
        val board = BoardState()
        board.addShape(ShapeKind.RECTANGLE, 150f, 0f, 40f, 30f)
        val arrow = board.addArrow(ArrowEnd.Free(60f, 20f), ArrowEnd.Free(160f, 20f))!!
        assertTrue(board.insertGap(WorldPoint(100f, 0f), true, 30f))
        assertEquals(180f, board.shapes.single().x, .001f)
        assertEquals(ArrowEnd.Free(60f, 20f), board.arrows.single().from)
        assertEquals(ArrowEnd.Free(160f, 20f), board.arrows.single().to)
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
