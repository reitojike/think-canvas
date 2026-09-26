package com.thinkcanvas.data

import android.content.Context
import com.thinkcanvas.canvas.BoardSnapshot
import com.thinkcanvas.canvas.ArrowEnd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CanvasStore private constructor(context: Context) {
    private val database = CanvasDatabase.open(context)
    private val dao = database.canvasDao()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun load(): BoardSnapshot = mutex.withLock {
        if (dao.firstBoard() == null) dao.putBoard(BoardRow())
        BoardSnapshot(
            texts = dao.elements().map { it.toModel() },
            shapes = dao.spatialElements().map { it.toModel() },
            arrows = dao.arrows().map { it.toModel() },
            ink = InkStrokeRow.toElements(dao.inkStrokes()),
        )
    }

    fun save(snapshot: BoardSnapshot): Deferred<Unit> {
        val targets = (snapshot.texts.map { it.id } + snapshot.shapes.map { it.id }).toSet()
        require(snapshot.arrows.all { arrow ->
            listOf(arrow.from, arrow.to).all { it !is ArrowEnd.Attached || it.targetId in targets }
        }) { "矢印の接続先が見つかりません" }
        return scope.async {
            val texts = snapshot.texts.map(TextElementRow::fromModel)
            val shapes = snapshot.shapes.map(SpatialElementRow::fromModel)
            val arrows = snapshot.arrows.map(ArrowElementRow::fromModel)
            val ink = snapshot.ink.flatMap(InkStrokeRow::fromModel)
            mutex.withLock { dao.replaceAll(texts, shapes, arrows, ink) }
        }
    }

    companion object {
        @Volatile private var instance: CanvasStore? = null

        fun get(context: Context): CanvasStore = instance ?: synchronized(this) {
            instance ?: CanvasStore(context.applicationContext).also { instance = it }
        }
    }
}
