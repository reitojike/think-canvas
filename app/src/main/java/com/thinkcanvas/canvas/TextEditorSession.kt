package com.thinkcanvas.canvas

import androidx.compose.runtime.mutableStateOf
import com.thinkcanvas.BoardSaveAcknowledgement

internal data class Draft(
    val id: String?,
    val x: Float,
    val y: Float,
    val text: String = "",
    val kind: TextKind = TextKind.BODY,
    val color: TextColor = TextColor.INK,
)

/** Transient editor state for one board; retained with its save session across recreation. */
class TextEditorSession internal constructor() {
    internal val draft = mutableStateOf<Draft?>(null)
    internal val pendingDraftAcknowledgement = mutableStateOf<BoardSaveAcknowledgement?>(null)
    internal val pendingNewElementId = mutableStateOf<String?>(null)
}
