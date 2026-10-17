package com.thinkcanvas.board

/** Guide visibility belongs to the save attempt, including across Activity recreation. */
internal data class GuideState(
    val visible: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
) {
    fun show() = copy(visible = true)
    fun dismiss() = if (saving) this else GuideState()
    fun start() = if (!visible || saving || error != null) null else copy(saving = true)
    fun saved() = GuideState()
    fun failed(message: String) = copy(saving = false, error = message)
    fun acknowledgeFailure() = copy(error = null)
}
