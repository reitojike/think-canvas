package com.thinkcanvas.canvas

import android.view.MotionEvent
import android.view.Window
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

/** #165 の診断 plumbing だけを検証する。Activity、注入、camera は扱わない。 */
class NextPanTraceTest {
    private fun callback(dispatches: AtomicInteger, thrown: RuntimeException? = null, consumed: Boolean = true) =
        Proxy.newProxyInstance(Window.Callback::class.java.classLoader,
            arrayOf(Window.Callback::class.java)) { _, method, _ ->
        if (method.name == "dispatchTouchEvent") { dispatches.incrementAndGet(); thrown?.let { throw it }; consumed }
        else if (method.returnType == java.lang.Boolean.TYPE) false else null
    } as Window.Callback

    private fun motion(action: Int, downTime: Long, history: Int = 0) =
        MotionEvent.obtain(downTime, downTime + 1, action, 10f, 20f, 0).apply {
            repeat(history) { addBatch(downTime + 2 + it, 11f + it, 21f, 1f, 1f, 0) }
        }

    @Test fun successForwardsOnceRestoresOnlyOwnWrapperAndEmitsNothing() {
        val output = mutableListOf<String>()
        val dispatches = AtomicInteger()
        val base = callback(dispatches)
        val trace = NextPanTrace({ output += it })
        var current = trace.wrap(base) { throw IllegalStateException("recorder") }
        trace.run(block = {
            val down = motion(MotionEvent.ACTION_DOWN, 100L)
            try {
                trace.injected(down, intArrayOf(1, 2))
                assertTrue(current.dispatchTouchEvent(down))
            } finally { down.recycle() }
            trace.note { "after" }
        }, later = { fail("later must not run on success") }, cleanup = {
            assertFalse(trace.restore(callback(AtomicInteger())) { fail("must not overwrite another callback") })
            assertTrue(trace.restore(current) { current = it })
        })
        assertSame(base, current)
        assertEquals(1, dispatches.get())
        assertTrue(output.isEmpty())
        val targetFailure = IllegalStateException("target")
        val failingDispatches = AtomicInteger()
        val failingTrace = NextPanTrace({ fail("no output") })
        val failing = failingTrace.wrap(callback(failingDispatches, targetFailure)) {
            throw IllegalStateException("recorder")
        }
        val down = motion(MotionEvent.ACTION_DOWN, 100L)
        val up = motion(MotionEvent.ACTION_UP, 100L)
        try {
            failingTrace.injected(down, intArrayOf(0, 0))
            // downTime が一致するため origin の失敗を記録してから配送する。
            failing.dispatchTouchEvent(up)
            fail("target exception must propagate")
        } catch (actual: IllegalStateException) {
            assertSame(targetFailure, actual)
        } finally { down.recycle(); up.recycle() }
        assertEquals(1, failingDispatches.get())
        val unconsumedDispatches = AtomicInteger()
        val unconsumedTrace = NextPanTrace({ fail("no output") })
        val unconsumed = unconsumedTrace.wrap(callback(unconsumedDispatches, consumed = false)) {
            throw IllegalStateException("recorder")
        }
        val unconsumedDown = motion(MotionEvent.ACTION_DOWN, 200L)
        try {
            unconsumedTrace.injected(unconsumedDown, intArrayOf(0, 0))
            assertFalse(unconsumed.dispatchTouchEvent(unconsumedDown))
        } finally { unconsumedDown.recycle() }
        assertEquals(1, unconsumedDispatches.get())
        val cleanup = IllegalStateException("cleanup")
        try {
            NextPanTrace({ fail("no output on success") }).run({}, { fail("no later on success") }, { throw cleanup })
            fail("cleanup failure on success must propagate")
        } catch (actual: IllegalStateException) {
            assertSame(cleanup, actual)
        }
    }

    @Test fun failureKeepsOriginalAndAttachesSecondaryFailures() {
        for (mode in listOf("none", "record", "emit", "later", "cleanup")) {
            val output = mutableListOf<String>()
            val secondary = IllegalStateException(mode)
            val original = AssertionError("fixture")
            val dispatches = AtomicInteger()
            val base = callback(dispatches)
            val trace = NextPanTrace({ line ->
                if (mode == "emit" && line.startsWith("sample")) throw secondary
                output += line
            }, sampleCapacity = 3, historyCapacity = 2)
            var current = trace.wrap(base) { if (mode == "record") throw secondary; intArrayOf(5, 6) }
            try {
                trace.run(block = {
                    trace.note { "before" }
                    val down = motion(MotionEvent.ACTION_DOWN, 100L)
                    val move = motion(MotionEvent.ACTION_MOVE, 100L, history = 5)
                    val stranger = motion(MotionEvent.ACTION_DOWN, 999L)
                    try {
                        trace.injected(down, intArrayOf(1, 2))
                        listOf(down, move, stranger).forEach { assertTrue(current.dispatchTouchEvent(it)) }
                        trace.injected(move, intArrayOf(1, 2))
                    } finally { listOf(down, move, stranger).forEach(MotionEvent::recycle) }
                    throw original
                }, later = { line ->
                    line("camera=late")
                    if (mode == "later") throw secondary
                }, cleanup = {
                    trace.restore(current) { current = it }
                    if (mode == "cleanup") throw secondary
                })
                fail("original must propagate")
            } catch (actual: Throwable) {
                assertSame(original, actual)
            }
            assertEquals(3, dispatches.get())
            assertSame(base, current)
            val attached = when (mode) {
                "none" -> emptyList()
                "record" -> listOf(secondary, secondary)
                else -> listOf(secondary)
            }
            assertEquals(attached, original.suppressed.toList())
            assertEquals("original failure=java.lang.AssertionError", output.first())
            assertTrue(output.contains("note before"))
            assertEquals("later(after failure, not state at assertion) camera=late", output.last())
            val end = output.singleOrNull { it.startsWith("end") }
            when (mode) {
                "emit" -> assertFalse(output.any { it.startsWith("sample") } || end != null)
                "record" -> {
                    assertTrue(end!!.startsWith("end samples=2 kept=2 dropped=0 filtered=1 "))
                    assertTrue(end.endsWith("recorderFailures=2 attached=2"))
                }
                else -> {
                    assertTrue(end!!.startsWith(
                        "end samples=4 kept=3 dropped=1 filtered=1 notes=1 notesDropped=0 recorderFailures=0"))
                    // addBatch 後の履歴は 101..105 の 5 件。末尾 2 件だけを保持する。
                    assertTrue(output.single { it.startsWith("sample 3 delivered ACTION_MOVE") }
                        .contains("decorOrigin=5,6 history=5 kept=2 [104:13.0,21.0 105:14.0,21.0]"))
                    assertFalse(output.any { it.startsWith("sample 4") })
                }
            }
        }
    }
}
