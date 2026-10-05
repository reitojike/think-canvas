package com.thinkcanvas.canvas

import org.junit.Assert.*
import org.junit.Test

class ImageElementTest {
    private val asset = "8c0a9b01-94fd-404c-944d-62c1249b4d01"
    private fun image() = ImageElement(assetId = asset, x = 10f, y = -20f,
        width = 200f, height = 100f, intrinsicWidth = 400, intrinsicHeight = 200)

    @Test fun invalidIdentityOrUnboundedGeometryIsRejected() {
        val valid = image()
        listOf<() -> ImageElement>(
            { valid.copy(assetId = "../asset") },
            { valid.copy(assetId = asset.uppercase()) },
            { valid.copy(id = "image") },
            { valid.copy(x = Float.NaN) },
            { valid.copy(width = Float.POSITIVE_INFINITY) },
            { valid.copy(width = 0f) },
            { valid.copy(x = Float.MAX_VALUE, width = Float.MAX_VALUE) },
            { valid.copy(intrinsicWidth = 0) },
            { valid.copy(intrinsicHeight = 65536) },
            { valid.copy(intrinsicWidth = 20000, intrinsicHeight = 10000) },
        ).forEach { assertTrue(runCatching(it).isFailure) }
    }

    @Test fun resizeKeepsAnchorAndIntrinsicRatioAtBothLimits() {
        val valid = image()
        val large = valid.resized(100000f, 50000f)
        assertEquals(8192f, large.width, 0f)
        assertEquals(4096f, large.height, 0f)
        val small = valid.resized(1f, 1f)
        assertEquals(40f, small.width, 0f)
        assertEquals(20f, small.height, 0f)
        assertEquals(valid.x, small.x, 0f)
        assertEquals(valid.y, small.y, 0f)
        assertEquals(valid, valid.resized(Float.NaN, 10f))
    }

    @Test fun initialPlacementFitsCapturedViewportWithoutDistortingPanorama() {
        val placed = ImageElement.placed(assetId = asset, intrinsicWidth = 60000,
            intrinsicHeight = 1, center = WorldPoint(800f, -200f), viewportWidth = 300f,
            viewportHeight = 600f)
        assertEquals(180f, placed.width, 0.001f)
        assertEquals(60000f, placed.width / placed.height, 0.01f)
        assertEquals(WorldPoint(800f, -200f), placed.bounds().center)
    }
}
