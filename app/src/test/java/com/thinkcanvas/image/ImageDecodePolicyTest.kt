package com.thinkcanvas.image

import org.junit.Assert.*
import org.junit.Test

class ImageDecodePolicyTest {
    @Test fun oversizedOrEmptySourcesAreRejectedBeforeAllocation() {
        assertFalse(ImageDecodePolicy.validSource(0, 30))
        assertFalse(ImageDecodePolicy.validSource(65536, 1))
        assertFalse(ImageDecodePolicy.validSource(20000, 20000))
        assertTrue(ImageDecodePolicy.validSource(10000, 10000))
        assertTrue(ImageDecodePolicy.validSource(65535, 1))
    }

    @Test fun samplingNeverExceedsSideOrPixelBudgetIncludingNonPowerOfTwoSources() {
        listOf(10000 to 10000, 65535 to 1, 16000 to 6000, 1 to 65535, 4097 to 1025)
            .forEach { (width, height) ->
                listOf(64, 256, 1024, 2048, 99999).forEach { requested ->
                    val sample = ImageDecodePolicy.sampleSize(width, height, requested)
                    val w = (width + sample - 1) / sample
                    val h = (height + sample - 1) / sample
                    assertTrue(maxOf(w, h) <= requested.coerceIn(1, 2048))
                    assertTrue(w.toLong() * h <= 4_000_000L)
                    assertEquals(0, sample and (sample - 1))
                }
            }
    }

    @Test fun orientationSwapsOnlyQuarterTurnDimensionsAndBucketsAreBounded() {
        (1..8).forEach { orientation ->
            assertEquals(if (orientation >= 5) 20 to 40 else 40 to 20,
                ImageDecodePolicy.orientedSize(40, 20, orientation))
        }
        assertEquals(64, ImageDecodePolicy.bucket(1))
        assertEquals(512, ImageDecodePolicy.bucket(257))
        assertEquals(2048, ImageDecodePolicy.bucket(Int.MAX_VALUE))
    }
}
