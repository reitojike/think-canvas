package com.thinkcanvas.image

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PngValidationTest {
    @get:Rule val folder = TemporaryFolder()

    private fun png(rows: ByteArray, split: Boolean = false, padding: Boolean = false,
                    interlaced: Boolean = false, depth: Int = 8): File {
        val compressed = ByteArrayOutputStream().also { bytes ->
            DeflaterOutputStream(bytes).use { it.write(rows) }
        }.toByteArray()
        val file = folder.newFile()
        DataOutputStream(file.outputStream()).use { out ->
            out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            fun chunk(type: String, bytes: ByteArray) {
                val name = type.toByteArray(Charsets.US_ASCII)
                out.writeInt(bytes.size); out.write(name); out.write(bytes)
                out.writeInt(CRC32().apply { update(name); update(bytes) }.value.toInt())
            }
            val header = ByteArrayOutputStream().also { bytes ->
                DataOutputStream(bytes).use {
                    it.writeInt(1); it.writeInt(1); it.writeByte(depth); it.writeByte(0)
                    it.writeByte(0); it.writeByte(0); it.writeByte(if (interlaced) 1 else 0)
                }
            }.toByteArray()
            chunk("IHDR", header)
            chunk("IDAT", byteArrayOf())
            val data = if (padding) compressed + byteArrayOf(0, 0, 0) else compressed
            if (split) data.forEach { chunk("IDAT", byteArrayOf(it)) } else chunk("IDAT", data)
            chunk("IDAT", byteArrayOf())
            chunk("IEND", byteArrayOf())
        }
        return file
    }

    @Test fun arbitraryIdatBoundariesEmptyChunksAndTrailingPaddingAreAccepted() {
        verifyPngPixels(png(byteArrayOf(0, 127), split = true, padding = true)) { false }
        verifyPngPixels(png(byteArrayOf(0, 127), padding = true)) { false }
    }

    @Test fun validZlibContainingIncompleteOrExcessiveRowsAndInvalidFiltersIsRejected() {
        listOf(byteArrayOf(0), byteArrayOf(0, 1, 2), byteArrayOf(5, 1)).forEach { rows ->
            assertThrows(IOException::class.java) { verifyPngPixels(png(rows)) { false } }
        }
    }

    @Test fun smallInterlacedAndPackedPixelsRequireOnlyNonemptyAdam7Passes() {
        verifyPngPixels(png(byteArrayOf(0, 0x80.toByte()), split = true, interlaced = true, depth = 1)) { false }
    }
}
