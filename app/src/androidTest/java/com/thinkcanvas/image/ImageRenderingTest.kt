package com.thinkcanvas.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thinkcanvas.board.BoardImageRenderer
import com.thinkcanvas.board.drawBoardThumbnail
import com.thinkcanvas.board.planShare
import com.thinkcanvas.canvas.BoardSnapshot
import com.thinkcanvas.canvas.ImageElement
import com.thinkcanvas.data.ImageAssetReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.CRC32
import com.thinkcanvas.test.PrSmoke
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageRenderingTest {
    private fun <T> withStore(block: (Context, ImageAssetStore, File) -> T): T {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "render-${UUID.randomUUID()}")
        try { return block(context, ImageAssetStore(directory), directory) }
        finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    @PrSmoke
    @Test fun allEightExifOrientationsPreserveQuadrantsAndOrientedIntrinsicRatio() = withStore { context, store, _ ->
        val expected = listOf(
            listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
            listOf(Color.GREEN, Color.RED, Color.YELLOW, Color.BLUE),
            listOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
            listOf(Color.BLUE, Color.YELLOW, Color.RED, Color.GREEN),
            listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW),
            listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN),
            listOf(Color.YELLOW, Color.GREEN, Color.BLUE, Color.RED),
            listOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE))
        for (orientation in 1..8) {
            val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
            for (y in 0 until 40) for (x in 0 until 80) bitmap.setPixel(x, y,
                when { x < 40 && y < 20 -> Color.RED; x >= 40 && y < 20 -> Color.GREEN
                    x < 40 -> Color.BLUE; else -> Color.YELLOW })
            val source = File(context.cacheDir, "exif-${UUID.randomUUID()}.jpg")
            try {
                source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
                bitmap.recycle()
                ExifInterface(source).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                    setAttribute(ExifInterface.TAG_GPS_LATITUDE, "35/1,0/1,0/1")
                    saveAttributes()
                }
                assertNotNull(ExifInterface(source).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
                val id = UUID.randomUUID().toString()
                val asset = source.inputStream().use { store.import(it, id) }
                val dimensions = if (orientation >= 5) 40 to 80 else 80 to 40
                assertEquals(dimensions, asset.intrinsicWidth to asset.intrinsicHeight)
                val decoded = store.decode(id, 256)
                try {
                    val actual = listOf(decoded.getPixel(decoded.width / 4, decoded.height / 4),
                        decoded.getPixel(decoded.width * 3 / 4, decoded.height / 4),
                        decoded.getPixel(decoded.width / 4, decoded.height * 3 / 4),
                        decoded.getPixel(decoded.width * 3 / 4, decoded.height * 3 / 4))
                    actual.zip(expected[orientation - 1]).forEach { (pixel, color) ->
                        assertTrue("orientation=$orientation", kotlin.math.abs(Color.red(pixel) - Color.red(color)) < 25 &&
                            kotlin.math.abs(Color.green(pixel) - Color.green(color)) < 25 &&
                            kotlin.math.abs(Color.blue(pixel) - Color.blue(color)) < 25)
                    }
                    val image = ImageElement(assetId = id, x = 10f, y = 20f,
                        width = asset.intrinsicWidth.toFloat(), height = asset.intrinsicHeight.toFloat(),
                        intrinsicWidth = asset.intrinsicWidth, intrinsicHeight = asset.intrinsicHeight)
                    val plan = planShare(BoardSnapshot(images = listOf(image)))
                    val exported = BoardImageRenderer.render(plan,
                        ImageAssetReader { assetId, side -> store.decode(assetId, side) })
                    try {
                        listOf(.25f to .25f, .75f to .25f, .25f to .75f, .75f to .75f)
                            .zip(expected[orientation - 1]).forEach { (position, color) ->
                                val pixel = exported.getPixel(
                                    ((image.x + image.width * position.first - plan.imageBounds.left) * plan.pixelsPerWorldUnit).toInt(),
                                    ((image.y + image.height * position.second - plan.imageBounds.top) * plan.pixelsPerWorldUnit).toInt())
                                assertTrue(kotlin.math.abs(Color.red(pixel) - Color.red(color)) < 25 &&
                                    kotlin.math.abs(Color.green(pixel) - Color.green(color)) < 25 &&
                                    kotlin.math.abs(Color.blue(pixel) - Color.blue(color)) < 25)
                            }
                        val bytes = ByteArrayOutputStream().use { output ->
                            exported.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray()
                        }
                        assertEquals(null, ExifInterface(ByteArrayInputStream(bytes)).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
                    } finally { exported.recycle() }
                } finally { decoded.recycle() }
            } finally { source.delete() }
        }
    }

    @Test fun transparentImageAppearsInBoardSelectionAndThumbnailWithoutExtraCaption() = withStore { context, store, _ ->
        val source = imageFixture(context)
        try {
            val id = UUID.randomUUID().toString()
            source.inputStream().use { store.import(it, id) }
            val image = ImageElement(assetId = id, x = 10f, y = 20f, width = 160f, height = 80f,
                intrinsicWidth = 80, intrinsicHeight = 40, altText = "画面に描かない説明")
            val snapshot = BoardSnapshot(images = listOf(image))
            val plan = planShare(snapshot)
            assertEquals(plan, planShare(snapshot, setOf(image.id)))
            val rendered = BoardImageRenderer.render(plan, ImageAssetReader { assetId, side -> store.decode(assetId, side) })
            try {
                fun pixel(fractionX: Float, fractionY: Float) = rendered.getPixel(
                    ((image.x + image.width * fractionX - plan.imageBounds.left) * plan.pixelsPerWorldUnit).toInt(),
                    ((image.y + image.height * fractionY - plan.imageBounds.top) * plan.pixelsPerWorldUnit).toInt())
                assertEquals(0xFFFCFCFB.toInt(), pixel(.25f, .25f))
                assertEquals(Color.RED, pixel(.75f, .75f))
            } finally { rendered.recycle() }
            val thumbnail = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888)
            val decoded = store.decode(id, 256)
            try {
                drawBoardThumbnail(Canvas(thumbnail), snapshot, 320f, 200f) { decoded }
                var redPixels = 0
                for (y in 0 until thumbnail.height) for (x in 0 until thumbnail.width)
                    if (thumbnail.getPixel(x, y) == Color.RED) redPixels++
                assertTrue(redPixels > 100)
            } finally { decoded.recycle(); thumbnail.recycle() }
        } finally { source.delete() }
    }

    @Test fun corruptPixelPayloadIsRejectedEvenWhenHeaderDimensionsAndChunksAreReadable() = withStore { context, store, directory ->
        val source = imageFixture(context)
        try {
            val broken = ByteArrayOutputStream()
            DataInputStream(source.inputStream()).use { input ->
                val output = DataOutputStream(broken)
                val header = ByteArray(8).also(input::readFully); output.write(header)
                while (input.available() > 0) {
                    val length = input.readInt()
                    val type = ByteArray(4).also(input::readFully)
                    val bytes = ByteArray(length).also(input::readFully)
                    input.readInt()
                    if (type.toString(Charsets.US_ASCII) == "IDAT") bytes.fill(0)
                    val crc = CRC32().apply { update(type); update(bytes) }
                    output.writeInt(length); output.write(type); output.write(bytes); output.writeInt(crc.value.toInt())
                }
            }
            assertTrue(runCatching { store.import(ByteArrayInputStream(broken.toByteArray()), UUID.randomUUID().toString()) }.isFailure)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally { source.delete() }
    }

    @Test fun splitIdatEmptyChunksAndTrailingPaddingPreserveImportedPixels() = withStore { context, store, _ ->
        val source = imageFixture(context)
        try {
            val repackaged = ByteArrayOutputStream()
            DataInputStream(source.inputStream()).use { input ->
                val output = DataOutputStream(repackaged)
                output.write(ByteArray(8).also(input::readFully))
                fun chunk(type: ByteArray, bytes: ByteArray) {
                    output.writeInt(bytes.size); output.write(type); output.write(bytes)
                    output.writeInt(CRC32().apply { update(type); update(bytes) }.value.toInt())
                }
                while (input.available() > 0) {
                    val length = input.readInt()
                    val type = ByteArray(4).also(input::readFully)
                    val bytes = ByteArray(length).also(input::readFully)
                    input.readInt()
                    if (type.toString(Charsets.US_ASCII) == "IDAT") {
                        chunk(type, byteArrayOf())
                        bytes.forEach { chunk(type, byteArrayOf(it)) }
                        chunk(type, byteArrayOf(0, 0, 0))
                    } else chunk(type, bytes)
                }
            }
            val id = UUID.randomUUID().toString()
            store.import(ByteArrayInputStream(repackaged.toByteArray()), id)
            val decoded = store.decode(id, 256)
            try {
                assertEquals(80, decoded.width); assertEquals(40, decoded.height)
                assertEquals(0, Color.alpha(decoded.getPixel(20, 10)))
                assertEquals(Color.RED, decoded.getPixel(60, 30))
            } finally { decoded.recycle() }
        } finally { source.delete() }
    }

    @PrSmoke
    @Test fun highResolutionSourceIsSampledAndMissingAssetFailsTheWholeOutput() = withStore { context, store, _ ->
        val source = File(context.cacheDir, "large-${UUID.randomUUID()}.png")
        try {
            val bitmap = Bitmap.createBitmap(4096, 2048, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.RED)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val id = UUID.randomUUID().toString()
            val asset = source.inputStream().use { store.import(it, id) }
            assertEquals(4096, asset.intrinsicWidth)
            val decoded = store.decode(id, 2048)
            assertEquals(2048, decoded.width)
            assertEquals(1024, decoded.height)
            assertTrue(decoded.byteCount <= ImageDecodePolicy.MAX_PIXELS * 4)
            decoded.recycle()
            val missing = ImageElement(assetId = UUID.randomUUID().toString(), x = 0f, y = 0f,
                width = 200f, height = 100f, intrinsicWidth = 80, intrinsicHeight = 40)
            assertTrue(runCatching { BoardImageRenderer.render(planShare(BoardSnapshot(images = listOf(missing))),
                ImageAssetReader { assetId, side -> store.decode(assetId, side) }) }.isFailure)
        } finally { source.delete() }
    }
}
