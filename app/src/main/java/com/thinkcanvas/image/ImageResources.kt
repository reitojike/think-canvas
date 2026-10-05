package com.thinkcanvas.image

import android.graphics.Bitmap
import com.thinkcanvas.data.CanvasStore
import java.util.LinkedHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ImageResourceKey(val assetId: String, val side: Int)

/** Nodes retain keys only. All decoded display bitmaps belong to this measured LRU. */
class ImageResources private constructor(private val store: CanvasStore) {
    private val lock = Any()
    private val cache = LinkedHashMap<ImageResourceKey, Bitmap>(16, .75f, true)
    private val failed = linkedSetOf<ImageResourceKey>()
    private val pending = mutableSetOf<ImageResourceKey>()
    private var bytes = 0L
    private val changes = MutableStateFlow(0L)
    val revision: StateFlow<Long> = changes
    val byteCount: Long get() = synchronized(lock) { bytes }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val decoding = Mutex()

    fun bitmap(key: ImageResourceKey): Bitmap? = synchronized(lock) { cache[key] }
    fun failed(key: ImageResourceKey): Boolean = synchronized(lock) { key in failed }
    internal fun loading(key: ImageResourceKey): Boolean = synchronized(lock) { key in pending }

    fun ensure(key: ImageResourceKey) {
        require(key.side == ImageDecodePolicy.bucket(key.side))
        synchronized(lock) {
            if (key in cache || key in failed || !pending.add(key)) return
        }
        scope.launch {
            try {
                decoding.withLock {
                    val bitmap = store.withImageAssets(setOf(key.assetId)) { it.decode(key.assetId, key.side) }
                    synchronized(lock) {
                        cache.put(key, bitmap)?.let { bytes -= it.byteCount }
                        bytes += bitmap.byteCount
                        while (bytes > ImageDecodePolicy.CACHE_BYTES && cache.isNotEmpty()) {
                            val oldest = cache.entries.iterator()
                            bytes -= oldest.next().value.byteCount
                            oldest.remove()
                        }
                    }
                }
            } catch (_: Exception) {
                synchronized(lock) {
                    failed += key
                    while (failed.size > 128) failed.remove(failed.first())
                }
            } finally {
                synchronized(lock) { pending.remove(key); changes.value++ }
            }
        }
    }

    companion object {
        @Volatile private var instance: ImageResources? = null
        fun get(store: CanvasStore): ImageResources = instance ?: synchronized(this) {
            instance ?: ImageResources(store).also { instance = it }
        }
    }
}
