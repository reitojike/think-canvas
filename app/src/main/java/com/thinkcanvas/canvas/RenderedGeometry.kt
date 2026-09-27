package com.thinkcanvas.canvas

import kotlin.math.cos
import kotlin.math.sin

/** Detailed appearance dimensions shared by the interactive and export representations. */
object DetailedRenderFacts {
    const val ARROW_ENDPOINT_OFFSET_DP = 6f
    const val ARROW_STROKE_DP = 2f
    const val ARROW_HEAD_LENGTH_DP = 11f
    const val ARROW_HEAD_HALF_ANGLE_RADIANS = .5f
    const val REGION_STROKE_DP = 1.5f
    const val SHAPE_STROKE_DP = 2f
    const val REGION_DASH_ON_DP = 8f
    const val REGION_DASH_OFF_DP = 5f
    const val REGION_CORNER_RADIUS_DP = 12f
    const val RECTANGLE_CORNER_RADIUS_DP = 3f
    const val REGION_LABEL_LEFT_WORLD = 8f
    const val REGION_LABEL_TOP_WORLD = 22f
    const val REGION_LABEL_SIZE_SP = 12f
}

data class ArrowRenderGeometry(
    val start: WorldPoint,
    val end: WorldPoint,
    val control: WorldPoint,
    val headLeft: WorldPoint,
    val headRight: WorldPoint,
    val bounds: WorldBounds,
)

/** Display geometry resolved for one representation context; model coordinates stay unchanged. */
data class ResolvedRenderedGeometry(val boundsById: Map<String, WorldBounds>) {
    fun bounds(id: String): WorldBounds? = boundsById[id]

    fun union(ids: Set<String> = boundsById.keys): WorldBounds? {
        val bounds = ids.mapNotNull(boundsById::get)
        if (bounds.isEmpty()) return null
        return WorldBounds(bounds.minOf { it.left }, bounds.minOf { it.top },
            bounds.maxOf { it.right }, bounds.maxOf { it.bottom })
    }
}

fun BoardSnapshot.arrowRenderGeometry(
    arrow: ArrowElement,
    scale: Float = 1f,
    pixelsPerDp: Float = 1f,
    renderedBounds: Map<String, WorldBounds> = emptyMap(),
): ArrowRenderGeometry? {
    if (!scale.isFinite() || scale <= 0f || !pixelsPerDp.isFinite() || pixelsPerDp <= 0f) return null
    val offset = DetailedRenderFacts.ARROW_ENDPOINT_OFFSET_DP * pixelsPerDp / scale
    val (start, end) = arrowPoints(arrow, offset, renderedBounds) ?: return null
    val control = arrowControl(arrow, offset, renderedBounds) ?: return null
    val angle = kotlin.math.atan2(end.y - control.y, end.x - control.x)
    val headLength = DetailedRenderFacts.ARROW_HEAD_LENGTH_DP * pixelsPerDp / scale
    val halfAngle = DetailedRenderFacts.ARROW_HEAD_HALF_ANGLE_RADIANS
    val headLeft = WorldPoint(end.x - headLength * cos(angle - halfAngle),
        end.y - headLength * sin(angle - halfAngle))
    val headRight = WorldPoint(end.x - headLength * cos(angle + halfAngle),
        end.y - headLength * sin(angle + halfAngle))
    // Including the control point gives a conservative envelope of the quadratic path.
    val strokeRadius = DetailedRenderFacts.ARROW_STROKE_DP * pixelsPerDp / (2f * scale)
    val points = listOf(start, end, control, headLeft, headRight)
    return ArrowRenderGeometry(start, end, control, headLeft, headRight,
        WorldBounds(points.minOf { it.x } - strokeRadius, points.minOf { it.y } - strokeRadius,
            points.maxOf { it.x } + strokeRadius, points.maxOf { it.y } + strokeRadius))
}

/** Combines exact text renderer bounds with shared shape, arrow, and brush facts. */
fun BoardSnapshot.resolveRenderedGeometry(
    resolvedTextBounds: Map<String, WorldBounds>,
    scale: Float = 1f,
    pixelsPerDp: Float = 1f,
): ResolvedRenderedGeometry {
    val bounds = LinkedHashMap<String, WorldBounds>()
    texts.forEach { text -> resolvedTextBounds[text.id]?.let { bounds[text.id] = it } }
    shapes.forEach { shape ->
        val strokeDp = if (shape.kind == ShapeKind.REGION) DetailedRenderFacts.REGION_STROKE_DP
            else DetailedRenderFacts.SHAPE_STROKE_DP
        val radius = strokeDp * pixelsPerDp / (2f * scale)
        val stored = WorldBounds(shape.x, shape.y, shape.x + shape.width, shape.y + shape.height)
        bounds[shape.id] = WorldBounds(stored.left - radius, stored.top - radius,
            stored.right + radius, stored.bottom + radius)
    }
    ink.forEach { element -> bounds[element.id] = element.renderedBounds() }
    arrows.forEach { arrow -> arrowRenderGeometry(arrow, scale, pixelsPerDp, bounds)?.let {
        bounds[arrow.id] = it.bounds
    } }
    return ResolvedRenderedGeometry(bounds)
}
