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
import com.thinkcanvas.canvas.WorldBounds
import com.thinkcanvas.test.PrSmoke
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BoardThumbnailTest {
    private fun labelPaint(pixelsPerDp: Float = 1f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * pixelsPerDp
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private fun titlePaint(pixelsPerDp: Float = 1f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 8f * pixelsPerDp
        typeface = Typeface.DEFAULT_BOLD
    }

    @PrSmoke
    @Test fun fixedScreenThumbnailGeometryPreservesDpAcrossDensity() {
        val region = ShapeElement(id = "region", kind = ShapeKind.REGION,
            name = "Density checked label", x = 180f, y = 80f, width = 220f, height = 120f)
        val title = TextElement(id = "title", kind = TextKind.TITLE,
            text = "Density checked title", x = 0f, y = 0f)
        val arrow = ArrowElement(id = "arrow",
            from = ArrowEnd.Attached(region.id, 1f, .5f), to = ArrowEnd.Free(700f, 140f))
        val snapshot = BoardSnapshot(texts = listOf(title), shapes = listOf(region), arrows = listOf(arrow))

        fun fittedScale(pixelsPerDp: Float, width: Float, height: Float): Float {
            val titlePaint = titlePaint(pixelsPerDp)
            val labelPaint = labelPaint(pixelsPerDp)
            var scale = .5f * pixelsPerDp
            repeat(8) {
                val bounds = thumbnailFitBounds(snapshot, titlePaint, labelPaint, scale, pixelsPerDp)!!
                scale = thumbnailFitScale(width, height, bounds, pixelsPerDp)
            }
            return scale
        }

        val scale1 = fittedScale(1f, 240f, 160f)
        val scale3 = fittedScale(3f, 720f, 480f)
        assertEquals("fit cap and outer margin stay constant in dp", scale1, scale3 / 3f, .001f)

        val bounds1 = thumbnailFitBounds(snapshot, titlePaint(1f), labelPaint(1f), scale1, 1f)!!
        val bounds3 = thumbnailFitBounds(snapshot, titlePaint(3f), labelPaint(3f), scale3, 3f)!!
        assertEquals(bounds1, bounds3)
        val geometry1 = thumbnailGeometry(snapshot,
            thumbnailTitleBounds(snapshot, titlePaint(1f), scale1), scale1, 1f)
        val geometry3 = thumbnailGeometry(snapshot,
            thumbnailTitleBounds(snapshot, titlePaint(3f), scale3), scale3, 3f)
        assertEquals("region attachment geometry is density independent",
            geometry1.arrowsById.getValue(arrow.id).start,
            geometry3.arrowsById.getValue(arrow.id).start)

        val bitmap = Bitmap.createBitmap(720, 480, Bitmap.Config.ARGB_8888)
        try {
            drawBoardThumbnail(Canvas(bitmap), snapshot, 720f, 480f, 3f)
            val paper = 0xFFF7F6F4.toInt()
            assertTrue("density-scaled fit keeps the physical outer margin",
                (0 until bitmap.height).all { y -> bitmap.getPixel(0, y) == paper &&
                    bitmap.getPixel(bitmap.width - 1, y) == paper })
        } finally {
            bitmap.recycle()
        }
    }

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

    @Test fun lowScaleRegionLabelFitsInsideLeftAndRightBitmapEdges() {
        val snapshot = BoardSnapshot(shapes = listOf(
            ShapeElement(id = "left", kind = ShapeKind.REGION, name = "左端の長い領域ラベル",
                x = 0f, y = 0f, width = 150f, height = 80f),
            ShapeElement(id = "right", kind = ShapeKind.REGION, name = "右端の長い領域ラベル",
                x = 3_850f, y = 0f, width = 150f, height = 80f),
        ))
        val bitmap = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888)
        try {
            drawBoardThumbnail(Canvas(bitmap), snapshot, 240f, 160f)
            val paper = 0xFFF7F6F4.toInt()
            assertTrue("region label fit keeps the outer bitmap edge clear",
                (0 until bitmap.height).all { y -> bitmap.getPixel(0, y) == paper &&
                    bitmap.getPixel(bitmap.width - 1, y) == paper })
            assertTrue("the test exercises the low scale fit branch",
                thumbnailFitBounds(snapshot,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; typeface = Typeface.DEFAULT_BOLD },
                    labelPaint(), .05f)!!.right > 4_000f)
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun multipleRegionLabelsContributeToFitBounds() {
        val shapes = listOf("North region", "South region", "East region").mapIndexed { index, name ->
            ShapeElement(id = "region-$index", kind = ShapeKind.REGION, name = name,
                x = index * 220f, y = index * 70f, width = 70f, height = 46f)
        }
        val snapshot = BoardSnapshot(shapes = shapes)
        val labels = thumbnailRegionLabelBounds(snapshot, labelPaint(), .2f)
        val fit = thumbnailFitBounds(snapshot,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; typeface = Typeface.DEFAULT_BOLD },
            labelPaint(), .2f)!!
        assertEquals(shapes.map { it.id }.toSet(), labels.keys)
        assertTrue("the fit envelope contains every region label",
            labels.values.all { it.left >= fit.left && it.right <= fit.right &&
                it.top >= fit.top && it.bottom <= fit.bottom })
    }

    @Test fun regionLabelFitEnvelopeDoesNotChangeShapeOrArrowAttachmentGeometry() {
        val region = ShapeElement(id = "region", kind = ShapeKind.REGION, name = "A long named region",
            x = 100f, y = 80f, width = 180f, height = 120f)
        val arrow = ArrowElement(id = "attached-arrow",
            from = ArrowEnd.Attached(region.id, 1f, .5f), to = ArrowEnd.Free(500f, 130f))
        val snapshot = BoardSnapshot(shapes = listOf(region), arrows = listOf(arrow))
        val geometry = thumbnailGeometry(snapshot, emptyMap(), .1f)
        val fit = thumbnailFitBounds(snapshot,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; typeface = Typeface.DEFAULT_BOLD },
            labelPaint(), .1f)!!
        val shapeBounds = geometry.boundsById.getValue(region.id)
        val expectedShape = WorldBounds(region.x - 5f, region.y - 5f,
            region.x + region.width + 5f, region.y + region.height + 5f)
        assertEquals(expectedShape, shapeBounds)
        assertTrue(geometry.boundsById.containsKey(arrow.id))
        assertTrue("label expands fit bounds beyond attachment geometry", fit.left < shapeBounds.left ||
            fit.right > shapeBounds.right || fit.top < shapeBounds.top || fit.bottom > shapeBounds.bottom)
        val attachment = geometry.arrowsById.getValue(arrow.id).start
        assertEquals(shapeBounds.right + 6f, attachment.x, .01f)
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
