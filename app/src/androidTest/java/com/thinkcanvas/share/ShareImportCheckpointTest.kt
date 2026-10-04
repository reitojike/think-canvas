package com.thinkcanvas.share

import android.os.Bundle
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thinkcanvas.canvas.WorldPoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ShareImportCheckpointTest {
    @Test
    fun tokenOnlyBundleFindsLatestAcceptedPatchAtStablePosition() = withTestDirectory { directory, tokens ->
        val token = newToken(tokens)
        val checkpoint = ShareImportCheckpoint(directory, token)
        val preview = ShareImportRequest(
            requestId = UUID.randomUUID().toString(),
            elementId = UUID.randomUUID().toString(),
            text = "共有された本文",
            destinationId = 42L,
        )
        checkpoint.write(preview)

        // Save the task reference before acceptance changes the private latest checkpoint.
        val (restoredToken, parcelSize) = parcelTaskToken(token)
        val accepted = preview.copy(position = WorldPoint(-128.25f, 4096.5f))
        checkpoint.write(accepted)

        val restored = ShareImportCheckpoint(directory, restoredToken).read()
        assertTrue("token Bundle must stay small", parcelSize < 1024)
        assertEquals(accepted, restored)
        assertEquals(preview.requestId, restored?.requestId)
        assertEquals(preview.elementId, restored?.elementId)
        assertEquals(preview.text, restored?.text)
        assertEquals(preview.destinationId, restored?.destinationId)
        assertEquals(accepted.position, restored?.position)
    }

    @Test
    fun terminalCheckpointDoesNotRestoreOrRetainOldPayload() = withTestDirectory { directory, tokens ->
        val token = newToken(tokens)
        val checkpoint = ShareImportCheckpoint(directory, token)
        checkpoint.write(ShareImportRequest(text = "terminal private body 🔒"))
        checkpoint.write(null)

        assertNull(ShareImportCheckpoint(directory, token).read())
        val terminalFile = File(directory, "$token.pending")
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0), terminalFile.readBytes())
    }

    @Test
    fun freshTaskCannotRestoreOldRequestAndDiscardKeepsOnlyCurrentRecord() =
        withTestDirectory { directory, tokens ->
            val oldToken = newToken(tokens)
            val oldRequest = ShareImportRequest(text = "旧taskの保留本文")
            ShareImportCheckpoint(directory, oldToken).write(oldRequest)

            val newToken = newToken(tokens)
            assertTrue(runCatching { ShareImportCheckpoint(directory, newToken).read() }.isFailure)
            val newRequest = ShareImportRequest(text = "新taskの本文")
            val current = ShareImportCheckpoint(directory, newToken)
            current.write(newRequest)

            ShareImportCheckpoint.discardOtherTasks(directory, newToken)

            assertFalse(File(directory, "$oldToken.pending").exists())
            assertTrue(File(directory, "$newToken.pending").isFile)
            assertEquals(newRequest, ShareImportCheckpoint(directory, newToken).read())
        }

    @Test
    fun largeJapaneseAndEmojiBodyRoundTripsWithoutEnteringTokenBundle() =
        withTestDirectory { directory, tokens ->
            val token = newToken(tokens)
            val text = "日本語の長文共有と絵文字🌸🚀を保持します。\n".repeat(4096)
            assertTrue(text.toByteArray(Charsets.UTF_8).size > 64 * 1024)
            val request = ShareImportRequest(
                requestId = UUID.randomUUID().toString(),
                elementId = UUID.randomUUID().toString(),
                text = text,
                destinationId = 9L,
                position = WorldPoint(12.5f, -64.75f),
            )
            ShareImportCheckpoint(directory, token).write(request)

            val (restoredToken, parcelSize) = parcelTaskToken(token)
            assertTrue("large body must not be put in the task Bundle", parcelSize < 1024)
            assertEquals(request, ShareImportCheckpoint(directory, restoredToken).read())
        }

    @Test
    fun atomicRenameFailureIsReportedAndSameRequestCanRetry() = withTestDirectory { directory, tokens ->
        val token = newToken(tokens)
        val checkpoint = ShareImportCheckpoint(directory, token)
        val request = ShareImportRequest(
            requestId = UUID.randomUUID().toString(),
            elementId = UUID.randomUUID().toString(),
            text = "rename failure後も同じ要求で再試行",
            destinationId = 31L,
            position = WorldPoint(80.5f, -20.25f),
        )
        val base = File(directory, "$token.pending")
        val blocker = File(base, "blocker")
        val backup = File(directory, "$token.pending.bak")
        val temporary = File(directory, "$token.pending.new")
        assertTrue(base.mkdir())
        blocker.writeText("AtomicFile base rename blocker", Charsets.UTF_8)

        val failed = runCatching { checkpoint.write(request) }
        val baseStayedBlocked = base.isDirectory && blocker.isFile

        // Remove only this test's blocker and AtomicFile paths; no recursive directory deletion.
        if (blocker.isFile) assertTrue(blocker.delete())
        val backupBlocker = File(backup, "blocker")
        if (backupBlocker.isFile) assertTrue(backupBlocker.delete())
        listOf(base, backup).forEach { path ->
            if (path.isFile) assertTrue(path.delete())
            else if (path.isDirectory && path.listFiles()?.isEmpty() == true) assertTrue(path.delete())
        }
        if (temporary.isFile) assertTrue(temporary.delete())

        assertTrue("AtomicFile rename failure must not be reported as success", failed.isFailure)
        assertTrue("the nonempty base directory must have blocked the commit", baseStayedBlocked)

        checkpoint.write(request)
        assertEquals(request, checkpoint.read())
        assertTrue(runCatching { checkpoint.write(request.copy(text = "\uD800")) }.isFailure)
        assertEquals("invalid UTF-8 input must preserve the previous record", request, checkpoint.read())
        checkpoint.write(null)
        assertNull(checkpoint.read())
    }

    @Test
    fun invalidTokenAndCorruptPrivateRecordAreRejected() = withTestDirectory { directory, tokens ->
        assertNull(ShareImportCheckpoint.validToken(null))
        assertNull(ShareImportCheckpoint.validToken("not-a-uuid"))
        val canonical = UUID.randomUUID().toString()
        assertNull(ShareImportCheckpoint.validToken(canonical.uppercase()))
        assertTrue(runCatching { ShareImportCheckpoint(directory, "not-a-uuid") }.isFailure)

        val token = newToken(tokens)
        val corrupt = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(1)
                output.writeBoolean(true)
                output.writeUTF(UUID.randomUUID().toString())
                output.writeUTF(UUID.randomUUID().toString())
                output.writeInt(Int.MAX_VALUE)
            }
            bytes.toByteArray()
        }
        File(directory, "$token.pending").writeBytes(corrupt)
        assertTrue(runCatching { ShareImportCheckpoint(directory, token).read() }.isFailure)
    }

    private fun parcelTaskToken(token: String): Pair<String, Int> {
        val bundle = Bundle().apply { putString(TASK_TOKEN_KEY, token) }
        assertEquals(setOf(TASK_TOKEN_KEY), bundle.keySet())
        val parcel = Parcel.obtain()
        try {
            bundle.writeToParcel(parcel, 0)
            val size = parcel.dataSize()
            parcel.setDataPosition(0)
            val restored = checkNotNull(parcel.readBundle(ShareImportCheckpointTest::class.java.classLoader))
            assertEquals(setOf(TASK_TOKEN_KEY), restored.keySet())
            val restoredToken = checkNotNull(restored.getString(TASK_TOKEN_KEY))
            assertEquals(token, restoredToken)
            return restoredToken to size
        } finally {
            parcel.recycle()
        }
    }

    private fun newToken(tokens: MutableSet<String>): String =
        UUID.randomUUID().toString().also { tokens.add(it) }

    private fun withTestDirectory(block: (File, MutableSet<String>) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "share-import-checkpoint-test-${UUID.randomUUID()}")
        assertTrue(directory.mkdir())
        val generatedTokens = mutableSetOf<String>()
        try {
            block(directory, generatedTokens)
        } finally {
            generatedTokens.forEach { token ->
                val base = File(directory, "$token.pending")
                listOf(base, File(directory, "$token.pending.bak"), File(directory, "$token.pending.new"))
                    .filter { it.isFile }
                    .forEach { assertTrue("test checkpoint should be removed", it.delete()) }
            }
            assertTrue("test directory should contain no unrelated files", directory.listFiles()?.isEmpty() == true)
            assertTrue(directory.delete())
        }
    }

    private companion object {
        const val TASK_TOKEN_KEY = "shareTaskToken"
    }
}
