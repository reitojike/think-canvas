package com.thinkcanvas.image

import android.graphics.Bitmap
import android.util.LruCache
import com.thinkcanvas.data.CanvasStore
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
    private val cache = object : LruCache<ImageResourceKey, Bitmap>(ImageDecodePolicy.CACHE_BYTES) {
        override fun sizeOf(key: ImageResourceKey, value: Bitmap): Int = value.byteCount
    }
    private val failed = linkedSetOf<ImageResourceKey>()
    private val pending = mutableSetOf<ImageResourceKey>()
    private val changes = MutableStateFlow(0L)
    val revision: StateFlow<Long> = changes
    val byteCount: Long get() = synchronized(lock) { cache.size().toLong() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val decoding = Mutex()

    fun bitmap(key: ImageResourceKey): Bitmap? = synchronized(lock) { cache[key] }
    fun failed(key: ImageResourceKey): Boolean = synchronized(lock) { key in failed }
    internal fun loading(key: ImageResourceKey): Boolean = synchronized(lock) { key in pending }

    fun ensure(key: ImageResourceKey) {
        require(key.side == ImageDecodePolicy.bucket(key.side))
        synchronized(lock) {
            // Checking demand must not promote an entry like bitmap() does.
            if (cache.snapshot().containsKey(key) || key in failed || !pending.add(key)) return
        }
        scope.launch {
            try {
                decoding.withLock {
                    val bitmap = store.withImageAssets(setOf(key.assetId)) { it.decode(key.assetId, key.side) }
                    synchronized(lock) {
                        cache.put(key, bitmap)
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
