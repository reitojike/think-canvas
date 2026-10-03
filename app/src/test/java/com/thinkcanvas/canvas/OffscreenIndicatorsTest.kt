package com.thinkcanvas.canvas

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OffscreenIndicatorsTest {
    private val canvasSize = IntSize(1000, 800)
    private val safe = Rect(0f, 0f, 1000f, 800f)

    private fun target(
        kind: IndicatorKind,
        id: String,
        screenBounds: Rect,
        viewport: Viewport = Viewport(),
        description: String = id,
    ): IndicatorTarget {
        val topLeft = viewport.screenToWorld(screenBounds.left, screenBounds.top)
        val bottomRight = viewport.screenToWorld(screenBounds.right, screenBounds.bottom)
        return IndicatorTarget(kind, setOf(id),
            WorldBounds(topLeft.first, topLeft.second, bottomRight.first, bottomRight.second), description)
    }

    private fun project(
        targets: List<IndicatorTarget>,
        viewport: Viewport = Viewport(),
        size: IntSize = canvasSize,
        density: Float = 1f,
        safeBounds: Rect = safe,
        obstacles: List<Rect> = emptyList(),
    ) = offscreenIndicatorLayouts(targets, viewport, size, density, safeBounds, obstacles)

    private fun intersects(a: Rect, b: Rect) =
        a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

    @Test fun allFourCardinalDirectionsProjectToTheCorrespondingInsetEdge() {
        val cases = listOf(
            DirectionCase(Rect(-220f, 380f, -180f, 420f), OffsetForEdge.LEFT, 180f),
            DirectionCase(Rect(1180f, 380f, 1220f, 420f), OffsetForEdge.RIGHT, 0f),
            DirectionCase(Rect(480f, -220f, 520f, -180f), OffsetForEdge.TOP, -90f),
            DirectionCase(Rect(480f, 980f, 520f, 1020f), OffsetForEdge.BOTTOM, 90f),
        )

        cases.forEach { (bounds, edge, expectedAngle) ->
            val layout = project(listOf(target(IndicatorKind.SELECTION, "target", bounds))).single()
            val center = layout.touchBounds.center
            when (edge) {
                OffsetForEdge.LEFT -> assertEquals(24f, center.x, .001f)
                OffsetForEdge.RIGHT -> assertEquals(976f, center.x, .001f)
                OffsetForEdge.TOP -> assertEquals(24f, center.y, .001f)
                OffsetForEdge.BOTTOM -> assertEquals(776f, center.y, .001f)
            }
            assertEquals(expectedAngle, layout.angleDegrees, .01f)
            assertEquals(IndicatorKind.SELECTION, layout.target.kind)
        }
    }

    @Test fun allFourCornerDirectionsKeepTheirQuadrantAndHeading() {
        val cases = listOf(
            Triple(Rect(-120f, -220f, -80f, -180f), -135f, -1),
            Triple(Rect(1080f, -220f, 1120f, -180f), -45f, 1),
            Triple(Rect(-120f, 980f, -80f, 1020f), 135f, -1),
            Triple(Rect(1080f, 980f, 1120f, 1020f), 45f, 1),
        )

        cases.forEachIndexed { index, (bounds, expectedAngle, horizontalSign) ->
            val layout = project(listOf(target(IndicatorKind.SEARCH, "corner-$index", bounds))).single()
            assertEquals("screen-space heading", expectedAngle, layout.angleDegrees, .01f)
            assertTrue("corner horizontal direction", (layout.touchBounds.center.x - 500f) * horizontalSign > 0f)
            assertEquals("corner vertical direction", if (index < 2) -1 else 1,
                if (layout.touchBounds.center.y < 400f) -1 else 1)
        }
    }

    @Test fun projectionAndTouchSizeRemainCorrectAcrossZoomAndDensity() {
        for (scale in listOf(.15f, 1f, 3f)) for (density in listOf(1f, 3f)) {
            val viewport = Viewport(scale = scale, panX = 37f, panY = -19f)
            val offscreen = target(IndicatorKind.SEARCH, "scaled-$scale-$density",
                Rect(1220f, 380f, 1260f, 420f), viewport)
            val layout = project(listOf(offscreen), viewport, canvasSize, density).single()

            assertEquals(48f * density, layout.touchBounds.width, .01f)
            assertEquals(48f * density, layout.touchBounds.height, .01f)
            assertEquals(976f - 24f * (density - 1f), layout.touchBounds.center.x,
                .02f)
            assertTrue(layout.touchBounds.left >= safe.left)
            assertTrue(layout.touchBounds.top >= safe.top)
            assertTrue(layout.touchBounds.right <= safe.right)
            assertTrue(layout.touchBounds.bottom <= safe.bottom)
        }
    }

    @Test fun inclusiveBoundaryContactAndPartialVisibilitySuppressIndicators() {
        val cases = listOf(
            Rect(-30f, 200f, 0f, 240f),       // exactly touches the left canvas edge
            Rect(-30f, 200f, 12f, 240f),      // partly overlaps the canvas
            Rect(1000f, 200f, 1030f, 240f),   // exactly touches the right canvas edge
            Rect(998f, 200f, 1040f, 240f),    // partly overlaps the right edge
        )

        cases.forEachIndexed { index, bounds ->
            assertTrue("case $index must be visible or touch the canvas",
                project(listOf(target(IndicatorKind.SEARCH, "edge-$index", bounds))).isEmpty())
        }
    }

    @Test fun giantAndSparseUnionBoundsThatOverlapCanvasAreNotOffscreen() {
        val giant = target(IndicatorKind.SELECTION, "giant", Rect(-1200f, -900f, 1400f, 1100f))
        val sparseUnion = target(IndicatorKind.SELECTION, "sparse-union", Rect(-800f, 220f, 1200f, 280f))

        assertTrue(project(listOf(giant)).isEmpty())
        assertTrue(project(listOf(sparseUnion)).isEmpty())
    }

    @Test fun returnedTouchAreasAreFortyEightDpAndEntirelyInsideSafeBounds() {
        val safeBounds = Rect(40f, 30f, 960f, 770f)
        val targets = listOf(
            target(IndicatorKind.SEARCH, "north", Rect(480f, -200f, 520f, -160f)),
            target(IndicatorKind.SELECTION, "east", Rect(1200f, 380f, 1240f, 420f)),
        )

        project(targets, density = 2f, safeBounds = safeBounds).forEach { layout ->
            assertEquals(96f, layout.touchBounds.width, .01f)
            assertEquals(96f, layout.touchBounds.height, .01f)
            assertTrue(layout.touchBounds.left >= safeBounds.left)
            assertTrue(layout.touchBounds.top >= safeBounds.top)
            assertTrue(layout.touchBounds.right <= safeBounds.right)
            assertTrue(layout.touchBounds.bottom <= safeBounds.bottom)
        }
    }

    @Test fun invalidViewportCanvasDensitySafeBoundsAndTargetBoundsProduceNoLayouts() {
        val valid = target(IndicatorKind.SEARCH, "valid", Rect(1100f, 350f, 1140f, 390f))
        val invalidBounds = IndicatorTarget(IndicatorKind.SEARCH, setOf("bad"),
            WorldBounds(Float.NaN, 0f, 10f, 10f), "bad")

        assertTrue(project(listOf(valid), viewport = Viewport(scale = 0f)).isEmpty())
        assertTrue(project(listOf(valid), viewport = Viewport(scale = Float.NaN)).isEmpty())
        assertTrue(project(listOf(valid), viewport = Viewport(panX = Float.POSITIVE_INFINITY)).isEmpty())
        assertTrue(project(listOf(valid), density = 0f).isEmpty())
        assertTrue(project(listOf(valid), density = Float.NaN).isEmpty())
        assertTrue(project(listOf(valid), size = IntSize.Zero).isEmpty())
        assertTrue(project(listOf(valid), safeBounds = Rect(0f, 0f, 0f, 500f)).isEmpty())
        assertTrue(project(listOf(valid), safeBounds = Rect(0f, 0f, Float.NaN, 500f)).isEmpty())
        assertTrue(project(listOf(invalidBounds)).isEmpty())
    }

    @Test fun emptyIdentityAndReversedTargetBoundsAreRejected() {
        val emptyIds = IndicatorTarget(IndicatorKind.SELECTION, emptySet(),
            WorldBounds(1200f, 100f, 1300f, 200f), "empty")
        val reversed = IndicatorTarget(IndicatorKind.SEARCH, setOf("reversed"),
            WorldBounds(1300f, 200f, 1200f, 100f), "reversed")

        assertTrue(project(listOf(emptyIds)).isEmpty())
        assertTrue(project(listOf(reversed)).isEmpty())
    }

    @Test fun atMostOneTargetPerKindIsReturnedInSearchFirstOrder() {
        val selectionFirst = target(IndicatorKind.SELECTION, "selection-a", Rect(-200f, 200f, -160f, 240f))
        val searchFirst = target(IndicatorKind.SEARCH, "search-a", Rect(1180f, 200f, 1220f, 240f))
        val extraSearch = target(IndicatorKind.SEARCH, "search-b", Rect(1180f, 600f, 1220f, 640f))
        val extraSelection = target(IndicatorKind.SELECTION, "selection-b", Rect(-200f, 600f, -160f, 640f))

        val layouts = project(listOf(selectionFirst, searchFirst, extraSearch, extraSelection))

        assertEquals(2, layouts.size)
        assertEquals(listOf(IndicatorKind.SEARCH, IndicatorKind.SELECTION), layouts.map { it.target.kind })
    }

    @Test fun selectionForTheSameSingleSearchIdIsSuppressed() {
        val search = target(IndicatorKind.SEARCH, "same-id", Rect(1180f, 380f, 1220f, 420f),
            description = "検索結果")
        val selection = IndicatorTarget(IndicatorKind.SELECTION, setOf("same-id"),
            search.bounds, "選択")

        val layouts = project(listOf(selection, search))

        assertEquals(1, layouts.size)
        assertEquals(IndicatorKind.SEARCH, layouts.single().target.kind)
        assertEquals("検索結果", layouts.single().target.description)
    }

    @Test fun repeatedIdentityWithinAKindDoesNotCreateDuplicateMarkers() {
        val searchA = target(IndicatorKind.SEARCH, "search", Rect(1180f, 380f, 1220f, 420f))
        val searchB = searchA.copy(description = "replacement")
        val selectionA = target(IndicatorKind.SELECTION, "selected", Rect(-220f, 380f, -180f, 420f))
        val selectionB = selectionA.copy(description = "replacement selection")

        val layouts = project(listOf(searchA, searchB, selectionA, selectionB))

        assertEquals(2, layouts.size)
        assertEquals(listOf(IndicatorKind.SEARCH, IndicatorKind.SELECTION), layouts.map { it.target.kind })
    }

    @Test fun sameEdgeMarkersKeepAtLeastEightDpBetweenTheirTouchTargets() {
        val search = target(IndicatorKind.SEARCH, "search", Rect(1180f, 380f, 1220f, 420f))
        val selection = target(IndicatorKind.SELECTION, "selection", Rect(1180f, 380f, 1220f, 420f))
        val density = 2f

        val layouts = project(listOf(selection, search), density = density)

        assertEquals(2, layouts.size)
        val (first, second) = layouts.map { it.touchBounds }
        assertFalse(intersects(first, second))
        val verticalGap = when {
            first.bottom <= second.top -> second.top - first.bottom
            second.bottom <= first.top -> first.top - second.bottom
            else -> 0f
        }
        assertTrue("same right edge uses at least 8dp gap: $verticalGap", verticalGap >= 8f * density)
        assertEquals(IndicatorKind.SEARCH, layouts.first().target.kind)
    }

    @Test fun existingChromeObstacleMovesMarkerToAFreePositionOnTheSameEdge() {
        val search = target(IndicatorKind.SEARCH, "search", Rect(1280f, 380f, 1320f, 420f))
        val obstacle = Rect(950f, 350f, 1000f, 450f)

        val layout = project(listOf(search), obstacles = listOf(obstacle)).single()

        assertTrue(layout.touchBounds.center.x > 500f)
        assertFalse("marker must not cover chrome", intersects(layout.touchBounds, obstacle))
        assertTrue(layout.touchBounds.left >= safe.left && layout.touchBounds.right <= safe.right)
        assertTrue(layout.touchBounds.top >= safe.top && layout.touchBounds.bottom <= safe.bottom)
    }

    @Test fun tinySafeAreaWithNoFullTouchSlotReturnsNoIndicator() {
        val search = target(IndicatorKind.SEARCH, "search", Rect(1200f, 380f, 1240f, 420f))

        assertTrue(project(listOf(search), safeBounds = Rect(0f, 0f, 40f, 40f)).isEmpty())
    }

    @Test fun fullyBlockedSafeEdgeOmitsLowerPriorityMarkerInsteadOfOverlapping() {
        val search = target(IndicatorKind.SEARCH, "search", Rect(1180f, 380f, 1220f, 420f))
        val selection = target(IndicatorKind.SELECTION, "selection", Rect(1180f, 380f, 1220f, 420f))

        // A 96px square fits one 48px target but cannot fit two with their 8px gap.
        val layouts = project(listOf(selection, search), size = IntSize(96, 96),
            safeBounds = Rect(0f, 0f, 96f, 96f))

        assertEquals(1, layouts.size)
        assertEquals(IndicatorKind.SEARCH, layouts.single().target.kind)
    }

    private data class DirectionCase(val bounds: Rect, val edge: OffsetForEdge, val angle: Float)
    private enum class OffsetForEdge { LEFT, RIGHT, TOP, BOTTOM }
}
