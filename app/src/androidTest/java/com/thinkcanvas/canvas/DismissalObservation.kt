package com.thinkcanvas.canvas

import android.os.SystemClock

/** #141 の対象 test 専用。元の predicate と後段の同期観測を混同しない。 */
internal class DismissalObservation(private val emit: (String) -> Unit) {
    private val events = mutableListOf<String>()
    private var sequence = 0
    private var frozen = false
    private var attempt = 0
    private var predicates = List(4) { "unevaluated" }
    var stage = "notStarted"

    fun event(event: String) = synchronized(events) {
        if (!frozen) {
            sequence++
            if (events.size < 128) events += "$sequence uptime=${SystemClock.uptimeMillis()} $event"
        }
    }

    fun beginPredicate() {
        attempt++
        predicates = List(4) { "unevaluated" }
    }

    fun observe(index: Int, query: () -> Boolean): Boolean {
        predicates = predicates.toMutableList().also { it[index] = "evaluating" }
        return try {
            query().also { value ->
                predicates = predicates.toMutableList().also { it[index] = value.toString() }
            }
        } catch (original: Throwable) {
            predicates = predicates.toMutableList().also { it[index] = "queryThrew:${original.javaClass.simpleName}/noResult" }
            throw original
        }
    }

    private fun emitOriginal(original: Throwable?) {
        val saved = synchronized(events) {
            frozen = true
            events.toList() + listOf(
                "original:lastPredicate attempt=$attempt stage=$stage " +
                    listOf("editable", "done", "cancel", "retry").zip(predicates)
                        .joinToString(" ") { (name, result) -> "$name=$result" },
                "original:end events=$sequence truncated=${sequence > events.size} failure=${original?.javaClass?.simpleName}",
            )
        }
        saved.forEach(emit)
    }

    fun run(block: () -> Unit, postSync: () -> Unit, detach: () -> Unit) {
        var original: Throwable? = null
        try {
            block()
        } catch (failure: Throwable) {
            original = failure
            throw failure
        } finally {
            var diagnosticFailure: Throwable? = original
            fun finish(action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    if (diagnosticFailure == null) diagnosticFailure = failure
                    else if (diagnosticFailure !== failure) diagnosticFailure!!.addSuppressed(failure)
                }
            }
            // 追加の onActivity / semantics fetch より前に、元の履歴を固定して出力する。
            finish { emitOriginal(original) }
            finish(postSync)
            finish(detach)
            if (original == null) diagnosticFailure?.let { throw it }
        }
    }
}
