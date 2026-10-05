package com.thinkcanvas.canvas

import kotlin.math.max
import kotlin.math.min

enum class SemanticTier { NEAR, MID, FAR }

fun semanticTier(bodyDp: Float, scale: Float): SemanticTier = when {
    bodyDp * scale >= 9f -> SemanticTier.NEAR
    bodyDp * scale >= 5f -> SemanticTier.MID
    else -> SemanticTier.FAR
}

data class SemanticProjection(
    val tier: SemanticTier,
    val collapsedRegions: Set<String>,
    val hidden: Set<String>,
    val midProgress: Float,
) {
    fun visible(id: String) = id !in hidden
    fun farLikeRegion(id: String) = tier == SemanticTier.FAR || id in collapsedRegions
}

data class TextExtent(val width: Float, val height: Float)

fun BoardSnapshot.semanticProjection(
    scale: Float,
    bodyDp: Float,
    keep: Set<String> = emptySet(),
    pixelsPerDp: Float = 1f,
    titleDp: Float = 15f,
    titleLineHeightWorld: Float = 22.5f,
    resolvedRenderedBounds: Map<String, WorldBounds> = resolveRenderedGeometry(
        emptyMap(), scale, pixelsPerDp).boundsById,
): SemanticProjection {
    val tier = semanticTier(bodyDp, scale)
    fun apparentDp(worldLength: Float) = worldLength * scale / pixelsPerDp
    fun textCenter(element: TextElement): WorldPoint {
        return resolvedRenderedBounds[element.id]?.center ?: WorldPoint(element.x, element.y)
    }
    fun center(id: String): WorldPoint? = texts.firstOrNull { it.id == id }
        ?.let(::textCenter) ?: centerOf(id)
    val collapsed = shapes.filter { shape ->
        shape.kind == ShapeKind.REGION && shape.name.isNotBlank() &&
            (tier == SemanticTier.FAR || apparentDp(shape.width) < 120f || apparentDp(shape.height) < 90f)
    }
    val hidden = mutableSetOf<String>()
    fun coveredPoint(id: String, center: WorldPoint): Boolean {
        return collapsed.any { region ->
            region.id != id && region.bounds().contains(center) &&
                (shapes.firstOrNull { it.id == id }?.bounds()?.area ?: 0f) < region.bounds().area
        }
    }
    fun covered(id: String): Boolean = center(id)?.let { coveredPoint(id, it) } ?: false

    shapes.filter { it.kind == ShapeKind.REGION && it.id !in keep && covered(it.id) }
        .forEach { hidden += it.id }
    texts.filter { it.id !in keep &&
        (covered(it.id) || tier == SemanticTier.FAR && it.kind == TextKind.BODY) }
        .forEach { hidden += it.id }
    shapes.filter { it.kind != ShapeKind.REGION && it.id !in keep }.forEach { shape ->
        val contained = texts.filter { shape.bounds().contains(textCenter(it)) }
        if (covered(shape.id) || tier == SemanticTier.FAR &&
            apparentDp(min(shape.width, shape.height)) < 24f ||
            contained.isNotEmpty() && contained.all { it.id in hidden }) hidden += shape.id
    }
    val titleGlyphDp = max(titleDp * scale, if (tier == SemanticTier.FAR) 9f else 11f)
    if (tier != SemanticTier.NEAR) texts.filter { it.kind == TextKind.TITLE && it.id !in keep &&
        titleAvailableWidth(it, titleLineHeightWorld,
            shapes.filter { shape -> shape.id !in hidden })?.let { width ->
            apparentDp(width) < titleGlyphDp * 2f } == true }
        .forEach { hidden += it.id }
    shapes.filter { it.kind != ShapeKind.REGION && it.id !in keep && it.id !in hidden }.forEach { shape ->
        val contained = texts.filter { shape.bounds().contains(textCenter(it)) }
        if (contained.isNotEmpty() && contained.all { it.id in hidden }) hidden += shape.id
    }
    ink.filter { it.id !in keep &&
        (covered(it.id) || tier == SemanticTier.FAR &&
            resolvedRenderedBounds[it.id]?.let { bounds ->
                apparentDp(max(bounds.right - bounds.left, bounds.bottom - bounds.top)) < 16f
            } == true) }
        .forEach { hidden += it.id }
    images.filter { it.id !in keep && (covered(it.id) || tier == SemanticTier.FAR &&
        apparentDp(max(it.width, it.height)) < 16f) }.forEach { hidden += it.id }
    arrows.filter { arrow ->
        if (arrow.id in keep) return@filter false
        val attachedHidden = listOf(arrow.from, arrow.to).any {
            it is ArrowEnd.Attached && it.targetId in hidden
        }
        val geometry = arrowRenderGeometry(arrow, scale, pixelsPerDp, resolvedRenderedBounds)
        val bounds = geometry?.bounds ?: resolvedRenderedBounds[arrow.id]
        val center = geometry?.let {
            WorldPoint((it.start.x + 2f * it.control.x + it.end.x) / 4f,
                (it.start.y + 2f * it.control.y + it.end.y) / 4f)
        } ?: bounds?.center ?: centerOf(arrow.id)
        val pathLengthDp = geometry?.let {
            val chordLength = kotlin.math.hypot(it.end.x - it.start.x, it.end.y - it.start.y)
            val midpoint = WorldPoint((it.start.x + it.end.x) / 2f,
                (it.start.y + it.end.y) / 2f)
            val bendExtent = kotlin.math.hypot(it.control.x - midpoint.x,
                it.control.y - midpoint.y) / 2f
            apparentDp(max(chordLength, bendExtent))
        } ?: 0f
        attachedHidden || center?.let { coveredPoint(arrow.id, it) } == true ||
            tier == SemanticTier.FAR && pathLengthDp < 20f
    }.forEach { hidden += it.id }
    hidden.removeAll(keep)
    return SemanticProjection(tier, collapsed.map { it.id }.toSet(), hidden,
        ((9f - bodyDp * scale) / 4f).coerceIn(0f, 1f))
}

