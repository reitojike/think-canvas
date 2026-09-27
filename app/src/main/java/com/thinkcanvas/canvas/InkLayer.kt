package com.thinkcanvas.canvas

import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke as ComposeStroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import kotlin.math.roundToInt

data class InkPreview(val kind: InkKind, val inputType: InkInputType, val points: List<InkPoint>)

private fun inkBrush(kind: InkKind): Brush = if (kind == InkKind.PEN)
    Brush.createWithColorIntArgb(StockBrushes.marker(), 0xFF23211E.toInt(), PEN_WIDTH_WORLD, 0.1f)
else Brush.createWithColorIntArgb(StockBrushes.marker(), 0x76C54B32, MARKER_WIDTH_WORLD, 0.1f)

internal fun makeStroke(kind: InkKind, inputType: InkInputType, points: List<InkPoint>): Stroke {
    val batch = MutableStrokeInputBatch()
    val tool = if (inputType == InkInputType.STYLUS) InputToolType.STYLUS else InputToolType.TOUCH
    points.forEach { point ->
        batch.add(StrokeInput().apply {
            update(point.x, point.y, point.elapsedMillis, tool,
                StrokeInput.NO_STROKE_UNIT_LENGTH, StrokeInput.NO_PRESSURE,
                StrokeInput.NO_TILT, StrokeInput.NO_ORIENTATION)
        })
    }
    return Stroke(inkBrush(kind), batch)
}

@Composable
fun InkLayer(
    elements: List<InkElement>,
    kind: InkKind,
    viewport: Viewport,
    selected: Set<String>,
    moving: Set<String>,
    preview: InkPreview?,
    dimmed: Boolean = false,
    onSelect: (String) -> Unit,
    onMove: (String, Float, Float) -> Boolean,
    onDelete: (String) -> Boolean,
) {
    val renderer = remember { CanvasStrokeRenderer.create() }
    val prepared = remember(elements, kind) {
        elements.filter { it.kind == kind }.flatMap { element ->
            element.strokes.map { element.id to makeStroke(kind, it.inputType, it.points) }
        }
    }
    val wetStroke = remember(preview, kind) {
        preview?.takeIf { it.kind == kind && it.points.isNotEmpty() }
            ?.let { makeStroke(kind, it.inputType, it.points) }
    }
    Canvas(Modifier.fillMaxSize()) {
        val transform = Matrix().apply {
            setValues(floatArrayOf(viewport.scale, 0f, viewport.panX,
                0f, viewport.scale, viewport.panY, 0f, 0f, 1f))
        }
        val native = drawContext.canvas.nativeCanvas
        if (dimmed) native.saveLayer(null, android.graphics.Paint().apply { alpha = 64 })
        native.save()
        native.concat(transform)
        prepared.forEach { (_, stroke) -> renderer.draw(native, stroke, transform) }
        wetStroke?.let { renderer.draw(native, it, transform) }
        native.restore()
        if (dimmed) native.restore()
        elements.filter { it.kind == kind && (it.id in selected || it.id in moving) }.forEach { element ->
            val bounds = element.bounds()
            val (x, y) = viewport.worldToScreen(bounds.left, bounds.top)
            val width = (bounds.right - bounds.left) * viewport.scale
            val height = (bounds.bottom - bounds.top) * viewport.scale
            drawRect(Color(0x19C54B32), androidx.compose.ui.geometry.Offset(x - 8f, y - 8f),
                androidx.compose.ui.geometry.Size(width + 16f, height + 16f))
            drawRect(Color(0xFFC54B32), androidx.compose.ui.geometry.Offset(x - 8f, y - 8f),
                androidx.compose.ui.geometry.Size(width + 16f, height + 16f),
                style = ComposeStroke(width = 1.dp.toPx()))
        }
    }
    elements.filter { it.kind == kind }.forEach { element ->
        val center = element.bounds().center
        val (x, y) = viewport.worldToScreen(center.x, center.y)
        Box(Modifier.offset { IntOffset(x.roundToInt() - 24.dp.roundToPx(),
            y.roundToInt() - 24.dp.roundToPx()) }.size(48.dp).semantics {
            contentDescription = if (kind == InkKind.PEN) "ペンの線" else "マーカーの線"
            stateDescription = if (element.id in selected) "選択中" else "未選択"
            onClick(label = "選択") { onSelect(element.id); true }
            customActions = listOf(
                CustomAccessibilityAction("左へ移動") { onMove(element.id, -16f, 0f) },
                CustomAccessibilityAction("右へ移動") { onMove(element.id, 16f, 0f) },
                CustomAccessibilityAction("上へ移動") { onMove(element.id, 0f, -16f) },
                CustomAccessibilityAction("下へ移動") { onMove(element.id, 0f, 16f) },
                CustomAccessibilityAction("削除") { onDelete(element.id) },
            )
        })
    }
}
