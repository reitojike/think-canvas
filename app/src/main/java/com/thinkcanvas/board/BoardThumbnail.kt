package com.thinkcanvas.board

import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import com.thinkcanvas.canvas.BoardSnapshot
import com.thinkcanvas.canvas.InkKind
import com.thinkcanvas.canvas.ShapeKind
import com.thinkcanvas.canvas.TextKind
import com.thinkcanvas.canvas.WorldBounds
import com.thinkcanvas.canvas.WorldPoint
import com.thinkcanvas.canvas.ArrowRenderGeometry
import com.thinkcanvas.canvas.arrowControl
import com.thinkcanvas.canvas.arrowPoints
import com.thinkcanvas.canvas.semanticProjection
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

@Composable
fun BoardThumbnail(snapshot: BoardSnapshot, modifier: Modifier = Modifier) {
    val pixelsPerDp = LocalDensity.current.density
    Canvas(modifier.background(Color(0xFFF7F6F4))) {
        drawBoardThumbnail(drawContext.canvas.nativeCanvas, snapshot, size.width, size.height,
            pixelsPerDp)
    }
}

/** The preview's visibility uses the exact scale produced by its own fit transform. */
data class ThumbnailProjectionContract(val pixelsPerWorldUnit: Float, val pixelsPerDp: Float) {
    val semanticScaleDpPerWorldUnit: Float
        get() = pixelsPerWorldUnit / pixelsPerDp
}

private fun thumbnailGeometry(snapshot: BoardSnapshot,
                              titleBounds: Map<String, WorldBounds>,
                              scale: Float): ThumbnailGeometry {
    val bounds = LinkedHashMap<String, WorldBounds>()
    titleBounds.forEach { (id, value) -> bounds[id] = value }
    snapshot.shapes.forEach { shape ->
        val stored = WorldBounds(shape.x, shape.y, shape.x + shape.width, shape.y + shape.height)
        val strokeRadius = .5f / scale
        bounds[shape.id] = WorldBounds(stored.left - strokeRadius, stored.top - strokeRadius,
            stored.right + strokeRadius, stored.bottom + strokeRadius)
    }
    snapshot.ink.forEach { bounds[it.id] = it.renderedBounds() }
    // Body text is omitted in this representation. Keep its anchor for arrow attachment,
    // but exclude it from visible bounds and fit.
    val arrowTargets = bounds + snapshot.texts.filter { it.id !in bounds }.associate {
        it.id to WorldBounds(it.x, it.y, it.x, it.y)
    }
    val arrowGeometry = snapshot.arrows.mapNotNull { arrow ->
        val offset = 6f // thumbnail's far-view endpoint offset is intentionally world based.
        val (start, end) = snapshot.arrowPoints(arrow, offset, arrowTargets) ?: return@mapNotNull null
        val control = snapshot.arrowControl(arrow, offset, arrowTargets) ?: return@mapNotNull null
        val angle = atan2(end.y - control.y, end.x - control.x)
        val headLength = 4f / scale
        val left = WorldPoint(end.x - headLength * cos(angle - .5f),
            end.y - headLength * sin(angle - .5f))
        val right = WorldPoint(end.x - headLength * cos(angle + .5f),
            end.y - headLength * sin(angle + .5f))
        val radius = .5f / scale
        val points = listOf(start, end, control, left, right)
        val arrowBounds = WorldBounds(points.minOf { it.x } - radius,
            points.minOf { it.y } - radius, points.maxOf { it.x } + radius,
            points.maxOf { it.y } + radius)
        arrow.id to ArrowRenderGeometry(start, end, control, left, right, arrowBounds)
    }.toMap()
    arrowGeometry.forEach { (id, geometry) -> bounds[id] = geometry.bounds }
    return ThumbnailGeometry(bounds, arrowGeometry)
}

private data class ThumbnailGeometry(
    val boundsById: Map<String, WorldBounds>,
    val arrowsById: Map<String, ArrowRenderGeometry>,
) {
    fun bounds(id: String) = boundsById[id]
    fun union(): WorldBounds? {
        val values = boundsById.values
        if (values.isEmpty()) return null
        return WorldBounds(values.minOf { it.left }, values.minOf { it.top },
            values.maxOf { it.right }, values.maxOf { it.bottom })
    }
}

