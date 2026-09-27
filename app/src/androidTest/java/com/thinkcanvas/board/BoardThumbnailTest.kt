package com.thinkcanvas.board

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thinkcanvas.canvas.ArrowElement
import com.thinkcanvas.canvas.ArrowEnd
import com.thinkcanvas.canvas.BoardSnapshot
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
