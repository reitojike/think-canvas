package com.thinkcanvas.canvas

import android.view.MotionEvent
import android.view.Window

/**
 * #165 の queryAndResultChangesReplaceTargetAndCloseRemovesHit() にある nextPanAt 1 回専用の失敗時診断。
 * 注入と Window.Callback への配送を記録するだけで、入力、待機、判定は変えない。通過時は何も出力しない。
 */
internal class NextPanTrace(
    private val emit: (String) -> Unit,
    private val sampleCapacity: Int = 24,
    private val historyCapacity: Int = 8,
) {
    private data class Point(val eventTime: Long, val x: Float, val y: Float)
    private data class Sample(val sequence: Int, val kind: String, val action: Int, val downTime: Long,
                              val eventTime: Long, val x: Float, val y: Float, val rawX: Float, val rawY: Float,
                              val originX: Int, val originY: Int, val historySize: Int, val history: List<Point>)
    private class Frozen(val samples: List<Sample>, val seen: Int, val filtered: Int, val notes: List<String>,
                         val notesSeen: Int, val failures: List<Throwable>, val failureCount: Int)

    private val lock = Any()
    private val samples = ArrayList<Sample>()
    private val notes = ArrayList<String>()
    private val failures = ArrayList<Throwable>()
    private var seen = 0
    private var filtered = 0
    private var notesSeen = 0
    private var failureCount = 0
    private var frozen = false
    @Volatile private var downTime: Long? = null
    private var original: Window.Callback? = null
    private var wrapper: Window.Callback? = null

    /** 記録側の失敗は保持するだけで、呼び出し元の注入や配送へ伝えない。 */
    fun recording(action: () -> Unit) {
        try { action() } catch (failure: Throwable) {
            synchronized(lock) { failureCount++; if (failures.size < 4) failures += failure }
        }
    }

    fun note(text: () -> String) = recording {
        val value = text()
        synchronized(lock) { if (!frozen) { notesSeen++; if (notes.size < 8) notes += value } }
    }

    fun injected(event: MotionEvent, origin: IntArray) = recording {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) downTime = event.downTime
        record("inject-attempt", event, origin[0], origin[1])
    }

    fun wrap(target: Window.Callback, origin: () -> IntArray): Window.Callback {
        val observer = object : Window.Callback by target {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                try {
                    recording {
                        if (event.downTime == downTime && event.pointerCount == 1) {
                            val location = origin()
                            record("delivered", event, location[0], location[1])
                        } else synchronized(lock) { if (!frozen) filtered++ }
                    }
                } catch (ignored: Throwable) {
                    // 失敗の記録自体が失敗しても、元の callback への配送は 1 回だけ必ず行う。
                }
                return target.dispatchTouchEvent(event)
            }
        }
        synchronized(lock) { original = target; wrapper = observer }
        return observer
    }

    /** 現在の callback が自分の wrapper の場合だけ元へ戻す。別の callback は上書きしない。 */
    fun restore(current: Window.Callback?, set: (Window.Callback) -> Unit): Boolean {
        val (mine, previous) = synchronized(lock) { wrapper to original }
        if (mine == null || current !== mine) return false
        set(checkNotNull(previous))
        return true
    }

    private fun record(kind: String, event: MotionEvent, originX: Int, originY: Int) {
        val size = event.historySize
        val history = (maxOf(0, size - historyCapacity) until size).map {
            Point(event.getHistoricalEventTime(it), event.getHistoricalX(0, it), event.getHistoricalY(0, it))
        }
        val sample = Sample(0, kind, event.actionMasked, event.downTime, event.eventTime, event.x, event.y,
            event.rawX, event.rawY, originX, originY, size, history)
        synchronized(lock) {
            if (frozen) return
            seen++
            if (samples.size < sampleCapacity) samples += sample.copy(sequence = seen)
        }
    }

    private fun freeze(): Frozen = synchronized(lock) {
        frozen = true
        Frozen(samples.toList(), seen, filtered, notes.toList(), notesSeen, failures.toList(), failureCount)
    }

    private fun format(trace: Frozen, original: Throwable) = buildList {
        add("original failure=${original.javaClass.name}")
        trace.notes.forEach { add("note $it") }
        trace.samples.forEach { sample ->
            add("sample ${sample.sequence} ${sample.kind} ${MotionEvent.actionToString(sample.action)} " +
                "downTime=${sample.downTime} eventTime=${sample.eventTime} xy=${sample.x},${sample.y} " +
                "raw=${sample.rawX},${sample.rawY} decorOrigin=${sample.originX},${sample.originY} " +
                "history=${sample.historySize} kept=${sample.history.size} " +
                sample.history.joinToString(" ", "[", "]") { "${it.eventTime}:${it.x},${it.y}" })
        }
        add("end samples=${trace.seen} kept=${trace.samples.size} dropped=${trace.seen - trace.samples.size} " +
            "filtered=${trace.filtered} notes=${trace.notesSeen} notesDropped=${trace.notesSeen - trace.notes.size} " +
            "recorderFailures=${trace.failureCount} attached=${trace.failures.size}")
    }

    /**
     * [block] の元の Throwable をそのまま投げ、診断、後段観測、cleanup の失敗は suppressed として付ける。
     * [later] の値は失敗後に取得したもので、判定時点の状態ではない。
     */
    fun run(block: () -> Unit, later: ((String) -> Unit) -> Unit, cleanup: () -> Unit) {
        var original: Throwable? = null
        try {
            block()
        } catch (failure: Throwable) {
            original = failure
            throw failure
        } finally {
            val failure = original
            var secondary: Throwable? = null
            fun attempt(action: () -> Unit) {
                try { action() } catch (next: Throwable) {
                    val owner = failure ?: secondary
                    if (owner == null) secondary = next else if (owner !== next) owner.addSuppressed(next)
                }
            }
            var trace: Frozen? = null
            if (failure != null) attempt { trace = freeze() }
            attempt(cleanup)
            if (failure != null) {
                trace?.let { frozen -> attempt { format(frozen, failure).forEach(emit) } }
                attempt { later { emit("later(after failure, not state at assertion) $it") } }
                trace?.failures?.forEach { recorded -> attempt { throw recorded } }
            }
            if (failure == null) secondary?.let { throw it }
        }
    }
}
