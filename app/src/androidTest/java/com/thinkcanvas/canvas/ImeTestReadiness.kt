package com.thinkcanvas.canvas

import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.test.core.app.ActivityScenario
import com.thinkcanvas.MainActivity

/** 入力可能性とactual IME visibilityを分けるtest-only snapshot。layout settlementは扱わない。 */
internal data class ImeTestObservation(
    val windowFocused: Boolean, val view: String?,
    val active: Boolean?, val accepting: Boolean,
    val imeVisible: Boolean?, val imeBottom: Int?,
) {
    val inputReady get() = windowFocused && view != null && active == true && accepting
    val actualVisible get() = imeVisible == true && imeBottom != null && imeBottom > 0
    val diagnostics get() = "windowFocus=$windowFocused, view=$view, active=$active, accepting=$accepting, " +
        "imeVisible=$imeVisible, imeBottom=$imeBottom"
}

/** matcher、診断、waitForIdle、caller assertionとsettlementはcallerに残す。show/hideを要求しない。 */
internal class ImeTestReadiness(
    private val scenario: ActivityScenario<MainActivity>,
    private val waitUntil: (Long, () -> Boolean) -> Unit,
) {
    fun observe(): ImeTestObservation {
        lateinit var snapshot: ImeTestObservation
        scenario.onActivity { activity ->
            val root = activity.window.decorView
            val view = root.findFocus()
            val input = activity.getSystemService(InputMethodManager::class.java)
            val insets = root.rootWindowInsets
            snapshot = ImeTestObservation(root.hasWindowFocus(), view?.javaClass?.simpleName,
                view?.let { input.isActive(it) }, input.isAcceptingText,
                insets?.isVisible(WindowInsets.Type.ime()), insets?.getInsets(WindowInsets.Type.ime())?.bottom)
        }
        return snapshot
    }

    fun awaitActualVisible(focused: () -> Boolean) {
        waitUntil(10_000) {
            val editorFocused = focused()
            val snapshot = observe()
            editorFocused && snapshot.inputReady && snapshot.actualVisible
        }
    }

    fun awaitHidden() = waitUntil(5_000) {
        var hidden = false
        scenario.onActivity {
            val insets = checkNotNull(it.window.decorView.rootWindowInsets)
            hidden = !insets.isVisible(WindowInsets.Type.ime()) &&
                insets.getInsets(WindowInsets.Type.ime()).bottom == 0
        }
        hidden
    }
}
