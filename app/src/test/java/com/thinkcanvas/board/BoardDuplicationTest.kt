package com.thinkcanvas.board

import com.thinkcanvas.canvas.ArrowElement
import com.thinkcanvas.canvas.ArrowEnd
import com.thinkcanvas.canvas.BoardSnapshot
import com.thinkcanvas.canvas.InkElement
import com.thinkcanvas.canvas.InkInputType
import com.thinkcanvas.canvas.InkKind
import com.thinkcanvas.canvas.InkPoint
import com.thinkcanvas.canvas.InkStroke
import com.thinkcanvas.canvas.ShapeElement
import com.thinkcanvas.canvas.ShapeKind
import com.thinkcanvas.canvas.TextElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardDuplicationTest {
    @Test fun duplicatePreservesLayoutAndReconnectsOnlyToCopiedElements() {
        val text = TextElement(id = "text", text = "考え", x = -12f, y = 44f)
        val shape = ShapeElement(id = "shape", kind = ShapeKind.REGION,
            x = 2f, y = 5f, width = 220f, height = 130f, name = "まとまり")
        val arrow = ArrowElement(id = "arrow", from = ArrowEnd.Attached("text", .2f, .8f),
            to = ArrowEnd.Attached("shape", .5f, .5f), bend = 14f)
        val stroke = InkStroke(id = "stroke", startedAt = 0, endedAt = 1,
            inputType = InkInputType.STYLUS,
            points = listOf(InkPoint(10f, 20f, 0), InkPoint(20f, 30f, 1)))
        val ink = InkElement(id = "ink", kind = InkKind.MARKER, strokes = listOf(stroke))
        val source = BoardSnapshot(listOf(text), listOf(shape), listOf(arrow), listOf(ink))

        val copy = source.duplicated()

        assertNotEquals(text.id, copy.texts.single().id)
        assertNotEquals(shape.id, copy.shapes.single().id)
        assertNotEquals(arrow.id, copy.arrows.single().id)
        assertNotEquals(ink.id, copy.ink.single().id)
        assertNotEquals(stroke.id, copy.ink.single().strokes.single().id)
        assertEquals(text.copy(id = copy.texts.single().id), copy.texts.single())
        assertEquals(shape.copy(id = copy.shapes.single().id), copy.shapes.single())
        assertEquals(ink.copy(id = copy.ink.single().id,
            strokes = listOf(stroke.copy(id = copy.ink.single().strokes.single().id))), copy.ink.single())
        assertEquals(copy.texts.single().id,
            (copy.arrows.single().from as ArrowEnd.Attached).targetId)
        assertEquals(copy.shapes.single().id,
            (copy.arrows.single().to as ArrowEnd.Attached).targetId)
        assertEquals(14f, copy.arrows.single().bend)
        assertTrue(source.texts.single().id != copy.texts.single().id)
    }
}
