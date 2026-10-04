package com.thinkcanvas.canvas

/** Display-only delta: unchanged attached arrows can move when their targets change. */
fun affectedHistoryIds(before: BoardSnapshot, after: BoardSnapshot): Set<String> {
    fun <T> changed(old: List<T>, new: List<T>, id: (T) -> String): Set<String> {
        val oldById = old.associateBy(id)
        val newById = new.associateBy(id)
        return (oldById.keys + newById.keys).filterTo(linkedSetOf()) {
            oldById[it] != newById[it]
        }
    }
    val targets = changed(before.texts, after.texts) { it.id } +
        changed(before.shapes, after.shapes) { it.id }
    return buildSet {
        addAll(targets)
        addAll(changed(before.ink, after.ink) { it.id })
        addAll(changed(before.arrows, after.arrows) { it.id })
        (before.arrows + after.arrows).forEach { arrow ->
            if (listOf(arrow.from, arrow.to).any {
                it is ArrowEnd.Attached && it.targetId in targets
            }) add(arrow.id)
        }
    }
}

/** Filter candidate attached arrows using the actual measured representation, not model guesses. */
fun affectedHistoryDisplayIds(
    before: BoardSnapshot,
    after: BoardSnapshot,
    beforeGeometry: ResolvedRenderedGeometry,
    afterGeometry: ResolvedRenderedGeometry,
    scale: Float = 1f,
    pixelsPerDp: Float = 1f,
): Set<String> {
    val oldArrows = before.arrows.associateBy { it.id }
    val newArrows = after.arrows.associateBy { it.id }
    return affectedHistoryIds(before, after).filterTo(linkedSetOf()) { id ->
        val old = oldArrows[id]
        val new = newArrows[id]
        old == null || new == null || old != new ||
            before.arrowRenderGeometry(old, scale, pixelsPerDp, beforeGeometry.boundsById) !=
                after.arrowRenderGeometry(new, scale, pixelsPerDp, afterGeometry.boundsById)
    }
}

fun historyDisplayBounds(
    ids: Set<String>,
    beforeGeometry: ResolvedRenderedGeometry,
    afterGeometry: ResolvedRenderedGeometry,
): ResolvedRenderedGeometry = ResolvedRenderedGeometry(ids.mapNotNull { id ->
    (afterGeometry.bounds(id) ?: beforeGeometry.bounds(id))?.let { id to it }
}.toMap())
