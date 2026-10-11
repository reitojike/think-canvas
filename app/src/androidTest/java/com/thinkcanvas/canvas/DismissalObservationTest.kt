package com.thinkcanvas.canvas

import com.thinkcanvas.BoardSaveState
import com.thinkcanvas.test.PrSmoke
import org.junit.Assert.*
import org.junit.Test

class DismissalObservationTest {
    @PrSmoke
    @Test fun simulatedFailureVerifiesDiagnosticPlumbingOnly() {
        // #141 の再現ではなく、出力と例外保持だけを検証する。Activity / editor は生成しない。
        val secret = "content-must-not-be-logged"
        val snapshot = BoardSnapshot(texts = listOf(TextElement(text = secret, x = 0f, y = 0f)))
        for (queryThrows in listOf(false, true)) for (diagnostic in listOf("none", "output", "postSync", "detach")) {
            val output = mutableListOf<String>()
            val original = AssertionError(secret)
            val secondary = IllegalStateException(secret)
            val observation = DismissalObservation { line ->
                output += line
                if (diagnostic == "output" && line.startsWith("original:lastPredicate")) throw secondary
            }
            repeat(129) { observation.event("callback") }
            val order = mutableListOf<Int>()
            try {
                observation.run(block = {
                    observation.stage = "semantics"
                    observation.beginPredicate()
                    repeat(4) { assertTrue(observation.observe(it) { true }) }
                    observation.beginPredicate()
                    observation.observe(0) { order += 0; true } &&
                        observation.observe(1) { order += 1; if (queryThrows) throw original else false } &&
                        observation.observe(2) { order += 2; true } &&
                        observation.observe(3) { order += 3; true }
                    throw original
                }, postSync = {
                    assertTrue(output.any { it.startsWith("original:lastPredicate") })
                    observation.event("afterFreezeMustNotEnterOriginalHistory")
                    output += "postSync:fixture save=${BoardSaveState.Running(snapshot).dismissalTracePhase()}"
                    output += "postSync:fixture save=${BoardSaveState.Failed(snapshot).dismissalTracePhase()}"
                    if (diagnostic == "postSync") throw secondary
                }, detach = {
                    output += "detached"
                    if (diagnostic == "detach") throw secondary
                })
                fail("simulated exception must propagate")
            } catch (actual: Throwable) {
                assertSame(original, actual)
            }
            assertEquals(listOf(0, 1), order)
            assertEquals(128, output.count { it.contains("uptime=") })
            val last = output.single { it.startsWith("original:lastPredicate") }
            assertTrue(last.contains("editable=true"))
            assertTrue(last.contains(if (queryThrows) "done=queryThrew:AssertionError/noResult" else "done=false"))
            assertTrue(last.contains("cancel=unevaluated retry=unevaluated"))
            if (diagnostic != "output") assertTrue(output.any { it.contains("events=129 truncated=true") })
            assertEquals(if (diagnostic == "none") emptyList() else listOf(secondary), original.suppressed.toList())
            assertTrue(output.last() == "detached")
            assertFalse(output.any { it.contains(secret) || it.contains("afterFreezeMustNotEnterOriginalHistory") })
        }
    }
}
