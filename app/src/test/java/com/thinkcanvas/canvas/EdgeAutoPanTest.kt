package com.thinkcanvas.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeAutoPanTest {
    private val largeCanvas = IntSize(1000, 800)

    @Test
    fun prototypeProfilesExposeStableDefaultAndIndependentTuningValues() {
        assertEquals(EdgeAutoPanProfile(48f, 360f), EdgeAutoPanProfile.PrototypeA)
        assertEquals(EdgeAutoPanProfile(64f, 540f), EdgeAutoPanProfile.PrototypeB)
        assertEquals(EdgeAutoPanProfile.PrototypeA, EdgeAutoPanProfile.Default)
    }

    @Test
    fun allFourEdgesProduceCameraVelocityTowardTheFinger() {
        assertEquals(Offset(360f, 0f), edgeAutoPanVelocity(Offset(0f, 400f), largeCanvas, 1f))
        assertEquals(Offset(-360f, 0f), edgeAutoPanVelocity(Offset(1000f, 400f), largeCanvas, 1f))
        assertEquals(Offset(0f, 360f), edgeAutoPanVelocity(Offset(500f, 0f), largeCanvas, 1f))
        assertEquals(Offset(0f, -360f), edgeAutoPanVelocity(Offset(500f, 800f), largeCanvas, 1f))
    }

    @Test
    fun cornersCombineBothAxisVelocitiesWithoutChangingTheirLimits() {
        assertEquals(Offset(360f, 360f), edgeAutoPanVelocity(Offset.Zero, largeCanvas, 1f))
        assertEquals(Offset(-360f, -360f), edgeAutoPanVelocity(Offset(1000f, 800f), largeCanvas, 1f))
    }

    @Test
    fun centerAndExactBandBoundariesHaveNoVelocity() {
        listOf(
            Offset(500f, 400f),
            Offset(48f, 400f), Offset(952f, 400f),
            Offset(500f, 48f), Offset(500f, 752f),
        ).forEach { pointer ->
            assertEquals(Offset.Zero, edgeAutoPanVelocity(pointer, largeCanvas, 1f))
        }
    }

    @Test
    fun quadraticFalloffHasHandCalculatedSpeedsAndDecreasesTowardTheCenter() {
        val samples = listOf(
            0f to 360f,
            12f to 202.5f,
            24f to 90f,
            36f to 22.5f,
            48f to 0f,
        )
        val speeds = samples.map { (x, expected) ->
            val actual = edgeAutoPanVelocity(Offset(x, 400f), largeCanvas, 1f).x
            assertEquals(expected, actual, .001f)
            actual
        }
        assertTrue(speeds.zipWithNext().all { (nearer, farther) -> nearer >= farther })
    }

    @Test
    fun proximityHalfwayIntoEachBandHasQuarterOfMaximumSpeed() {
        assertEquals(90f, edgeAutoPanVelocity(Offset(24f, 400f), largeCanvas, 1f).x, .001f)
        assertEquals(-90f, edgeAutoPanVelocity(Offset(976f, 400f), largeCanvas, 1f).x, .001f)
        assertEquals(90f, edgeAutoPanVelocity(Offset(500f, 24f), largeCanvas, 1f).y, .001f)
        assertEquals(-90f, edgeAutoPanVelocity(Offset(500f, 776f), largeCanvas, 1f).y, .001f)
    }

    @Test
    fun pointerFarOutsideCanvasIsClampedToThePerAxisMaximum() {
        assertEquals(Offset(360f, 360f),
            edgeAutoPanVelocity(Offset(-400f, -250f), largeCanvas, 1f))
        assertEquals(Offset(-360f, -360f),
            edgeAutoPanVelocity(Offset(1400f, 1050f), largeCanvas, 1f))
    }

    @Test
    fun smallCanvasNarrowsEachBandToOneQuarterAndKeepsAStationaryCenter() {
        val smallCanvas = IntSize(120, 80)
        assertEquals(Offset(360f, 0f), edgeAutoPanVelocity(Offset(0f, 40f), smallCanvas, 1f))
        assertEquals(Offset(90f, 0f), edgeAutoPanVelocity(Offset(15f, 40f), smallCanvas, 1f))
        assertEquals(Offset.Zero, edgeAutoPanVelocity(Offset(30f, 40f), smallCanvas, 1f))
        assertEquals(Offset.Zero, edgeAutoPanVelocity(Offset(60f, 40f), smallCanvas, 1f))
        assertEquals(Offset(-360f, 0f), edgeAutoPanVelocity(Offset(120f, 40f), smallCanvas, 1f))
        assertEquals(Offset(0f, 360f), edgeAutoPanVelocity(Offset(60f, 0f), smallCanvas, 1f))
        assertEquals(Offset(0f, -360f), edgeAutoPanVelocity(Offset(60f, 80f), smallCanvas, 1f))
    }

    @Test
    fun prototypeBUsesItsOwnBandAndSpeed() {
        assertEquals(Offset(540f, 0f),
            edgeAutoPanVelocity(Offset(0f, 400f), largeCanvas, 1f, EdgeAutoPanProfile.PrototypeB))
        assertEquals(Offset(135f, 0f),
            edgeAutoPanVelocity(Offset(32f, 400f), largeCanvas, 1f, EdgeAutoPanProfile.PrototypeB))
        assertEquals(Offset.Zero,
            edgeAutoPanVelocity(Offset(64f, 400f), largeCanvas, 1f, EdgeAutoPanProfile.PrototypeB))
    }

    @Test
    fun densityScalesBandAndPixelVelocityTogether() {
        val densityOne = edgeAutoPanVelocity(Offset(24f, 400f), largeCanvas, 1f)
        val densityTwo = edgeAutoPanVelocity(Offset(48f, 800f), IntSize(2000, 1600), 2f)
        val densityThree = edgeAutoPanVelocity(Offset(72f, 1200f), IntSize(3000, 2400), 3f)

        assertEquals(90f, densityOne.x, .001f)
        assertEquals(180f, densityTwo.x, .001f)
        assertEquals(270f, densityThree.x, .001f)
    }

    @Test
    fun invalidDimensionsDensityAndNonFinitePointerReturnZero() {
        assertEquals(Offset.Zero, edgeAutoPanVelocity(Offset(0f, 0f), IntSize.Zero, 1f))
        assertEquals(Offset.Zero, edgeAutoPanVelocity(Offset(0f, 0f), largeCanvas, 0f))
        assertEquals(Offset.Zero, edgeAutoPanVelocity(Offset(0f, 0f), largeCanvas, Float.NaN))
        assertEquals(Offset.Zero,
            edgeAutoPanVelocity(Offset(Float.POSITIVE_INFINITY, 400f), largeCanvas, 1f))
        assertEquals(Offset.Zero,
            edgeAutoPanVelocity(Offset(0f, Float.NaN), largeCanvas, 1f))
    }

    @Test
    fun elapsedFrameTimeRejectsNonPositiveValuesAndCapsLongFramesAtFiftyMilliseconds() {
        assertEquals(0f, edgeAutoPanFrameSeconds(-1L), 0f)
        assertEquals(0f, edgeAutoPanFrameSeconds(0L), 0f)
        assertEquals(.016f, edgeAutoPanFrameSeconds(16_000_000L), .000001f)
        assertEquals(.05f, edgeAutoPanFrameSeconds(50_000_000L), .000001f)
        assertEquals(.05f, edgeAutoPanFrameSeconds(100_000_000L), .000001f)
        assertEquals(.05f, edgeAutoPanFrameSeconds(Long.MAX_VALUE), .000001f)
    }

    @Test
    fun worldDragDeltaAtUnitScaleIsPointerPositionMinusWorldAnchor() {
        assertEquals(WorldPoint(40f, 20f),
            worldDragDelta(Viewport(), WorldPoint(10f, -5f), Offset(50f, 15f)))
    }

    @Test
    fun pointerOnlyMovementUsesViewportScaleAndIgnoresScreenPan() {
        val viewport = Viewport(scale = 2f, panX = 20f, panY = -10f)
        val anchor = WorldPoint(100f, 200f)

        assertEquals(WorldPoint(0f, 0f), worldDragDelta(viewport, anchor, Offset(220f, 390f)))
        assertEquals(WorldPoint(6f, -3f), worldDragDelta(viewport, anchor, Offset(232f, 384f)))
    }

    @Test
    fun cameraOnlyMovementUnderAStationaryPointerUsesTheNewViewport() {
        val anchor = WorldPoint(100f, 200f)
        val before = Viewport(scale = 2f, panX = 20f, panY = -10f)
        val afterCameraPan = before.pan(-40f, 30f)
        val stationaryPointer = Offset(220f, 390f)

        assertEquals(WorldPoint(0f, 0f), worldDragDelta(before, anchor, stationaryPointer))
        assertEquals(WorldPoint(20f, -15f), worldDragDelta(afterCameraPan, anchor, stationaryPointer))
    }

    @Test
    fun pointerAndCameraMovementCombineWithoutDoubleApplyingEitherDelta() {
        val anchor = WorldPoint(100f, 200f)
        val before = Viewport(scale = 2f, panX = 20f, panY = -10f)
        val baselinePointer = Offset(220f, 390f)
        val shiftedPointer = Offset(232f, 384f)
        val cameraPanned = before.pan(-40f, 30f)

        val pointerOnly = worldDragDelta(before, anchor, shiftedPointer)
        val cameraOnly = worldDragDelta(cameraPanned, anchor, baselinePointer)
        val combined = worldDragDelta(cameraPanned, anchor, shiftedPointer)

        assertEquals(WorldPoint(6f, -3f), pointerOnly)
        assertEquals(WorldPoint(20f, -15f), cameraOnly)
        assertEquals(WorldPoint(26f, -18f), combined)
        assertEquals(pointerOnly.x + cameraOnly.x, combined.x, .001f)
        assertEquals(pointerOnly.y + cameraOnly.y, combined.y, .001f)
    }

    @Test
    fun worldDragDeltaRespectsMinimumDefaultAndMaximumViewportScales() {
        val anchor = WorldPoint(100f, -40f)
        val cases = listOf(
            Triple(.15f, WorldPoint(100f, -200f), Viewport(.15f, 30f, -15f)),
            Triple(1f, WorldPoint(15f, -30f), Viewport(1f, 30f, -15f)),
            Triple(3f, WorldPoint(5f, -10f), Viewport(3f, 30f, -15f)),
        )

        cases.forEach { (scale, expected, viewport) ->
            val screenAnchor = viewport.worldToScreen(anchor.x, anchor.y)
            val pointer = Offset(screenAnchor.first + 15f, screenAnchor.second - 30f)
            assertEquals("scale=$scale", expected, worldDragDelta(viewport, anchor, pointer))
        }
    }
}
