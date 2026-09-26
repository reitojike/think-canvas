package com.reitojike.thinkcanvas.data

import android.content.Context
import com.reitojike.thinkcanvas.canvas.TextElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    fun save(elements: List<TextElement>) {
        val snapshot = elements.map(TextElementRow::fromModel)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
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
