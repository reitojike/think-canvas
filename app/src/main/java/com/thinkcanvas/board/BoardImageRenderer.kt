package com.thinkcanvas.board

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import com.thinkcanvas.canvas.InkKind
import com.thinkcanvas.canvas.ShapeKind
import com.thinkcanvas.canvas.TextColor
import com.thinkcanvas.canvas.TextKind
import com.thinkcanvas.canvas.arrowControl
import com.thinkcanvas.canvas.arrowPoints
import com.thinkcanvas.canvas.makeStroke
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** 共有プレビューと PNG に共通の描画面。画面 chrome と選択表示は描かない。 */
object BoardImageRenderer {
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

    private fun drawShapes(canvas: Canvas, plan: SharePlan) {
        plan.source.shapes.filter { it.id in plan.includedIds }.forEach { shape ->
            val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = color(shape.color)
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
            }
            val right = shape.x + shape.width
            val bottom = shape.y + shape.height
            when (shape.kind) {
                ShapeKind.RECTANGLE -> canvas.drawRoundRect(shape.x, shape.y, right, bottom,
                    6f, 6f, outline)
                ShapeKind.ELLIPSE -> canvas.drawOval(shape.x, shape.y, right, bottom, outline)
                ShapeKind.REGION -> {
                    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = 0x0F23211E
                        style = Paint.Style.FILL
                    }
                    canvas.drawRoundRect(shape.x, shape.y, right, bottom, 12f, 12f, fill)
                    canvas.drawRoundRect(shape.x, shape.y, right, bottom, 12f, 12f, outline)
                    if (shape.name.isNotBlank()) {
                        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = 0xFF23211E.toInt()
                            textSize = 12f
                            typeface = Typeface.DEFAULT_BOLD
                        }
                        canvas.drawText(shape.name, shape.x + 12f, shape.y + 18f, label)
                    }
                }
            }
        }
    }

    private fun drawArrows(canvas: Canvas, plan: SharePlan) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF23211E.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1.8f
            strokeCap = Paint.Cap.ROUND
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
                moveTo(to.x - 10f * cos(angle - .42f), to.y - 10f * sin(angle - .42f))
                lineTo(to.x, to.y)
                lineTo(to.x - 10f * cos(angle + .42f), to.y - 10f * sin(angle + .42f))
            }
            canvas.drawPath(head, paint)
        }
    }

    private fun drawTexts(canvas: Canvas, plan: SharePlan) {
        plan.source.texts.filter { it.id in plan.includedIds }.forEach { element ->
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = color(element.color)
                textSize = if (element.kind == TextKind.TITLE) 15f else 14f
                typeface = if (element.kind == TextKind.TITLE) Typeface.DEFAULT_BOLD
                    else Typeface.DEFAULT
            }
            val layout = StaticLayout.Builder.obtain(element.text, 0, element.text.length,
                paint, 166).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false).build()
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
