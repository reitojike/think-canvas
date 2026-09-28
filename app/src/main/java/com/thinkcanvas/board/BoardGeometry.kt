package com.thinkcanvas.board

import com.thinkcanvas.canvas.BoardSnapshot
import com.thinkcanvas.canvas.TextExtent
import com.thinkcanvas.canvas.Viewport
import com.thinkcanvas.canvas.WorldBounds
import com.thinkcanvas.canvas.boundsOf
import com.thinkcanvas.canvas.arrowPoints
import com.thinkcanvas.canvas.arrowControl
import kotlin.math.max
import kotlin.math.min
import com.thinkcanvas.canvas.ResolvedRenderedGeometry
import com.thinkcanvas.canvas.resolveRenderedGeometry
import com.thinkcanvas.canvas.DetailedRenderFacts

/** 詳細表示の囲み名が画面上で占める寸法。世界座標の倍率では拡大縮小しない。 */
data class RegionLabelSize(val width: Float, val height: Float)

fun BoardSnapshot.contentBounds(textExtents: Map<String, TextExtent> = emptyMap()): WorldBounds? {
    val exactTextBounds = texts.mapNotNull { text ->
        val measured = textExtents[text.id]?.takeIf { it.width.isFinite() && it.height.isFinite() &&
            it.width >= 0f && it.height >= 0f } ?: return@mapNotNull null
        text.id to WorldBounds(text.x, text.y, text.x + measured.width, text.y + measured.height)
    }.toMap()
    return resolveRenderedGeometry(exactTextBounds).union()
}

fun BoardSnapshot.contentBounds(geometry: ResolvedRenderedGeometry): WorldBounds? = geometry.union()

fun BoardSnapshot.fittedViewport(width: Float, height: Float,
                                 regionLabels: Map<String, RegionLabelSize> = emptyMap(),
                                 textExtents: Map<String, TextExtent> = emptyMap(),
                                 maximumScale: Float = .9f,
                                 renderedGeometry: ResolvedRenderedGeometry? = null): Viewport {
    val bounds = renderedGeometry?.union() ?: contentBounds(textExtents) ?: return Viewport()
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
            val labelLeft = (shape.x + DetailedRenderFacts.REGION_LABEL_LEFT_WORLD).toDouble() * scale
            val labelTop = (shape.y - DetailedRenderFacts.REGION_LABEL_TOP_WORLD).toDouble() * scale
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
    val upper = min(maximumScale.toDouble().coerceIn(.15, .9), min(availableWidth / contentWidth,
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
