package com.thinkcanvas.board

import android.os.Build
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thinkcanvas.canvas.BoardSnapshot
import com.thinkcanvas.canvas.ArrowElement
import com.thinkcanvas.canvas.ArrowEnd
import com.thinkcanvas.canvas.InkElement
import com.thinkcanvas.canvas.InkInputType
import com.thinkcanvas.canvas.InkKind
import com.thinkcanvas.canvas.InkPoint
import com.thinkcanvas.canvas.InkStroke
import com.thinkcanvas.canvas.ShapeElement
import com.thinkcanvas.canvas.ShapeKind
import com.thinkcanvas.canvas.TextElement
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BoardImageDeliveryTest {
    @Test fun detailedRegionAndArrowStylingRemainsInExport() {
        val source = BoardSnapshot(shapes = listOf(ShapeElement(id = "region",
            kind = ShapeKind.REGION, x = 40f, y = 40f, width = 120f, height = 80f)),
            arrows = listOf(ArrowElement(id = "arrow", from = ArrowEnd.Free(40f, 170f),
                to = ArrowEnd.Free(140f, 170f))))
        val plan = planShare(source)
        val bitmap = BoardImageRenderer.render(plan)
        fun pixel(x: Float, y: Float) = bitmap.getPixel(
            ((x - plan.imageBounds.left) * plan.pixelsPerWorldUnit).toInt(),
            ((y - plan.imageBounds.top) * plan.pixelsPerWorldUnit).toInt())
        val background = 0xFFFCFCFB.toInt()
        assertEquals("囲みの内側は塗らない", background, pixel(100f, 80f))
        val topEdge = (60..140).map { x -> pixel(x.toFloat(), 40f) }
        assertTrue("囲みの上辺には破線の線分がある", topEdge.count { it != background } > 10)
        assertTrue("囲みの上辺には破線の間隔がある", topEdge.count { it == background } > 5)
        assertNotEquals("矢印先端は塗られた三角形", background, pixel(133f, 172f))
        bitmap.recycle()
    }

    @Test fun longRegionNameRemainsVisibleBeyondNarrowRegion() {
        val region = ShapeElement(id = "region", kind = ShapeKind.REGION,
            x = 40f, y = 60f, width = 32f, height = 40f, name = "Very long region name")
        val plan = planShare(BoardSnapshot(shapes = listOf(region)))
        val bitmap = BoardImageRenderer.render(plan)
        fun px(value: Float) = ((value - plan.imageBounds.left) * plan.pixelsPerWorldUnit).toInt()
        fun py(value: Float) = ((value - plan.imageBounds.top) * plan.pixelsPerWorldUnit).toInt()
        val labelBeyondShape = (py(40f)..py(58f)).any { y ->
            (px(75f)..px(170f)).any { x ->
                bitmap.getPixel(x, y) != 0xFFFCFCFB.toInt()
            }
        }
        assertTrue("狭い囲みの外へ続く名前も画像に含む", labelBeyondShape)
        bitmap.recycle()
    }

    @Test fun markerStaysBehindShapesAndPenInExport() {
        fun stroke(id: String, kind: InkKind) = InkElement(id = id, kind = kind,
            strokes = listOf(InkStroke(id = "$id-stroke", startedAt = 1, endedAt = 21,
                inputType = InkInputType.TOUCH,
                points = listOf(InkPoint(0f, 70f, 0), InkPoint(120f, 70f, 20)))))
        val source = BoardSnapshot(shapes = listOf(ShapeElement(id = "outline",
            kind = ShapeKind.RECTANGLE, x = 40f, y = 30f, width = 80f, height = 80f)),
            ink = listOf(stroke("marker", InkKind.MARKER), stroke("pen", InkKind.PEN)))
        val plan = planShare(source)
        val outlineOnly = BoardImageRenderer.render(plan.copy(includedIds = setOf("outline")))
        val withMarker = BoardImageRenderer.render(plan.copy(includedIds = setOf("outline", "marker")))
        val withPen = BoardImageRenderer.render(plan)
        fun pixel(bitmap: android.graphics.Bitmap, x: Float, y: Float): Int = bitmap.getPixel(
            ((x - plan.imageBounds.left) * plan.pixelsPerWorldUnit).toInt(),
            ((y - plan.imageBounds.top) * plan.pixelsPerWorldUnit).toInt())
        assertNotEquals("marker が背景に見える", pixel(outlineOnly, 80f, 70f),
            pixel(withMarker, 80f, 70f))
        assertEquals("図形の線が marker より前面", pixel(outlineOnly, 40f, 70f),
            pixel(withMarker, 40f, 70f))
        assertNotEquals("pen が marker より前面", pixel(withMarker, 80f, 70f),
            pixel(withPen, 80f, 70f))
        outlineOnly.recycle(); withMarker.recycle(); withPen.recycle()
    }

    @Test fun allElementKindsArePresentInSharedBitmap() {
        val source = BoardSnapshot(
            texts = listOf(TextElement(id = "text", text = "A", x = 10f, y = 10f)),
            shapes = listOf(ShapeElement(id = "shape", kind = ShapeKind.ELLIPSE,
                x = 200f, y = 10f, width = 80f, height = 60f)),
            arrows = listOf(ArrowElement(id = "arrow", from = ArrowEnd.Free(200f, 120f),
                to = ArrowEnd.Free(280f, 160f))),
            ink = listOf(
                InkElement(id = "ink", kind = InkKind.PEN, strokes = listOf(
                    InkStroke(id = "stroke", startedAt = 1, endedAt = 21,
                        inputType = InkInputType.TOUCH,
                        points = listOf(InkPoint(10f, 150f, 0), InkPoint(100f, 170f, 20))),
                )),
            ),
        )
        val plan = planShare(source)
        assertEquals(setOf("text", "shape", "arrow", "ink"), plan.includedIds)
        val bitmap = BoardImageRenderer.render(plan)
        fun hasMark(left: Int, top: Int, right: Int, bottom: Int): Boolean {
            fun px(x: Int) = ((x - plan.imageBounds.left) * plan.pixelsPerWorldUnit)
                .toInt().coerceIn(0, bitmap.width - 1)
            fun py(y: Int) = ((y - plan.imageBounds.top) * plan.pixelsPerWorldUnit)
                .toInt().coerceIn(0, bitmap.height - 1)
            for (y in py(top)..py(bottom)) for (x in px(left)..px(right)) {
                if (bitmap.getPixel(x, y) != 0xFFFCFCFB.toInt()) return true
            }
            return false
        }
        assertTrue("文字", hasMark(10, 10, 80, 80))
        assertTrue("図形", hasMark(190, 0, 290, 80))
        assertTrue("矢印", hasMark(190, 110, 290, 175))
        assertTrue("手書き", hasMark(0, 140, 110, 180))
        assertEquals(source, plan.source)
        bitmap.recycle()
    }

    @Suppress("DEPRECATION")
    @Test fun renderedPngMatchesPreviewAndCanBeCopiedWithoutChangingBoard() { runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = BoardSnapshot(shapes = listOf(ShapeElement(id = "box",
            kind = ShapeKind.RECTANGLE, x = 0f, y = 0f, width = 100f, height = 50f)))
        val plan = planShare(source)
        val preview = BoardImageRenderer.render(plan)
        assertEquals(296, preview.width)
        assertEquals(196, preview.height)
        assertEquals(0xFFFCFCFB.toInt(), preview.getPixel(0, 0))
        assertNotEquals(0xFFFCFCFB.toInt(), preview.getPixel(48, 90))

        val png = ImageDelivery.png(preview)
        val uri = ImageDelivery.cacheUri(context, png)
        val readBack = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertTrue(png.contentEquals(readBack))
        ImageDelivery.copy(context, uri)
        val chooser = ImageDelivery.shareIntent(context, uri)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = if (Build.VERSION.SDK_INT >= 33)
            chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else chooser.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
        assertEquals(Intent.ACTION_SEND, send?.action)
        assertEquals("image/png", send?.type)
        assertEquals(uri, send?.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
        assertTrue(send!!.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(source, plan.source)
        assertEquals(1, source.shapes.size)
        preview.recycle()
    } }

    @Test fun mediaStoreSaveCanBeReadAndCleanedUp() { runBlocking {
        if (Build.VERSION.SDK_INT < 29) return@runBlocking
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = BoardSnapshot(shapes = listOf(ShapeElement(id = "oval",
            kind = ShapeKind.ELLIPSE, x = 0f, y = 0f, width = 80f, height = 60f)))
        val bitmap = BoardImageRenderer.render(planShare(source))
        val bytes = ImageDelivery.png(bitmap)
        bitmap.recycle()
        val uri = ImageDelivery.saveToPhotos(context, bytes)
        try {
            assertTrue(context.contentResolver.openInputStream(uri)!!.use {
                bytes.contentEquals(it.readBytes())
            })
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
        assertEquals(1, source.shapes.size)
    } }
}
