package com.thinkcanvas.image

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thinkcanvas.canvas.BoardSnapshot
import com.thinkcanvas.canvas.ImageElement
import com.thinkcanvas.data.CanvasStore
import com.thinkcanvas.data.CanvasDatabase
import com.thinkcanvas.data.ImageElementRow
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import com.thinkcanvas.test.PrSmoke
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageResourcesTest {
    @PrSmoke
    @Test fun duplicateSharesImmutableAssetAndDeletingOneBoardKeepsTheOtherReadable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = CanvasStore.get(context)
        val source = imageFixture(context)
        val original = store.create("duplicate image test")
        var duplicateId: Long? = null
        try {
            val image = store.importImage(Uri.fromFile(source)).await().use { imported ->
                ImageElement(assetId = imported.asset.assetId, x = 0f, y = 0f,
                    width = 200f, height = 100f, intrinsicWidth = 80, intrinsicHeight = 40).also {
                    store.save(original.details.id, BoardSnapshot(images = listOf(it))).await()
                }
            }
            val duplicate = checkNotNull(store.duplicate(original.details.id))
            duplicateId = duplicate.details.id
            assertNotEquals(image.id, duplicate.snapshot.images.single().id)
            assertEquals(image.assetId, duplicate.snapshot.images.single().assetId)
            assertTrue(store.delete(original.details.id))
            assertEquals(duplicate.snapshot, checkNotNull(store.savedBoard(duplicate.details.id)).snapshot)
            store.withImageAssets(setOf(image.assetId)) { it.decode(image.assetId, 256).recycle() }
            assertTrue(store.delete(duplicate.details.id))
            assertTrue(runCatching { store.withImageAssets(setOf(image.assetId)) { it.decode(image.assetId, 256).recycle() } }.isFailure)
        } finally {
            store.delete(original.details.id)
            duplicateId?.let { store.delete(it) }
            source.delete()
        }
    }

    @Test fun multipleHighResolutionImagesEvictOldBitmapsWithinMeasuredCacheBudget() { runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = CanvasStore.get(context)
        val database = CanvasDatabase.open(context)
        val board = store.create("cache test")
        val source = File(context.cacheDir, "cache-source-${UUID.randomUUID()}.png")
        try {
            val bitmap = Bitmap.createBitmap(4096, 2048, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.RED)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val images = mutableListOf<ImageElement>()
            repeat(8) { index ->
                store.importImage(Uri.fromFile(source)).await().use { imported ->
                    images += ImageElement(assetId = imported.asset.assetId, x = index * 300f, y = 0f,
                        width = 200f, height = 100f, intrinsicWidth = 4096, intrinsicHeight = 2048)
                    store.save(board.details.id, BoardSnapshot(images = images.toList())).await()
                }
            }
            val resources = ImageResources.get(store)
            val keys = images.map { ImageResourceKey(it.assetId, 2048) }
            keys.forEach(resources::ensure)
            val deadline = SystemClock.uptimeMillis() + 20_000
            while (keys.any(resources::loading) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
            assertFalse(keys.any(resources::loading))
            assertFalse(keys.any(resources::failed))
            assertTrue(resources.byteCount <= ImageDecodePolicy.CACHE_BYTES)
            assertTrue(keys.count { resources.bitmap(it) != null } in 1..4)
            assertEquals(8, database.canvasDao().images(board.details.id).size)
        } finally {
            store.delete(board.details.id)
            database.close()
            source.delete()
        }
    } }
}
