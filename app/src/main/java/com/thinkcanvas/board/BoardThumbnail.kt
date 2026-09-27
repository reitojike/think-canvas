package com.thinkcanvas.board

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import com.thinkcanvas.canvas.BoardSnapshot
import com.thinkcanvas.canvas.ShapeKind
import com.thinkcanvas.canvas.TextKind
import com.thinkcanvas.canvas.semanticProjection
import kotlin.math.min

@Composable
fun BoardThumbnail(snapshot: BoardSnapshot, modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color(0xFFF7F6F4))) {
        val bounds = snapshot.contentBounds() ?: return@Canvas
        val scale = min(.5f, min((size.width - 28f) / (bounds.right - bounds.left).coerceAtLeast(1f),
            (size.height - 28f) / (bounds.bottom - bounds.top).coerceAtLeast(1f)))
        val left = (size.width - (bounds.right - bounds.left) * scale) / 2f - bounds.left * scale
        val top = (size.height - (bounds.bottom - bounds.top) * scale) / 2f - bounds.top * scale
        val projection = snapshot.semanticProjection(.25f, 14f)
        val canvas = drawContext.canvas.nativeCanvas
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
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x8C23211E.toInt(); textSize = 8f; typeface = Typeface.DEFAULT_BOLD
        }
        snapshot.texts.filter { it.kind == TextKind.TITLE && projection.visible(it.id) }
            .forEach { element ->
                canvas.drawText(element.text.lineSequence().first().take(22),
                    left + element.x * scale, top + element.y * scale + title.textSize, title)
            }
    }
}
