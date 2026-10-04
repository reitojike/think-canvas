package com.thinkcanvas.share

import com.thinkcanvas.canvas.TextElement
import com.thinkcanvas.canvas.WorldPoint
import java.util.UUID

/** Only the ordinary text sharing contract is admitted. The body stays unchanged. */
fun sharedPlainText(action: String?, mime: String?, extra: Any?): String? =
    if (action == "android.intent.action.SEND" && mime == "text/plain" && extra is CharSequence)
        extra.toString().takeUnless { it.isBlank() } else null

data class ShareImportRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val elementId: String = UUID.randomUUID().toString(),
    val text: String,
    val destinationId: Long? = null,
    val position: WorldPoint? = null,
) {
    init {
        UUID.fromString(requestId)
        UUID.fromString(elementId)
        require(text.isNotBlank())
        require(position == null || destinationId != null &&
            position.x.isFinite() && position.y.isFinite())
    }

    val accepted: Boolean get() = position != null

    fun element(): TextElement {
        val point = checkNotNull(position)
        return TextElement(id = elementId, text = text, x = point.x, y = point.y)
    }
}

enum class ShareImportPhase { LOADING, EMPTY, DEFERRED, PREVIEW, PICKER, OPENING, ACCEPTED, SAVING, FAILED }

data class ShareImportState(
    val phase: ShareImportPhase = ShareImportPhase.LOADING,
    val request: ShareImportRequest? = null,
    val writing: Boolean = false,
    val restoredUncertain: Boolean = false,
) {
    val blocksCanvas: Boolean get() = writing && phase == ShareImportPhase.EMPTY || phase in setOf(ShareImportPhase.PREVIEW,
        ShareImportPhase.PICKER, ShareImportPhase.OPENING, ShareImportPhase.ACCEPTED,
        ShareImportPhase.SAVING, ShareImportPhase.FAILED)
}
