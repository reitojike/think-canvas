package com.thinkcanvas.canvas

internal enum class TextPrimaryAction { EDIT, ADD_TO_SELECTION, REMOVE_FROM_SELECTION }

internal fun textPrimaryAction(id: String, selectedIds: Set<String>): TextPrimaryAction = when {
    selectedIds.size <= 1 -> TextPrimaryAction.EDIT
    id in selectedIds -> TextPrimaryAction.REMOVE_FROM_SELECTION
    else -> TextPrimaryAction.ADD_TO_SELECTION
}
