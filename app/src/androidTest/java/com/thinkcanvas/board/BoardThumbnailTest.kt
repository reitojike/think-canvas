package com.thinkcanvas.board

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thinkcanvas.canvas.ArrowElement
import com.thinkcanvas.canvas.ArrowEnd
import com.thinkcanvas.canvas.BoardSnapshot
import com.thinkcanvas.canvas.ShapeElement
import com.thinkcanvas.canvas.ShapeKind
import com.thinkcanvas.canvas.TextElement
import com.thinkcanvas.canvas.TextKind
import com.thinkcanvas.canvas.InkElement
import com.thinkcanvas.canvas.InkInputType
import com.thinkcanvas.canvas.InkKind
import com.thinkcanvas.canvas.InkPoint
import com.thinkcanvas.canvas.InkStroke
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BoardThumbnailTest {
    @Test fun fittedTitleGeometryMatchesItsFixedPixelExtent() {
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 8f
            typeface = Typeface.DEFAULT_BOLD
        }
        val element = TextElement(id = "title", kind = TextKind.TITLE,
            text = "Fixed size title geometry", x = 240f, y = 80f)
        val snapshot = BoardSnapshot(texts = listOf(element))
        val scale = .25f
        val bounds = thumbnailTitleBounds(snapshot, title, scale).getValue(element.id)
        val measuredWidth = title.measureText(element.text.take(22))

        assertTrue("fixed pixel width converts back through the fitted scale",
            kotlin.math.abs((bounds.right - bounds.left) * scale - measuredWidth) < .01f)
        assertTrue("fixed pixel height converts back through the fitted scale",
            kotlin.math.abs((bounds.bottom - bounds.top) * scale - title.textSize) < .01f)
    }

    @Test fun titleNearFittedEdgeIsNotClippedAtScaleBelowHalf() {
        val snapshot = BoardSnapshot(
            texts = listOf(TextElement(id = "edge-title", kind = TextKind.TITLE,
                text = "A title near the fitted edge", x = 4_000f, y = 100f)),
            shapes = listOf(ShapeElement(id = "wide-board", kind = ShapeKind.RECTANGLE,
                x = 0f, y = 0f, width = 4_000f, height = 360f)),
        )
        val bitmap = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888)
        try {
            drawBoardThumbnail(Canvas(bitmap), snapshot, 240f, 160f)
            val paper = 0xFFF7F6F4.toInt()
            assertTrue("fitted title leaves room at the right bitmap edge",
                (0 until bitmap.height).all { y ->
                    (bitmap.width - 4 until bitmap.width).all { x -> bitmap.getPixel(x, y) == paper }
                })
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun titleAttachedArrowUsesTheCorrectedTitleBounds() {
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 8f
            typeface = Typeface.DEFAULT_BOLD
        }
        val element = TextElement(id = "arrow-title", kind = TextKind.TITLE,
            text = "Attached", x = 120f, y = 60f)
        val arrow = ArrowElement(id = "title-arrow",
            from = ArrowEnd.Attached(element.id, 1f, .5f), to = ArrowEnd.Free(700f, 140f))
        val snapshot = BoardSnapshot(texts = listOf(element), arrows = listOf(arrow))
        val scale = .2f
        val correctedBounds = thumbnailTitleBounds(snapshot, title, scale)
        val geometry = thumbnailGeometry(snapshot, correctedBounds, scale)
        val target = correctedBounds.getValue(element.id)
        val start = geometry.arrowsById.getValue(arrow.id).start

        assertTrue("attached arrow starts beyond the fixed-pixel title's corrected right edge",
            start.x >= target.right + 5.9f)
        assertTrue("arrow geometry is included in the same thumbnail fit geometry",
            geometry.bounds(arrow.id) != null)
    }

    @Test fun inkOnlyBoardHasVisiblePreview() {
        val ink = InkElement(kind = InkKind.PEN, strokes = listOf(InkStroke(
            startedAt = 0L, endedAt = 1L, inputType = InkInputType.TOUCH,
            points = listOf(InkPoint(20f, 20f, 0L), InkPoint(220f, 120f, 1L)))))
        assertVisible(BoardSnapshot(ink = listOf(ink)))
    }

    @Test fun arrowOnlyBoardHasVisiblePreview() {
        val arrow = ArrowElement(from = ArrowEnd.Free(20f, 80f),
            to = ArrowEnd.Free(220f, 80f))
        assertVisible(BoardSnapshot(arrows = listOf(arrow)))
    }

    @Test fun thumbnailVisibilityUsesItsActualFitScaleForBorderlineInk() {
        val ink = InkElement(kind = InkKind.PEN, strokes = listOf(InkStroke(
            startedAt = 0L, endedAt = 1L, inputType = InkInputType.TOUCH,
            points = listOf(InkPoint(20f, 80f, 0L), InkPoint(50f, 80f, 1L)))))
        val snapshot = BoardSnapshot(ink = listOf(ink))
        val bitmap = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888)
        try {
            drawBoardThumbnail(Canvas(bitmap), snapshot, 240f, 160f)
            val changed = (0 until bitmap.height).sumOf { y -> (0 until bitmap.width).count { x ->
                bitmap.getPixel(x, y) != 0xFFF7F6F4.toInt()
            } }
            assertTrue("visibility must follow the preview transform", changed > 20)
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertVisible(snapshot: BoardSnapshot) {
        val bitmap = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888)
        try {
            drawBoardThumbnail(Canvas(bitmap), snapshot, 240f, 160f)
            val paper = 0xFFF7F6F4.toInt()
            var changed = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width)
                if (bitmap.getPixel(x, y) != paper) changed++
            assertTrue("一覧のプレビューが空です", changed > 20)
        } finally {
            bitmap.recycle()
        }
    }
}
