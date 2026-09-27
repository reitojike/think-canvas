package com.thinkcanvas.board

import com.thinkcanvas.canvas.BoardSnapshot
import com.thinkcanvas.canvas.Viewport
import com.thinkcanvas.canvas.WorldBounds
import com.thinkcanvas.canvas.boundsOf
import com.thinkcanvas.canvas.arrowPoints
import com.thinkcanvas.canvas.arrowControl
import kotlin.math.max
import kotlin.math.min

fun BoardSnapshot.contentBounds(): WorldBounds? {
    val bounds = (texts.mapNotNull { boundsOf(it.id) } +
        shapes.mapNotNull { boundsOf(it.id) } +
        ink.mapNotNull { boundsOf(it.id) } +
        arrows.mapNotNull { arrow ->
            val (from, to) = arrowPoints(arrow) ?: return@mapNotNull null
            val control = arrowControl(arrow) ?: return@mapNotNull null
            WorldBounds(min(from.x, min(to.x, control.x)), min(from.y, min(to.y, control.y)),
                max(from.x, max(to.x, control.x)), max(from.y, max(to.y, control.y)))
        }).filter { listOf(it.left, it.top, it.right, it.bottom).all(Float::isFinite) }
    if (bounds.isEmpty()) return null
    return WorldBounds(bounds.minOf { it.left }, bounds.minOf { it.top },
        bounds.maxOf { it.right }, bounds.maxOf { it.bottom })
}

fun BoardSnapshot.fittedViewport(width: Float, height: Float): Viewport {
    val bounds = contentBounds() ?: return Viewport()
    val contentWidth = (bounds.right - bounds.left).coerceAtLeast(1f)
    val contentHeight = (bounds.bottom - bounds.top).coerceAtLeast(1f)
    val scale = min(.9f, min((width - 40f).coerceAtLeast(1f) / contentWidth,
        (height - 180f).coerceAtLeast(1f) / contentHeight)).coerceAtLeast(.15f)
    return Viewport(scale, width / 2f - bounds.center.x * scale,
        height / 2f - bounds.center.y * scale)
}
