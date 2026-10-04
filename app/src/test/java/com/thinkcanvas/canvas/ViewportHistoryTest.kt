package com.thinkcanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportHistoryTest {
    @Test fun initializationAndResizePreserveWorldFocusWithoutHistoryEntries() {
        val history = ViewportHistory()

        assertFalse(history.initialized)
        assertNull(history.focus())
        history.resize(400f, 200f, density = 2f)
        history.initialize(Viewport(scale = 1.5f, panX = -100f, panY = 25f))

        val before = history.focus()!!
        assertFocus(ViewportFocus(200f, 50f, 1.5f), before)
        assertTrue(history.initialized)
        assertFalse(history.canBack)
        assertFalse(history.canForward)

        history.resize(800f, 600f, density = 3f)

        assertFocus(before, history.focus()!!)
        assertFalse(history.canBack)
        assertFalse(history.canForward)
    }

    @Test fun backAndForwardRestoreWorldFocusAndScaleWithoutSelfRecording() {
        val history = measuredHistory()
        val first = history.focus()!!
        val second = ViewportFocus(320f, -140f, 2f)
        history.viewportState.value = viewportFor(second, 400f, 200f)
        assertTrue(history.record(first))

        val restoredFirst = history.back()!!
        history.viewportState.value = restoredFirst
        assertFocus(first, history.focus()!!)
        assertTrue(history.canForward)
        assertFalse(history.canBack)

        val restoredSecond = history.forward()!!
        history.viewportState.value = restoredSecond
        assertFocus(second, history.focus()!!)
        assertFalse(history.canForward)
        assertTrue(history.canBack)
    }

    @Test fun nearlyIdenticalFocusDoesNotCreateDuplicateHistory() {
        val history = measuredHistory()
        val origin = history.focus()!!
        // At density 1, this is below the specified 2dp center threshold and 1% scale threshold.
        history.viewportState.value = history.viewportState.value.copy(panX = 0.5f, scale = 1.005f)

        assertFalse(history.record(origin))
        assertFalse(history.canBack)
        assertFalse(history.canForward)
    }

    @Test fun sameSearchGroupCoalescesWhileDifferentGroupsRemainSeparate() {
        val history = measuredHistory()
        val a = history.focus()!!
        val group = Any()
        val b = ViewportFocus(250f, 100f, 1f)
        history.viewportState.value = viewportFor(b, 400f, 200f)
        assertTrue(history.record(a, group))

        val c = ViewportFocus(400f, 125f, 1.25f)
        history.viewportState.value = viewportFor(c, 400f, 200f)
        assertTrue(history.record(b, group))

        history.viewportState.value = history.back()!!
        assertFocus(a, history.focus()!!)
        assertFalse(history.canBack)
        history.viewportState.value = history.forward()!!
        assertFocus(c, history.focus()!!)
        assertTrue(history.canBack)
    }

    @Test fun searchGroupStartsAtFirstRealMovementWithExistingHistory() {
        val history = measuredHistory()
        val a = history.focus()!!
        val b = ViewportFocus(250f, 100f, 1f)
        history.viewportState.value = viewportFor(b, 400f, 200f)
        assertTrue(history.record(a))

        val group = Any()
        assertFalse(history.record(b, group))
        val c = ViewportFocus(600f, 200f, 1f)
        history.viewportState.value = viewportFor(c, 400f, 200f)
        assertTrue(history.record(b, group))
        assertFalse(history.record(c, group))
        val d = ViewportFocus(900f, 300f, 1f)
        history.viewportState.value = viewportFor(d, 400f, 200f)
        assertTrue(history.record(c, group))

        history.viewportState.value = history.back()!!
        assertFocus(b, history.focus()!!)
        history.viewportState.value = history.back()!!
        assertFocus(a, history.focus()!!)
        assertFalse(history.canBack)
        history.viewportState.value = history.forward()!!
        assertFocus(b, history.focus()!!)
        history.viewportState.value = history.forward()!!
        assertFocus(d, history.focus()!!)
        assertFalse(history.canForward)
    }

    @Test fun sameGroupReturnToStartRemovesOnlyOwnedAnchorAndCanRestart() {
        val history = measuredHistory()
        val prior = history.focus()!!
        val start = ViewportFocus(250f, 100f, 1f)
        move(history, start)
        val group = Any()
        move(history, ViewportFocus(600f, 200f, 1f), group)
        val returned = ViewportFocus(250.5f, 100f, 1.005f)
        move(history, returned, group)
        assertTrue(history.canBack)
        assertFalse(history.canForward)
        assertFalse(history.record(history.focus(), group))
        val end = ViewportFocus(900f, 300f, 1f)
        move(history, end, group)

        history.viewportState.value = history.back()!!
        assertFocus(returned, history.focus()!!)
        history.viewportState.value = history.back()!!
        assertFocus(prior, history.focus()!!)
        assertFalse(history.canBack)
        history.viewportState.value = history.forward()!!
        assertFocus(returned, history.focus()!!)
        history.viewportState.value = history.forward()!!
        assertFocus(end, history.focus()!!)
        assertFalse(history.canForward)
    }

    @Test fun sameGroupRoundTripAtInitialViewLeavesNoHistory() {
        for (nearLastStep in listOf(false, true)) {
            val history = measuredHistory()
            val start = history.focus()!!
            val group = Any()
            move(history, ViewportFocus(600f, 200f, 1f), group)
            if (nearLastStep) {
                move(history, ViewportFocus(start.centerX + 3f, start.centerY, start.scale), group)
                move(history, ViewportFocus(start.centerX + 1.5f, start.centerY, start.scale), group)
            } else {
                move(history, start, group)
            }

            assertFalse(history.canBack)
            assertFalse(history.canForward)
            assertNull(history.back())
            assertNull(history.forward())
        }
    }

    @Test fun groupCancellationPreservesUnownedNearOlderEntry() {
        val history = measuredHistory()
        val prior = history.focus()!!
        move(history, ViewportFocus(203f, 100f, 1f))
        val origin = history.focus()!!
        val start = ViewportFocus(201.5f, 100f, 1f)
        history.viewportState.value = viewportFor(start, 400f, 200f)
        assertFalse(history.record(origin))
        val group = Any()
        move(history, ViewportFocus(600f, 200f, 1f), group)
        move(history, start, group)

        assertTrue(history.canBack)
        history.viewportState.value = history.back()!!
        assertFocus(prior, history.focus()!!)
        assertFalse(history.canBack)
    }

    @Test fun groupCancellationAtCapacityKeepsBoundAndNoPhantom() {
        val history = measuredHistory(capacity = 1)
        val start = ViewportFocus(250f, 100f, 1f)
        move(history, start)
        val group = Any()
        move(history, ViewportFocus(600f, 200f, 1f), group)
        move(history, start, group)
        assertFalse(history.canBack)
        assertNull(history.back())

        move(history, ViewportFocus(900f, 300f, 1f), group)
        history.viewportState.value = history.back()!!
        assertFocus(start, history.focus()!!)
        assertFalse(history.canBack)
    }

    @Test fun differentBoundaryReturnToStartKeepsSeparateNavigation() {
        for (boundary in listOf(Any(), null)) {
            val history = measuredHistory()
            val start = history.focus()!!
            val middle = ViewportFocus(600f, 200f, 1f)
            move(history, middle, Any())
            move(history, start, boundary)

            history.viewportState.value = history.back()!!
            assertFocus(middle, history.focus()!!)
            history.viewportState.value = history.back()!!
            assertFocus(start, history.focus()!!)
            assertFalse(history.canBack)
        }
    }

    @Test fun newNavigationAfterBackDropsOnlyViewportForwardEntries() {
        val history = measuredHistory()
        val a = history.focus()!!
        val b = ViewportFocus(250f, 90f, 1.2f)
        history.viewportState.value = viewportFor(b, 400f, 200f)
        assertTrue(history.record(a))
        val c = ViewportFocus(410f, 180f, 1.8f)
        history.viewportState.value = viewportFor(c, 400f, 200f)
        assertTrue(history.record(b))

        history.viewportState.value = history.back()!!
        assertFocus(b, history.focus()!!)
        assertTrue(history.canForward)
        val d = ViewportFocus(-80f, 320f, .8f)
        history.viewportState.value = viewportFor(d, 400f, 200f)
        assertTrue(history.record(b))

        assertFalse(history.canForward)
        history.viewportState.value = history.back()!!
        assertFocus(b, history.focus()!!)
        history.viewportState.value = history.back()!!
        assertFocus(a, history.focus()!!)
    }

    @Test fun capacityDropsOldestViewportEntries() {
        val history = measuredHistory(capacity = 2)
        val focuses = listOf(
            history.focus()!!,
            ViewportFocus(100f, 200f, 1f),
            ViewportFocus(200f, 300f, 1.2f),
            ViewportFocus(300f, 400f, 1.4f),
        )
        for (index in 1 until focuses.size) {
            history.viewportState.value = viewportFor(focuses[index], 400f, 200f)
            assertTrue(history.record(focuses[index - 1]))
        }

        history.viewportState.value = history.back()!!
        assertFocus(focuses[2], history.focus()!!)
        history.viewportState.value = history.back()!!
        assertFocus(focuses[1], history.focus()!!)
        assertFalse(history.canBack)
    }

    @Test fun invalidMeasurementAndInvalidCameraDoNotBecomeHistory() {
        val history = ViewportHistory()
        history.resize(Float.NaN, 200f)
        history.resize(400f, Float.POSITIVE_INFINITY)
        history.resize(0f, 200f)
        assertFalse(history.initialized)
        assertNull(history.focus())

        history.resize(400f, 200f)
        history.initialize(Viewport())
        val origin = history.focus()!!
        history.viewportState.value = Viewport(scale = 0f)

        assertNull(history.focus())
        assertFalse(history.record(origin))
        assertFalse(history.canBack)
        assertFalse(history.canForward)
    }

    private fun move(history: ViewportHistory, target: ViewportFocus, group: Any? = null) {
        val origin = history.focus()!!
        history.viewportState.value = viewportFor(target, 400f, 200f)
        assertTrue(history.record(origin, group))
    }

    private fun measuredHistory(capacity: Int = 80) = ViewportHistory(capacity).also {
        it.resize(400f, 200f, density = 1f)
        it.initialize(Viewport())
    }

    private fun assertFocus(expected: ViewportFocus, actual: ViewportFocus) {
        assertEquals(expected.centerX, actual.centerX, .001f)
        assertEquals(expected.centerY, actual.centerY, .001f)
        assertEquals(expected.scale, actual.scale, .0001f)
    }

    private fun viewportFor(focus: ViewportFocus, width: Float, height: Float) = Viewport(
        scale = focus.scale,
        panX = width / 2f - focus.centerX * focus.scale,
        panY = height / 2f - focus.centerY * focus.scale,
    )
}
