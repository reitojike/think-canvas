package com.thinkcanvas.canvas

import java.util.UUID

enum class InkKind { PEN, MARKER }
enum class InkInputType { TOUCH, STYLUS }

internal const val PEN_WIDTH_WORLD = 2.5f
internal const val MARKER_WIDTH_WORLD = 15f

fun InkKind.hitTolerance(scale: Float): Float {
    val radius = if (this == InkKind.MARKER) MARKER_WIDTH_WORLD / 2f else PEN_WIDTH_WORLD / 2f
    val screenSlop = if (this == InkKind.MARKER) 12f else 7f
    return radius + screenSlop / scale
}

data class InkPoint(val x: Float, val y: Float, val elapsedMillis: Long) {
    init {
        require(x.isFinite() && y.isFinite() && elapsedMillis >= 0)
    }

    fun translated(dx: Float, dy: Float) = copy(x = x + dx, y = y + dy)
}

data class InkStroke(
    val id: String = UUID.randomUUID().toString(),
    val startedAt: Long,
    val endedAt: Long,
    val inputType: InkInputType,
    val points: List<InkPoint>,
) {
    init {
        require(points.isNotEmpty() && startedAt >= 0 && endedAt >= startedAt)
        require(points.zipWithNext().all { (a, b) -> b.elapsedMillis >= a.elapsedMillis })
    }

    fun translated(dx: Float, dy: Float) = copy(points = points.map { it.translated(dx, dy) })
}

data class InkElement(
    val id: String = UUID.randomUUID().toString(),
    val kind: InkKind,
    val strokes: List<InkStroke>,
) {
    init { require(strokes.isNotEmpty()) }

    fun bounds(): WorldBounds {
        val points = strokes.flatMap { it.points }
        return WorldBounds(points.minOf { it.x }, points.minOf { it.y },
            points.maxOf { it.x }, points.maxOf { it.y })
    }

    fun hitStroke(point: WorldPoint, tolerance: Float): Boolean = strokes.any { stroke ->
        stroke.points.zipWithNext().any { (a, b) ->
            distanceToSegment(point, WorldPoint(a.x, a.y), WorldPoint(b.x, b.y)) <= tolerance
        } || (stroke.points.size == 1 &&
            distanceToSegment(point, WorldPoint(stroke.points[0].x, stroke.points[0].y),
                WorldPoint(stroke.points[0].x, stroke.points[0].y)) <= tolerance)
    }

    fun translated(dx: Float, dy: Float) = copy(strokes = strokes.map { it.translated(dx, dy) })
}

fun List<InkElement>.withStroke(kind: InkKind, stroke: InkStroke): Pair<List<InkElement>, Boolean> {
    val last = lastOrNull()
    val grouped = last != null && last.kind == kind &&
        stroke.startedAt >= last.strokes.last().endedAt &&
        stroke.startedAt - last.strokes.last().endedAt <= 1_500L
    return if (grouped) {
        dropLast(1) + last.copy(strokes = last.strokes + stroke) to true
    } else this + InkElement(kind = kind, strokes = listOf(stroke)) to false
}
