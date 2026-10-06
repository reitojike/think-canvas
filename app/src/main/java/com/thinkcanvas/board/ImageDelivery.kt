package com.thinkcanvas.board

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

object ImageDelivery {
    suspend fun png(bitmap: Bitmap): ByteArray = withContext(Dispatchers.Default) {
        ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "画像を作成できません"
            }
            output.toByteArray()
        }
    }

    suspend fun saveToPhotos(context: Context, bytes: ByteArray): Uri =
        withContext(Dispatchers.IO) {
            require(Build.VERSION.SDK_INT >= 29)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName())
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/ThinkCanvas")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = requireNotNull(resolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)) {
                "画像の保存先を作成できません"
            }
            try {
                resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                    ?: error("画像の保存先を開けません")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                check(resolver.update(uri, values, null, null) == 1) {
                    "画像の保存を完了できません"
                }
                uri
            } catch (error: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
        }

    suspend fun writeDocument(context: Context, uri: Uri, bytes: ByteArray) =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                    ?: error("選んだ保存先を開けません")
            } catch (error: Throwable) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                throw error
            }
        }

    suspend fun cacheUri(context: Context, bytes: ByteArray): Uri =
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "board-images")
            check(directory.isDirectory || directory.mkdirs()) { "共有画像を準備できません" }
            val staleBefore = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            directory.listFiles()?.filter { it.isFile && it.lastModified() < staleBefore }
                ?.forEach(File::delete)
            val file = File(directory, UUID.randomUUID().toString() + ".png")
            try {
                file.writeBytes(bytes)
                FileProvider.getUriForFile(context, "${context.packageName}.share", file)
            } catch (error: Throwable) {
                file.delete()
                throw error
            }
        }

    fun copy(context: Context, uri: Uri) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newUri(context.contentResolver,
            "ThinkCanvas 画像", uri))
    }

    internal fun shareIntent(context: Context, uri: Uri): Intent {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "ThinkCanvas 画像", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(intent, "画像を共有")
    }

    private fun fileName(): String = "think-canvas-${System.currentTimeMillis()}.png"
}
