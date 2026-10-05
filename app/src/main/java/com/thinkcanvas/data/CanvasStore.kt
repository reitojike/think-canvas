package com.thinkcanvas.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import com.thinkcanvas.board.duplicated
import com.thinkcanvas.canvas.ArrowEnd
import com.thinkcanvas.canvas.BoardSnapshot
import com.thinkcanvas.share.executeShareOperation
import com.thinkcanvas.share.ShareImportCheckpoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import com.thinkcanvas.image.ImageAssetStore
import com.thinkcanvas.image.ImageImportCheckpoint
import com.thinkcanvas.image.ImageImportRequest
import com.thinkcanvas.image.ImportedImageAsset

class ImageAssetReader internal constructor(private val decodeOperation: (String, Int) -> Bitmap) {
    fun decode(assetId: String, requestedSide: Int): Bitmap = decodeOperation(assetId, requestedSide)
}

data class StoredBoard(val details: BoardRow, val snapshot: BoardSnapshot)

class CanvasStore private constructor(context: Context) {
    private val resolver = context.contentResolver
    private val imageDecodePermit = Semaphore(1)
    private val imageAssets = ImageAssetStore(File(context.filesDir, "image-assets"), imageDecodePermit)
    private val imageCheckpoint = ImageImportCheckpoint(File(context.filesDir, "image-import"))
    private val database = CanvasDatabase.open(context)
    private val dao = database.canvasDao()
    private val preferences: SharedPreferences = context.getSharedPreferences("thinkcanvas.settings",
        Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operations = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init { scope.launch {
        runCatching { imageAssets.sweepStaging(); collectImages() }
        for (operation in operations) operation()
    } }

    /** Publication is immediate; only the following collector touches disk. */
    fun setSessionImageRoots(owner: String, assets: Set<String>) {
        imageAssets.setOwnerRoots(owner, assets)
        submit { collectImages() }
    }

    private suspend fun collectImages() {
        // Cleanup cannot turn an otherwise durable board save into Failed.
        runCatching { imageAssets.collect(dao.allImageAssetIds().toSet(), imageCheckpoint.retainedAssetIds()) }
    }

    private fun pinImages(assets: Set<String>) = imageAssets.pin(assets) { submit { collectImages() } }

    fun importImage(uri: Uri): Deferred<ImportedImageAsset> {
        val result = CompletableDeferred<ImportedImageAsset>()
        val id = UUID.randomUUID().toString()
        val pin = pinImages(setOf(id))
        val cancellation = CancellationSignal()
        val stream = AtomicReference<InputStream?>()
        result.invokeOnCompletion { error ->
            if (error != null) {
                cancellation.cancel()
                runCatching { stream.getAndSet(null)?.close() }
                pin.close()
            }
        }
        check(operations.trySend {
            try {
                if (result.isCancelled) throw kotlinx.coroutines.CancellationException()
                val descriptor = resolver.openFileDescriptor(uri, "r", cancellation)
                    ?: error("画像を開けません")
                val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                stream.set(input)
                if (result.isCancelled) input.close()
                val asset = input.use { imageAssets.import(it, id) { result.isCancelled } }
                stream.compareAndSet(input, null)
                if (!result.complete(ImportedImageAsset(asset, pin))) pin.close()
            } catch (error: Throwable) { pin.close(); result.completeExceptionally(error) }
            finally { runCatching { stream.getAndSet(null)?.close() }; collectImages() }
        }.isSuccess)
        return result
    }

    suspend fun prepareImageTask(token: String): ImageImportRequest? = submit {
        imageCheckpoint.discardOtherTasks(token)
        imageCheckpoint.read(token).also { collectImages() }
    }.await()

    suspend fun writeImageCheckpoint(token: String, request: ImageImportRequest?) = submit {
        request?.accepted?.let { image ->
            val actual = imageAssets.describe(image.assetId)
            check(actual.intrinsicWidth == image.intrinsicWidth && actual.intrinsicHeight == image.intrinsicHeight)
        }
        imageCheckpoint.write(token, request)
        collectImages()
    }.await()

    fun clearImageCheckpoint(token: String): Deferred<Unit> = submit {
        imageCheckpoint.write(token, null)
        collectImages()
    }

    suspend fun <T> withImageAssets(assets: Set<String>, block: (ImageAssetReader) -> T): T {
        val pin = pinImages(assets)
        val active = AtomicBoolean(true)
        try {
            submit { assets.forEach { imageAssets.describe(it) } }.await()
            val reader = ImageAssetReader { id, side ->
                check(active.get() && id in assets)
                imageAssets.decode(id, side)
            }
            return withContext(Dispatchers.IO) { block(reader) }
        } finally { active.set(false); pin.close() }
    }

    private fun <T> submit(block: suspend () -> T): Deferred<T> {
        val result = CompletableDeferred<T>()
        check(operations.trySend {
            try { result.complete(block()) }
            catch (error: Throwable) { result.completeExceptionally(error) }
        }.isSuccess)
        return result
    }

    private suspend fun read(board: BoardRow): StoredBoard = StoredBoard(board, BoardSnapshot(
        texts = dao.elements(board.id).map { it.toModel() },
        shapes = dao.spatialElements(board.id).map { it.toModel() },
        arrows = dao.arrows(board.id).map { it.toModel() },
        ink = InkStrokeRow.toElements(dao.inkStrokes(board.id)),
        images = dao.images(board.id).map { it.toModel() },
    ))

    suspend fun restore(): StoredBoard? = submit {
        val initial = dao.boards()
        if (!preferences.getBoolean("initialized", false)) {
            val first = if (initial.isEmpty()) {
                BoardRow().also { dao.putBoard(it) }
            } else initial.firstOrNull { it.id == 1L } ?: initial.first()
            val settings = preferences.edit().putBoolean("initialized", true)
                .putLong("lastOpenedBoardId", first.id)
            if (initial.isEmpty()) settings.putLong("guideEligibleBoardId", first.id)
            check(settings.commit()) { "利用状態を保存できません" }
            collectImages()
        }
        val wantedId = preferences.getLong("lastOpenedBoardId", -1)
        val selected = dao.board(wantedId)
        if (selected == null && wantedId != -1L)
            preferences.edit().remove("lastOpenedBoardId").commit()
        selected?.let { read(it) }
    }.await()

    suspend fun boards(): List<BoardRow> = submit { dao.boards() }.await()

    /** Sharing must not trigger the normal launcher's first-board creation. */
    suspend fun lastOpenedBoard(): StoredBoard? = submit {
        dao.board(preferences.getLong("lastOpenedBoardId", -1))?.let { read(it) }
    }.await()

    suspend fun shareReceipt(requestId: String): ShareImportReceiptRow? =
        submit { dao.shareReceipt(requestId) }.await()

    /** Auxiliary task cleanup uses the existing IO actor and never alters board content. */
    fun clearShareCheckpoint(record: ShareImportCheckpoint): Deferred<Unit> = submit { record.write(null) }

    suspend fun boardsWithContent(): List<StoredBoard> = submit {
        dao.boards().map { read(it) }
    }.await()

    suspend fun savedBoard(boardId: Long): StoredBoard? = submit {
        dao.board(boardId)?.let { read(it) }
    }.await()

    suspend fun open(boardId: Long): StoredBoard? = submit {
        dao.board(boardId)?.let { board ->
            check(preferences.edit().putLong("lastOpenedBoardId", boardId).commit()) {
                "利用状態を保存できません"
            }
            read(board)
        }
    }.await()

    suspend fun create(name: String = "無題のボード"): StoredBoard = submit {
        val board = dao.createBoard(name)
        check(preferences.edit().putLong("lastOpenedBoardId", board.id).commit()) {
            "利用状態を保存できません"
        }
        read(board)
    }.await()

    suspend fun rename(boardId: Long, name: String): Boolean = submit {
        dao.renameBoard(boardId, name, System.currentTimeMillis()) == 1
    }.await()

    suspend fun duplicate(boardId: Long): StoredBoard? = submit {
        dao.board(boardId)?.let { source ->
            val snapshot = read(source).snapshot.duplicated()
            val name = (source.name.ifBlank { "無題のボード" }) + " のコピー"
            val result = dao.createBoardWithSnapshot(name, snapshot)
            read(result)
        }
    }.await()

    suspend fun delete(boardId: Long): Boolean = submit {
        val removed = dao.deleteBoard(boardId)
        if (removed) {
            val settings = preferences.edit()
            if (preferences.getLong("lastOpenedBoardId", -1) == boardId)
                settings.remove("lastOpenedBoardId")
            if (preferences.getLong("guideEligibleBoardId", -1) == boardId)
                settings.remove("guideEligibleBoardId")
            check(settings.commit()) { "利用状態を保存できません" }
            collectImages()
        }
        removed
    }.await()

    fun save(boardId: Long, snapshot: BoardSnapshot): Deferred<Unit> {
        validateSnapshot(snapshot)
        val pin = pinImages(snapshot.images.map { it.assetId }.toSet())
        return submit {
            try { dao.replaceAll(boardId,
                snapshot.texts.map { TextElementRow.fromModel(boardId, it) },
                snapshot.shapes.map { SpatialElementRow.fromModel(boardId, it) },
                snapshot.arrows.map { ArrowElementRow.fromModel(boardId, it) },
                snapshot.ink.flatMap { InkStrokeRow.fromModel(boardId, it) },
                snapshot.images.map { ImageElementRow.fromModel(boardId, it) })
            } finally { pin.close(); collectImages() }
        }
    }

    fun saveShare(boardId: Long, snapshot: BoardSnapshot, receipt: ShareImportReceiptRow): Deferred<Unit> {
        require(receipt.boardId == boardId)
        validateSnapshot(snapshot)
        val result = CompletableDeferred<Unit>()
        val pin = pinImages(snapshot.images.map { it.assetId }.toSet())
        check(operations.trySend {
            try { executeShareOperation(result) {
                dao.replaceAllForShare(receipt,
                    snapshot.texts.map { TextElementRow.fromModel(boardId, it) },
                    snapshot.shapes.map { SpatialElementRow.fromModel(boardId, it) },
                    snapshot.arrows.map { ArrowElementRow.fromModel(boardId, it) },
                    snapshot.ink.flatMap { InkStrokeRow.fromModel(boardId, it) },
                    snapshot.images.map { ImageElementRow.fromModel(boardId, it) })
            } } finally { pin.close(); collectImages() }
        }.isSuccess)
        return result
    }

    private fun validateSnapshot(snapshot: BoardSnapshot) {
        val targets = (snapshot.texts.map { it.id } + snapshot.shapes.map { it.id } + snapshot.images.map { it.id }).toSet()
        require(snapshot.arrows.all { arrow ->
            listOf(arrow.from, arrow.to).all { it !is ArrowEnd.Attached || it.targetId in targets }
        }) { "矢印の接続先が見つかりません" }
    }

    // 既存の単一ボード画面は読み書きとも ID 1 に固定する。複数ボードは ID 指定 API を使う。
    suspend fun load(): BoardSnapshot {
        val restored = restore()
        return if (restored?.details?.id == 1L) restored.snapshot
        else savedBoard(1L)?.snapshot ?: BoardSnapshot()
    }
    fun save(snapshot: BoardSnapshot): Deferred<Unit> = save(1L, snapshot)

    fun shouldShowGuide(boardId: Long, snapshot: BoardSnapshot): Boolean =
        !preferences.getBoolean("guideDismissed", false) &&
            preferences.getLong("guideEligibleBoardId", -1) == boardId &&
            snapshot.texts.isEmpty() && snapshot.shapes.isEmpty() &&
            snapshot.arrows.isEmpty() && snapshot.ink.isEmpty() && snapshot.images.isEmpty()

    fun dismissGuide() {
        check(preferences.edit().putBoolean("guideDismissed", true)
            .remove("guideEligibleBoardId").commit()) { "利用状態を保存できません" }
    }

    companion object {
        @Volatile private var instance: CanvasStore? = null

        fun get(context: Context): CanvasStore = instance ?: synchronized(this) {
            instance ?: CanvasStore(context.applicationContext).also { instance = it }
        }
    }
}
