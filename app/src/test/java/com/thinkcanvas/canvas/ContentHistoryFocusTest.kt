package com.thinkcanvas.canvas

import com.thinkcanvas.board.fittedViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentHistoryFocusTest {
    @Test fun unchangedAttachedArrowIsExcludedWhenOnlyItsTargetColorChanges() {
        val text = TextElement(id = "target", text = "same", x = 10f, y = 20f)
        val arrow = ArrowElement(id = "attached", from = ArrowEnd.Attached(text.id, 1f, .5f),
            to = ArrowEnd.Free(4000f, 20f))
        val before = BoardSnapshot(texts = listOf(text), arrows = listOf(arrow))
        val after = before.copy(texts = listOf(text.copy(color = TextColor.VERMILION)))
        val bounds = mapOf(text.id to WorldBounds(10f, 20f, 160f, 50f))
        val geometry = before.resolveRenderedGeometry(bounds)
        assertEquals(setOf(text.id), affectedHistoryDisplayIds(before, after, geometry, geometry))
    }

    @Test fun attachedArrowRemainsAffectedWhenRenderedAttachmentMoves() {
        val shape = ShapeElement(id = "target", kind = ShapeKind.RECTANGLE,
            x = 10f, y = 20f, width = 100f, height = 50f)
        val arrow = ArrowElement(id = "attached", from = ArrowEnd.Attached(shape.id, 1f, .5f),
            to = ArrowEnd.Free(4000f, 20f))
        val before = BoardSnapshot(shapes = listOf(shape), arrows = listOf(arrow))
        val after = before.copy(shapes = listOf(shape.copy(x = 110f)))
        assertEquals(setOf(shape.id, arrow.id), affectedHistoryDisplayIds(before, after,
            before.resolveRenderedGeometry(emptyMap()), after.resolveRenderedGeometry(emptyMap())))
    }

    @Test fun affectedIdsIncludeChangesAcrossEveryElementFamilyAndExcludeUnchanged() {
        val text = TextElement(id = "text", text = "before", x = 10f, y = 20f)
        val shape = ShapeElement(id = "shape", kind = ShapeKind.RECTANGLE,
            x = 30f, y = 40f, width = 80f, height = 50f)
        val arrow = freeArrow("arrow", 0f)
        val ink = ink("ink", 60f)
        val untouched = TextElement(id = "untouched", text = "same", x = -20f, y = 15f)
        val before = BoardSnapshot(texts = listOf(text, untouched), shapes = listOf(shape),
            arrows = listOf(arrow), ink = listOf(ink))
        val after = before.copy(
            texts = listOf(text.copy(text = "after"), untouched),
            shapes = listOf(shape.copy(color = TextColor.VERMILION)),
            arrows = listOf(arrow.copy(bend = 12f)),
            ink = listOf(ink.copy(kind = InkKind.MARKER)),
        )

        assertEquals(setOf(text.id, shape.id, arrow.id, ink.id), affectedHistoryIds(before, after))
    }

    @Test fun addedAndRemovedElementsAreBothAffectedHistoryTargets() {
        val removedText = TextElement(id = "removed-text", text = "gone", x = 1f, y = 2f)
        val removedShape = ShapeElement(id = "removed-shape", kind = ShapeKind.ELLIPSE,
            x = 5f, y = 6f, width = 20f, height = 15f)
        val removedArrow = freeArrow("removed-arrow", 10f)
        val removedInk = ink("removed-ink", 20f)
        val addedText = TextElement(id = "added-text", text = "new", x = 30f, y = 40f)
        val addedShape = ShapeElement(id = "added-shape", kind = ShapeKind.RECTANGLE,
            x = 50f, y = 60f, width = 30f, height = 40f)
        val addedArrow = freeArrow("added-arrow", 70f)
        val addedInk = ink("added-ink", 90f)
        val before = BoardSnapshot(texts = listOf(removedText), shapes = listOf(removedShape),
            arrows = listOf(removedArrow), ink = listOf(removedInk))
        val after = BoardSnapshot(texts = listOf(addedText), shapes = listOf(addedShape),
            arrows = listOf(addedArrow), ink = listOf(addedInk))

        assertEquals(setOf(removedText.id, removedShape.id, removedArrow.id, removedInk.id,
            addedText.id, addedShape.id, addedArrow.id, addedInk.id), affectedHistoryIds(before, after))
    }

    @Test fun changingAnAttachedTargetAlsoMarksArrowsConnectedAtEitherEnd() {
        val text = TextElement(id = "text-target", text = "before", x = 20f, y = 30f)
        val shape = ShapeElement(id = "shape-target", kind = ShapeKind.REGION,
            x = 100f, y = 120f, width = 100f, height = 80f)
        val attachedFrom = ArrowElement(id = "from-arrow",
            from = ArrowEnd.Attached(text.id, .25f, .75f), to = ArrowEnd.Free(320f, 220f))
        val attachedTo = ArrowElement(id = "to-arrow",
            from = ArrowEnd.Free(-20f, 80f), to = ArrowEnd.Attached(shape.id, .8f, .4f))
        val disconnected = freeArrow("unrelated-arrow", 400f)
        val before = BoardSnapshot(texts = listOf(text), shapes = listOf(shape),
            arrows = listOf(attachedFrom, attachedTo, disconnected))
        val after = before.copy(
            texts = listOf(text.copy(x = 45f)),
            shapes = listOf(shape.copy(width = 150f)),
        )

        assertEquals(setOf(text.id, shape.id, attachedFrom.id, attachedTo.id),
            affectedHistoryIds(before, after))
    }

    @Test fun changedAttachmentEndpointsMarkTheirArrowEvenWhenTargetIsUnchanged() {
        val target = ShapeElement(id = "target", kind = ShapeKind.RECTANGLE,
            x = 100f, y = 100f, width = 50f, height = 40f)
        val arrow = ArrowElement(id = "arrow", from = ArrowEnd.Attached(target.id, .2f, .3f),
            to = ArrowEnd.Free(240f, 160f))
        val before = BoardSnapshot(shapes = listOf(target), arrows = listOf(arrow))
        val after = before.copy(arrows = listOf(arrow.copy(from = ArrowEnd.Attached(target.id, .8f, .7f))))

        assertEquals(setOf(arrow.id), affectedHistoryIds(before, after))
    }

    @Test fun displayBoundsPreferSurvivingAfterGeometryAndUseBeforeForDeletedIds() {
        val updated = WorldBounds(100f, 110f, 160f, 170f)
        val deletedBefore = WorldBounds(-40f, -30f, -10f, 5f)
        val beforeGeometry = ResolvedRenderedGeometry(mapOf(
            "survivor" to WorldBounds(1f, 2f, 3f, 4f),
            "deleted" to deletedBefore,
            "unrelated" to WorldBounds(700f, 700f, 800f, 800f),
        ))
        val afterGeometry = ResolvedRenderedGeometry(mapOf(
            "survivor" to updated,
            "unrelated" to WorldBounds(900f, 900f, 1000f, 1000f),
        ))

        assertEquals(mapOf("survivor" to updated, "deleted" to deletedBefore),
            historyDisplayBounds(setOf("survivor", "deleted"), beforeGeometry, afterGeometry).boundsById)
    }

    @Test fun displayBoundsRetainMultipleSurvivorsAndOmitIdsWithoutGeometry() {
        val first = WorldBounds(10f, 20f, 40f, 60f)
        val second = WorldBounds(120f, 140f, 180f, 200f)
        val before = ResolvedRenderedGeometry(mapOf("first" to WorldBounds(0f, 0f, 1f, 1f)))
        val after = ResolvedRenderedGeometry(mapOf("first" to first, "second" to second))

        assertEquals(mapOf("first" to first, "second" to second),
            historyDisplayBounds(setOf("first", "second", "no-geometry"), before, after).boundsById)
    }

    @Test fun deletionUndoRedoKeepsDisplayTargetsForEveryFamily() {
        val text = TextElement(id = "text", text = "delete", x = 10f, y = 20f)
        val rectangle = ShapeElement(id = "rectangle", kind = ShapeKind.RECTANGLE,
            x = 200f, y = 40f, width = 80f, height = 50f)
        val arrow = freeArrow("arrow", 400f)
        val stroke = ink("ink", 600f)
        val untouched = rectangle.copy(id = "untouched", x = 10_000f, y = -8000f)
        val initial = BoardSnapshot(texts = listOf(text), shapes = listOf(rectangle, untouched),
            arrows = listOf(arrow), ink = listOf(stroke))
        // Explicit JVM fixture bounds; these are not Android text measurements.
        val textBounds = mapOf(text.id to WorldBounds(10f, 20f, 150f, 60f))
        val initialGeometry = initial.resolveRenderedGeometry(textBounds)
        for (id in listOf(text.id, rectangle.id, arrow.id, stroke.id)) {
            val board = BoardState(initial.texts, initial.shapes, initial.arrows, initial.ink)
            val ids = setOf(id)
            val deleted = initial.copy(texts = initial.texts.filterNot { it.id == id },
                shapes = initial.shapes.filterNot { it.id == id },
                arrows = initial.arrows.filterNot { it.id == id },
                ink = initial.ink.filterNot { it.id == id })
            val expectedBounds = mapOf(id to checkNotNull(initialGeometry.bounds(id)))
            assertFalse(board.canUndo)
            assertFalse(board.canRedo)
            assertTrue(board.delete(ids))
            assertEquals(deleted, board.snapshot())
            assertTrue(board.canUndo)
            assertFalse(board.canRedo)
            val deletedGeometry = board.snapshot().resolveRenderedGeometry(textBounds)
            assertEquals(ids, affectedHistoryDisplayIds(initial, deleted,
                initialGeometry, deletedGeometry))

            assertTrue(board.undo())
            assertEquals(initial, board.snapshot())
            assertFalse(board.canUndo)
            assertTrue(board.canRedo)
            val restoredGeometry = board.snapshot().resolveRenderedGeometry(textBounds)
            val undoIds = affectedHistoryDisplayIds(deleted, board.snapshot(),
                deletedGeometry, restoredGeometry)
            assertEquals(ids, undoIds)
            assertEquals(expectedBounds, historyDisplayBounds(undoIds,
                deletedGeometry, restoredGeometry).boundsById)

            assertTrue(board.redo())
            assertEquals(deleted, board.snapshot())
            assertTrue(board.canUndo)
            assertFalse(board.canRedo)
            val redoGeometry = board.snapshot().resolveRenderedGeometry(textBounds)
            val redoIds = affectedHistoryDisplayIds(initial, board.snapshot(),
                restoredGeometry, redoGeometry)
            assertEquals(ids, redoIds)
            assertEquals(expectedBounds, historyDisplayBounds(redoIds,
                restoredGeometry, redoGeometry).boundsById)
        }
    }

    @Test fun multipleUndoTargetsUseRestoredBoundsForFit() {
        val rectangle = ShapeElement(id = "rectangle", kind = ShapeKind.RECTANGLE,
            x = 10f, y = 20f, width = 200f, height = 120f)
        val stroke = ink("ink", 700f)
        val untouched = rectangle.copy(id = "untouched", x = 10_000f, y = -8000f)
        val initial = BoardSnapshot(shapes = listOf(rectangle, untouched), ink = listOf(stroke))
        val board = BoardState(initialShapes = initial.shapes, initialInk = initial.ink)
        val ids = setOf(rectangle.id, stroke.id)
        val expectedBounds = initial.resolveRenderedGeometry(emptyMap()).boundsById.filterKeys { it in ids }
        assertTrue(board.moveSelection(ids, 90f, 40f))
        val moved = initial.copy(shapes = listOf(rectangle.copy(x = 100f, y = 60f), untouched),
            ink = listOf(stroke.copy(strokes = stroke.strokes.map { line ->
                line.copy(points = line.points.map { it.copy(x = it.x + 90f, y = it.y + 40f) }) })))
        assertEquals(moved, board.snapshot())
        assertTrue(board.canUndo)
        assertFalse(board.canRedo)
        val movedGeometry = board.snapshot().resolveRenderedGeometry(emptyMap())

        assertTrue(board.undo())
        assertEquals(initial, board.snapshot())
        assertFalse(board.canUndo)
        assertTrue(board.canRedo)
        val restoredGeometry = board.snapshot().resolveRenderedGeometry(emptyMap())
        val undoIds = affectedHistoryDisplayIds(moved, board.snapshot(), movedGeometry, restoredGeometry)
        assertEquals(ids, undoIds)
        val display = historyDisplayBounds(undoIds, movedGeometry, restoredGeometry)
        assertEquals(expectedBounds, display.boundsById)
        assertFalse(untouched.id in display.boundsById)

        val width = 1000f
        val height = 800f
        val view = board.snapshot().fittedViewport(width, height, renderedGeometry = display)
        for (bounds in expectedBounds.values) {
            val (left, top) = view.worldToScreen(bounds.left, bounds.top)
            val (right, bottom) = view.worldToScreen(bounds.right, bounds.bottom)
            assertTrue(left >= 0f && top >= 0f && right <= width && bottom <= height)
        }
        assertEquals(initial, board.snapshot())
        assertFalse(board.canUndo)
        assertTrue(board.canRedo)
        assertTrue(board.redo())
        assertEquals(moved, board.snapshot())
        assertTrue(board.canUndo)
        assertFalse(board.canRedo)
    }

    private fun freeArrow(id: String, x: Float) = ArrowElement(id = id,
        from = ArrowEnd.Free(x, x + 1f), to = ArrowEnd.Free(x + 20f, x + 30f))

    private fun ink(id: String, x: Float) = InkElement(id = id, kind = InkKind.PEN,
        strokes = listOf(InkStroke(id = "$id-stroke", startedAt = 0L, endedAt = 10L,
            inputType = InkInputType.TOUCH, points = listOf(
                InkPoint(x, x + 1f, 0L), InkPoint(x + 4f, x + 7f, 10L),
            ))))
}
