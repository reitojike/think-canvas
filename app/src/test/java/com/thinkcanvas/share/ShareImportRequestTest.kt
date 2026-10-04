package com.thinkcanvas.share

import com.thinkcanvas.canvas.WorldPoint
import org.junit.Assert.*
import org.junit.Test

class ShareImportRequestTest {
    @Test fun textAndUrlKeepTheirOriginalBody() {
        val body = "  考え\nhttps://example.invalid/a?q=b  "
        assertEquals(body, sharedPlainText("android.intent.action.SEND", "text/plain", StringBuilder(body)))
    }

    @Test fun unsupportedAndInvalidInputsDoNotBecomeRequests() {
        assertNull(sharedPlainText("android.intent.action.SEND_MULTIPLE", "text/plain", "text"))
        assertNull(sharedPlainText("android.intent.action.SEND", "image/png", "text"))
        assertNull(sharedPlainText("android.intent.action.SEND", "text/plain", 1))
        assertNull(sharedPlainText("android.intent.action.SEND", "text/plain", " \n "))
        assertNull(sharedPlainText(null, "text/plain", "text"))
    }

    @Test fun freshSameBodyHasDifferentIdentitiesWhileTheConfirmedPatchKeepsThem() {
        val first = ShareImportRequest(text = "same")
        val second = ShareImportRequest(text = "same")
        assertNotEquals(first.requestId, second.requestId)
        assertNotEquals(first.elementId, second.elementId)
        val confirmed = first.copy(destinationId = 4, position = WorldPoint(-20f, 40f))
        assertEquals(first.requestId, confirmed.requestId)
        assertEquals(first.elementId, confirmed.element().id)
        assertEquals(-20f, confirmed.element().x)
        assertEquals(first.text, confirmed.element().text)
    }

    @Test fun blankAndNonFinitePositionsCannotBeAccepted() {
        assertTrue(runCatching { ShareImportRequest(text = " ") }.isFailure)
        assertTrue(runCatching { ShareImportRequest(text = "a", position = WorldPoint(0f, 0f)) }.isFailure)
        assertTrue(runCatching { ShareImportRequest(text = "a", destinationId = 1,
            position = WorldPoint(Float.NaN, 0f)) }.isFailure)
    }
}
