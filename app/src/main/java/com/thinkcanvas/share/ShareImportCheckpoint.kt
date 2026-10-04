package com.thinkcanvas.share

import android.util.AtomicFile
import com.thinkcanvas.canvas.WorldPoint
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

/** A task reference, never a payload inbox. All methods run outside the UI thread. */
class ShareImportCheckpoint internal constructor(
    private val directory: File, val token: String, private val file: AtomicFile,
) {
    constructor(directory: File, token: String) : this(directory, token, AtomicFile(File(directory, "$token.pending")))
    init { UUID.fromString(token) }

    fun read(): ShareImportRequest? = synchronized(ioLock) { readRecord() }

    private fun readRecord(): ShareImportRequest? = DataInputStream(file.openRead()).use { input ->
        check(input.readInt() == 1)
        if (!input.readBoolean()) return@use null
        val requestId = input.readUTF()
        val elementId = input.readUTF()
        val length = input.readInt()
        // A corrupt private record must not allocate an arbitrary attacker-sized buffer.
        check(length >= 0 && length.toLong() <= file.baseFile.length())
        val text = ByteArray(length).also { input.readFully(it) }.toString(Charsets.UTF_8)
        val destination = if (input.readBoolean()) input.readLong() else null
        val position = if (input.readBoolean()) WorldPoint(input.readFloat(), input.readFloat()) else null
        ShareImportRequest(requestId, elementId, text, destination, position)
    }

    fun write(request: ShareImportRequest?): Unit = synchronized(ioLock) {
        val textBytes = request?.text?.toByteArray(Charsets.UTF_8)
        if (request != null && textBytes?.toString(Charsets.UTF_8) != request.text)
            throw IOException("共有本文をUTF-8で保持できません")
        check(directory.isDirectory || directory.mkdirs())
        val stream = file.startWrite()
        try {
            val output = DataOutputStream(stream)
            output.writeInt(1)
            output.writeBoolean(request != null)
            if (request != null) {
                output.writeUTF(request.requestId)
                output.writeUTF(request.elementId)
                val bytes = checkNotNull(textBytes)
                output.writeInt(bytes.size)
                output.write(bytes)
                output.writeBoolean(request.destinationId != null)
                request.destinationId?.let { output.writeLong(it) }
                output.writeBoolean(request.position != null)
                request.position?.let { output.writeFloat(it.x); output.writeFloat(it.y) }
            }
            output.flush()
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
        if (readRecord() != request)
            throw IOException("共有状態を保存内容へ反映できません")
    }

    companion object {
        // AtomicFile has no locking; all owners and task cleanup share this IO boundary.
        private val ioLock = Any()

        fun validToken(value: String?): String? = value?.takeIf {
            runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false)
        }

        fun discardOtherTasks(directory: File, currentToken: String): Unit = synchronized(ioLock) {
            // Only app-owned records in this fixed private directory, never board assets.
            directory.listFiles()?.filter { it.isFile && !it.name.startsWith("$currentToken.") }
                ?.forEach { it.delete() }
        }
    }
}
