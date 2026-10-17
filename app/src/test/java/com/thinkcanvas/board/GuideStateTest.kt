package com.thinkcanvas.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideStateTest {
    @Test fun startRequiresVisibleGuideAndPendingSaveRejectsReentryAndDismissal() {
        assertNull(GuideState().start())
        val pending = requireNotNull(GuideState().show().start())
        assertTrue(pending.visible)
        assertTrue(pending.saving)
        assertNull(pending.start())
        assertEquals(pending, pending.dismiss())
        assertFalse(pending.saved().visible)
    }

    @Test fun failureKeepsGuideAndRequiresAcknowledgementBeforeRetry() {
        val pending = requireNotNull(GuideState().show().start())
        val failed = pending.failed("保存失敗")
        assertTrue(failed.visible)
        assertFalse(failed.saving)
        assertEquals("保存失敗", failed.error)
        assertNull(failed.start())
        val retry = requireNotNull(failed.acknowledgeFailure().start())
        assertTrue(retry.visible)
        assertTrue(retry.saving)
        assertNull(retry.error)
        assertEquals(GuideState(), retry.saved())
    }

    @Test fun dismissBeforeStartDoesNotEnterSaveAndReplayCanBeStartedAgain() {
        val closed = GuideState().show().dismiss()
        assertEquals(GuideState(), closed)
        assertNull(closed.start())
        assertTrue(requireNotNull(closed.show().start()).saving)
    }
}
