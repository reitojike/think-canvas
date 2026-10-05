package com.thinkcanvas.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.thinkcanvas.canvas.isCanonicalUuid
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Semaphore
import kotlinx.coroutines.CancellationException

data class ImageAsset(val assetId: String, val intrinsicWidth: Int, val intrinsicHeight: Int)

/** A pin is acquired before queuing IO, so even a queued operation protects its files. */
class ImageAssetLease internal constructor(private val release: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean()
    override fun close() { if (closed.compareAndSet(false, true)) release() }
}

class ImportedImageAsset(val asset: ImageAsset, private val lease: ImageAssetLease) : AutoCloseable {
    override fun close() = lease.close()
}

/** File mutations belong to CanvasStore's IO actor. Root/pin publication is synchronous. */
class ImageAssetStore internal constructor(private val directory: File,
                                          private val decodePermit: Semaphore = Semaphore(1)) {
    private val lock = Any()
    private val owners = mutableMapOf<String, Set<String>>()
    private val leases = mutableMapOf<String, Set<String>>()

    fun setOwnerRoots(owner: String, assets: Set<String>) = synchronized(lock) {
        require(assets.all(::isCanonicalUuid))
        if (assets.isEmpty()) owners.remove(owner) else owners[owner] = assets.toSet()
    }

    fun pin(assets: Set<String>, onRelease: () -> Unit = {}): ImageAssetLease {
        require(assets.all(::isCanonicalUuid))
        val token = UUID.randomUUID().toString()
        synchronized(lock) { leases[token] = assets.toSet() }
        return ImageAssetLease {
            synchronized(lock) { leases.remove(token) }
            onRelease()
        }
    }

    private fun file(assetId: String, suffix: String = ".img"): File {
        require(isCanonicalUuid(assetId))
        return File(directory, "$assetId$suffix")
    }

    fun import(input: InputStream, assetId: String, cancelled: () -> Boolean = { false }): ImageAsset {
        check(directory.isDirectory || directory.mkdirs()) { "画像の保存先を用意できません" }
        val target = file(assetId)
        val staging = file(assetId, ".partial")
        require(!target.exists() && !staging.exists())
        try {
            FileOutputStream(staging).use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    if (cancelled()) throw CancellationException("画像の取り込みを取り消しました")
                    val length = input.read(buffer)
                    if (length == -1) break
                    total += length
                    if (total > ImageDecodePolicy.MAX_COPY_BYTES) throw IOException("画像が大きすぎます")
                    output.write(buffer, 0, length)
                }
                output.flush()
                output.fd.sync()
            }
            if (cancelled()) throw CancellationException("画像の取り込みを取り消しました")
            val accepted = describeFile(staging, assetId)
            verifyPngPixels(staging, cancelled)
            // Bounds alone can succeed for a corrupt compressed pixel payload.
            decodeFile(staging, 256).recycle()
            if (cancelled()) throw CancellationException("画像の取り込みを取り消しました")
            check(staging.renameTo(target)) { "画像を保存できません" }
            check(describeFile(target, assetId) == accepted) { "画像を確認できません" }
            return accepted
        } catch (error: Throwable) {
            staging.delete()
            target.delete()
            throw error
        }
    }

    fun describe(assetId: String): ImageAsset = describeFile(file(assetId), assetId)

    /** Actor-only; no pending copy exists when this startup sweep runs. */
    fun sweepStaging() {
        directory.listFiles()?.filter { it.isFile && it.name.endsWith(".partial") &&
            isCanonicalUuid(it.name.removeSuffix(".partial")) }?.forEach { it.delete() }
    }

    /** Root comparison and removal share the same lock with publishers and pins. */
    fun collect(savedAssets: Set<String>, checkpointAssets: Set<String> = emptySet()) = synchronized(lock) {
        val roots = savedAssets + checkpointAssets + owners.values.flatten() + leases.values.flatten()
        directory.listFiles()?.filter { it.isFile && it.name.endsWith(".img") }?.forEach { candidate ->
            val id = candidate.name.removeSuffix(".img")
            if (isCanonicalUuid(id) && id !in roots) candidate.delete()
        }
    }

    fun decode(assetId: String, requestedSide: Int): Bitmap {
        return decodeFile(file(assetId), requestedSide)
    }

    private fun decodeFile(source: File, requestedSide: Int): Bitmap {
        decodePermit.acquire()
        try {
            val facts = sourceFacts(source)
            val options = BitmapFactory.Options().apply {
                inSampleSize = ImageDecodePolicy.sampleSize(facts.width, facts.height, requestedSide)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = BitmapFactory.decodeFile(source.absolutePath, options)
                ?: throw IOException("画像を読み込めません")
            if (maxOf(bitmap.width, bitmap.height) > ImageDecodePolicy.MAX_SIDE ||
                bitmap.width.toLong() * bitmap.height > ImageDecodePolicy.MAX_PIXELS) {
                bitmap.recycle()
                throw IOException("画像の読込み上限を超えました")
            }
            val matrix = orientationMatrix(facts.orientation)
            if (matrix.isIdentity) return bitmap
            try {
                return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } finally { bitmap.recycle() }
        } catch (_: OutOfMemoryError) {
            throw IOException("画像を読み込むためのメモリが足りません")
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw IOException("画像を読み込めません")
        } finally { decodePermit.release() }
    }

    private data class SourceFacts(val width: Int, val height: Int, val orientation: Int)

    private fun describeFile(source: File, assetId: String): ImageAsset {
        val facts = sourceFacts(source)
        val (width, height) = ImageDecodePolicy.orientedSize(facts.width, facts.height, facts.orientation)
        return ImageAsset(assetId, width, height)
    }

    private fun sourceFacts(source: File): SourceFacts {
        if (!source.isFile || source.length() !in 1..ImageDecodePolicy.MAX_COPY_BYTES)
            throw IOException("画像を読み込めません")
        val mime = staticMime(source)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, options)
        if (options.outMimeType != mime || !ImageDecodePolicy.validSource(options.outWidth, options.outHeight))
            throw IOException("対応する静止画像ではないか、画像が大きすぎます")
        val orientation = ExifInterface(source).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
            .let { if (it == 0) 1 else it }
        if (orientation !in 1..8) throw IOException("画像の向きを確認できません")
        return SourceFacts(options.outWidth, options.outHeight, orientation)
    }

    private fun staticMime(source: File): String = DataInputStream(FileInputStream(source)).use { input ->
        val header = ByteArray(12)
        input.readFully(header)
        fun unsigned(index: Int) = header[index].toInt() and 255
        if (unsigned(0) == 255 && unsigned(1) == 216 && unsigned(2) == 255) return@use "image/jpeg"
        val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        if (header.take(8).toByteArray().contentEquals(png)) {
            // Scan chunk headers, including acTL that can occur after ancillary chunks.
            input.close()
            DataInputStream(FileInputStream(source)).use { chunks ->
                chunks.skipBytes(8)
                var remaining = source.length() - 8
                var sawEnd = false
                while (remaining >= 12 && !sawEnd) {
                    val length = chunks.readInt().toLong() and 0xffffffffL
                    val type = ByteArray(4).also(chunks::readFully).toString(Charsets.US_ASCII)
                    if (type == "acTL") throw IOException("動画画像には対応していません")
                    if (length + 12 > remaining) throw IOException("画像を確認できません")
                    skipFully(chunks, length + 4)
                    remaining -= length + 12
                    sawEnd = type == "IEND"
                }
                if (!sawEnd || remaining != 0L) throw IOException("画像を確認できません")
            }
            return@use "image/png"
        }
        if (header.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
            header.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP") {
            var remaining = source.length() - 12
            val declared = (4..7).foldIndexed(0L) { index, value, position ->
                value or (unsigned(position).toLong() shl (8 * index)) }
            if (declared != source.length() - 8) throw IOException("画像を確認できません")
            while (remaining >= 8) {
                val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
                val length = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
                val padded = length + (length and 1L)
                if (padded + 8 > remaining) throw IOException("画像を確認できません")
                if (type == "ANIM" || type == "ANMF") throw IOException("動画画像には対応していません")
                if (type == "VP8X") {
                    if (length != 10L || input.readUnsignedByte() and 2 != 0)
                        throw IOException("動画画像には対応していません")
                    skipFully(input, padded - 1)
                } else skipFully(input, padded)
                remaining -= padded + 8
            }
            if (remaining != 0L) throw IOException("画像を確認できません")
            return@use "image/webp"
        }
        throw IOException("JPEG・PNG・WebPの静止画像を選んでください")
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped else {
                if (input.read() == -1) throw IOException("画像を確認できません")
                remaining--
            }
        }
    }

    private fun orientationMatrix(orientation: Int) = Matrix().apply {
        when (orientation) {
            2 -> setScale(-1f, 1f)
            3 -> setRotate(180f)
            4 -> setScale(1f, -1f)
            5 -> { setRotate(90f); postScale(-1f, 1f) }
            6 -> setRotate(90f)
            7 -> { setRotate(-90f); postScale(-1f, 1f) }
            8 -> setRotate(-90f)
        }
    }
}
