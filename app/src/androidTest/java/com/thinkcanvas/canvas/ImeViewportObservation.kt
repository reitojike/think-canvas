package com.thinkcanvas.canvas

import android.view.View
import android.view.WindowInsets
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.test.core.app.ActivityScenario
import com.thinkcanvas.MainActivity
import org.junit.Assert.assertEquals
import kotlin.math.abs

/** PR105のsettledCameraと同じ、native/layout/world cameraを独立して読むtest-only観測。 */
internal data class ImeViewportObservation(
    val canvas: Rect,
    val focus: ViewportFocus?,
    val viewport: Viewport,
    val reference: Offset,
    val editorExists: Boolean,
    val editorFocused: Boolean,
    val windowFocused: Boolean,
    val imeVisible: Boolean,
    val imeBottom: Int,
    val systemBottom: Int,
    val rootHeight: Int,
    val decorHeight: Int,
    val nativeCanvas: Rect,
    val draftSession: String?,
    val draftWorld: Offset?,
    val canBack: Boolean,
    val canForward: Boolean,
    val previousViews: List<ViewportFocus>,
    val nextViews: List<ViewportFocus>,
)

internal fun observeImeViewport(
    scenario: ActivityScenario<MainActivity>, navigation: ViewportHistory, editor: TextEditorSession,
    canvas: Rect, reference: Offset, editorExists: Boolean, editorFocused: Boolean,
): ImeViewportObservation {
    lateinit var result: ImeViewportObservation
    scenario.onActivity {
        val decor = it.window.decorView
        val root = decor.findViewById<View>(android.R.id.content)
        val origin = IntArray(2)
        root.getLocationInWindow(origin)
        val insets = checkNotNull(decor.rootWindowInsets)
        val safe = insets.getInsets(WindowInsets.Type.systemBars() or
            WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
        val draft = editor.draft.value
        result = ImeViewportObservation(canvas, navigation.focus(), navigation.viewportState.value,
            reference, editorExists, editorFocused, decor.hasWindowFocus(),
            insets.isVisible(WindowInsets.Type.ime()), insets.getInsets(WindowInsets.Type.ime()).bottom,
            insets.getInsets(WindowInsets.Type.systemBars()).bottom, root.height, decor.height,
            Rect((origin[0] + safe.left).toFloat(), (origin[1] + safe.top).toFloat(),
                (origin[0] + root.width - safe.right).toFloat(), (origin[1] + root.height - safe.bottom).toFloat()),
            draft?.sessionId, draft?.let { value -> Offset(value.x, value.y) }, navigation.canBack,
            navigation.canForward, navigation.viewsForTest("previous"), navigation.viewsForTest("next"))
    }
    return result
}

// availabilityだけでは非空stackへの余分なentryを検出できない。製品APIを増やさず読み取りだけで照合する。
private fun ViewportHistory.viewsForTest(name: String): List<ViewportFocus> {
    val entries = ViewportHistory::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this)
    return (entries as Iterable<*>).map { it as ViewportFocus }
}

internal class ImeViewportSettlement(private val shown: Boolean, private val referenceWorld: Offset) {
    var latest: ImeViewportObservation? = null
        private set
    private var stable = 0
    private var latestCoherent = false

    fun accept(current: ImeViewportObservation): Boolean {
        val focus = current.focus
        val (x, y) = current.viewport.worldToScreen(referenceWorld.x, referenceWorld.y)
        val coherent = focus != null && !current.canvas.isEmpty && current.canvas == current.nativeCanvas &&
            current.windowFocused && current.imeVisible == shown && (current.imeBottom > 0) == shown &&
            (!shown || current.editorExists && current.editorFocused && current.draftSession != null) &&
            abs(current.viewport.panX - (current.canvas.width / 2f - focus.centerX * focus.scale)) <= 2f &&
            abs(current.viewport.panY - (current.canvas.height / 2f - focus.centerY * focus.scale)) <= 2f &&
            abs(current.reference.x - current.canvas.left - x) <= 2f &&
            abs(current.reference.y - current.canvas.top - y) <= 2f
        val previous = latest
        // 旧whole equalityと同様に、連続する両sampleがcontractを満たすことを要求する。
        stable = if (coherent && latestCoherent && previous != null && settlesWith(previous, current))
            stable + 1 else 0
        latest = current
        latestCoherent = coherent
        return stable >= 2
    }

    // 安定判定はsettlement contractのkeyだけを比べる。raw viewport、system bar/root/decor高さ、
    // draft位置は判定に使わない。数値は後段oracleと同じ精度で、same-size復元の厳密比較は後段で維持する。
    private fun settlesWith(previous: ImeViewportObservation, current: ImeViewportObservation): Boolean {
        val before = previous.focus ?: return false
        val after = current.focus ?: return false
        return previous.canvas == current.canvas && previous.nativeCanvas == current.nativeCanvas &&
            previous.windowFocused == current.windowFocused && previous.imeVisible == current.imeVisible &&
            previous.imeBottom == current.imeBottom && previous.editorExists == current.editorExists &&
            previous.editorFocused == current.editorFocused && previous.draftSession == current.draftSession &&
            abs(before.centerX - after.centerX) <= 2f / before.scale &&
            abs(before.centerY - after.centerY) <= 2f / before.scale &&
            abs(before.scale - after.scale) <= .001f &&
            abs(previous.reference.x - current.reference.x) <= 2f &&
            abs(previous.reference.y - current.reference.y) <= 2f &&
            previous.canBack == current.canBack && previous.canForward == current.canForward &&
            previous.previousViews == current.previousViews && previous.nextViews == current.nextViews
    }
}

internal fun assertWorldFocusAndHistoryUnchanged(before: ImeViewportObservation, after: ImeViewportObservation) {
    val expected = checkNotNull(before.focus)
    val actual = checkNotNull(after.focus)
    val evidence = "before=$before, after=$after"
    assertEquals(evidence, expected.centerX, actual.centerX, 2f / expected.scale)
    assertEquals(evidence, expected.centerY, actual.centerY, 2f / expected.scale)
    assertEquals(evidence, expected.scale, actual.scale, .001f)
    assertEquals(evidence, before.canBack, after.canBack)
    assertEquals(evidence, before.canForward, after.canForward)
    assertEquals(evidence, before.previousViews, after.previousViews)
    assertEquals(evidence, before.nextViews, after.nextViews)
}

internal fun assertSameSizeCameraReturned(before: ImeViewportObservation, after: ImeViewportObservation) {
    assertWorldFocusAndHistoryUnchanged(before, after)
    val evidence = "before=$before, after=$after"
    assertEquals(evidence, before.canvas, after.canvas)
    // 元のstrict oracleの精度を、同じcanvas sizeで維持する。
    assertEquals(evidence, before.viewport, after.viewport)
    assertEquals(evidence, before.reference, after.reference)
}
