package com.thinkcanvas.data

import android.content.Context
import android.content.SharedPreferences
import com.thinkcanvas.board.duplicated
import com.thinkcanvas.canvas.ArrowEnd
import com.thinkcanvas.canvas.BoardSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

data class StoredBoard(val details: BoardRow, val snapshot: BoardSnapshot)

class CanvasStore private constructor(context: Context) {
    private val database = CanvasDatabase.open(context)
    private val dao = database.canvasDao()
    private val preferences: SharedPreferences = context.getSharedPreferences("thinkcanvas.settings",
        Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operations = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init { scope.launch { for (operation in operations) operation() } }

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
    ))

    suspend fun restore(): StoredBoard? = submit {
        val initial = dao.boards()
        if (!preferences.getBoolean("initialized", false)) {
            if (initial.isEmpty()) dao.putBoard(BoardRow())
            preferences.edit().putBoolean("initialized", true).commit()
        }
        val boards = dao.boards()
        val wantedId = preferences.getLong("lastOpenedBoardId", -1)
        val selected = boards.firstOrNull { it.id == wantedId }
            ?: boards.firstOrNull { it.id == 1L } ?: boards.firstOrNull()
        selected?.let { read(it) }
    }.await()

    suspend fun boards(): List<BoardRow> = submit { dao.boards() }.await()

    suspend fun boardsWithContent(): List<StoredBoard> = submit {
        dao.boards().map { read(it) }
    }.await()

    suspend fun open(boardId: Long): StoredBoard? = submit {
        dao.board(boardId)?.let { board ->
            preferences.edit().putLong("lastOpenedBoardId", boardId).commit()
            read(board)
        }
    }.await()

    suspend fun create(name: String = "無題のボード"): StoredBoard = submit {
        val board = dao.createBoard(name)
        preferences.edit().putLong("lastOpenedBoardId", board.id).commit()
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
        if (removed && preferences.getLong("lastOpenedBoardId", -1) == boardId)
            preferences.edit().remove("lastOpenedBoardId").commit()
        removed
    }.await()

    fun save(boardId: Long, snapshot: BoardSnapshot): Deferred<Unit> {
        val targets = (snapshot.texts.map { it.id } + snapshot.shapes.map { it.id }).toSet()
        require(snapshot.arrows.all { arrow ->
            listOf(arrow.from, arrow.to).all { it !is ArrowEnd.Attached || it.targetId in targets }
        }) { "矢印の接続先が見つかりません" }
        return submit {
            dao.replaceAll(boardId,
                snapshot.texts.map { TextElementRow.fromModel(boardId, it) },
                snapshot.shapes.map { SpatialElementRow.fromModel(boardId, it) },
                snapshot.arrows.map { ArrowElementRow.fromModel(boardId, it) },
                snapshot.ink.flatMap { InkStrokeRow.fromModel(boardId, it) })
        }
    }

    // 既存の単一ボード画面を段階的に移行する間も、同じ保存経路を使う。
    suspend fun load(): BoardSnapshot = restore()?.snapshot ?: BoardSnapshot()
    fun save(snapshot: BoardSnapshot): Deferred<Unit> = save(1L, snapshot)

    fun guideDismissed(): Boolean = preferences.getBoolean("guideDismissed", false)
    fun dismissGuide() { preferences.edit().putBoolean("guideDismissed", true).apply() }

    companion object {
        @Volatile private var instance: CanvasStore? = null

        fun get(context: Context): CanvasStore = instance ?: synchronized(this) {
            instance ?: CanvasStore(context.applicationContext).also { instance = it }
        }
    }
}
