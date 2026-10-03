package com.thinkcanvas.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min

enum class IndicatorKind { SEARCH, SELECTION }

data class IndicatorTarget(
    val kind: IndicatorKind,
    val ids: Set<String>,
    val bounds: WorldBounds,
    val description: String,
)

data class IndicatorLayout(
    val target: IndicatorTarget,
    val touchBounds: Rect,
    val angleDegrees: Float,
)

/** Only the two intentional target families are projected; stored geometry never changes. */
fun offscreenIndicatorLayouts(
    targets: List<IndicatorTarget>,
    viewport: Viewport,
    canvasSize: IntSize,
    density: Float,
    safeBounds: Rect,
    obstacles: List<Rect> = emptyList(),
): List<IndicatorLayout> {
    if (!density.isFinite() || density <= 0f || !viewport.scale.isFinite() || viewport.scale <= 0f ||
        !viewport.panX.isFinite() || !viewport.panY.isFinite() ||
        canvasSize.width <= 0 || canvasSize.height <= 0 || !safeBounds.finite()) return emptyList()
    val radius = 24f * density
    val gap = 8f * density
    if (safeBounds.width < radius * 2 || safeBounds.height < radius * 2 ||
        safeBounds.left < 0f || safeBounds.top < 0f ||
        safeBounds.right > canvasSize.width || safeBounds.bottom > canvasSize.height) return emptyList()
    val centers = Rect(safeBounds.left + radius, safeBounds.top + radius,
        safeBounds.right - radius, safeBounds.bottom - radius)
    val origin = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
    // The safe perimeter must surround the canvas center for a forward ray intersection.
    if (origin.x !in centers.left..centers.right || origin.y !in centers.top..centers.bottom)
        return emptyList()
    val candidates = targets.filter { target -> target.ids.isNotEmpty() &&
        target.bounds.let { b -> listOf(b.left, b.top, b.right, b.bottom).all(Float::isFinite) &&
            b.left <= b.right && b.top <= b.bottom } }
        .distinctBy { it.kind }.sortedBy { it.kind.ordinal }
    val searchIds = candidates.firstOrNull { it.kind == IndicatorKind.SEARCH }?.ids
    val layouts = mutableListOf<IndicatorLayout>()
    for (target in candidates) {
        if (target.kind == IndicatorKind.SELECTION && target.ids.size == 1 && target.ids == searchIds)
            continue
        val (left, top) = viewport.worldToScreen(target.bounds.left, target.bounds.top)
        val (right, bottom) = viewport.worldToScreen(target.bounds.right, target.bounds.bottom)
        if (!listOf(left, top, right, bottom).all(Float::isFinite)) continue
        if (right >= 0f && left <= canvasSize.width && bottom >= 0f && top <= canvasSize.height)
            continue
        val direction = Offset(left / 2f + right / 2f - origin.x,
            top / 2f + bottom / 2f - origin.y)
        if (direction == Offset.Zero || !direction.x.isFinite() || !direction.y.isFinite()) continue
        val tx = when {
            direction.x > 0 -> (centers.right - origin.x) / direction.x
            direction.x < 0 -> (centers.left - origin.x) / direction.x
            else -> Float.POSITIVE_INFINITY
        }
        val ty = when {
            direction.y > 0 -> (centers.bottom - origin.y) / direction.y
            direction.y < 0 -> (centers.top - origin.y) / direction.y
            else -> Float.POSITIVE_INFINITY
        }
        val horizontalEdge = ty <= tx
        val projected = origin + direction * min(tx, ty)
        val intersection = Offset(projected.x.coerceIn(centers.left, centers.right),
            projected.y.coerceIn(centers.top, centers.bottom))
        val desired = if (horizontalEdge) intersection.x else intersection.y
        val low = if (horizontalEdge) centers.left else centers.top
        val high = if (horizontalEdge) centers.right else centers.bottom
        val blocked = obstacles.filter(Rect::finite) + layouts.map { it.touchBounds }
        val positions = mutableListOf(desired.coerceIn(low, high), low, high)
        // The nearest feasible interval starts at an obstacle's expanded boundary.
        blocked.forEach { rect ->
            positions += (if (horizontalEdge) rect.left else rect.top) - radius - gap
            positions += (if (horizontalEdge) rect.right else rect.bottom) + radius + gap
        }
        val touch = positions.filter { it.isFinite() && it in low..high }.distinct()
            .sortedWith(compareBy<Float> { abs(it - desired) }.thenBy { it })
            .map { position ->
                val center = if (horizontalEdge) Offset(position, intersection.y)
                    else Offset(intersection.x, position)
                val x = (center.x - radius).coerceIn(safeBounds.left, safeBounds.right - radius * 2)
                val y = (center.y - radius).coerceIn(safeBounds.top, safeBounds.bottom - radius * 2)
                Rect(x, y, x + radius * 2, y + radius * 2)
            }.firstOrNull { rect -> blocked.none { it.inflate(gap - .01f).overlaps(rect) } }
            ?: continue
        layouts += IndicatorLayout(target, touch,
            atan2(direction.y, direction.x) * 180f / kotlin.math.PI.toFloat())
    }
    return layouts
}

private fun Rect.finite(): Boolean = listOf(left, top, right, bottom).all(Float::isFinite) &&
    left <= right && top <= bottom
