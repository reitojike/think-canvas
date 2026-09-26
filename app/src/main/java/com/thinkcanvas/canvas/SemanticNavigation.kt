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

fun BoardSnapshot.semanticProjection(
    scale: Float,
    bodyDp: Float,
    keep: Set<String> = emptySet(),
    pixelsPerDp: Float = 1f,
    titleDp: Float = 15f,
): SemanticProjection {
    val tier = semanticTier(bodyDp, scale)
    fun apparentDp(worldLength: Float) = worldLength * scale / pixelsPerDp
    val collapsed = shapes.filter { shape ->
        shape.kind == ShapeKind.REGION && shape.name.isNotBlank() &&
            (tier == SemanticTier.FAR || apparentDp(shape.width) < 120f || apparentDp(shape.height) < 90f)
    }
    val hidden = mutableSetOf<String>()
    fun covered(id: String): Boolean {
        val center = centerOf(id) ?: return false
        return collapsed.any { region ->
            region.id != id && region.bounds().contains(center) &&
                (shapes.firstOrNull { it.id == id }?.bounds()?.area ?: 0f) < region.bounds().area
        }
    }

    shapes.filter { it.kind == ShapeKind.REGION && it.id !in keep && covered(it.id) }
        .forEach { hidden += it.id }
    texts.filter { it.id !in keep &&
        (covered(it.id) || tier == SemanticTier.FAR && it.kind == TextKind.BODY) }
        .forEach { hidden += it.id }
    val titleGlyphDp = max(titleDp * scale, if (tier == SemanticTier.FAR) 9f else 11f)
    if (tier != SemanticTier.NEAR) texts.filter { it.kind == TextKind.TITLE && it.id !in keep &&
        titleAvailableWidth(it)?.let { width -> apparentDp(width) < titleGlyphDp * 2f } == true }
        .forEach { hidden += it.id }
    shapes.filter { it.kind != ShapeKind.REGION && it.id !in keep }.forEach { shape ->
        val contained = texts.filter { shape.bounds().contains(centerOf(it.id)!!) }
        if (covered(shape.id) || tier == SemanticTier.FAR &&
            (apparentDp(min(shape.width, shape.height)) < 24f ||
                contained.isNotEmpty() && contained.all { it.id in hidden })) hidden += shape.id
    }
    ink.filter { it.id !in keep &&
        (covered(it.id) || tier == SemanticTier.FAR &&
            apparentDp(max(it.bounds().right - it.bounds().left,
                it.bounds().bottom - it.bounds().top)) < 16f) }
        .forEach { hidden += it.id }
    arrows.filter { arrow ->
        if (arrow.id in keep) return@filter false
        val attachedHidden = listOf(arrow.from, arrow.to).any {
            it is ArrowEnd.Attached && it.targetId in hidden
        }
        val length = arrowPoints(arrow)?.let { (a, b) ->
            apparentDp(kotlin.math.hypot(b.x - a.x, b.y - a.y))
        } ?: 0f
        attachedHidden || covered(arrow.id) || tier == SemanticTier.FAR && length < 20f
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
        .mapNotNull { boundsOf(it.id)?.let { bounds -> CanvasMatch(it.id, bounds, false) } } +
        shapes.asSequence().filter { it.kind == ShapeKind.REGION &&
            it.name.contains(term, ignoreCase = true) }
            .map { CanvasMatch(it.id, it.bounds(), true) })
        .sortedWith(compareBy<CanvasMatch> { it.bounds.top }.thenBy { it.bounds.left }.thenBy { it.id })
        .toList()
}

fun BoardSnapshot.titleAvailableWidth(element: TextElement): Float? {
    val center = centerOf(element.id) ?: return null
    val rightEdges = shapes.asSequence().filter { it.kind == ShapeKind.REGION &&
        it.bounds().contains(center) }.map { it.x + it.width } +
        shapes.asSequence().filter { it.x > element.x &&
            center.y in it.y..(it.y + it.height) }.map { it.x }
    return rightEdges.minOrNull()?.let { (it - element.x).coerceAtLeast(0f) }
}

fun searchIndex(current: Int, change: Int, count: Int): Int =
    if (count == 0) 0 else ((current + change) % count + count) % count

fun BoardSnapshot.visibleLassoSelection(
    vertices: List<WorldPoint>,
    projection: SemanticProjection,
): Set<String> = lassoSelection(vertices).filterTo(mutableSetOf()) { projection.visible(it) }

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
