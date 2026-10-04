package com.thinkcanvas.canvas

import org.junit.Assert.assertEquals
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

    private fun freeArrow(id: String, x: Float) = ArrowElement(id = id,
        from = ArrowEnd.Free(x, x + 1f), to = ArrowEnd.Free(x + 20f, x + 30f))

    private fun ink(id: String, x: Float) = InkElement(id = id, kind = InkKind.PEN,
        strokes = listOf(InkStroke(id = "$id-stroke", startedAt = 0L, endedAt = 10L,
            inputType = InkInputType.TOUCH, points = listOf(
                InkPoint(x, x + 1f, 0L), InkPoint(x + 4f, x + 7f, 10L),
            ))))
}
