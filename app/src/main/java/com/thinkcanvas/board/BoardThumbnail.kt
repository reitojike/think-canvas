package com.thinkcanvas.board

import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import com.thinkcanvas.canvas.BoardSnapshot
import com.thinkcanvas.canvas.InkKind
import com.thinkcanvas.canvas.ShapeKind
import com.thinkcanvas.canvas.TextKind
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
    Canvas(modifier.background(Color(0xFFF7F6F4))) {
        drawBoardThumbnail(drawContext.canvas.nativeCanvas, snapshot, size.width, size.height)
    }
}

/** 一覧の遠景表現。対象外の本文などは semanticProjection の規則に従う。 */
internal fun drawBoardThumbnail(canvas: AndroidCanvas, snapshot: BoardSnapshot,
                                width: Float, height: Float) {
        canvas.drawColor(0xFFF7F6F4.toInt())
        val bounds = snapshot.contentBounds() ?: return
        val scale = min(.5f, min((width - 28f).coerceAtLeast(1f) /
            (bounds.right - bounds.left).coerceAtLeast(1f),
            (height - 28f).coerceAtLeast(1f) /
            (bounds.bottom - bounds.top).coerceAtLeast(1f)))
        val left = (width - (bounds.right - bounds.left) * scale) / 2f - bounds.left * scale
        val top = (height - (bounds.bottom - bounds.top) * scale) / 2f - bounds.top * scale
        val projection = snapshot.semanticProjection(.25f, 14f)
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
            val (from, to) = snapshot.arrowPoints(arrow) ?: return@forEach
            val control = snapshot.arrowControl(arrow) ?: return@forEach
            val endX = left + to.x * scale
            val endY = top + to.y * scale
            val controlX = left + control.x * scale
            val controlY = top + control.y * scale
            canvas.drawPath(Path().apply {
                moveTo(left + from.x * scale, top + from.y * scale)
                quadTo(controlX, controlY, endX, endY)
            }, arrowLine)
            val angle = atan2(endY - controlY, endX - controlX)
            canvas.drawPath(Path().apply {
                moveTo(endX, endY)
                lineTo(endX - 4f * cos(angle - .5f), endY - 4f * sin(angle - .5f))
                lineTo(endX - 4f * cos(angle + .5f), endY - 4f * sin(angle + .5f))
                close()
            }, arrowHead)
        }
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x8C23211E.toInt(); textSize = 8f; typeface = Typeface.DEFAULT_BOLD
        }
        snapshot.texts.filter { it.kind == TextKind.TITLE && projection.visible(it.id) }
            .forEach { element ->
                canvas.drawText(element.text.lineSequence().first().take(22),
                    left + element.x * scale, top + element.y * scale + title.textSize, title)
            }
        drawInk(InkKind.PEN)
}
