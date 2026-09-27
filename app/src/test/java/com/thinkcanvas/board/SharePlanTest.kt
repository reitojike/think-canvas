package com.thinkcanvas.board

import com.thinkcanvas.canvas.ArrowElement
import com.thinkcanvas.canvas.ArrowEnd
import com.thinkcanvas.canvas.BoardSnapshot
import com.thinkcanvas.canvas.ShapeElement
import com.thinkcanvas.canvas.ShapeKind
import com.thinkcanvas.canvas.TextElement
import com.thinkcanvas.canvas.WorldBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharePlanTest {
    private fun measured(source: BoardSnapshot): Map<String, WorldBounds> = buildMap {
        source.texts.forEach { put(it.id, WorldBounds(it.x, it.y,
            it.x + 80f, it.y + 42f)) }
        source.shapes.filter { it.kind == ShapeKind.REGION && it.name.isNotBlank() }
            .forEach { region -> put(region.id, WorldBounds(region.x - 1f, region.y - 22f,
                maxOf(region.x + region.width + 1f, region.x + 8f + region.name.length * 16f),
                region.y + region.height + 1f)) }
    }

    @Test fun measuredExportTextBoundsAreUsedByPlan() {
        val text = TextElement(id = "text", text = "複数行の本文", x = 40f, y = 60f)
        val source = BoardSnapshot(texts = listOf(text))
        val measured = WorldBounds(40f, 60f, 540f, 260f)
        val typography = ExportTypography(textWidth = 500)

        val plan = planShare(source, renderedBounds = mapOf(text.id to measured),
            typography = typography)

        assertEquals(measured, plan.contentBounds)
        assertEquals(typography, plan.typography)
        assertTrue(plan.imageBounds.right >= measured.right + 24f)
    }

    @Test fun narrowRegionKeepsFullLabelInsideExportBounds() {
        val region = ShapeElement(id = "named-region", kind = ShapeKind.REGION,
            x = 40f, y = 60f, width = 32f, height = 40f,
            name = "Very long region name")
        val source = BoardSnapshot(shapes = listOf(region))
        val plan = planShare(source, renderedBounds = measured(source))
        assertTrue(plan.contentBounds.right > region.x + region.width)
        assertTrue(plan.contentBounds.right >= region.x + 8f + region.name.length * 16f)
        assertTrue(plan.contentBounds.top <= region.y - 22f)
        assertTrue(plan.imageBounds.right > plan.contentBounds.right)
    }

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

        val bounds = measured(source)
        val plan = planShare(source, setOf(region.id), bounds)

        assertEquals(setOf("region", "inside", "inner-arrow"), plan.includedIds)
        assertEquals(source, plan.source)
        assertFalse("outside" in plan.includedIds)
        assertFalse("crossing-arrow" in plan.includedIds)
        assertTrue("crossing-arrow" in planShare(source, setOf("crossing-arrow"), bounds).includedIds)
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
        assertEquals(300, plan.width)
        assertEquals(200, plan.height)
        assertEquals(10f, source.shapes.single().x)
    }

    @Test fun emptyAndTooLargeTargetsAreRejected() {
        assertTrue(runCatching { planShare(BoardSnapshot()) }.isFailure)
        assertTrue(runCatching { planShare(BoardSnapshot(), emptySet()) }.isFailure)
        val huge = BoardSnapshot(shapes = listOf(ShapeElement(kind = ShapeKind.RECTANGLE,
            x = 0f, y = 0f, width = 3900f, height = 100f)))
        assertTrue(runCatching { planShare(huge) }.isFailure)
    }

    @Test fun exportPlanRequiresResolvedTextAndRegionLabelBounds() {
        val text = TextElement(id = "text", text = "本文", x = 0f, y = 0f)
        assertTrue(runCatching { planShare(BoardSnapshot(texts = listOf(text))) }.isFailure)
    }
}
