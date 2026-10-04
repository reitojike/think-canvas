package com.thinkcanvas.canvas

import androidx.compose.ui.geometry.Offset

/** One blank UP, before selection or editor lifecycle has started. */
internal data class BlankTap(
    val upMillis: Long,
    val screen: Offset,
    val world: WorldPoint,
    val generation: Int,
    val content: BoardSnapshot,
    val selectedId: String?,
    val selectedIds: Set<String>,
    val searchOpen: Boolean,
) {
    fun matchesSecondDown(time: Long, point: Offset, minimum: Long, timeout: Long, slop: Float): Boolean =
        time - upMillis in minimum..timeout && (point - screen).getDistance() < slop
}
