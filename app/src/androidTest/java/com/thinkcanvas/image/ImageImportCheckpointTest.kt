package com.thinkcanvas.image

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thinkcanvas.canvas.WorldPoint
import java.io.File
import java.util.UUID
import com.thinkcanvas.test.PrSmoke
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageImportCheckpointTest {
    private fun request() = ImageImportRequest(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        UUID.randomUUID().toString(), 1, WorldPoint(100f, -80f), 400f, 600f, ImagePickerSource.PHOTO)

    private fun <T> withDirectory(block: (File) -> T): T {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "image-checkpoint-${UUID.randomUUID()}")
        try { return block(directory) }
        finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    @PrSmoke
    @Test fun acceptedCheckpointRestoresSameIdentityAndFixedPatchForFreshOwner() = withDirectory { directory ->
        val request = request().accepting(ImageAsset(UUID.randomUUID().toString(), 400, 200))
        ImageImportCheckpoint(directory).write(request.taskToken, request)
        val restored = ImageImportCheckpoint(directory)
        assertEquals(request, restored.read(request.taskToken))
        assertEquals(setOf(request.accepted!!.assetId), restored.retainedAssetIds())
        val json = File(directory, "${request.taskToken}.pending").readText()
        assertFalse(json.contains("uri", ignoreCase = true))
        assertFalse(json.contains("filename", ignoreCase = true))
    }

    @Test fun freshTaskDiscardsOldAcceptedRequestAndTerminalClearReleasesCheckpointRoot() = withDirectory { directory ->
        val old = request().accepting(ImageAsset(UUID.randomUUID().toString(), 40, 20))
        val current = request()
        val checkpoint = ImageImportCheckpoint(directory)
        checkpoint.write(old.taskToken, old)
        checkpoint.write(current.taskToken, current)
        checkpoint.discardOtherTasks(current.taskToken)
        assertEquals(null, checkpoint.read(old.taskToken))
        assertEquals(current, checkpoint.read(current.taskToken))
        assertTrue(checkpoint.retainedAssetIds().isEmpty())
        checkpoint.write(current.taskToken, null)
        assertEquals(null, checkpoint.read(current.taskToken))
    }

    @Test fun corruptOrOversizedRecordAndNonCanonicalTokenAreRejected() = withDirectory { directory ->
        directory.mkdirs()
        val token = UUID.randomUUID().toString()
        val record = File(directory, "$token.pending")
        record.writeText("{}"); assertTrue(runCatching { ImageImportCheckpoint(directory).read(token) }.isFailure)
        record.writeBytes(ByteArray(16385))
        assertTrue(runCatching { ImageImportCheckpoint(directory).read(token) }.isFailure)
        assertTrue(runCatching { ImageImportCheckpoint(directory).read("../outside") }.isFailure)
    }
}
