package com.thinkcanvas.canvas

import com.thinkcanvas.image.ImageDecodePolicy
import java.util.UUID
import kotlin.math.abs

/** Immutable reference to a private asset; geometry is always in world coordinates. */
data class ImageElement(
    val id: String = UUID.randomUUID().toString(),
    val assetId: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val intrinsicWidth: Int,
    val intrinsicHeight: Int,
    val altText: String = "",
) {
    init {
        require(isCanonicalUuid(id) && isCanonicalUuid(assetId))
        require(x.isFinite() && y.isFinite())
        require(width.isFinite() && height.isFinite() && width > 0f && height > 0f)
        require((x + width).isFinite() && (y + height).isFinite())
        require(ImageDecodePolicy.validSource(intrinsicWidth, intrinsicHeight))
        val expectedRatio = intrinsicWidth.toDouble() / intrinsicHeight
        require(abs(width.toDouble() / height / expectedRatio - 1.0) < 0.0001)
    }

    fun resized(desiredWidth: Float, desiredHeight: Float): ImageElement {
        if (!desiredWidth.isFinite() || !desiredHeight.isFinite()) return this
        val widthScale = desiredWidth / width
        val heightScale = desiredHeight / height
        val scale = if (abs(widthScale - 1f) >= abs(heightScale - 1f)) widthScale else heightScale
        val longSide = (maxOf(width, height) * scale).coerceIn(40f, 8192f)
        val intrinsicLong = maxOf(intrinsicWidth, intrinsicHeight).toFloat()
        val newWidth = longSide * (intrinsicWidth / intrinsicLong)
        val newHeight = longSide * (intrinsicHeight / intrinsicLong)
        if (!(x + newWidth).isFinite() || !(y + newHeight).isFinite()) return this
        return copy(width = newWidth, height = newHeight)
    }

    companion object {
        fun placed(assetId: String, intrinsicWidth: Int, intrinsicHeight: Int,
                   center: WorldPoint, viewportWidth: Float, viewportHeight: Float,
                   id: String = UUID.randomUUID().toString()): ImageElement {
            require(ImageDecodePolicy.validSource(intrinsicWidth, intrinsicHeight))
            require(viewportWidth.isFinite() && viewportWidth > 0f &&
                viewportHeight.isFinite() && viewportHeight > 0f)
            val scale = minOf(viewportWidth * .6f / intrinsicWidth,
                viewportHeight * .6f / intrinsicHeight, 480f / maxOf(intrinsicWidth, intrinsicHeight))
            val width = intrinsicWidth * scale
            val height = intrinsicHeight * scale
            return ImageElement(id, assetId, center.x - width / 2f, center.y - height / 2f,
                width, height, intrinsicWidth, intrinsicHeight)
        }
    }
}

fun isCanonicalUuid(value: String): Boolean = value.length == 36 &&
    runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

fun ImageElement.bounds() = WorldBounds(x, y, x + width, y + height)
