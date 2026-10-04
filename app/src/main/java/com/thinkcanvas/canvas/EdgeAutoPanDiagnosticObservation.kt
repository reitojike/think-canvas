package com.thinkcanvas.canvas

import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.json.JSONObject

/** DIAGNOSTIC_ONLY / MUST_NOT_MERGE。保存・Compose stateへ接続しない有限memory recorder。 */
object EdgeAutoPanDiagnosticObservation {
    private const val LIMIT = 2048
    private val lock = Any()
    @Volatile var enabled = false
        private set
    @Volatile var clockMillis: (() -> Long)? = null
    @Volatile var autoAdvance: (() -> Boolean)? = null
    @Volatile var saveCount: (() -> Int)? = null
    @Volatile var semanticsPhase = ""
    @Volatile var pointerId: Long? = null
    private val events = ArrayList<Map<String, Any?>>(LIMIT)
    private var sequence = 0L
    private var dropped = 0
    private var frameSequence = 0L
    private var frameNanos: Long? = null
    private var compositionEpoch = 0L
    private var layoutEpoch = 0L
    private var layoutCompositionEpoch = 0L
    private var latestState: Map<String, Any?> = emptyMap()

    fun enable(clock: () -> Long, automatic: () -> Boolean) = synchronized(lock) {
        clearLocked()
        clockMillis = clock
        autoAdvance = automatic
        enabled = true
    }

    private fun clearLocked() {
        events.clear()
        sequence = 0
        dropped = 0
        frameSequence = 0
        frameNanos = null
        compositionEpoch = 0
        layoutEpoch = 0
        layoutCompositionEpoch = 0
        latestState = emptyMap()
        semanticsPhase = ""
        pointerId = null
        clockMillis = null
        autoAdvance = null
        saveCount = null
    }

    fun clear() = synchronized(lock) {
        enabled = false
        clearLocked()
    }

    fun beginFrame(nanos: Long): Long = synchronized(lock) {
        if (!enabled) return@synchronized 0L
        frameNanos = nanos
        ++frameSequence
    }

    fun beginComposition(): Long = synchronized(lock) {
        if (!enabled) 0L else ++compositionEpoch
    }

    fun state(event: String, fields: Map<String, Any?>) = synchronized(lock) {
        if (!enabled) return@synchronized
        latestState = fields.toMap()
        recordLocked(event, fields)
    }

    fun record(event: String, fields: Map<String, Any?> = emptyMap()) = synchronized(lock) {
        if (enabled) recordLocked(event, latestState + fields)
    }

    private fun recordLocked(event: String, fields: Map<String, Any?>) {
        if (events.size >= LIMIT) { dropped++; return }
        events.add(fields + linkedMapOf(
            "event" to event,
            "sequence" to ++sequence,
            "elapsedRealtimeNanos" to SystemClock.elapsedRealtimeNanos(),
            "thread" to Thread.currentThread().name,
            "manualClockMillis" to clockMillis?.invoke(),
            "autoAdvance" to autoAdvance?.invoke(),
            "frameSequence" to frameSequence,
            "frameNanos" to frameNanos,
            "compositionEpoch" to compositionEpoch,
            "layoutEpoch" to layoutEpoch,
            "layoutCompositionEpoch" to layoutCompositionEpoch,
            "saveCount" to saveCount?.invoke(),
        ))
    }

    fun layout(epoch: Long, display: Map<String, Any?>, position: Offset, bounds: Rect) =
        synchronized(lock) {
            if (!enabled) return@synchronized
            layoutEpoch++
            layoutCompositionEpoch = epoch
            recordLocked("TARGET_LAYOUT_PUBLISHED", latestState + mapOf(
                "display" to display,
                "actualPositionInWindow" to point(position),
                "actualBoundsInWindow" to rect(bounds),
                "publishedCompositionEpoch" to epoch,
            ))
        }

    /** JSON生成もhot pathでは行わず、test finallyで一括処理する。 */
    fun finishJsonLines(): String = synchronized(lock) {
        recordLocked("RECORDER_SUMMARY", mapOf("capacity" to LIMIT, "droppedEvents" to dropped))
        enabled = false
        events.joinToString("\n", postfix = "\n") { JSONObject(it).toString() }
    }

    fun point(value: Offset): Map<String, Any?> = mapOf("x" to value.x, "y" to value.y)
    fun point(value: WorldPoint): Map<String, Any?> = mapOf("x" to value.x, "y" to value.y)
    fun viewport(value: Viewport): Map<String, Any?> =
        mapOf("scale" to value.scale, "panX" to value.panX, "panY" to value.panY)
    fun rect(value: Rect): Map<String, Any?> = mapOf(
        "left" to value.left, "top" to value.top, "right" to value.right, "bottom" to value.bottom)

    fun board(snapshot: BoardSnapshot, canUndo: Boolean, canRedo: Boolean): Map<String, Any?> {
        fun end(value: ArrowEnd): Map<String, Any?> = when (value) {
            is ArrowEnd.Free -> mapOf("kind" to "Free", "x" to value.x, "y" to value.y)
            else -> mapOf("kind" to "Attached")
        }
        return mapOf(
            "texts" to snapshot.texts.map { mapOf("id" to it.id, "x" to it.x, "y" to it.y) },
            "shapes" to snapshot.shapes.map { mapOf("id" to it.id, "x" to it.x, "y" to it.y) },
            "arrows" to snapshot.arrows.map { mapOf("id" to it.id, "from" to end(it.from), "to" to end(it.to)) },
            "canUndo" to canUndo, "canRedo" to canRedo,
        )
    }
}
