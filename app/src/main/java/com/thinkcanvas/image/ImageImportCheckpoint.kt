package com.thinkcanvas.image

import android.util.AtomicFile
import com.thinkcanvas.canvas.ImageElement
import com.thinkcanvas.canvas.WorldPoint
import com.thinkcanvas.canvas.isCanonicalUuid
import java.io.File
import org.json.JSONObject

enum class ImagePickerSource { PHOTO, FILE }

data class ImageImportRequest(
    val taskToken: String,
    val requestId: String,
    val elementId: String,
    val boardId: Long,
    val center: WorldPoint,
    val viewportWidth: Float,
    val viewportHeight: Float,
    val source: ImagePickerSource,
    val accepted: ImageElement? = null,
) {
    init {
        require(listOf(taskToken, requestId, elementId).all(::isCanonicalUuid))
        require(boardId > 0 && center.x.isFinite() && center.y.isFinite())
        require(viewportWidth.isFinite() && viewportWidth > 0f &&
            viewportHeight.isFinite() && viewportHeight > 0f)
        require(accepted == null || accepted.id == elementId && accepted.altText.isEmpty())
    }

    fun accepting(asset: ImageAsset) = copy(accepted = ImageElement.placed(asset.assetId,
        asset.intrinsicWidth, asset.intrinsicHeight, center, viewportWidth, viewportHeight, elementId))
}

/** No source URI or filename enters this record. CanvasStore owns all file IO. */
class ImageImportCheckpoint internal constructor(private val directory: File) {
    private fun file(token: String): AtomicFile {
        require(isCanonicalUuid(token))
        return AtomicFile(File(directory, "$token.pending"))
    }

    fun read(token: String): ImageImportRequest? = synchronized(ioLock) { readRecord(token) }

    private fun readRecord(token: String): ImageImportRequest? {
        val record = file(token)
        if (!record.baseFile.exists() && !File(directory, "$token.pending.bak").exists()) return null
        val bytes = record.openRead().use { input ->
            val buffer = ByteArray(MAX_BYTES + 1)
            var count = 0
            while (count < buffer.size) {
                val length = input.read(buffer, count, buffer.size - count)
                if (length == -1) break
                count += length
            }
            buffer.copyOf(count)
        }
        check(bytes.size <= MAX_BYTES)
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        check(json.getInt("version") == 1 && json.getString("taskToken") == token)
        val elementId = json.getString("elementId")
        val accepted = json.optJSONObject("accepted")?.let { image ->
            ImageElement(id = elementId, assetId = image.getString("assetId"),
                x = image.getDouble("x").toFloat(), y = image.getDouble("y").toFloat(),
                width = image.getDouble("width").toFloat(), height = image.getDouble("height").toFloat(),
                intrinsicWidth = image.getInt("intrinsicWidth"), intrinsicHeight = image.getInt("intrinsicHeight"))
        }
        return ImageImportRequest(token, json.getString("requestId"), elementId, json.getLong("boardId"),
            WorldPoint(json.getDouble("centerX").toFloat(), json.getDouble("centerY").toFloat()),
            json.getDouble("viewportWidth").toFloat(), json.getDouble("viewportHeight").toFloat(),
            ImagePickerSource.valueOf(json.getString("source")), accepted)
    }

    fun write(token: String, request: ImageImportRequest?) = synchronized(ioLock) {
        require(request == null || request.taskToken == token)
        if (request == null) { file(token).delete(); return@synchronized }
        check(directory.isDirectory || directory.mkdirs())
        val json = JSONObject().put("version", 1).put("taskToken", token)
            .put("requestId", request.requestId).put("elementId", request.elementId)
            .put("boardId", request.boardId).put("centerX", request.center.x)
            .put("centerY", request.center.y).put("viewportWidth", request.viewportWidth)
            .put("viewportHeight", request.viewportHeight).put("source", request.source.name)
        request.accepted?.let { image ->
            json.put("accepted", JSONObject().put("assetId", image.assetId)
                .put("x", image.x).put("y", image.y).put("width", image.width).put("height", image.height)
                .put("intrinsicWidth", image.intrinsicWidth).put("intrinsicHeight", image.intrinsicHeight))
        }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_BYTES)
        val record = file(token)
        val stream = record.startWrite()
        try {
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
            record.finishWrite(stream)
        } catch (error: Throwable) { record.failWrite(stream); throw error }
        check(readRecord(token) == request) { "画像の取り込み状態を確認できません" }
    }

    fun discardOtherTasks(token: String) = synchronized(ioLock) {
        require(isCanonicalUuid(token))
        directory.listFiles()?.filter { it.isFile && !it.name.startsWith("$token.") }
            ?.forEach { file ->
                val owner = file.name.substringBefore('.')
                if (isCanonicalUuid(owner)) file.delete()
            }
    }

    fun retainedAssetIds(): Set<String> = synchronized(ioLock) {
        directory.listFiles().orEmpty().filter { it.isFile &&
            (it.name.endsWith(".pending") || it.name.endsWith(".pending.bak")) }
            .mapNotNull { file ->
                val token = file.name.removeSuffix(".bak").removeSuffix(".pending")
                if (!isCanonicalUuid(token)) null else runCatching { readRecord(token)?.accepted?.assetId }.getOrNull()
            }.toSet()
    }

    companion object {
        private const val MAX_BYTES = 16384
        private val ioLock = Any()
    }
}
