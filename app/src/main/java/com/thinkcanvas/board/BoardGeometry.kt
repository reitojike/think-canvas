package com.thinkcanvas.board

import com.thinkcanvas.canvas.BoardSnapshot
import com.thinkcanvas.canvas.Viewport
import com.thinkcanvas.canvas.WorldBounds
import com.thinkcanvas.canvas.boundsOf
import com.thinkcanvas.canvas.arrowPoints
import com.thinkcanvas.canvas.arrowControl
import kotlin.math.max
import kotlin.math.min

/** 詳細表示の囲み名が画面上で占める寸法。世界座標の倍率では拡大縮小しない。 */
data class RegionLabelSize(val width: Float, val height: Float)

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

fun BoardSnapshot.fittedViewport(width: Float, height: Float,
                                 regionLabels: Map<String, RegionLabelSize> = emptyMap()): Viewport {
    val bounds = contentBounds() ?: return Viewport()
    val availableWidth = (width - 40f).coerceAtLeast(1f).toDouble()
    val availableHeight = (height - 180f).coerceAtLeast(1f).toDouble()
    val labels = shapes.mapNotNull { shape ->
        regionLabels[shape.id]?.takeIf { shape.name.isNotBlank() &&
            it.width.isFinite() && it.height.isFinite() && it.width >= 0f && it.height >= 0f }
            ?.let { shape to it }
    }
    // 囲み名は 12sp の画面上の文字であり、世界座標の要素とは異なり倍率を掛けない。
    fun screenBounds(scale: Double): List<Double> {
        var left = bounds.left.toDouble() * scale
        var top = bounds.top.toDouble() * scale
        var right = bounds.right.toDouble() * scale
        var bottom = bounds.bottom.toDouble() * scale
        labels.forEach { (shape, label) ->
            val labelLeft = (shape.x + 8f).toDouble() * scale
            val labelTop = (shape.y - 22f).toDouble() * scale
            left = min(left, labelLeft)
            top = min(top, labelTop)
            right = max(right, labelLeft + label.width)
            bottom = max(bottom, labelTop + label.height)
        }
        return listOf(left, top, right, bottom)
    }
    fun fits(scale: Double): Boolean {
        val (left, top, right, bottom) = screenBounds(scale)
        return right - left <= availableWidth && bottom - top <= availableHeight
    }
    val contentWidth = (bounds.right.toDouble() - bounds.left).coerceAtLeast(1.0)
    val contentHeight = (bounds.bottom.toDouble() - bounds.top).coerceAtLeast(1.0)
    val upper = min(.9, min(availableWidth / contentWidth,
        availableHeight / contentHeight)).coerceAtLeast(.15)
    val scale = if (fits(upper)) upper else if (!fits(.15)) .15 else {
        var low = .15
        var high = upper
        repeat(24) {
            val middle = (low + high) / 2
            if (fits(middle)) low = middle else high = middle
        }
        low
    }
    val (left, top, right, bottom) = screenBounds(scale)
    return Viewport(scale.toFloat(), (width / 2.0 - (left + right) / 2).toFloat(),
        (height / 2.0 - (top + bottom) / 2).toFloat())
}