data class CanvasMatch(val id: String, val bounds: WorldBounds, val region: Boolean)

fun BoardSnapshot.searchCanvas(query: String): List<CanvasMatch> {
    val term = query.trim()
    if (term.isEmpty()) return emptyList()
    return (texts.asSequence().filter { it.text.contains(term, ignoreCase = true) }
        .map { CanvasMatch(it.id, WorldBounds(it.x, it.y, it.x, it.y), false) } +
        shapes.asSequence().filter { it.kind == ShapeKind.REGION &&
            it.name.contains(term, ignoreCase = true) }
            .map { CanvasMatch(it.id, it.bounds(), true) })
        .sortedWith(compareBy<CanvasMatch> { it.bounds.top }.thenBy { it.bounds.left }.thenBy { it.id })
        .toList()
}

fun BoardSnapshot.titleAvailableWidth(element: TextElement,
                                      lineHeightWorld: Float = 22.5f,
                                      boundaryShapes: List<ShapeElement> = shapes): Float? {
    val lineCenter = WorldPoint(element.x, element.y + lineHeightWorld / 2f)
    val rightEdges = boundaryShapes.asSequence().filter { it.kind == ShapeKind.REGION &&
        it.bounds().contains(lineCenter) }.map { it.x + it.width } +
        boundaryShapes.asSequence().filter { shape ->
            !(shape.kind == ShapeKind.REGION && shape.bounds().contains(lineCenter)) &&
                shape.x + shape.width > element.x &&
                shape.y < element.y + lineHeightWorld && shape.y + shape.height > element.y
        }.map { max(it.x, element.x) }
    return rightEdges.minOrNull()?.let { (it - element.x).coerceAtLeast(0f) }
}

fun searchIndex(current: Int, change: Int, count: Int): Int =
    if (count == 0) 0 else ((current + change) % count + count) % count

fun BoardSnapshot.visibleLassoSelection(
    vertices: List<WorldPoint>,
    projection: SemanticProjection,
    resolvedRenderedBounds: Map<String, WorldBounds> = resolveRenderedGeometry(
        emptyMap()).boundsById,
    arrowEndpointOffset: Float = 6f,
): Set<String> = lassoSelection(vertices, resolvedRenderedBounds, arrowEndpointOffset)
    .filterTo(mutableSetOf()) { projection.visible(it) }

fun Viewport.centerOn(point: WorldPoint, width: Float, height: Float, targetScale: Float,
                      verticalFraction: Float = .5f): Viewport {
    val s = targetScale.coerceIn(.15f, 3f)
    return Viewport(s, width / 2f - point.x * s, height * verticalFraction - point.y * s)
}

fun Viewport.cycleZoom(bodyDp: Float, width: Float, height: Float): Viewport {
    val target = when (semanticTier(bodyDp, scale)) {
        SemanticTier.NEAR -> 7f / bodyDp
        SemanticTier.MID -> 3.5f / bodyDp
        SemanticTier.FAR -> 1f
    }
    val (x, y) = screenToWorld(width / 2f, height / 2f)
    return centerOn(WorldPoint(x, y), width, height, target)
}

fun Viewport.doubleTapZoom(x: Float, y: Float, bodyDp: Float, width: Float, height: Float): Viewport {
    val (worldX, worldY) = screenToWorld(x, y)
    val target = if (semanticTier(bodyDp, scale) == SemanticTier.NEAR) 7f / bodyDp else 1f
    return centerOn(WorldPoint(worldX, worldY), width, height, target)
}

fun Viewport.fitRegion(region: ShapeElement, width: Float, height: Float,
                       pixelsPerDp: Float = 1f): Viewport {
    val fittingScale = min((width - 40f) / region.width, (height - 200f) / region.height)
    val expandedScale = max(1.1f, max(120f * pixelsPerDp / region.width,
        90f * pixelsPerDp / region.height))
    val s = min(fittingScale, expandedScale).coerceIn(.15f, 3f)
    return centerOn(region.bounds().center, width, height, s)
}

fun Viewport.focusMatch(match: CanvasMatch, width: Float, height: Float): Viewport {
    val target = if (match.region) min(max(scale, .35f),
        min((width - 60f) / (match.bounds.right - match.bounds.left),
            height * .5f / (match.bounds.bottom - match.bounds.top)))
    else max(scale, .8f)
    return centerOn(match.bounds.center, width, height, target, .36f)
}
