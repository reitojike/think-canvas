package com.thinkcanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticNavigationTest {
    @Test fun tierUsesVisibleBodySizeAndBoundaryValues() {
        assertEquals(SemanticTier.NEAR, semanticTier(14f, 9f / 14f))
        assertEquals(SemanticTier.MID, semanticTier(14f, 5f / 14f))
        assertEquals(SemanticTier.FAR, semanticTier(14f, .25f))
        assertEquals(SemanticTier.NEAR, semanticTier(18f, .5f))
        assertEquals(SemanticTier.MID, semanticTier(10f, .5f))
    }

    @Test fun collapsedRegionHidesContentButPreservesSelectedAndSnapshot() {
        val outer = ShapeElement(id = "outer", kind = ShapeKind.REGION,
            x = 0f, y = 0f, width = 110f, height = 100f, name = "まとまり")
        val inner = ShapeElement(id = "inner", kind = ShapeKind.REGION,
            x = 10f, y = 10f, width = 40f, height = 40f, name = "内側")
        val inside = TextElement(id = "inside", text = "探す内容", x = 20f, y = 20f)
        val outside = TextElement(id = "outside", text = "外", x = 200f, y = 20f)
        val snapshot = BoardSnapshot(texts = listOf(inside, outside), shapes = listOf(outer, inner))
        val projection = snapshot.semanticProjection(1f, 14f)
        assertEquals(setOf("inner", "inside"), projection.hidden)
        assertFalse(projection.visible("inside"))
        assertTrue(projection.visible("outside"))
        assertTrue(projection.farLikeRegion("outer"))
        assertTrue(snapshot.semanticProjection(1f, 14f, setOf("inside")).visible("inside"))
        assertEquals(listOf(inside, outside), snapshot.texts)
    }

    @Test fun farViewHidesBodyAndSmallInkButKeepsSearchHit() {
        val body = TextElement(id = "body", text = "探す", x = 10f, y = 10f)
        val title = TextElement(id = "title", text = "見出し", kind = TextKind.TITLE, x = 200f, y = 10f)
        val stroke = InkStroke(id = "stroke", startedAt = 0, endedAt = 1,
            inputType = InkInputType.TOUCH,
            points = listOf(InkPoint(0f, 0f, 0), InkPoint(10f, 0f, 1)))
        val ink = InkElement(id = "ink", kind = InkKind.PEN, strokes = listOf(stroke))
        val snapshot = BoardSnapshot(texts = listOf(body, title), ink = listOf(ink))
        val projection = snapshot.semanticProjection(.25f, 14f)
        assertEquals(SemanticTier.FAR, projection.tier)
        assertFalse(projection.visible("body"))
        assertFalse(projection.visible("ink"))
        assertTrue(projection.visible("title"))
        assertTrue(snapshot.semanticProjection(.25f, 14f, setOf("body")).visible("body"))
    }

    @Test fun regionCollapseUsesStrictWidthAndHeightBoundaries() {
        fun collapsed(width: Float, height: Float): Boolean {
            val region = ShapeElement(id = "region", kind = ShapeKind.REGION,
                x = 0f, y = 0f, width = width, height = height, name = "Group")
            return "region" in BoardSnapshot(shapes = listOf(region))
                .semanticProjection(1f, 14f).collapsedRegions
        }
        assertFalse(collapsed(120f, 90f))
        assertTrue(collapsed(119.9f, 90f))
        assertTrue(collapsed(120f, 89.9f))
        val denseRegion = ShapeElement(id = "dense", kind = ShapeKind.REGION,
            x = 0f, y = 0f, width = 300f, height = 300f, name = "Group")
        val denseSnapshot = BoardSnapshot(shapes = listOf(denseRegion))
        assertFalse("dense" in denseSnapshot.semanticProjection(.5f, 14f).collapsedRegions)
        assertTrue("dense" in denseSnapshot.semanticProjection(.5f, 14f,
            pixelsPerDp = 3f).collapsedRegions)
    }

    @Test fun clippedTitleIsNotRenderedOrHitUnlessSelected() {
        val title = TextElement(id = "title", text = "長い見出し", kind = TextKind.TITLE,
            x = 93f, y = 20f)
        val neighbor = ShapeElement(id = "neighbor", kind = ShapeKind.RECTANGLE,
            x = 100f, y = 0f, width = 50f, height = 100f)
        val snapshot = BoardSnapshot(texts = listOf(title), shapes = listOf(neighbor))
        assertFalse(snapshot.semanticProjection(.5f, 14f).visible("title"))
        assertTrue(snapshot.semanticProjection(.5f, 14f, setOf("title")).visible("title"))
        val partlyClipped = title.copy(x = 50f)
        val midSnapshot = BoardSnapshot(texts = listOf(partlyClipped), shapes = listOf(neighbor))
        assertTrue(midSnapshot.semanticProjection(.5f, 14f).visible("title"))
        assertFalse(midSnapshot.semanticProjection(.5f, 14f,
            pixelsPerDp = 3f, titleDp = 15f).visible("title"))
    }

    @Test fun lassoCannotSelectElementsHiddenByFarView() {
        val body = TextElement(id = "hidden", text = "本文", x = 10f, y = 10f)
        val title = TextElement(id = "visible", text = "見出し", kind = TextKind.TITLE,
            x = 100f, y = 10f)
        val snapshot = BoardSnapshot(texts = listOf(body, title))
        val bounds = listOf(WorldPoint(0f, 0f), WorldPoint(200f, 0f),
            WorldPoint(200f, 100f), WorldPoint(0f, 100f))
        assertEquals(setOf("hidden", "visible"), snapshot.lassoSelection(bounds))
        assertEquals(setOf("visible"), snapshot.visibleLassoSelection(bounds,
            snapshot.semanticProjection(.25f, 14f)))
    }

    @Test fun searchSortsBySavedPositionAndWrapsWithoutMutation() {
        val snapshot = BoardSnapshot(
            texts = listOf(
                TextElement(id = "b", text = "Idea two", x = 100f, y = 20f),
                TextElement(id = "a", text = "IDEA one", x = 10f, y = 20f)),
            shapes = listOf(ShapeElement(id = "c", kind = ShapeKind.REGION,
                x = 0f, y = 100f, width = 200f, height = 150f, name = "idea region")))
        assertEquals(listOf("a", "b", "c"), snapshot.searchCanvas("idea").map { it.id })
        assertEquals(2, searchIndex(0, -1, 3))
        assertEquals(0, searchIndex(2, 1, 3))
        assertTrue(snapshot.searchCanvas("missing").isEmpty())
        assertEquals("IDEA one", snapshot.texts.last().text)
        val tied = BoardSnapshot(texts = listOf(
            TextElement(id = "z", text = "same", x = 10f, y = 20f),
            TextElement(id = "a", text = "same", x = 10f, y = 20f)))
        assertEquals(listOf("a", "z"), tied.searchCanvas("same").map { it.id })
    }

    @Test fun zoomTargetsKeepWorldPointAndClampScale() {
        val viewport = Viewport(scale = 1f, panX = 30f, panY = -10f)
        val mid = viewport.cycleZoom(14f, 400f, 800f)
        assertEquals(.5f, mid.scale, .0001f)
        val (beforeX, beforeY) = viewport.screenToWorld(200f, 400f)
        val (afterX, afterY) = mid.screenToWorld(200f, 400f)
        assertEquals(beforeX, afterX, .0001f)
        assertEquals(beforeY, afterY, .0001f)
        assertEquals(.25f, mid.cycleZoom(14f, 400f, 800f).scale, .0001f)
        assertEquals(1f, mid.cycleZoom(14f, 400f, 800f)
            .cycleZoom(14f, 400f, 800f).scale, .0001f)
        val fromDoubleTap = viewport.doubleTapZoom(140f, 300f, 14f, 400f, 800f)
        assertEquals(.5f, fromDoubleTap.scale, .0001f)
        val targetWorld = viewport.screenToWorld(140f, 300f)
        val targetAtCenter = fromDoubleTap.screenToWorld(200f, 400f)
        assertEquals(targetWorld.first, targetAtCenter.first, .0001f)
        assertEquals(targetWorld.second, targetAtCenter.second, .0001f)
        val region = ShapeElement(kind = ShapeKind.REGION, x = 0f, y = 0f,
            width = 1000f, height = 1000f, name = "広い")
        val fitted = viewport.fitRegion(region, 400f, 800f)
        assertTrue(fitted.scale in .15f..3f)
        val fittedCenter = fitted.screenToWorld(200f, 400f)
        assertEquals(region.bounds().center.x, fittedCenter.first, .0001f)
        assertEquals(region.bounds().center.y, fittedCenter.second, .0001f)
        val small = ShapeElement(id = "small", kind = ShapeKind.REGION,
            x = 0f, y = 0f, width = 200f, height = 150f, name = "小")
        val expanded = viewport.fitRegion(small, 400f, 800f, pixelsPerDp = 3f)
        assertEquals(1.8f, expanded.scale, .0001f)
        assertFalse("small" in BoardSnapshot(shapes = listOf(small))
            .semanticProjection(expanded.scale, 14f, pixelsPerDp = 3f).collapsedRegions)
    }

    @Test fun projectionSearchAndViewportDoNotEnterUndoHistory() {
        val board = BoardState()
        board.create("Idea", TextKind.BODY, TextColor.INK, 120f, 340f)
        val saved = board.snapshot()
        assertTrue(board.canUndo)
        assertFalse(board.canRedo)

        saved.semanticProjection(.25f, 14f, saved.searchCanvas("idea").map { it.id }.toSet())
        Viewport().cycleZoom(14f, 400f, 800f).doubleTapZoom(200f, 300f, 14f, 400f, 800f)
        assertEquals(saved, board.snapshot())
        assertTrue(board.canUndo)
        assertFalse(board.canRedo)
        assertTrue(board.undo())
        assertTrue(board.elements.isEmpty())
        assertTrue(board.redo())
        assertEquals(saved, board.snapshot())
    }
}
