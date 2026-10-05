package com.thinkcanvas.image

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageAssetStoreTest {
    private fun <T> withStore(block: (ImageAssetStore, File) -> T): T {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "image-test-${UUID.randomUUID()}")
        val store = ImageAssetStore(directory)
        try { return block(store, directory) }
        finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        bitmap.setPixel(10, 10, Color.RED)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    @Test fun privateCopySurvivesSourceLossAndPreservesAlphaWithinDecodeBudget() = withStore { store, directory ->
        val id = UUID.randomUUID().toString()
        val source = File(directory.parentFile, "source-${UUID.randomUUID()}.png")
        source.writeBytes(png())
        val asset = source.inputStream().use { store.import(it, id) }
        source.delete()
        assertEquals(ImageAsset(id, 40, 20), asset)
        val decoded = store.decode(id, 64)
        assertEquals(Color.TRANSPARENT, decoded.getPixel(0, 0))
        assertEquals(Color.RED, decoded.getPixel(10, 10))
        assertTrue(decoded.byteCount <= ImageDecodePolicy.MAX_PIXELS * 4)
        decoded.recycle()
    }

    @Test fun failedOrCancelledCopiesLeaveNoPartialOrFinalAsset() = withStore { store, directory ->
        val broken = object : InputStream() {
            var read = 0
            override fun read(): Int { if (++read > 30) throw IOException("故障"); return 0 }
        }
        assertTrue(runCatching { store.import(broken, UUID.randomUUID().toString()) }.isFailure)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertTrue(runCatching { store.import(ByteArrayInputStream(png()), UUID.randomUUID().toString()) { true } }.isFailure)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun byteLimitAndInvalidIdentityFailWithoutRetainingAnAsset() = withStore { store, directory ->
        var remaining = ImageDecodePolicy.MAX_COPY_BYTES + 1
        val huge = object : InputStream() {
            override fun read(): Int = if (remaining-- > 0) 0 else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining <= 0) return -1
                val count = minOf(remaining, length.toLong()).toInt()
                buffer.fill(0, offset, offset + count)
                remaining -= count
                return count
            }
        }
        assertTrue(runCatching { store.import(huge, UUID.randomUUID().toString()) }.isFailure)
        assertTrue(runCatching { store.import(ByteArrayInputStream(png()), "../outside") }.isFailure)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun savedCopyUndoOwnerAndActiveLeaseEachPreventPrematureCollection() = withStore { store, directory ->
        val id = UUID.randomUUID().toString()
        store.import(ByteArrayInputStream(png()), id)
        store.collect(setOf(id))
        assertTrue(File(directory, "$id.img").exists())
        store.setOwnerRoots("undo-owner", setOf(id))
        store.collect(emptySet())
        assertEquals(id, store.describe(id).assetId)
        val lease = store.pin(setOf(id))
        store.setOwnerRoots("undo-owner", emptySet())
        store.collect(emptySet())
        assertEquals(id, store.describe(id).assetId)
        lease.close()
        lease.close()
        store.collect(emptySet())
        assertFalse(File(directory, "$id.img").exists())
    }

    @Test fun differentSessionOwnersAndAcceptedCheckpointDoNotOverwriteProtection() = withStore { store, directory ->
        val id = UUID.randomUUID().toString()
        store.import(ByteArrayInputStream(png()), id)
        store.setOwnerRoots("first", setOf(id))
        store.setOwnerRoots("second", setOf(id))
        store.setOwnerRoots("first", emptySet())
        store.collect(emptySet())
        assertTrue(File(directory, "$id.img").exists())
        store.setOwnerRoots("second", emptySet())
        store.collect(emptySet(), setOf(id))
        assertTrue(File(directory, "$id.img").exists())
        store.collect(emptySet())
        assertFalse(File(directory, "$id.img").exists())
    }

    @Test fun startupSweepOnlyRemovesOwnedPartialFilesAndUnknownFormatsAreRejected() = withStore { store, directory ->
        directory.mkdirs()
        val orphan = File(directory, "${UUID.randomUUID()}.partial").apply { writeBytes(byteArrayOf(1)) }
        val foreign = File(directory, "foreign.partial").apply { writeBytes(byteArrayOf(1)) }
        store.sweepStaging()
        assertFalse(orphan.exists())
        assertTrue(foreign.exists())
        val gif = "GIF89a".toByteArray() + ByteArray(30)
        assertTrue(runCatching { store.import(ByteArrayInputStream(gif), UUID.randomUUID().toString()) }.isFailure)
        assertEquals(listOf("foreign.partial"), directory.listFiles().orEmpty().map { it.name })
    }
}
