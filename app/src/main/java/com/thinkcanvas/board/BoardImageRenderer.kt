package com.thinkcanvas.board

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import com.thinkcanvas.canvas.InkKind
import com.thinkcanvas.canvas.BoardSnapshot
import com.thinkcanvas.canvas.ShapeElement
import com.thinkcanvas.canvas.ShapeKind
import com.thinkcanvas.canvas.TextColor
import com.thinkcanvas.canvas.TextElement
import com.thinkcanvas.canvas.TextKind
import com.thinkcanvas.canvas.WorldBounds
import com.thinkcanvas.canvas.arrowControl
import com.thinkcanvas.canvas.arrowPoints
import com.thinkcanvas.canvas.makeStroke
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** 共有プレビューと PNG に共通の描画面。画面 chrome と選択表示は描かない。 */
object BoardImageRenderer {
    /** 計画と描画に同じ組版を使い、文字や囲み名を PNG の外接範囲から落とさない。 */
    fun renderedBounds(source: BoardSnapshot, typography: ExportTypography): Map<String, WorldBounds> =
        buildMap {
            source.texts.forEach { element ->
                val layout = textLayout(element, typography)
                val width = (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) }
                    ?.let(::ceil) ?: 0f
                val lineHeight = if (element.kind == TextKind.TITLE)
                    typography.titleLineHeight else typography.bodyLineHeight
                val height = max(layout.height.toFloat(), lineHeight * layout.lineCount)
                put(element.id, WorldBounds(element.x, element.y,
                    element.x + width, element.y + height))
            }
            source.shapes.filter { it.kind == ShapeKind.REGION && it.name.isNotBlank() }
                .forEach { region -> put(region.id, regionBounds(region, typography)) }
        }

    fun render(plan: SharePlan): Bitmap {
        val bitmap = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(0xFFFCFCFB.toInt())
            val scale = plan.pixelsPerWorldUnit
            val transform = Matrix().apply {
                setValues(floatArrayOf(scale, 0f, -plan.imageBounds.left * scale,
                    0f, scale, -plan.imageBounds.top * scale, 0f, 0f, 1f))
            }
            canvas.save()
            canvas.concat(transform)
            val inkRenderer = CanvasStrokeRenderer.create()
            drawInk(canvas, plan, transform, InkKind.MARKER, inkRenderer)
            drawShapes(canvas, plan)
            drawArrows(canvas, plan)
            drawTexts(canvas, plan)
            drawInk(canvas, plan, transform, InkKind.PEN, inkRenderer)
            canvas.restore()
            return bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    private fun color(value: TextColor): Int = when (value) {
        TextColor.INK -> 0xFF23211E.toInt()
        TextColor.VERMILION -> 0xFFC54B32.toInt()
    }

    private fun regionPaint(typography: ExportTypography) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF23211E.toInt()
        textSize = typography.regionSize
        typeface = Typeface.DEFAULT
        letterSpacing = typography.regionLetterSpacingEm
    }

    private fun regionBounds(region: ShapeElement, typography: ExportTypography): WorldBounds {
        val paint = regionPaint(typography)
        val labelLeft = region.x + 8f
        val labelTop = region.y - 22f
        return WorldBounds(region.x, minOf(region.y, labelTop),
            max(region.x + region.width, labelLeft + paint.measureText(region.name)),
            max(region.y + region.height,
                labelTop + paint.fontMetrics.bottom - paint.fontMetrics.top))
    }

    private fun drawShapes(canvas: Canvas, plan: SharePlan) {
        plan.source.shapes.filter { it.id in plan.includedIds }.forEach { shape ->
            val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = color(shape.color)
                style = Paint.Style.STROKE
                strokeWidth = if (shape.kind == ShapeKind.REGION) 1.5f else 2f
                if (shape.kind == ShapeKind.REGION)
                    pathEffect = DashPathEffect(floatArrayOf(8f, 5f), 0f)
            }
            val right = shape.x + shape.width
            val bottom = shape.y + shape.height
            when (shape.kind) {
                ShapeKind.RECTANGLE -> canvas.drawRoundRect(shape.x, shape.y, right, bottom,
                    3f, 3f, outline)
                ShapeKind.ELLIPSE -> canvas.drawOval(shape.x, shape.y, right, bottom, outline)
                ShapeKind.REGION -> {
                    canvas.drawRoundRect(shape.x, shape.y, right, bottom, 12f, 12f, outline)
                    if (shape.name.isNotBlank()) {
                        val label = regionPaint(plan.typography)
                        canvas.drawText(shape.name, shape.x + 8f,
                            shape.y - 22f - label.fontMetrics.top, label)
                    }
                }
            }
        }
    }

    private fun drawArrows(canvas: Canvas, plan: SharePlan) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF23211E.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f
            strokeCap = Paint.Cap.ROUND
        }
        val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF23211E.toInt()
            style = Paint.Style.FILL
        }
        plan.source.arrows.filter { it.id in plan.includedIds }.forEach { arrow ->
            val (from, to) = plan.source.arrowPoints(arrow) ?: return@forEach
            val control = plan.source.arrowControl(arrow) ?: return@forEach
            val path = Path().apply {
                moveTo(from.x, from.y)
                quadTo(control.x, control.y, to.x, to.y)
            }
            canvas.drawPath(path, paint)
            val angle = atan2(to.y - control.y, to.x - control.x)
            val head = Path().apply {
                moveTo(to.x, to.y)
                lineTo(to.x - 11f * cos(angle - .5f), to.y - 11f * sin(angle - .5f))
                lineTo(to.x - 11f * cos(angle + .5f), to.y - 11f * sin(angle + .5f))
                close()
            }
            canvas.drawPath(head, headPaint)
        }
    }

    private fun textLayout(element: TextElement, typography: ExportTypography): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = color(element.color)
            textSize = if (element.kind == TextKind.TITLE) typography.titleSize
                else typography.bodySize
            typeface = if (element.kind == TextKind.TITLE) Typeface.DEFAULT_BOLD
                else Typeface.DEFAULT
            letterSpacing = if (element.kind == TextKind.TITLE)
                typography.titleLetterSpacingEm else typography.bodyLetterSpacingEm
        }
        val desiredHeight = if (element.kind == TextKind.TITLE)
            typography.titleLineHeight else typography.bodyLineHeight
        val naturalHeight = paint.fontMetrics.descent - paint.fontMetrics.ascent
        return StaticLayout.Builder.obtain(element.text, 0, element.text.length,
            paint, typography.textWidth.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing((desiredHeight - naturalHeight).coerceAtLeast(0f), 1f)
            .build()
    }

    private fun drawTexts(canvas: Canvas, plan: SharePlan) {
        plan.source.texts.filter { it.id in plan.includedIds }.forEach { element ->
            val layout = textLayout(element, plan.typography)
            canvas.save()
            canvas.translate(element.x, element.y)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun drawInk(canvas: Canvas, plan: SharePlan, transform: Matrix,
                        kind: InkKind, renderer: CanvasStrokeRenderer) {
        plan.source.ink.filter { it.id in plan.includedIds && it.kind == kind }.forEach { element ->
            element.strokes.forEach { stroke ->
                renderer.draw(canvas, makeStroke(element.kind, stroke.inputType, stroke.points),
                    transform)
            }
        }
    }
}
