package com.thinkcanvas.internal.canvas

data class Viewport(
    val scale: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    fun worldToScreen(x: Float, y: Float): Pair<Float, Float> =
        Pair(x * scale + panX, y * scale + panY)

    fun screenToWorld(x: Float, y: Float): Pair<Float, Float> =
        Pair((x - panX) / scale, (y - panY) / scale)

    fun pan(dx: Float, dy: Float): Viewport = copy(panX = panX + dx, panY = panY + dy)

    fun zoomAt(x: Float, y: Float, factor: Float, dx: Float = 0f, dy: Float = 0f): Viewport {
        val next = (scale * factor).coerceIn(0.15f, 3f)
        val ratio = next / scale
        return copy(
            scale = next,
            panX = x - (x - panX) * ratio + dx,
            panY = y - (y - panY) * ratio + dy,
        )
    }
}
