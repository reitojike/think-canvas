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
