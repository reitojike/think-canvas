package com.thinkcanvas.image

/** Pure allocation policy shared by the importer, display and PNG renderer. */
object ImageDecodePolicy {
    const val MAX_COPY_BYTES = 64L * 1024 * 1024
    const val MAX_SIDE = 2048
    const val MAX_PIXELS = 4_000_000L
    const val CACHE_BYTES = 32 * 1024 * 1024

    fun validSource(width: Int, height: Int): Boolean = width in 1..65535 &&
        height in 1..65535 && width.toLong() * height <= 100_000_000L

    fun sampleSize(width: Int, height: Int, requestedSide: Int): Int {
        require(validSource(width, height))
        val limit = requestedSide.coerceIn(1, MAX_SIDE)
        var sample = 1
        while (true) {
            val w = (width + sample - 1) / sample
            val h = (height + sample - 1) / sample
            if (maxOf(w, h) <= limit && w.toLong() * h <= MAX_PIXELS) return sample
            sample *= 2
        }
    }

    fun orientedSize(width: Int, height: Int, orientation: Int): Pair<Int, Int> {
        require(orientation in 1..8)
        return if (orientation in 5..8) height to width else width to height
    }

    fun bucket(requestedSide: Int): Int = listOf(64, 128, 256, 512, 1024, 2048)
        .firstOrNull { it >= requestedSide } ?: MAX_SIDE
}
