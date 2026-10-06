package com.thinkcanvas.canvas

/** The unfinished editor IME request; focus and keyboard.show remain with the caller. */
internal suspend fun awaitEditorImeWindow(
    isCurrent: () -> Boolean,
    awaitWindowOwnership: suspend () -> Unit,
    awaitImeControl: suspend () -> Unit,
    awaitFrame: suspend () -> Unit,
    hasWindowFocus: () -> Boolean,
    isInputReady: () -> Boolean = { true },
): Boolean {
    while (isCurrent()) {
        awaitWindowOwnership()
        if (!isCurrent()) return false
        awaitImeControl()
        awaitFrame()
        if (!isCurrent()) return false
        if (hasWindowFocus() && isInputReady()) return true
    }
    return false
}
