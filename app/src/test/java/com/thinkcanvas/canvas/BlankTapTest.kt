package com.thinkcanvas.canvas

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test

class BlankTapTest {
    private val first = BlankTap(1000L, Offset(100f, 200f), WorldPoint(-30f, 70f), 4,
        BoardSnapshot(), null, emptySet(), false)

    @Test fun shiftedSecondDownUsesProvidedPlatformSlopAndUpInterval() {
        assertTrue(first.matchesSecondDown(1040L, Offset(108f, 203f), 40L, 300L, 100f))
        assertTrue(first.matchesSecondDown(1300L, Offset(108f, 203f), 40L, 300L, 100f))
        assertEquals(WorldPoint(-30f, 70f), first.world)
    }

    @Test fun tooEarlyLateOrOutsidePlatformSlopIsNotDouble() {
        assertFalse(first.matchesSecondDown(1039L, first.screen, 40L, 300L, 100f))
        assertFalse(first.matchesSecondDown(1301L, first.screen, 40L, 300L, 100f))
        assertFalse(first.matchesSecondDown(1050L, Offset(200f, 200f), 40L, 300L, 100f))
        assertFalse(first.matchesSecondDown(1050L, Offset(171f, 271f), 40L, 300L, 100f))
        assertFalse(first.matchesSecondDown(1050L, Offset(108f, 203f), 40L, 300L, 8f))
    }
}
