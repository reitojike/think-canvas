package com.thinkcanvas.canvas

import org.junit.Assert.*
import org.junit.Test

class ImageGeometryTest {
    private val image = ImageElement(assetId = "8c0a9b01-94fd-404c-944d-62c1249b4d01",
        x = 50f, y = 50f, width = 200f, height = 100f, intrinsicWidth = 400, intrinsicHeight = 200)

    @Test fun regionAndExplicitImageSelectionTranslateExactlyOnceAndPreserveAttachment() {
        val region = ShapeElement(id = "region", kind = ShapeKind.REGION,
            x = 0f, y = 0f, width = 300f, height = 300f)
        val arrow = ArrowElement(from = ArrowEnd.Attached(image.id, 1f, .5f),
            to = ArrowEnd.Free(500f, 100f))
        val source = BoardSnapshot(shapes = listOf(region), images = listOf(image), arrows = listOf(arrow))
        val moved = source.translatedSelection(setOf(region.id, image.id), 25f, -30f)
        assertEquals(75f, moved.images.single().x, 0f)
        assertEquals(20f, moved.images.single().y, 0f)
        assertEquals(WorldPoint(281f, 70f), moved.arrowPoints(arrow)!!.first)
        assertEquals(ArrowEnd.Free(500f, 100f), moved.arrows.single().to)
    }

    @Test fun gapUsesImageCenterWithoutStretchingAcrossBoundary() {
        val source = BoardSnapshot(images = listOf(image))
        val moved = source.withGap(WorldPoint(100f, 0f), horizontal = true, amount = 80f)
        assertEquals(130f, moved.images.single().x, 0f)
        assertEquals(image.width, moved.images.single().width, 0f)
        assertEquals(source, source.withGap(WorldPoint(200f, 0f), horizontal = true, amount = 80f))
    }

    @Test fun lassoUsesImageCenterEvenWhenNoBitmapHasBeenDecoded() {
        val source = BoardSnapshot(images = listOf(image))
        val vertices = listOf(WorldPoint(140f, 90f), WorldPoint(160f, 90f),
            WorldPoint(160f, 110f), WorldPoint(140f, 110f))
        assertEquals(setOf(image.id), source.lassoSelection(vertices))
    }
}
