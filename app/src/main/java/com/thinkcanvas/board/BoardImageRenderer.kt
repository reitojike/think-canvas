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
import com.thinkcanvas.canvas.DetailedRenderFacts
import com.thinkcanvas.canvas.makeStroke
import com.thinkcanvas.canvas.arrowRenderGeometry
import com.thinkcanvas.canvas.resolveRenderedGeometry
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** 共有プレビューと PNG に共通の描画面。画面 chrome と選択表示は描かない。 */
object BoardImageRenderer {
    /** 計画と描画に同じ組版を使い、文字や囲み名を PNG の外接範囲から落とさない。 */
    fun renderedBounds(source: BoardSnapshot, typography: ExportTypography): Map<String, WorldBounds> {
        val textBounds = buildMap {
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
        }
        val resolved = source.resolveRenderedGeometry(textBounds,
            scale = 1f, pixelsPerDp = typography.pixelsPerDp).boundsById.toMutableMap()
        source.shapes.filter { it.kind == ShapeKind.REGION && it.name.isNotBlank() }
            .forEach { region -> resolved[region.id] = regionBounds(region, typography) }
        return resolved
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
            drawRegionLabels(canvas, plan)
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
        val labelLeft = region.x + DetailedRenderFacts.REGION_LABEL_LEFT_WORLD
        val labelTop = region.y - DetailedRenderFacts.REGION_LABEL_TOP_WORLD
        val strokeRadius = DetailedRenderFacts.REGION_STROKE_DP * typography.pixelsPerDp / 2f
        return WorldBounds(minOf(region.x - strokeRadius, labelLeft),
            minOf(region.y - strokeRadius, labelTop),
            max(region.x + region.width + strokeRadius, labelLeft + paint.measureText(region.name)),
            max(region.y + region.height + strokeRadius,
                labelTop + paint.fontMetrics.bottom - paint.fontMetrics.top))
    }

    private fun drawShapes(canvas: Canvas, plan: SharePlan) {
        plan.source.shapes.filter { it.id in plan.includedIds }.forEach { shape ->
            val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = color(shape.color)
                style = Paint.Style.STROKE
                strokeWidth = (if (shape.kind == ShapeKind.REGION)
                    DetailedRenderFacts.REGION_STROKE_DP else DetailedRenderFacts.SHAPE_STROKE_DP) *
                    plan.typography.pixelsPerDp
                if (shape.kind == ShapeKind.REGION)
                    pathEffect = DashPathEffect(floatArrayOf(
                        DetailedRenderFacts.REGION_DASH_ON_DP * plan.typography.pixelsPerDp,
                        DetailedRenderFacts.REGION_DASH_OFF_DP * plan.typography.pixelsPerDp), 0f)
            }
            val right = shape.x + shape.width
            val bottom = shape.y + shape.height
            when (shape.kind) {
                ShapeKind.RECTANGLE -> canvas.drawRoundRect(shape.x, shape.y, right, bottom,
                    DetailedRenderFacts.RECTANGLE_CORNER_RADIUS_DP * plan.typography.pixelsPerDp,
                    DetailedRenderFacts.RECTANGLE_CORNER_RADIUS_DP * plan.typography.pixelsPerDp, outline)
                ShapeKind.ELLIPSE -> canvas.drawOval(shape.x, shape.y, right, bottom, outline)
                ShapeKind.REGION -> {
                    val radius = DetailedRenderFacts.REGION_CORNER_RADIUS_DP * plan.typography.pixelsPerDp
                    canvas.drawRoundRect(shape.x, shape.y, right, bottom, radius, radius, outline)
                }
            }
        }
    }

    private fun drawRegionLabels(canvas: Canvas, plan: SharePlan) {
        val paper = Paint().apply { color = 0xFFFCFCFB.toInt() }
        plan.source.shapes.filter { it.id in plan.includedIds &&
            it.kind == ShapeKind.REGION && it.name.isNotBlank() }.forEach { region ->
            val label = regionPaint(plan.typography)
            val left = region.x + DetailedRenderFacts.REGION_LABEL_LEFT_WORLD
            val top = region.y - DetailedRenderFacts.REGION_LABEL_TOP_WORLD
            canvas.drawRect(left, top, left + label.measureText(region.name),
                top + label.fontMetrics.bottom - label.fontMetrics.top, paper)
            canvas.drawText(region.name, left, top - label.fontMetrics.top, label)
        }
    }

    private fun drawArrows(canvas: Canvas, plan: SharePlan) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF23211E.toInt()
            style = Paint.Style.STROKE
            strokeWidth = DetailedRenderFacts.ARROW_STROKE_DP * plan.typography.pixelsPerDp
            strokeCap = Paint.Cap.ROUND
        }
        val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF23211E.toInt()
            style = Paint.Style.FILL
        }
        plan.source.arrows.filter { it.id in plan.includedIds }.forEach { arrow ->
            val geometry = plan.source.arrowRenderGeometry(arrow, scale = 1f,
                pixelsPerDp = plan.typography.pixelsPerDp) ?: return@forEach
            val path = Path().apply {
                moveTo(geometry.start.x, geometry.start.y)
                quadTo(geometry.control.x, geometry.control.y,
                    geometry.end.x, geometry.end.y)
            }
            canvas.drawPath(path, paint)
            val head = Path().apply {
                moveTo(geometry.end.x, geometry.end.y)
                lineTo(geometry.headLeft.x, geometry.headLeft.y)
                lineTo(geometry.headRight.x, geometry.headRight.y)
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
