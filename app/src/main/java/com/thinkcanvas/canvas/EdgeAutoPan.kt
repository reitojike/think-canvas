package com.thinkcanvas.canvas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

/** 表示密度から独立した、有限の操作比較に使う設定。 */
data class EdgeAutoPanProfile(val bandDp: Float = 48f, val maxSpeedDpPerSecond: Float = 360f) {
    init {
        require(bandDp.isFinite() && bandDp > 0f)
        require(maxSpeedDpPerSecond.isFinite() && maxSpeedDpPerSecond > 0f)
    }
    companion object {
        val PrototypeA = EdgeAutoPanProfile(48f, 360f)
        val PrototypeB = EdgeAutoPanProfile(64f, 540f)
        val Default = PrototypeA
    }
}

/** cameraのpx/秒。右/下の空間を見るにはpanを負にする。 */
internal fun edgeAutoPanVelocity(pointer: Offset, size: IntSize, density: Float,
                                 profile: EdgeAutoPanProfile = EdgeAutoPanProfile.Default): Offset {
    if (size.width <= 0 || size.height <= 0 || !density.isFinite() || density <= 0f ||
        !pointer.x.isFinite() || !pointer.y.isFinite()) return Offset.Zero
    val band = profile.bandDp * density
    val maximum = profile.maxSpeedDpPerSecond * density
    if (!band.isFinite() || !maximum.isFinite()) return Offset.Zero
    fun axis(position: Float, dimension: Int): Float {
        val width = minOf(band, dimension / 4f)
        val proximity = when {
            position < width -> ((width - position) / width).coerceIn(0f, 1f)
            position > dimension - width -> -((position - dimension + width) / width).coerceIn(0f, 1f)
            else -> 0f
        }
        return maximum * proximity * kotlin.math.abs(proximity)
    }
    return Offset(axis(pointer.x, size.width), axis(pointer.y, size.height))
}

internal fun edgeAutoPanFrameSeconds(elapsedNanos: Long): Float =
    elapsedNanos.coerceIn(0L, 50_000_000L) / 1_000_000_000f

/** pointer・frame・releaseで共有する唯一の移動差分。cameraを別に加算しない。 */
internal fun worldDragDelta(viewport: Viewport, anchor: WorldPoint, pointer: Offset): WorldPoint =
    viewport.screenToWorld(pointer.x, pointer.y).let { (x, y) -> WorldPoint(x - anchor.x, y - anchor.y) }

/** Composition内だけのowner。保存・再生成で復元しない。 */
internal class MoveDragSession(ids: Set<String>, val anchor: WorldPoint, val generation: Int,
                               val source: BoardSnapshot, pointer: Offset) {
    val ids = ids.toSet()
    var pointer by mutableStateOf(pointer)
    fun hasSameContent(board: BoardState): Boolean = source.texts === board.elements &&
        source.shapes === board.shapes && source.arrows === board.arrows && source.ink === board.ink
}
