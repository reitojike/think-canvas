package com.thinkcanvas.board

import com.thinkcanvas.canvas.ArrowEnd
import com.thinkcanvas.canvas.BoardSnapshot
import com.thinkcanvas.canvas.ShapeKind
import com.thinkcanvas.canvas.WorldBounds
import com.thinkcanvas.canvas.arrowControl
import com.thinkcanvas.canvas.arrowPoints
import com.thinkcanvas.canvas.bounds
import com.thinkcanvas.canvas.boundsOf
import com.thinkcanvas.canvas.centerOf
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** プレビューと PNG が同じ対象・範囲・解像度を使うための読み取り専用計画。 */
data class SharePlan(
    val source: BoardSnapshot,
    val includedIds: Set<String>,
    val contentBounds: WorldBounds,
    val imageBounds: WorldBounds,
    val width: Int,
    val height: Int,
    val pixelsPerWorldUnit: Float,
    val typography: ExportTypography,
)

fun planShare(source: BoardSnapshot, selectedIds: Set<String>? = null,
              renderedBounds: Map<String, WorldBounds> = emptyMap(),
              typography: ExportTypography = ExportTypography()): SharePlan {
    val allIds = (source.texts.map { it.id } + source.shapes.map { it.id } +
        source.arrows.map { it.id } + source.ink.map { it.id }).toSet()
    val included = if (selectedIds == null) allIds.toMutableSet()
        else selectedIds.intersect(allIds).toMutableSet()
    require(included.isNotEmpty()) { "画像にする内容がありません" }

    if (selectedIds != null) {
        val selectedRegions = source.shapes.filter {
            it.id in included && it.kind == ShapeKind.REGION
        }
        selectedRegions.forEach { region ->
            val area = region.bounds()
            source.texts.filter { source.centerOf(it.id)?.let(area::contains) == true }
                .forEach { included += it.id }
            source.shapes.filter { it.id != region.id &&
                source.centerOf(it.id)?.let(area::contains) == true }
                .forEach { included += it.id }
            source.ink.filter { source.centerOf(it.id)?.let(area::contains) == true }
                .forEach { included += it.id }
        }
        source.arrows.filter { arrow ->
            selectedRegions.any { region ->
                val ends = source.arrowPoints(arrow, offset = 0f) ?: return@any false
                val targets = listOf(arrow.from, arrow.to)
                    .filterIsInstance<ArrowEnd.Attached>()
                region.bounds().contains(ends.first) && region.bounds().contains(ends.second) &&
                    targets.all { it.targetId in included }
            }
        }.forEach { included += it.id }
    }

    val bounds = included.mapNotNull { id ->
        val shape = source.shapes.firstOrNull { it.id == id }
        val shapeBounds = renderedBounds[id] ?: source.boundsOf(id)
        if (id in renderedBounds) shapeBounds
        else if (shape != null && shape.kind == ShapeKind.REGION && shape.name.isNotBlank() &&
            shapeBounds != null) {
            // 共有画像の囲み名は上辺の外に描く。文字幅を保守的に見積もり切り抜きを防ぐ。
            WorldBounds(shapeBounds.left, min(shapeBounds.top, shape.y - 24f),
                max(shapeBounds.right, shape.x + 8f + shape.name.length * 16f),
                shapeBounds.bottom)
        } else shapeBounds ?: source.arrows.firstOrNull { it.id == id }?.let { arrow ->
            val ends = source.arrowPoints(arrow) ?: return@let null
            val control = source.arrowControl(arrow) ?: return@let null
            WorldBounds(min(ends.first.x, min(ends.second.x, control.x)),
                min(ends.first.y, min(ends.second.y, control.y)),
                max(ends.first.x, max(ends.second.x, control.x)),
                max(ends.first.y, max(ends.second.y, control.y)))
        }
    }
    require(bounds.size == included.size && bounds.all {
        listOf(it.left, it.top, it.right, it.bottom).all(Float::isFinite)
    }) { "画像化できない座標が含まれています" }
    val content = WorldBounds(bounds.minOf { it.left }, bounds.minOf { it.top },
        bounds.maxOf { it.right }, bounds.maxOf { it.bottom })
    val contentWidth = (content.right.toDouble() - content.left).coerceAtLeast(1.0)
    val contentHeight = (content.bottom.toDouble() - content.top).coerceAtLeast(1.0)
    val margin = max(24.0, max(contentWidth, contentHeight) * .05)
    val imageWidth = contentWidth + 2 * margin
    val imageHeight = contentHeight + 2 * margin
    require(imageWidth.isFinite() && imageHeight.isFinite() &&
        imageWidth <= 4096 && imageHeight <= 4096 &&
        ceil(imageWidth) * ceil(imageHeight) <= 16_000_000) {
        "対象が大きすぎるため画像にできません"
    }
    fun pixelSize(scale: Double): Pair<Int, Int> =
        ceil(imageWidth * scale).toInt() to ceil(imageHeight * scale).toInt()
    fun fits(scale: Double): Boolean {
        val (width, height) = pixelSize(scale)
        return width <= 4096 && height <= 4096 && width.toLong() * height <= 16_000_000
    }
    var low = 1.0
    var high = 2.0
    repeat(24) {
        val middle = (low + high) / 2
        if (fits(middle)) low = middle else high = middle
    }
    val (width, height) = pixelSize(low)
    return SharePlan(source, included.toSet(), content,
        WorldBounds((content.left - margin).toFloat(), (content.top - margin).toFloat(),
            (content.right + margin).toFloat(), (content.bottom + margin).toFloat()),
        width, height, low.toFloat(), typography)
}
