package com.thinkcanvas.canvas

import org.junit.Assert.*
import org.junit.Test

class ImageProjectionTest {
    private val image = ImageElement(assetId = "8c0a9b01-94fd-404c-944d-62c1249b4d01",
        x = 20f, y = 30f, width = 100f, height = 50f, intrinsicWidth = 400, intrinsicHeight = 200)

    @Test fun selectedImageRemainsVisibleInCollapsedRegionAndRenderBoundsStayExact() {
        val region = ShapeElement(kind = ShapeKind.REGION, x = 0f, y = 0f,
            width = 200f, height = 200f, name = "まとまり")
        val snapshot = BoardSnapshot(shapes = listOf(region), images = listOf(image))
        assertFalse(snapshot.semanticProjection(.15f, 14f).visible(image.id))
        assertTrue(snapshot.semanticProjection(.15f, 14f, keep = setOf(image.id)).visible(image.id))
        assertEquals(image.bounds(), snapshot.resolveRenderedGeometry(emptyMap()).bounds(image.id))
    }

    @Test fun imageDescriptionOrGeometryChangeAndFollowingArrowParticipateInHistoryFocus() {
        val arrow = ArrowElement(from = ArrowEnd.Attached(image.id, 1f, .5f), to = ArrowEnd.Free(300f, 55f))
        val before = BoardSnapshot(images = listOf(image), arrows = listOf(arrow))
        val after = before.copy(images = listOf(image.copy(x = 80f)))
        assertEquals(setOf(image.id, arrow.id), affectedHistoryIds(before, after))
        assertEquals(setOf(image.id, arrow.id), affectedHistoryDisplayIds(before, after,
            before.resolveRenderedGeometry(emptyMap()), after.resolveRenderedGeometry(emptyMap())))
        val described = before.copy(images = listOf(image.copy(altText = "猫")))
        assertEquals(setOf(image.id), affectedHistoryDisplayIds(before, described,
            before.resolveRenderedGeometry(emptyMap()), described.resolveRenderedGeometry(emptyMap())))
    }
}