/** 一覧の遠景表現。対象外の本文などは semanticProjection の規則に従う。 */
internal fun drawBoardThumbnail(canvas: AndroidCanvas, snapshot: BoardSnapshot,
                                width: Float, height: Float, pixelsPerDp: Float = 1f) {
        canvas.drawColor(0xFFF7F6F4.toInt())
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x8C23211E.toInt(); textSize = 8f; typeface = Typeface.DEFAULT_BOLD
        }
        val titleBounds = snapshot.texts.filter { it.kind == TextKind.TITLE }.associate { element ->
            val line = element.text.lineSequence().first().take(22)
            element.id to com.thinkcanvas.canvas.WorldBounds(element.x, element.y,
                element.x + title.measureText(line), element.y + title.textSize)
        }
        var scale = 1f
        var geometry = thumbnailGeometry(snapshot, titleBounds, scale)
        var bounds = geometry.union() ?: return
        fun fit(bounds: com.thinkcanvas.canvas.WorldBounds) = min(.5f, min((width - 28f).coerceAtLeast(1f) /
            (bounds.right - bounds.left).coerceAtLeast(1f),
            (height - 28f).coerceAtLeast(1f) /
            (bounds.bottom - bounds.top).coerceAtLeast(1f)))
        repeat(8) {
            scale = fit(bounds)
            geometry = thumbnailGeometry(snapshot, titleBounds, scale)
            bounds = geometry.union() ?: bounds
        }
        val left = (width - (bounds.right - bounds.left) * scale) / 2f - bounds.left * scale
        val top = (height - (bounds.bottom - bounds.top) * scale) / 2f - bounds.top * scale
        val projection = snapshot.semanticProjection(
            ThumbnailProjectionContract(scale, pixelsPerDp).semanticScaleDpPerWorldUnit,
            14f, resolvedRenderedBounds = geometry.boundsById)
        fun drawInk(kind: InkKind) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (kind == InkKind.MARKER) 0x76C54B32 else 0xFF23211E.toInt()
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = max(1f, (if (kind == InkKind.MARKER) 15f else 2.5f) * scale)
            }
            snapshot.ink.filter { it.kind == kind && projection.visible(it.id) }.forEach { element ->
                element.strokes.forEach { stroke ->
                    val points = stroke.points
                    if (points.size == 1) canvas.drawCircle(left + points[0].x * scale,
                        top + points[0].y * scale, paint.strokeWidth / 2f, paint)
                    else {
                        val path = Path().apply {
                            moveTo(left + points[0].x * scale, top + points[0].y * scale)
                            points.drop(1).forEach { lineTo(left + it.x * scale,
                                top + it.y * scale) }
                        }
                        canvas.drawPath(path, paint)
                    }
                }
            }
        }
        drawInk(InkKind.MARKER)
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFBDB8B1.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x0F23211E; style = Paint.Style.FILL }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF23211E.toInt(); textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        snapshot.shapes.filter { projection.visible(it.id) }.forEach { shape ->
            val x = left + shape.x * scale
            val y = top + shape.y * scale
            val right = x + shape.width * scale
            val bottom = y + shape.height * scale
            when (shape.kind) {
                ShapeKind.REGION -> {
                    canvas.drawRoundRect(x, y, right, bottom, 8f, 8f, fill)
                    canvas.drawRoundRect(x, y, right, bottom, 8f, 8f, outline)
                    if (shape.name.isNotBlank()) canvas.drawText(shape.name.take(16),
                        (x + right) / 2f, (y + bottom) / 2f + label.textSize / 3f, label)
                }
                ShapeKind.RECTANGLE -> canvas.drawRoundRect(x, y, right, bottom, 2f, 2f, outline)
                ShapeKind.ELLIPSE -> canvas.drawOval(x, y, right, bottom, outline)
            }
        }
        val arrowLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x8C23211E.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f
        }
        val arrowHead = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8C23211E.toInt() }
        snapshot.arrows.filter { projection.visible(it.id) }.forEach { arrow ->
            val arrowGeometry = geometry.arrowsById[arrow.id] ?: return@forEach
            val from = arrowGeometry.start
            val to = arrowGeometry.end
            val control = arrowGeometry.control
            val endX = left + to.x * scale
            val endY = top + to.y * scale
            val controlX = left + control.x * scale
            val controlY = top + control.y * scale
            canvas.drawPath(Path().apply {
                moveTo(left + from.x * scale, top + from.y * scale)
                quadTo(controlX, controlY, endX, endY)
            }, arrowLine)
            canvas.drawPath(Path().apply {
                moveTo(endX, endY)
                lineTo(left + arrowGeometry.headLeft.x * scale,
                    top + arrowGeometry.headLeft.y * scale)
                lineTo(left + arrowGeometry.headRight.x * scale,
                    top + arrowGeometry.headRight.y * scale)
                close()
            }, arrowHead)
        }
        snapshot.texts.filter { it.kind == TextKind.TITLE && projection.visible(it.id) }
            .forEach { element ->
                canvas.drawText(element.text.lineSequence().first().take(22),
                    left + element.x * scale, top + element.y * scale + title.textSize, title)
            }
        drawInk(InkKind.PEN)
}
