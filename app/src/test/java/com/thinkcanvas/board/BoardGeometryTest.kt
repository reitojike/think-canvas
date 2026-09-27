package com.thinkcanvas.board

import com.thinkcanvas.canvas.BoardSnapshot
import com.thinkcanvas.canvas.ShapeElement
import com.thinkcanvas.canvas.ShapeKind
import com.thinkcanvas.canvas.TextElement
import com.thinkcanvas.canvas.TextExtent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardGeometryTest {
    @Test fun veryLargeBoardStartsAtSupportedMinimumZoom() {
        val board = BoardSnapshot(shapes = listOf(ShapeElement(kind = ShapeKind.RECTANGLE,
            x = 0f, y = 0f, width = 10_000f, height = 100f)))

        val viewport = board.fittedViewport(1000f, 800f)

        assertEquals(.15f, viewport.scale, .0001f)
        assertEquals(.15f, viewport.zoomAt(500f, 400f, .5f).scale, .0001f)
        assertEquals(500f, viewport.worldToScreen(5000f, 50f).first, .01f)
    }

    @Test fun openingFitIncludesDetailedRegionLabelPixels() {
        val region = ShapeElement(id = "region", kind = ShapeKind.REGION,
            x = 100f, y = 100f, width = 500f, height = 400f, name = "長い囲み名")
        val board = BoardSnapshot(shapes = listOf(region))

        val viewport = board.fittedViewport(1000f, 800f,
            mapOf(region.id to RegionLabelSize(900f, 40f)))
        val (labelLeft, labelTop) = viewport.worldToScreen(region.x + 8f, region.y - 22f)

        assertTrue(labelLeft >= 20f)
        assertTrue(labelLeft + 900f <= 980f)
        assertTrue(labelTop >= 20f)
        assertTrue(viewport.scale in .15f..3f)
    }

    @Test fun openingFitUsesMeasuredWrappedTextExtent() {
        val text = TextElement(id = "text", text = "長い文章\n次の行", x = 100f, y = 120f)
        val board = BoardSnapshot(texts = listOf(text))
        val measured = mapOf(text.id to TextExtent(1200f, 700f))

        val viewport = board.fittedViewport(1000f, 800f, textExtents = measured)
        val (left, top) = viewport.worldToScreen(text.x, text.y)
        val (right, bottom) = viewport.worldToScreen(text.x + 1200f, text.y + 700f)

        assertTrue(viewport.scale < .9f)
        assertTrue(left >= 20f && right <= 980f)
        assertTrue(top >= 90f && bottom <= 710f)
        assertEquals(1200f, board.contentBounds(measured)!!.right - text.x, .001f)
    }
}
