package com.reitojike.thinkcanvas.data

import android.content.Context
import com.reitojike.thinkcanvas.canvas.TextElement
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

    suspend fun load(): List<TextElement> = mutex.withLock {
        if (dao.firstBoard() == null) dao.putBoard(BoardRow())
        dao.elements().map { it.toModel() }
    }

    fun save(elements: List<TextElement>): Deferred<Unit> {
        val snapshot = elements.map(TextElementRow::fromModel)
        return scope.async {
            mutex.withLock { dao.replaceAll(snapshot) }
        }
    }

    companion object {
        @Volatile private var instance: CanvasStore? = null

        fun get(context: Context): CanvasStore = instance ?: synchronized(this) {
            instance ?: CanvasStore(context.applicationContext).also { instance = it }
        }
    }
}
