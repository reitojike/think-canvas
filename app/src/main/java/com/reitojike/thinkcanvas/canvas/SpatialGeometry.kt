package com.reitojike.thinkcanvas.canvas

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class WorldPoint(val x: Float, val y: Float) {
    operator fun plus(other: WorldPoint) = WorldPoint(x + other.x, y + other.y)
    operator fun minus(other: WorldPoint) = WorldPoint(x - other.x, y - other.y)
}

data class WorldBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val center get() = WorldPoint((left + right) / 2f, (top + bottom) / 2f)
    fun contains(point: WorldPoint) = point.x in left..right && point.y in top..bottom
    val area get() = (right - left) * (bottom - top)
}

fun ShapeElement.bounds() = WorldBounds(x, y, x + width, y + height)

fun BoardSnapshot.boundsOf(id: String): WorldBounds? =
    shapes.firstOrNull { it.id == id }?.bounds()
        ?: texts.firstOrNull { it.id == id }?.let { WorldBounds(it.x, it.y, it.x + 160f, it.y + 48f) }

fun BoardSnapshot.centerOf(id: String): WorldPoint? = boundsOf(id)?.center

fun BoardSnapshot.smallestRegionAt(point: WorldPoint): ShapeElement? = shapes
    .asSequence().filter { it.kind == ShapeKind.REGION && it.bounds().contains(point) }
    .minByOrNull { it.bounds().area }

fun pointInPolygon(point: WorldPoint, vertices: List<WorldPoint>): Boolean {
    if (vertices.size < 3) return false
    var inside = false
    var j = vertices.lastIndex
    for (i in vertices.indices) {
        val a = vertices[i]
        val b = vertices[j]
        if ((a.y > point.y) != (b.y > point.y) &&
            point.x < (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x
        ) inside = !inside
        j = i
    }
    return inside
}

fun distanceToSegment(point: WorldPoint, a: WorldPoint, b: WorldPoint): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val lengthSquared = dx * dx + dy * dy
    val t = if (lengthSquared == 0f) 0f else
        (((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
    return sqrt((point.x - a.x - t * dx) * (point.x - a.x - t * dx) +
        (point.y - a.y - t * dy) * (point.y - a.y - t * dy))
}

fun ShapeElement.hitStroke(point: WorldPoint, tolerance: Float): Boolean {
    val b = bounds()
    val small = width <= 48f && height <= 48f
    if (kind == ShapeKind.RECTANGLE || kind == ShapeKind.REGION) {
        if (small && b.contains(point)) return true
        val nearX = point.x in (b.left - tolerance)..(b.right + tolerance)
        val nearY = point.y in (b.top - tolerance)..(b.bottom + tolerance)
        return nearX && nearY && (abs(point.x - b.left) <= tolerance ||
            abs(point.x - b.right) <= tolerance || abs(point.y - b.top) <= tolerance ||
            abs(point.y - b.bottom) <= tolerance)
    }
    val rx = width / 2f
    val ry = height / 2f
    val normalized = sqrt(((point.x - b.center.x) / rx) * ((point.x - b.center.x) / rx) +
        ((point.y - b.center.y) / ry) * ((point.y - b.center.y) / ry))
    return (small && normalized <= 1f) || abs(normalized - 1f) * min(rx, ry) <= tolerance
}

fun BoardSnapshot.resolve(end: ArrowEnd, toward: WorldPoint? = null): WorldPoint? {
    return when (end) {
    is ArrowEnd.Free -> WorldPoint(end.x, end.y)
    is ArrowEnd.Attached -> {
        val bounds = boundsOf(end.targetId) ?: return null
        val center = bounds.center
        val anchor = WorldPoint(bounds.left + end.u * (bounds.right - bounds.left),
            bounds.top + end.v * (bounds.bottom - bounds.top))
        val target = if (anchor == center) toward ?: anchor else anchor
        val dx = target.x - center.x
        val dy = target.y - center.y
        val shape = shapes.firstOrNull { it.id == end.targetId }
        val rx = (bounds.right - bounds.left) / 2f
        val ry = (bounds.bottom - bounds.top) / 2f
        if (dx == 0f && dy == 0f) return WorldPoint(bounds.right + 6f, center.y)
        val factor = if (shape?.kind == ShapeKind.ELLIPSE) {
            1f / sqrt(dx * dx / (rx * rx) + dy * dy / (ry * ry))
        } else min(if (dx == 0f) Float.POSITIVE_INFINITY else rx / abs(dx),
            if (dy == 0f) Float.POSITIVE_INFINITY else ry / abs(dy))
        val length = sqrt(dx * dx + dy * dy)
        WorldPoint(center.x + dx * factor + 6f * dx / length,
            center.y + dy * factor + 6f * dy / length)
    }
    }
}

fun BoardSnapshot.arrowPoints(arrow: ArrowElement): Pair<WorldPoint, WorldPoint>? {
    val fromCenter = when (val end = arrow.from) {
        is ArrowEnd.Free -> WorldPoint(end.x, end.y)
        is ArrowEnd.Attached -> centerOf(end.targetId) ?: return null
    }
    val toCenter = when (val end = arrow.to) {
        is ArrowEnd.Free -> WorldPoint(end.x, end.y)
        is ArrowEnd.Attached -> centerOf(end.targetId) ?: return null
    }
    return (resolve(arrow.from, toCenter) ?: return null) to
        (resolve(arrow.to, fromCenter) ?: return null)
}

fun BoardSnapshot.arrowControl(arrow: ArrowElement): WorldPoint? {
    val (from, to) = arrowPoints(arrow) ?: return null
    val dx = to.x - from.x
    val dy = to.y - from.y
    val length = max(1f, sqrt(dx * dx + dy * dy))
    return WorldPoint((from.x + to.x) / 2f - dy / length * arrow.bend,
        (from.y + to.y) / 2f + dx / length * arrow.bend)
}

fun BoardSnapshot.lassoSelection(vertices: List<WorldPoint>): Set<String> {
    val selected = (texts.map { it.id } + shapes.map { it.id })
        .filter { id -> centerOf(id)?.let { pointInPolygon(it, vertices) } == true }.toMutableSet()
    arrows.forEach { arrow ->
        val points = arrowPoints(arrow)
        if (points != null && pointInPolygon(points.first, vertices) &&
            pointInPolygon(points.second, vertices)) selected += arrow.id
    }
    return selected
}

fun BoardSnapshot.translatedSelection(ids: Set<String>, dx: Float, dy: Float): BoardSnapshot {
    val moved = ids.toMutableSet()
    var expanded: Boolean
    do {
        val previousSize = moved.size
        shapes.filter { it.id in moved && it.kind == ShapeKind.REGION }.forEach { region ->
            texts.forEach { if (region.bounds().contains(centerOf(it.id)!!)) moved += it.id }
            shapes.filter { it.id != region.id }.forEach {
                if (region.bounds().contains(it.bounds().center)) moved += it.id
            }
        }
        expanded = moved.size != previousSize
    } while (expanded)
    fun ArrowEnd.shift(): ArrowEnd = if (this is ArrowEnd.Free) copy(x = x + dx, y = y + dy) else this
    return BoardSnapshot(
        texts = texts.map { if (it.id in moved) it.copy(x = it.x + dx, y = it.y + dy) else it },
        shapes = shapes.map { if (it.id in moved) it.copy(x = it.x + dx, y = it.y + dy) else it },
        arrows = arrows.map { arrow -> if (arrow.id in moved)
            arrow.copy(from = arrow.from.shift(), to = arrow.to.shift()) else arrow },
    )
}
