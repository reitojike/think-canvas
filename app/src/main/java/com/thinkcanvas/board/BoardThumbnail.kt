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

internal fun thumbnailTitleBounds(snapshot: BoardSnapshot, title: Paint,
                                 scale: Float): Map<String, WorldBounds> =
    snapshot.texts.filter { it.kind == TextKind.TITLE }.associate { element ->
        val line = element.text.lineSequence().first().take(22)
        val widthWorld = title.measureText(line) / scale
        val heightWorld = title.textSize / scale
        element.id to WorldBounds(element.x, element.y,
            element.x + widthWorld, element.y + heightWorld)
    }

/** Fixed-screen region labels affect fitting, but remain outside attachment geometry. */
internal fun thumbnailRegionLabelBounds(snapshot: BoardSnapshot, label: Paint,
                                       scale: Float): Map<String, WorldBounds> =
    snapshot.shapes.filter { it.kind == ShapeKind.REGION && it.name.isNotBlank() }
        .associate { shape ->
            val width = label.measureText(shape.name.take(16)) / scale
            val height = label.textSize / scale
            val centerX = shape.x + shape.width / 2f
            val baseline = shape.y + shape.height / 2f + label.textSize / (3f * scale)
            shape.id to WorldBounds(centerX - width / 2f, baseline - height,
                centerX + width / 2f, baseline)
        }

internal fun thumbnailFitBounds(snapshot: BoardSnapshot, title: Paint, label: Paint,
                                scale: Float, pixelsPerDp: Float = 1f): WorldBounds? {
    val values = thumbnailGeometry(snapshot, thumbnailTitleBounds(snapshot, title, scale),
        scale, pixelsPerDp)
        .boundsById.values + thumbnailRegionLabelBounds(snapshot, label, scale).values
    if (values.isEmpty()) return null
    return WorldBounds(values.minOf { it.left }, values.minOf { it.top },
        values.maxOf { it.right }, values.maxOf { it.bottom })
}

internal fun thumbnailFitScale(width: Float, height: Float, bounds: WorldBounds,
                               pixelsPerDp: Float): Float =
    min(.5f * pixelsPerDp, min((width - 28f * pixelsPerDp).coerceAtLeast(1f) /
        (bounds.right - bounds.left).coerceAtLeast(1f),
        (height - 28f * pixelsPerDp).coerceAtLeast(1f) /
        (bounds.bottom - bounds.top).coerceAtLeast(1f)))

internal fun thumbnailGeometry(snapshot: BoardSnapshot,
                               titleBounds: Map<String, WorldBounds>,
                               scale: Float, pixelsPerDp: Float = 1f): ThumbnailGeometry {
    val bounds = LinkedHashMap<String, WorldBounds>()
    titleBounds.forEach { (id, value) -> bounds[id] = value }
    snapshot.shapes.forEach { shape ->
        val stored = WorldBounds(shape.x, shape.y, shape.x + shape.width, shape.y + shape.height)
        val strokeRadius = .5f * pixelsPerDp / scale
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
        val headLength = 4f * pixelsPerDp / scale
        val left = WorldPoint(end.x - headLength * cos(angle - .5f),
            end.y - headLength * sin(angle - .5f))
        val right = WorldPoint(end.x - headLength * cos(angle + .5f),
            end.y - headLength * sin(angle + .5f))
        val radius = .5f * pixelsPerDp / scale
        val points = listOf(start, end, control, left, right)
        val arrowBounds = WorldBounds(points.minOf { it.x } - radius,
            points.minOf { it.y } - radius, points.maxOf { it.x } + radius,
            points.maxOf { it.y } + radius)
        arrow.id to ArrowRenderGeometry(start, end, control, left, right, arrowBounds)
    }.toMap()
    arrowGeometry.forEach { (id, geometry) -> bounds[id] = geometry.bounds }
    return ThumbnailGeometry(bounds, arrowGeometry)
}

internal data class ThumbnailGeometry(
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
            color = 0x8C23211E.toInt(); textSize = 8f * pixelsPerDp; typeface = Typeface.DEFAULT_BOLD
        }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF23211E.toInt(); textSize = 11f * pixelsPerDp; typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        var scale = 1f
        var geometry = thumbnailGeometry(snapshot, thumbnailTitleBounds(snapshot, title, scale),
            scale, pixelsPerDp)
        var bounds = thumbnailFitBounds(snapshot, title, label, scale, pixelsPerDp) ?: return
        repeat(8) {
            scale = thumbnailFitScale(width, height, bounds, pixelsPerDp)
            geometry = thumbnailGeometry(snapshot, thumbnailTitleBounds(snapshot, title, scale),
                scale, pixelsPerDp)
            bounds = thumbnailFitBounds(snapshot, title, label, scale, pixelsPerDp) ?: bounds
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
                strokeWidth = max(pixelsPerDp,
                    (if (kind == InkKind.MARKER) 15f else 2.5f) * scale)
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
            color = 0xFFBDB8B1.toInt(); style = Paint.Style.STROKE; strokeWidth = pixelsPerDp
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x0F23211E; style = Paint.Style.FILL }
        snapshot.shapes.filter { projection.visible(it.id) }.forEach { shape ->
            val x = left + shape.x * scale
            val y = top + shape.y * scale
            val right = x + shape.width * scale
            val bottom = y + shape.height * scale
            when (shape.kind) {
                ShapeKind.REGION -> {
                    canvas.drawRoundRect(x, y, right, bottom, 8f * pixelsPerDp,
                        8f * pixelsPerDp, fill)
                    canvas.drawRoundRect(x, y, right, bottom, 8f * pixelsPerDp,
                        8f * pixelsPerDp, outline)
                    if (shape.name.isNotBlank()) canvas.drawText(shape.name.take(16),
                        (x + right) / 2f, (y + bottom) / 2f + label.textSize / 3f, label)
                }
                ShapeKind.RECTANGLE -> canvas.drawRoundRect(x, y, right, bottom,
                    2f * pixelsPerDp, 2f * pixelsPerDp, outline)
                ShapeKind.ELLIPSE -> canvas.drawOval(x, y, right, bottom, outline)
            }
        }
        val arrowLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x8C23211E.toInt(); style = Paint.Style.STROKE; strokeWidth = pixelsPerDp
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
