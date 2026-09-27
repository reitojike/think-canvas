package com.thinkcanvas.canvas

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

fun BoardSnapshot.boundsOf(id: String,
                           resolvedRenderedBounds: Map<String, WorldBounds> = emptyMap()): WorldBounds? =
    resolvedRenderedBounds[id]
        ?: shapes.firstOrNull { it.id == id }?.bounds()
        ?: ink.firstOrNull { it.id == id }?.renderedBounds()

/** Model-only operations without a display context use the saved anchor, never a text estimate. */
fun BoardSnapshot.centerOf(id: String): WorldPoint? = boundsOf(id)?.center
    ?: texts.firstOrNull { it.id == id }?.let { WorldPoint(it.x, it.y) }
    ?: arrows.firstOrNull { it.id == id }?.let { arrow ->
        arrowPoints(arrow)?.let { (from, to) ->
            WorldPoint((from.x + to.x) / 2f, (from.y + to.y) / 2f)
        }
    }

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
    val small = width <= tolerance * 4f && height <= tolerance * 4f
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

fun ShapeElement.containsInterior(point: WorldPoint): Boolean {
    val b = bounds()
    if (!b.contains(point)) return false
    if (kind != ShapeKind.ELLIPSE) return true
    val dx = (point.x - b.center.x) / (width / 2f)
    val dy = (point.y - b.center.y) / (height / 2f)
    return dx * dx + dy * dy <= 1f
}

fun BoardSnapshot.resolve(end: ArrowEnd, toward: WorldPoint? = null, offset: Float = 6f,
                          renderedBounds: Map<String, WorldBounds> = emptyMap()): WorldPoint? {
    return when (end) {
    is ArrowEnd.Free -> WorldPoint(end.x, end.y)
    is ArrowEnd.Attached -> {
        val bounds = renderedBounds[end.targetId] ?: boundsOf(end.targetId) ?: return null
        val center = bounds.center
        val anchor = WorldPoint(bounds.left + end.u * (bounds.right - bounds.left),
            bounds.top + end.v * (bounds.bottom - bounds.top))
        val target = if (anchor == center) toward ?: anchor else anchor
        val dx = target.x - center.x
        val dy = target.y - center.y
        val shape = shapes.firstOrNull { it.id == end.targetId }
        val rx = (bounds.right - bounds.left) / 2f
        val ry = (bounds.bottom - bounds.top) / 2f
        if (dx == 0f && dy == 0f) return WorldPoint(bounds.right + offset, center.y)
        val factor = if (shape?.kind == ShapeKind.ELLIPSE) {
            1f / sqrt(dx * dx / (rx * rx) + dy * dy / (ry * ry))
        } else min(if (dx == 0f) Float.POSITIVE_INFINITY else rx / abs(dx),
            if (dy == 0f) Float.POSITIVE_INFINITY else ry / abs(dy))
        val length = sqrt(dx * dx + dy * dy)
        WorldPoint(center.x + dx * factor + offset * dx / length,
            center.y + dy * factor + offset * dy / length)
    }
    }
}

fun BoardSnapshot.arrowPoints(arrow: ArrowElement, offset: Float = 6f,
                              renderedBounds: Map<String, WorldBounds> = emptyMap()): Pair<WorldPoint, WorldPoint>? {
    val fromCenter = when (val end = arrow.from) {
        is ArrowEnd.Free -> WorldPoint(end.x, end.y)
        is ArrowEnd.Attached -> (renderedBounds[end.targetId]?.center ?: centerOf(end.targetId)) ?: return null
    }
    val toCenter = when (val end = arrow.to) {
        is ArrowEnd.Free -> WorldPoint(end.x, end.y)
        is ArrowEnd.Attached -> (renderedBounds[end.targetId]?.center ?: centerOf(end.targetId)) ?: return null
    }
    return (resolve(arrow.from, toCenter, offset, renderedBounds) ?: return null) to
        (resolve(arrow.to, fromCenter, offset, renderedBounds) ?: return null)
}

fun BoardSnapshot.detachedEnd(arrow: ArrowElement, from: Boolean, offset: Float = 6f,
                              renderedBounds: Map<String, WorldBounds> = emptyMap()): ArrowEnd.Free? {
    val points = arrowPoints(arrow, offset, renderedBounds) ?: return null
    val point = if (from) points.first else points.second
    return ArrowEnd.Free(point.x, point.y)
}

fun BoardSnapshot.arrowControl(arrow: ArrowElement, offset: Float = 6f,
                               renderedBounds: Map<String, WorldBounds> = emptyMap()): WorldPoint? {
    val (from, to) = arrowPoints(arrow, offset, renderedBounds) ?: return null
    val dx = to.x - from.x
    val dy = to.y - from.y
    val length = max(1f, sqrt(dx * dx + dy * dy))
    return WorldPoint((from.x + to.x) / 2f - dy / length * arrow.bend,
        (from.y + to.y) / 2f + dx / length * arrow.bend)
}

fun BoardSnapshot.distanceToArrow(point: WorldPoint, arrow: ArrowElement, offset: Float = 6f,
                                  renderedBounds: Map<String, WorldBounds> = emptyMap()): Float {
    val (from, to) = arrowPoints(arrow, offset, renderedBounds) ?: return Float.POSITIVE_INFINITY
    val control = arrowControl(arrow, offset, renderedBounds) ?: return Float.POSITIVE_INFINITY
    var minimum = Float.POSITIVE_INFINITY
    var previous = from
    for (index in 1..24) {
        val t = index / 24f
        val inverse = 1f - t
        val current = WorldPoint(
            inverse * inverse * from.x + 2f * inverse * t * control.x + t * t * to.x,
            inverse * inverse * from.y + 2f * inverse * t * control.y + t * t * to.y,
        )
        minimum = min(minimum, distanceToSegment(point, previous, current))
        previous = current
    }
    return minimum
}

