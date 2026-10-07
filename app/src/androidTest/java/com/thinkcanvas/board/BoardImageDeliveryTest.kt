package com.thinkcanvas.board

import android.os.Build
import android.graphics.BitmapFactory
import android.content.Intent
import android.content.res.Configuration
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
import com.thinkcanvas.canvas.arrowRenderGeometry
import kotlinx.coroutines.runBlocking
import com.thinkcanvas.test.PrSmoke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BoardImageDeliveryTest {
    @Test fun attachedAndFreeArrowEndpointsArePresentInRenderedBitmap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val typography = ExportTypography.from(context.resources)
        val leftText = TextElement(id = "left-text", text = "From", x = 20f, y = 50f)
        val rightText = TextElement(id = "right-text", text = "To", x = 300f, y = 100f)
        val leftShape = ShapeElement(id = "left-shape", kind = ShapeKind.RECTANGLE,
            x = 20f, y = 50f, width = 100f, height = 70f)
        val rightShape = ShapeElement(id = "right-shape", kind = ShapeKind.ELLIPSE,
            x = 300f, y = 100f, width = 110f, height = 80f)
        val cases = listOf(
            ArrowElement(id = "free", from = ArrowEnd.Free(20f, 250f),
                to = ArrowEnd.Free(220f, 260f)) to BoardSnapshot(),
            ArrowElement(id = "shape-shape", from = ArrowEnd.Attached(leftShape.id, 1f, .5f),
                to = ArrowEnd.Attached(rightShape.id, 0f, .5f)) to
                BoardSnapshot(shapes = listOf(leftShape, rightShape)),
            ArrowElement(id = "text-text", from = ArrowEnd.Attached(leftText.id, 1f, .5f),
                to = ArrowEnd.Attached(rightText.id, 0f, .5f), bend = 26f) to
                BoardSnapshot(texts = listOf(leftText, rightText)),
            ArrowElement(id = "text-shape", from = ArrowEnd.Attached(leftText.id, 1f, .5f),
                to = ArrowEnd.Attached(rightShape.id, 0f, .5f)) to
                BoardSnapshot(texts = listOf(leftText), shapes = listOf(rightShape)),
            ArrowElement(id = "shape-text", from = ArrowEnd.Attached(leftShape.id, 1f, .5f),
                to = ArrowEnd.Attached(rightText.id, 0f, .5f)) to
                BoardSnapshot(texts = listOf(rightText), shapes = listOf(leftShape)),
        )

        cases.forEach { (arrow, elements) ->
            val source = elements.copy(arrows = listOf(arrow))
            val renderedBounds = BoardImageRenderer.renderedBounds(source, typography)
            val plan = planShare(source, renderedBounds = renderedBounds, typography = typography)
            val geometry = source.arrowRenderGeometry(arrow,
                pixelsPerDp = typography.pixelsPerDp,
                renderedBounds = plan.resolvedGeometry.boundsById)
            assertTrue("${arrow.id}: arrow geometry contributes to plan bounds", geometry != null &&
                plan.contentBounds.left <= geometry.bounds.left &&
                plan.contentBounds.top <= geometry.bounds.top &&
                plan.contentBounds.right >= geometry.bounds.right &&
                plan.contentBounds.bottom >= geometry.bounds.bottom)

            val withArrow = BoardImageRenderer.render(plan)
            val withoutArrow = BoardImageRenderer.render(
                plan.copy(includedIds = plan.includedIds - arrow.id))
            try {
                var changedPixels = 0
                for (y in 0 until withArrow.height) for (x in 0 until withArrow.width) {
                    if (withArrow.getPixel(x, y) != withoutArrow.getPixel(x, y)) changedPixels++
                }
                assertTrue("${arrow.id}: arrow pixels are drawn", changedPixels > 0)
                if (arrow.id == "text-text") {
                    val png = runBlocking { ImageDelivery.png(withArrow) }
                    val decoded = BitmapFactory.decodeByteArray(png, 0, png.size)
                    try {
                        var pngChangedPixels = 0
                        for (y in 0 until decoded.height) for (x in 0 until decoded.width) {
                            if (decoded.getPixel(x, y) != withoutArrow.getPixel(x, y))
                                pngChangedPixels++
                        }
                        assertTrue("text-text: arrow pixels survive PNG export", pngChangedPixels > 0)
                    } finally {
                        decoded.recycle()
                    }
                }
            } finally {
                withArrow.recycle()
                withoutArrow.recycle()
            }
        }
    }

    @Test fun selectedTextArrowUsesThePlanGeometryForDrawing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val typography = ExportTypography.from(context.resources)
        val from = TextElement(id = "from", text = "From", x = 20f, y = 50f)
        val to = TextElement(id = "to", text = "To", x = 300f, y = 100f)
        val arrow = ArrowElement(id = "selected-arrow",
            from = ArrowEnd.Attached(from.id, 1f, .5f),
            to = ArrowEnd.Attached(to.id, 0f, .5f))
        val source = BoardSnapshot(texts = listOf(from, to), arrows = listOf(arrow))
        val plan = planShare(source, selectedIds = setOf(arrow.id),
            renderedBounds = BoardImageRenderer.renderedBounds(source, typography),
            typography = typography)
        assertEquals(setOf(arrow.id), plan.includedIds)
        val withArrow = BoardImageRenderer.render(plan)
        val withoutArrow = BoardImageRenderer.render(plan.copy(includedIds = emptySet()))
        try {
            var changedPixels = 0
            for (y in 0 until withArrow.height) for (x in 0 until withArrow.width) {
                if (withArrow.getPixel(x, y) != withoutArrow.getPixel(x, y)) changedPixels++
            }
            assertTrue("selected arrow is present although its target texts are not selected",
                changedPixels > 0)
        } finally {
            withArrow.recycle()
            withoutArrow.recycle()
        }
    }

    @Test fun regionLabelMasksArrowAndOutlineInExport() {
        val region = ShapeElement(id = "region", kind = ShapeKind.REGION,
            x = 40f, y = 50f, width = 160f, height = 100f, name = "重なり")
        val arrow = ArrowElement(id = "arrow", from = ArrowEnd.Free(20f, 50f),
            to = ArrowEnd.Free(240f, 50f))
        val source = BoardSnapshot(shapes = listOf(region), arrows = listOf(arrow))
        val typography = ExportTypography(regionSize = 36f)
        val plan = planShare(source, renderedBounds =
            BoardImageRenderer.renderedBounds(source, typography), typography = typography)
        val withArrow = BoardImageRenderer.render(plan)
        val withoutArrow = BoardImageRenderer.render(plan.copy(includedIds = setOf(region.id)))

        try {
            fun pixel(bitmap: android.graphics.Bitmap, x: Float, y: Float): Int = bitmap.getPixel(
                ((x - plan.imageBounds.left) * plan.pixelsPerWorldUnit).toInt(),
                ((y - plan.imageBounds.top) * plan.pixelsPerWorldUnit).toInt())
            assertTrue(pixel(withArrow, 25f, 50f) != pixel(withoutArrow, 25f, 50f))
            for (x in 50..115) assertEquals(pixel(withoutArrow, x.toFloat(), 50f),
                pixel(withArrow, x.toFloat(), 50f))
        } finally {
            withArrow.recycle()
            withoutArrow.recycle()
        }
    }

    @PrSmoke
    @Test fun exportUsesDeviceTextMetricsForPlanAndBitmap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val typography = ExportTypography.from(context.resources)
        val enlargedConfig = Configuration(context.resources.configuration).apply { fontScale = 1.5f }
        val enlarged = ExportTypography.from(
            context.createConfigurationContext(enlargedConfig).resources)
        val text = TextElement(id = "multiline", text = "一行目の本文\n二行目の本文",
            x = 40f, y = 60f)
        val source = BoardSnapshot(texts = listOf(text))
        val measured = BoardImageRenderer.renderedBounds(source, typography)
        val textBounds = measured.getValue(text.id)
        val plan = planShare(source, renderedBounds = measured, typography = typography)
        val bitmap = BoardImageRenderer.render(plan)

        try {
            assertTrue(typography.textWidth >= 166)
            assertTrue(enlarged.bodySize > typography.bodySize)
            assertTrue(enlarged.bodyLineHeight > typography.bodyLineHeight)
            assertTrue(textBounds.bottom - textBounds.top >= typography.bodyLineHeight * 2f)
            assertTrue(plan.contentBounds.right >= textBounds.right)
            assertTrue(plan.contentBounds.bottom >= textBounds.bottom)
            assertTrue(plan.imageBounds.right > textBounds.right)
            assertEquals(typography, plan.typography)
            assertTrue(bitmap.width > 0 && bitmap.height > 0)
        } finally {
            bitmap.recycle()
        }
    }

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
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = BoardSnapshot(shapes = listOf(region))
        val typography = ExportTypography.from(context.resources)
        val plan = planShare(source, renderedBounds =
            BoardImageRenderer.renderedBounds(source, typography), typography = typography)
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

    @PrSmoke
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
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val typography = ExportTypography.from(context.resources)
        val measured = BoardImageRenderer.renderedBounds(source, typography)
        val plan = planShare(source, renderedBounds = measured, typography = typography)
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
    @PrSmoke
    @Test fun renderedPngMatchesPreviewAndCanBeCopiedWithoutChangingBoard() { runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = BoardSnapshot(shapes = listOf(ShapeElement(id = "box",
            kind = ShapeKind.RECTANGLE, x = 0f, y = 0f, width = 100f, height = 50f)))
        val plan = planShare(source)
        val preview = BoardImageRenderer.render(plan)
        assertEquals(300, preview.width)
        assertEquals(200, preview.height)
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

    @PrSmoke
    @Test fun mediaStoreSaveCanBeReadAndCleanedUp() { runBlocking {
        if (Build.VERSION.SDK_INT < 29) return@runBlocking
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // The first MediaProvider call can overlap the emulator's mounted-volume scan.
        // Drain its pending work before publishing and immediately reading our image.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val idleResult = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "content call --uri content://media --method wait_for_idle"))
            .bufferedReader().use { it.readText() }
        assertTrue("MediaProvider idle barrier failed: $idleResult",
            idleResult.trim() == "Result: null")
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
