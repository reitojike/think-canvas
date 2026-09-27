package com.thinkcanvas.board

import com.thinkcanvas.canvas.ArrowElement
import com.thinkcanvas.canvas.ArrowEnd
import com.thinkcanvas.canvas.BoardSnapshot
import com.thinkcanvas.canvas.ShapeElement
import com.thinkcanvas.canvas.ShapeKind
import com.thinkcanvas.canvas.TextElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharePlanTest {
    @Test fun selectedRegionIncludesItsContentsButNotCrossingArrow() {
        val region = ShapeElement(id = "region", kind = ShapeKind.REGION,
            x = 0f, y = 0f, width = 200f, height = 200f, name = "考え")
        val inside = TextElement(id = "inside", text = "中", x = 40f, y = 60f)
        val outside = TextElement(id = "outside", text = "外", x = 300f, y = 60f)
        val innerArrow = ArrowElement(id = "inner-arrow",
            from = ArrowEnd.Attached("inside", .5f, .5f), to = ArrowEnd.Free(140f, 100f))
        val crossingArrow = ArrowElement(id = "crossing-arrow",
            from = ArrowEnd.Attached("inside", .5f, .5f),
            to = ArrowEnd.Attached("outside", .5f, .5f))
        val source = BoardSnapshot(listOf(inside, outside), listOf(region),
            listOf(innerArrow, crossingArrow))

        val plan = planShare(source, setOf(region.id))

        assertEquals(setOf("region", "inside", "inner-arrow"), plan.includedIds)
        assertEquals(source, plan.source)
        assertFalse("outside" in plan.includedIds)
        assertFalse("crossing-arrow" in plan.includedIds)
        assertTrue("crossing-arrow" in planShare(source, setOf("crossing-arrow")).includedIds)
    }

    @Test fun paddingAndResolutionAreBoundedWithoutMutatingSource() {
        val source = BoardSnapshot(shapes = listOf(ShapeElement(id = "shape",
            kind = ShapeKind.RECTANGLE, x = 10f, y = -20f, width = 100f, height = 50f)))
        val plan = planShare(source)

        assertEquals(24f, plan.contentBounds.left - plan.imageBounds.left, .001f)
        assertEquals(24f, plan.imageBounds.right - plan.contentBounds.right, .001f)
        assertEquals(24f, plan.contentBounds.top - plan.imageBounds.top, .001f)
        assertEquals(24f, plan.imageBounds.bottom - plan.contentBounds.bottom, .001f)
        assertEquals(2f, plan.pixelsPerWorldUnit, .001f)
        assertEquals(296, plan.width)
        assertEquals(196, plan.height)
        assertEquals(10f, source.shapes.single().x)
    }

    @Test fun emptyAndTooLargeTargetsAreRejected() {
        assertTrue(runCatching { planShare(BoardSnapshot()) }.isFailure)
        assertTrue(runCatching { planShare(BoardSnapshot(), emptySet()) }.isFailure)
        val huge = BoardSnapshot(shapes = listOf(ShapeElement(kind = ShapeKind.RECTANGLE,
            x = 0f, y = 0f, width = 3900f, height = 100f)))
        assertTrue(runCatching { planShare(huge) }.isFailure)
    }
}