fun BoardSnapshot.lassoSelection(vertices: List<WorldPoint>,
                                 renderedBounds: Map<String, WorldBounds> = emptyMap()): Set<String> {
    val selected = (texts.map { it.id } + shapes.map { it.id } + ink.map { it.id })
        .filter { id -> (renderedBounds[id]?.center ?: centerOf(id))
            ?.let { pointInPolygon(it, vertices) } == true }.toMutableSet()
    arrows.forEach { arrow ->
        val points = arrowPoints(arrow, renderedBounds = renderedBounds)
        if (points != null && pointInPolygon(points.first, vertices) &&
            pointInPolygon(points.second, vertices)) selected += arrow.id
    }
    return selected
}

fun BoardSnapshot.translatedSelection(ids: Set<String>, dx: Float, dy: Float,
                                      renderedBounds: Map<String, WorldBounds> = emptyMap()): BoardSnapshot {
    val moved = ids.toMutableSet()
    var expanded: Boolean
    do {
        val previousSize = moved.size
        shapes.filter { it.id in moved && it.kind == ShapeKind.REGION }.forEach { region ->
            texts.forEach { if (region.bounds().contains(renderedBounds[it.id]?.center
                ?: centerOf(it.id)!!)) moved += it.id }
            shapes.filter { it.id != region.id }.forEach {
                if (region.bounds().contains(it.bounds().center)) moved += it.id
            }
            ink.forEach { if (region.bounds().contains(it.bounds().center)) moved += it.id }
        }
        expanded = moved.size != previousSize
    } while (expanded)
    fun ArrowEnd.shift(): ArrowEnd = if (this is ArrowEnd.Free) copy(x = x + dx, y = y + dy) else this
    return BoardSnapshot(
        texts = texts.map { if (it.id in moved) it.copy(x = it.x + dx, y = it.y + dy) else it },
        shapes = shapes.map { if (it.id in moved) it.copy(x = it.x + dx, y = it.y + dy) else it },
        arrows = arrows.map { arrow -> if (arrow.id in ids)
            arrow.copy(from = arrow.from.shift(), to = arrow.to.shift()) else arrow },
        ink = ink.map { if (it.id in moved) it.translated(dx, dy) else it },
    )
}

fun BoardSnapshot.withGap(origin: WorldPoint, horizontal: Boolean, amount: Float,
                          renderedBounds: Map<String, WorldBounds> = emptyMap()): BoardSnapshot {
    if (amount == 0f || !amount.isFinite()) return this
    val source = this
    val scope = smallestRegionAt(origin)
    val boundary = if (horizontal) origin.x else origin.y
    val movePositive = amount > 0f
    fun inScope(id: String): Boolean {
        if (scope == null) return true
        val shape = shapes.firstOrNull { it.id == id }
        if (shape != null && shape.kind == ShapeKind.REGION &&
            shape.bounds().area >= scope.bounds().area) return false
        return (renderedBounds[id]?.center ?: centerOf(id))?.let { scope.bounds().contains(it) } == true
    }
    fun shift(value: Float) = if ((value >= boundary) == movePositive) amount else 0f
    val updatedTexts = texts.map {
        if (inScope(it.id)) {
            val center = renderedBounds[it.id]?.center ?: source.centerOf(it.id)!!
            val delta = shift(if (horizontal) center.x else center.y)
            if (horizontal) it.copy(x = it.x + delta) else it.copy(y = it.y + delta)
        } else it
    }
    val updatedShapes = shapes.map { shape ->
        if (shape.id == scope?.id) {
            if (horizontal) {
                if (amount > 0f) shape.copy(width = (shape.width + amount).coerceAtLeast(40f))
                else shape.copy(x = shape.x + amount, width = (shape.width - amount).coerceAtLeast(40f))
            } else {
                if (amount > 0f) shape.copy(height = (shape.height + amount).coerceAtLeast(30f))
                else shape.copy(y = shape.y + amount, height = (shape.height - amount).coerceAtLeast(30f))
            }
        } else if (!inScope(shape.id)) shape else {
            val low = if (horizontal) shape.x else shape.y
            val high = low + if (horizontal) shape.width else shape.height
            when {
                low < boundary && high > boundary -> if (horizontal)
                    shape.copy(x = shape.x + minOf(amount, 0f),
                        width = (shape.width + abs(amount)).coerceAtLeast(40f))
                else shape.copy(y = shape.y + minOf(amount, 0f),
                    height = (shape.height + abs(amount)).coerceAtLeast(30f))
                else -> {
                    val delta = shift(if (horizontal) shape.bounds().center.x else shape.bounds().center.y)
                    if (horizontal) shape.copy(x = shape.x + delta) else shape.copy(y = shape.y + delta)
                }
            }
        }
    }
    // 余白挿入は矢印の選択ではない。自由端は固定し、接続端は移動した接続先に描画時に追従する。
    val updatedInk = ink.map { element ->
        if (!inScope(element.id)) element else {
            val center = renderedBounds[element.id]?.center ?: source.centerOf(element.id)!!
            val delta = shift(if (horizontal) center.x else center.y)
            if (horizontal) element.translated(delta, 0f) else element.translated(0f, delta)
        }
    }
    return BoardSnapshot(updatedTexts, updatedShapes, arrows, updatedInk)
}
