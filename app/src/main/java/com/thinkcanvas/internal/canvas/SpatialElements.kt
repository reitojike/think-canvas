package com.thinkcanvas.internal.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val inkColor = Color(0xFF23211E)
private val redColor = Color(0xFFC54B32)

data class SpatialPreview(
    val start: WorldPoint,
    val end: WorldPoint,
    val tool: SpatialTool,
)

@Composable
fun SpatialElements(
    snapshot: BoardSnapshot,
    viewport: Viewport,
    selected: Set<String>,
    moving: Set<String>,
    preview: SpatialPreview?,
    lasso: List<WorldPoint>,
    gap: Pair<WorldPoint, WorldPoint>?,
    ghostIds: Set<String>,
    onHandle: (String, HandleKind) -> Boolean,
    onConnect: (String, HandleKind) -> Boolean,
    onSelect: (String) -> Unit,
    onAdd: (String) -> Boolean,
    onRemove: (String) -> Boolean,
    onRename: (String) -> Boolean,
    onColor: (String) -> Boolean,
    onMove: (String, Float, Float) -> Boolean,
    onDelete: (String) -> Boolean,
    onReverse: (String) -> Boolean,
) {
    Canvas(Modifier.fillMaxSize()) {
        fun screen(p: WorldPoint): Offset {
            val (x, y) = viewport.worldToScreen(p.x, p.y)
            return Offset(x, y)
        }
        snapshot.shapes.forEach { shape ->
            val b = shape.bounds()
            val topLeft = screen(WorldPoint(b.left, b.top))
            val width = shape.width * viewport.scale
            val height = shape.height * viewport.scale
            val color = if (shape.id in ghostIds) redColor.copy(alpha = .65f)
                else if (shape.color == TextColor.VERMILION) redColor else inkColor
            val style = Stroke(width = if (shape.kind == ShapeKind.REGION) 1.5.dp.toPx() else 2.dp.toPx(),
                pathEffect = if (shape.kind == ShapeKind.REGION)
                    PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx())) else null)
            if (shape.kind == ShapeKind.ELLIPSE) {
                drawOval(color, topLeft = topLeft, size = androidx.compose.ui.geometry.Size(width, height), style = style)
            } else {
                drawRoundRect(color, topLeft = topLeft,
                    size = androidx.compose.ui.geometry.Size(width, height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(if (shape.kind == ShapeKind.REGION) 12.dp.toPx() else 3.dp.toPx()),
                    style = style)
            }
            if (shape.id in selected || shape.id in moving) {
                drawRect(redColor.copy(alpha = .06f), topLeft, androidx.compose.ui.geometry.Size(width, height))
                drawRect(redColor, topLeft, androidx.compose.ui.geometry.Size(width, height),
                    style = Stroke(if (shape.id in moving) 2.dp.toPx() else 1.dp.toPx(),
                        pathEffect = if (shape.id in moving) null else
                            PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))))
            }
        }
        snapshot.arrows.forEach { arrow ->
            val (worldStart, worldEnd) = snapshot.arrowPoints(arrow, 6f / viewport.scale) ?: return@forEach
            val worldControl = snapshot.arrowControl(arrow, 6f / viewport.scale) ?: return@forEach
            val start = screen(worldStart)
            val end = screen(worldEnd)
            val control = screen(worldControl)
            val path = Path().apply {
                moveTo(start.x, start.y)
                quadraticBezierTo(control.x, control.y, end.x, end.y)
            }
            drawPath(path, inkColor, style = Stroke(2.dp.toPx()))
            val angle = atan2(end.y - control.y, end.x - control.x)
            val size = 11.dp.toPx()
            val head = Path().apply {
                moveTo(end.x, end.y)
                lineTo(end.x - size * cos(angle - .5f), end.y - size * sin(angle - .5f))
                lineTo(end.x - size * cos(angle + .5f), end.y - size * sin(angle + .5f))
                close()
            }
            drawPath(head, inkColor)
            if (arrow.id in selected || arrow.id in moving) {
                listOf(start to arrow.from, end to arrow.to).forEach { (point, endpoint) ->
                    drawCircle(redColor, 7.dp.toPx(), point)
                    if (endpoint is ArrowEnd.Free) drawCircle(Color.White, 4.dp.toPx(), point)
                }
                val middle = screen(worldControl)
                val diamond = Path().apply {
                    moveTo(middle.x, middle.y - 7.dp.toPx())
                    lineTo(middle.x + 7.dp.toPx(), middle.y)
                    lineTo(middle.x, middle.y + 7.dp.toPx())
                    lineTo(middle.x - 7.dp.toPx(), middle.y)
                    close()
                }
                drawPath(diamond, redColor)
            }
        }
        preview?.let { p ->
            val a = screen(p.start)
            val b = screen(p.end)
            when (p.tool) {
                SpatialTool.RECTANGLE, SpatialTool.REGION -> {
                    val left = minOf(a.x, b.x)
                    val top = minOf(a.y, b.y)
                    drawRect(redColor.copy(alpha = .12f), Offset(left, top),
                        androidx.compose.ui.geometry.Size(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y)))
                    drawRect(redColor, Offset(left, top),
                        androidx.compose.ui.geometry.Size(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y)),
                        style = Stroke(2.dp.toPx()))
                }
                SpatialTool.ELLIPSE -> drawOval(redColor, topLeft = Offset(minOf(a.x, b.x), minOf(a.y, b.y)),
                    size = androidx.compose.ui.geometry.Size(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y)),
                    style = Stroke(2.dp.toPx()))
                SpatialTool.ARROW -> drawLine(redColor, a, b, 2.dp.toPx())
                SpatialTool.LASSO -> drawLine(redColor, a, b, 2.dp.toPx())
                SpatialTool.NONE -> Unit
            }
        }
        if (lasso.size > 1) {
            val path = Path().apply {
                val first = screen(lasso.first())
                moveTo(first.x, first.y)
                lasso.drop(1).forEach { val p = screen(it); lineTo(p.x, p.y) }
            }
            drawPath(path, redColor, style = Stroke(2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 4.dp.toPx()))))
        }
        gap?.let { (a, b) ->
            val start = screen(a)
            val end = screen(b)
            drawCircle(redColor.copy(alpha = .16f), 16.dp.toPx(), start)
            drawCircle(redColor, 4.dp.toPx(), start)
            val horizontal = kotlin.math.abs(end.x - start.x) >= kotlin.math.abs(end.y - start.y)
            val stripe = 8.dp.toPx()
            if (horizontal) {
                val left = minOf(start.x, end.x)
                val width = kotlin.math.abs(end.x - start.x)
                drawRect(redColor.copy(alpha = .13f), Offset(left, 0f),
                    androidx.compose.ui.geometry.Size(width, size.height))
                var y = -size.width
                while (y < size.height) {
                    drawLine(redColor.copy(alpha = .35f), Offset(left, y),
                        Offset(left + width, y + width), 1.dp.toPx())
                    y += stripe
                }
            } else {
                val top = minOf(start.y, end.y)
                val height = kotlin.math.abs(end.y - start.y)
                drawRect(redColor.copy(alpha = .13f), Offset(0f, top),
                    androidx.compose.ui.geometry.Size(size.width, height))
                var x = -size.height
                while (x < size.width) {
                    drawLine(redColor.copy(alpha = .35f), Offset(x, top),
                        Offset(x + height, top + height), 1.dp.toPx())
                    x += stripe
                }
            }
        }
    }
    snapshot.shapes.filter { it.kind == ShapeKind.REGION && it.name.isNotBlank() }.forEach { shape ->
        val (x, y) = viewport.worldToScreen(shape.x + 8f, shape.y - 22f)
        Text(shape.name, color = inkColor, fontSize = 12.sp,
            modifier = Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .background(Color(0xFFFCFCFB)).semantics { contentDescription = "囲み: ${shape.name}" })
    }
    snapshot.shapes.forEach { shape ->
        val (x, y) = viewport.worldToScreen(shape.x + shape.width / 2f, shape.y + shape.height / 2f)
        val kind = when (shape.kind) {
            ShapeKind.RECTANGLE -> "四角"
            ShapeKind.ELLIPSE -> "丸"
            ShapeKind.REGION -> "囲み"
        }
        Box(Modifier.offset { IntOffset(x.roundToInt() - 24.dp.roundToPx(),
            y.roundToInt() - 24.dp.roundToPx()) }.size(48.dp).semantics {
            contentDescription = if (shape.name.isBlank()) kind else "$kind: ${shape.name}"
            stateDescription = if (shape.id in selected) "選択中" else "未選択"
            onClick(label = "選択") { onSelect(shape.id); true }
            customActions = listOf(
                if (shape.id in selected) CustomAccessibilityAction("選択から外す") { onRemove(shape.id) }
                else CustomAccessibilityAction("選択に追加") { onAdd(shape.id) },
                CustomAccessibilityAction("左へ移動") { onMove(shape.id, -16f, 0f) },
                CustomAccessibilityAction("右へ移動") { onMove(shape.id, 16f, 0f) },
                CustomAccessibilityAction("上へ移動") { onMove(shape.id, 0f, -16f) },
                CustomAccessibilityAction("下へ移動") { onMove(shape.id, 0f, 16f) },
                CustomAccessibilityAction("大きくする") { onHandle(shape.id, HandleKind.RESIZE) },
                if (shape.kind == ShapeKind.REGION)
                    CustomAccessibilityAction("囲みの名前を編集") { onRename(shape.id) }
                else CustomAccessibilityAction("色を変更") { onColor(shape.id) },
                CustomAccessibilityAction("削除") { onDelete(shape.id) },
            )
        })
    }
    snapshot.arrows.forEach { arrow ->
        val point = snapshot.arrowControl(arrow, 6f / viewport.scale) ?: return@forEach
        val (x, y) = viewport.worldToScreen(point.x, point.y)
        Box(Modifier.offset { IntOffset(x.roundToInt() - 24.dp.roundToPx(),
            y.roundToInt() - 24.dp.roundToPx()) }.size(48.dp).semantics {
            contentDescription = "矢印"
            stateDescription = if (arrow.id in selected) "選択中" else "未選択"
            onClick(label = "選択") { onSelect(arrow.id); true }
            customActions = listOf(
                if (arrow.id in selected) CustomAccessibilityAction("選択から外す") { onRemove(arrow.id) }
                else CustomAccessibilityAction("選択に追加") { onAdd(arrow.id) },
                CustomAccessibilityAction("左へ移動") { onMove(arrow.id, -16f, 0f) },
                CustomAccessibilityAction("右へ移動") { onMove(arrow.id, 16f, 0f) },
                CustomAccessibilityAction("上へ移動") { onMove(arrow.id, 0f, -16f) },
                CustomAccessibilityAction("下へ移動") { onMove(arrow.id, 0f, 16f) },
                CustomAccessibilityAction("始点を自由端にする") { onHandle(arrow.id, HandleKind.FROM) },
                CustomAccessibilityAction("終点を自由端にする") { onHandle(arrow.id, HandleKind.TO) },
                CustomAccessibilityAction("始点を接続・付け替え") { onConnect(arrow.id, HandleKind.FROM) },
                CustomAccessibilityAction("終点を接続・付け替え") { onConnect(arrow.id, HandleKind.TO) },
                CustomAccessibilityAction("曲げる") { onHandle(arrow.id, HandleKind.BEND) },
                CustomAccessibilityAction("反転") { onReverse(arrow.id) },
                CustomAccessibilityAction("削除") { onDelete(arrow.id) },
            )
        })
    }
    snapshot.shapes.filter { it.id in selected }.forEach { shape ->
        val (x, y) = viewport.worldToScreen(shape.x + shape.width, shape.y + shape.height)
        Handle(x, y, "サイズ変更") { onHandle(shape.id, HandleKind.RESIZE) }
        val (moveX, moveY) = viewport.worldToScreen(shape.x + shape.width / 2f,
            shape.y + shape.height + 14f)
        Handle(moveX, moveY, "移動") { onHandle(shape.id, HandleKind.MOVE) }
    }
    snapshot.arrows.filter { it.id in selected }.forEach { arrow ->
        snapshot.arrowPoints(arrow, 6f / viewport.scale)?.let { (a, b) ->
            val (ax, ay) = viewport.worldToScreen(a.x, a.y)
            val (bx, by) = viewport.worldToScreen(b.x, b.y)
            Handle(ax, ay, "始点を接続・付け替え") { onConnect(arrow.id, HandleKind.FROM) }
            Handle(bx, by, "終点を接続・付け替え") { onConnect(arrow.id, HandleKind.TO) }
        }
        snapshot.arrowControl(arrow, 6f / viewport.scale)?.let { c ->
            val (x, y) = viewport.worldToScreen(c.x, c.y)
            Handle(x, y, "曲がりを変更") { onHandle(arrow.id, HandleKind.BEND) }
        }
    }
}

enum class HandleKind { MOVE, RESIZE, FROM, TO, BEND }

@Composable
private fun Handle(x: Float, y: Float, label: String, onClick: () -> Unit) {
    Box(Modifier.offset { IntOffset(x.roundToInt() - 24.dp.roundToPx(), y.roundToInt() - 24.dp.roundToPx()) }
        .size(48.dp).semantics {
            contentDescription = label
            onClick(label = label) { onClick(); true }
        },
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(12.dp).background(redColor, CircleShape))
    }
}
