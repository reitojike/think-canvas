package com.thinkcanvas.image

import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.Inflater
import kotlinx.coroutines.CancellationException

/** BitmapFactory can return a partial PNG. Verify its stream before accepting immutable bytes.
 * Inflation uses fixed buffers, never a source-sized raster, including on API 26/27. */
internal fun verifyPngPixels(source: File, cancelled: () -> Boolean) {
    DataInputStream(source.inputStream().buffered()).use { input ->
        val signature = ByteArray(8).also(input::readFully)
        if (!signature.contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))) return
        val inflater = Inflater()
        val compressed = ByteArray(32 * 1024)
        val output = ByteArray(32 * 1024)
        data class Pass(val start: Long, val end: Long, val stride: Long)
        val passes = mutableListOf<Pass>()
        var expected = 0L
        var produced = 0L
        var sawHeader = false
        var sawData = false
        var dataEnded = false
        try {
            while (true) {
                if (cancelled()) throw CancellationException()
                val length = input.readInt().toLong() and 0xffffffffL
                if (length > source.length()) throw IOException("画像を確認できません")
                val typeBytes = ByteArray(4).also(input::readFully)
                val type = typeBytes.toString(Charsets.US_ASCII)
                val crc = CRC32().apply { update(typeBytes) }
                if (!sawHeader && type != "IHDR") throw IOException("画像を確認できません")
                if (type == "IHDR") {
                    if (sawHeader || length != 13L) throw IOException("画像を確認できません")
                    val header = ByteArray(13).also(input::readFully)
                    crc.update(header)
                    val width = java.nio.ByteBuffer.wrap(header, 0, 4).int
                    val height = java.nio.ByteBuffer.wrap(header, 4, 4).int
                    if (!ImageDecodePolicy.validSource(width, height)) throw IOException("画像が大きすぎます")
                    val depth = header[8].toInt() and 255
                    val channels = when (header[9].toInt() and 255) {
                        0, 3 -> 1; 2 -> 3; 4 -> 2; 6 -> 4
                        else -> throw IOException("画像を確認できません")
                    }
                    if (depth !in listOf(1, 2, 4, 8, 16) || header[10] != 0.toByte() ||
                        header[11] != 0.toByte() || header[12].toInt() !in 0..1)
                        throw IOException("画像を確認できません")
                    val steps = if (header[12] == 0.toByte()) listOf(intArrayOf(0, 0, 1, 1)) else listOf(
                        intArrayOf(0, 0, 8, 8), intArrayOf(4, 0, 8, 8), intArrayOf(0, 4, 4, 8),
                        intArrayOf(2, 0, 4, 4), intArrayOf(0, 2, 2, 4), intArrayOf(1, 0, 2, 2),
                        intArrayOf(0, 1, 1, 2))
                    steps.forEach { (x, y, dx, dy) ->
                        val w = ((width - x + dx - 1) / dx).coerceAtLeast(0)
                        val h = ((height - y + dy - 1) / dy).coerceAtLeast(0)
                        if (w > 0 && h > 0) {
                            val stride = (w.toLong() * channels * depth + 7) / 8 + 1
                            passes += Pass(expected, expected + stride * h, stride)
                            expected += stride * h
                        }
                    }
                    sawHeader = true
                } else {
                    if (type == "IDAT") {
                        if (dataEnded) throw IOException("画像を確認できません")
                        sawData = true
                    } else if (sawData) dataEnded = true
                    var remaining = length
                    while (remaining > 0) {
                        if (cancelled()) throw CancellationException()
                        val count = minOf(remaining, compressed.size.toLong()).toInt()
                        input.readFully(compressed, 0, count)
                        crc.update(compressed, 0, count)
                        remaining -= count
                        // PNG permits unused trailing bytes in the final IDAT. Still check CRC.
                        if (type != "IDAT" || inflater.finished()) continue
                        inflater.setInput(compressed, 0, count)
                        while (!inflater.needsInput() && !inflater.finished()) {
                            if (cancelled()) throw CancellationException()
                            val size = inflater.inflate(output)
                            if (size == 0 && !inflater.finished()) {
                                if (inflater.needsInput()) break
                                throw IOException("画像を確認できません")
                            }
                            val end = produced + size
                            if (end > expected) throw IOException("画像を確認できません")
                            passes.forEach { pass ->
                                val start = maxOf(produced, pass.start)
                                val limit = minOf(end, pass.end)
                                var filter = pass.start + ((start - pass.start + pass.stride - 1) / pass.stride) * pass.stride
                                while (filter < limit) {
                                    if ((output[(filter - produced).toInt()].toInt() and 255) !in 0..4)
                                        throw IOException("画像を確認できません")
                                    filter += pass.stride
                                }
                            }
                            produced = end
                        }
                    }
                }
                if ((input.readInt().toLong() and 0xffffffffL) != crc.value)
                    throw IOException("画像を確認できません")
                if (type == "IEND") {
                    if (length != 0L || !sawData || !inflater.finished() || produced != expected)
                        throw IOException("画像を確認できません")
                    break
                }
            }
        } finally { inflater.end() }
    }
}
