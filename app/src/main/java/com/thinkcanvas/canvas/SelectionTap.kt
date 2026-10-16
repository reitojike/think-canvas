package com.thinkcanvas.canvas

internal fun selectionTapLabel(id: String, selectedIds: Set<String>, singleLabel: String = "選択"): String = when {
    selectedIds.size <= 1 -> singleLabel
    id in selectedIds -> "選択から外す"
    else -> "選択に追加"
}
