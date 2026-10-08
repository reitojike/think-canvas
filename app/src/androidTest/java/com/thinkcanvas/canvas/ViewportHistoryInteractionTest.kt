package com.thinkcanvas.canvas

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thinkcanvas.BoardSaveState
import com.thinkcanvas.BoardSessionViewModel
import com.thinkcanvas.MainActivity
import com.thinkcanvas.data.ArrowElementRow
import com.thinkcanvas.data.BoardRow
import com.thinkcanvas.data.CanvasDatabase
import com.thinkcanvas.data.CanvasStore
import com.thinkcanvas.data.InkStrokeRow
import com.thinkcanvas.data.SpatialElementRow
import com.thinkcanvas.data.TextElementRow
import com.thinkcanvas.data.showBoardOneAtStartup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import com.thinkcanvas.test.PrSmoke
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Spec009: actual target ownership, native viewport gestures and durable content are independent observations. */
@RunWith(AndroidJUnit4::class)
class ViewportHistoryInteractionTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val note = TextElement(id = "indicator-note", text = "Find alpha", x = 500f, y = 1200f)
    private val reference = TextElement(id = "indicator-reference", text = "Reference", kind = TextKind.TITLE,
        x = 1300f, y = 1800f)
    private val other = TextElement(id = "indicator-other", text = "Find beta", kind = TextKind.TITLE,
        x = 900f, y = 1400f)
    private val rectangle = ShapeElement(id = "indicator-shape", kind = ShapeKind.RECTANGLE,
        x = 720f, y = 1450f, width = 300f, height = 140f, name = "Selection shape")
    private val region = ShapeElement(id = "indicator-region", kind = ShapeKind.REGION,
        x = 400f, y = 1000f, width = 650f, height = 600f, name = "Cluster")
    private val stroke = InkElement(id = "indicator-ink", kind = InkKind.PEN, strokes = listOf(
        InkStroke(id = "indicator-stroke", startedAt = 1L, endedAt = 101L, inputType = InkInputType.TOUCH,
            points = listOf(InkPoint(600f, 1400f, 0L), InkPoint(780f, 1400f, 100L)))))
    private val arrow = ArrowElement(id = "indicator-arrow", from = ArrowEnd.Free(750f, 1300f),
        to = ArrowEnd.Free(1000f, 1550f))
    private val defaults get() = BoardSnapshot(texts = listOf(note, other, reference),
        shapes = listOf(rectangle), arrows = listOf(arrow), ink = listOf(stroke))
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private data class CameraObservation(val canvas: Rect, val focus: ViewportFocus,
        val viewport: Viewport, val marker: Offset, val imeVisible: Boolean,
        val imeBottom: Int, val systemBottom: Int, val rootHeight: Int, val windowFocused: Boolean)

    private inner class Harness(val scenario: ActivityScenario<MainActivity>,
                                val sessions: BoardSessionViewModel, val initial: BoardSnapshot) {
        val board get() = sessions.stateFor(1L, initial)
        val editor get() = sessions.textEditorFor(1L, initial)
        val saves = AtomicInteger()
        var saveGate: CompletableDeferred<Unit>? = null
        var heldDurableSave: Deferred<Unit>? = null
        val canvas get() = composeRule.onNodeWithContentDescription("キャンバス")
            .fetchSemanticsNode().boundsInWindow
        fun position(label: String) = composeRule.onAllNodesWithContentDescription(label)
            .fetchSemanticsNodes().first().positionInWindow
        fun nodes(prefix: String) = composeRule.onAllNodesWithContentDescription(prefix, substring = true)
            .fetchSemanticsNodes()
        fun indicators() = nodes("へ移動").filter {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { value ->
                value.startsWith("現在の検索結果、") || value.startsWith("選択対象、") } == true }
        fun marker(prefix: String) = nodes(prefix).single()
        fun click(label: String) {
            val historyAction = label == "戻す" || label == "進む"
            val gate = if (historyAction && nodes("ボード内を探す").isNotEmpty())
                CompletableDeferred<Unit>() else null
            saveGate = gate
            try {
                val candidates = if (label == "戻す" || label == "進む")
                    composeRule.onAllNodesWithContentDescription(label).fetchSemanticsNodes() else nodes(label)
                val node = candidates.first { it.config.contains(SemanticsActions.OnClick) }
                composeRule.runOnUiThread { assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke()) }
                composeRule.waitForIdle()
                if (gate != null) {
                    // Make the existing inputAllowed resume observable instead of depending on Room timing.
                    assertTrue(sessions.saveStateFor(1L, initial).value is BoardSaveState.Running)
                    runBlocking { checkNotNull(heldDurableSave).await() }
                    assertEquals(board.snapshot(), saved())
                    composeRule.runOnUiThread { gate.complete(Unit) }
                }
                if (historyAction) {
                    composeRule.waitUntil(5_000) { sessions.saveStateFor(1L, initial).value == BoardSaveState.Idle }
                    composeRule.waitForIdle()
                }
                if (gate != null) {
                    waitSearchReady()
                    hideSearchIme()
                }
            } finally {
                saveGate = null
                heldDurableSave = null
                if (gate != null && !gate.isCompleted) gate.cancel()
            }
        }
        fun action(label: String, action: String) {
            val node = nodes(label).first { it.config.contains(SemanticsActions.CustomActions) }
            composeRule.runOnUiThread {
                assertTrue(node.config[SemanticsActions.CustomActions].single { it.label == action }.action())
            }
            composeRule.waitForIdle()
        }
        fun saved() = runBlocking { checkNotNull(CanvasStore.get(instrumentation.targetContext).savedBoard(1L)).snapshot }
        fun unchanged() {
            assertEquals(initial, board.snapshot())
            assertEquals(initial, saved())
            assertEquals(0, saves.get())
            assertFalse(board.canUndo)
            assertFalse(board.canRedo)
        }
        fun event(action: Int, points: List<Offset>, downTime: Long): Long {
            val origin = IntArray(2)
            scenario.onActivity { it.window.decorView.getLocationOnScreen(origin) }
            val properties = Array(points.size) { index -> MotionEvent.PointerProperties().apply {
                id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
            } }
            val coordinates = Array(points.size) { index -> MotionEvent.PointerCoords().apply {
                x = points[index].x + origin[0]; y = points[index].y + origin[1]; pressure = 1f; size = 1f
            } }
            val eventTime = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(downTime, eventTime, action, points.size,
                properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
            return eventTime
        }
        fun pan(delta: Offset, start: Offset = Offset(canvas.left + canvas.width * .2f,
                                                       canvas.top + canvas.height * .72f)) {
            val time = SystemClock.uptimeMillis()
            try {
                event(MotionEvent.ACTION_DOWN, listOf(start), time)
                event(MotionEvent.ACTION_MOVE, listOf(start + delta), time)
                // 履歴と停止位置の観測を高速releaseの慣性から分ける。
                Thread.sleep(180L)
                event(MotionEvent.ACTION_UP, listOf(start + delta), time)
            } catch (failure: Throwable) {
                event(MotionEvent.ACTION_CANCEL, listOf(start + delta), time)
                throw failure
            }
            composeRule.waitForIdle()
        }
        fun outsideToLeft() {
            // Each swipe stays inside the native window; no synthetic off-window motion.
            repeat(4) { pan(Offset(-canvas.width * .62f, 0f),
                Offset(canvas.left + canvas.width * .8f, canvas.top + canvas.height * .72f)) }
            composeRule.waitUntil(5_000) { indicators().isNotEmpty() }
        }
        fun pinch(factor: Float) {
            val center = Offset(canvas.center.x, canvas.top + canvas.height * .65f)
            val before = listOf(center - Offset(8f, 0f), center + Offset(8f, 0f))
            val after = listOf(center - Offset(8f * factor, 0f), center + Offset(8f * factor, 0f))
            val time = SystemClock.uptimeMillis()
            event(MotionEvent.ACTION_DOWN, listOf(before.first()), time)
            event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), before, time)
            event(MotionEvent.ACTION_MOVE, after, time)
            event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), after, time)
            event(MotionEvent.ACTION_UP, listOf(after.first()), time)
            composeRule.waitForIdle()
        }
        fun search(query: String = "Find") {
            click("ボード内を検索")
            composeRule.onNodeWithContentDescription("ボード内を探す").performTextReplacement(query)
            waitSearchReady()
            hideSearchIme()
            composeRule.waitUntil(5_000) { nodes("1件目、全").isNotEmpty() }
        }
        fun waitSearchReady() = waitEditableImeReady("ボード内を探す")
        fun waitEditableImeReady(label: String) {
            try { composeRule.waitUntil(10_000) {
                val focused = nodes(label).singleOrNull()?.config
                    ?.getOrNull(SemanticsProperties.Focused) == true
                var ready = false
                scenario.onActivity { activity ->
                    val root = activity.window.decorView
                    val view = root.findFocus()
                    val input = activity.getSystemService(InputMethodManager::class.java)
                    val insets = root.rootWindowInsets
                    ready = focused && root.hasWindowFocus() && view != null &&
                        input.isActive(view) && input.isAcceptingText &&
                        insets?.isVisible(WindowInsets.Type.ime()) == true &&
                        insets.getInsets(WindowInsets.Type.ime()).bottom > 0
                }
                ready
            } } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
                val field = nodes(label).singleOrNull()
                var native = ""
                scenario.onActivity {
                    val root = it.window.decorView
                    val view = root.findFocus()
                    val input = it.getSystemService(InputMethodManager::class.java)
                    val insets = root.rootWindowInsets
                    native = "windowFocus=${root.hasWindowFocus()}, view=${view?.javaClass?.simpleName}, " +
                        "active=${view?.let { input.isActive(it) }}, accepting=${input.isAcceptingText}, " +
                        "imeVisible=${insets?.isVisible(WindowInsets.Type.ime())}, " +
                        "imeBottom=${insets?.getInsets(WindowInsets.Type.ime())?.bottom}"
                }
                throw AssertionError("Editable readiness: field=${field?.boundsInWindow}, " +
                    "focused=${field?.config?.getOrNull(SemanticsProperties.Focused)}, $native", timeout)
            }
            composeRule.waitForIdle()
        }
        fun hideSearchIme() {
            val query = nodes("ボード内を探す").single().config[SemanticsProperties.EditableText]
            // 検索を保つfixtureのkeyboard cleanup。実際のBackはhideTextImeで別に検証する。
            closeSoftKeyboard()
            composeRule.waitForIdle()
            composeRule.waitUntil(5_000) {
                var hidden = false
                scenario.onActivity {
                    val insets = checkNotNull(it.window.decorView.rootWindowInsets)
                    hidden = !insets.isVisible(WindowInsets.Type.ime()) &&
                        insets.getInsets(WindowInsets.Type.ime()).bottom == 0
                }
                hidden
            }
            composeRule.waitForIdle()
            assertEquals(query, nodes("ボード内を探す").single().config[SemanticsProperties.EditableText])
        }
        fun waitDiscardWindowOwned() {
            composeRule.waitUntil(5_000) {
                var dialogReady = false
                onView(isRoot()).inRoot(isDialog()).check { view, failure ->
                    if (failure != null) throw failure
                    val insets = checkNotNull(view.rootWindowInsets)
                    dialogReady = view.hasWindowFocus() && !insets.isVisible(WindowInsets.Type.ime()) &&
                        insets.getInsets(WindowInsets.Type.ime()).bottom == 0
                }
                var activityFocused = true
                scenario.onActivity { activityFocused = it.window.decorView.hasWindowFocus() }
                dialogReady && !activityFocused
            }
        }
        fun back() {
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.waitForIdle()
        }
        fun cameraObservation(): CameraObservation {
            val bounds = canvas
            val marker = position(reference.text)
            lateinit var observation: CameraObservation
            scenario.onActivity {
                val root = it.window.decorView
                val insets = checkNotNull(root.rootWindowInsets)
                observation = CameraObservation(bounds, checkNotNull(navigation.focus()),
                    navigation.viewportState.value, marker, insets.isVisible(WindowInsets.Type.ime()),
                    insets.getInsets(WindowInsets.Type.ime()).bottom,
                    insets.getInsets(WindowInsets.Type.systemBars()).bottom, root.height, root.hasWindowFocus())
            }
            return observation
        }
        fun settledCamera(imeVisible: Boolean): CameraObservation {
            var previous: CameraObservation? = null
            var stable = 0
            composeRule.waitUntil(10_000) {
                val current = cameraObservation()
                val (x, y) = current.viewport.worldToScreen(reference.x, reference.y)
                val drawingMatches = kotlin.math.abs(current.marker.x - current.canvas.left - x) <= 2f &&
                    kotlin.math.abs(current.marker.y - current.canvas.top - y) <= 2f
                stable = if (current.imeVisible == imeVisible &&
                    (current.imeBottom > 0) == imeVisible && current.windowFocused &&
                    drawingMatches && current == previous) stable + 1 else 0
                previous = current
                stable >= 2
            }
            return checkNotNull(previous).also {
                android.util.Log.i("ImeViewportRegression", it.toString())
            }
        }
        fun assertCameraReturned(before: CameraObservation) {
            composeRule.waitUntil(5_000) { editor.draft.value == null }
            val after = settledCamera(imeVisible = false)
            val evidence = "before=$before, after=$after"
            assertEquals(evidence, before.canvas, after.canvas)
            assertEquals(evidence, before.focus.centerX, after.focus.centerX, 2f / before.focus.scale)
            assertEquals(evidence, before.focus.centerY, after.focus.centerY, 2f / before.focus.scale)
            assertEquals(evidence, before.focus.scale, after.focus.scale, .001f)
            assertEquals(evidence, before.marker.x, after.marker.x, 2f)
            assertEquals(evidence, before.marker.y, after.marker.y, 2f)
            assertFalse(navigation.canBack)
            assertFalse(navigation.canForward)
        }
        fun openLowerDraft(before: CameraObservation) {
            val (x, y) = before.viewport.screenToWorld(before.canvas.width * .65f, before.canvas.height * .85f)
            // Isolate the IME/camera writer from blank-tap arbitration; input and exit are native.
            composeRule.runOnUiThread { editor.draft.value = Draft(null, x, y) }
            waitEditableImeReady("新しいテキスト")
            val shown = settledCamera(imeVisible = true)
            sameFocus(before.focus, shown.focus)
            val field = nodes("新しいテキスト").single().boundsInWindow
            val done = nodes("完了").first { it.config.contains(SemanticsActions.OnClick) }.boundsInWindow
            assertTrue("Visible field must remain inside canvas above Done: $field/$shown/$done",
                !field.isEmpty && field.left >= shown.canvas.left && field.right <= shown.canvas.right &&
                    field.top >= shown.canvas.top && field.bottom <= done.top)
        }
        fun hideTextIme() {
            assertTrue(instrumentation.uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            settledCamera(imeVisible = false)
            assertNotNull(editor.draft.value)
        }
        fun nextPanAt() {
            fun drawingMatchesCamera(bounds: Rect, position: Offset): Boolean {
                val (x, y) = navigation.viewportState.value.worldToScreen(reference.x, reference.y)
                return kotlin.math.abs(position.x - bounds.left - x) <= 2f &&
                    kotlin.math.abs(position.y - bounds.top - y) <= 2f
            }
            var previousBounds: Rect? = null
            var previousFocus: ViewportFocus? = null
            var previousPosition: Offset? = null
            var stable = 0
            composeRule.waitUntil(5_000) {
                var imeHidden = false
                scenario.onActivity {
                    val insets = checkNotNull(it.window.decorView.rootWindowInsets)
                    imeHidden = !insets.isVisible(WindowInsets.Type.ime()) &&
                        insets.getInsets(WindowInsets.Type.ime()).bottom == 0
                }
                val bounds = canvas
                val focus = navigation.focus()
                val referencePosition = position(reference.text)
                stable = if (imeHidden && editor.draft.value == null && !bounds.isEmpty && focus != null &&
                    drawingMatchesCamera(bounds, referencePosition) && bounds == previousBounds &&
                    focus == previousFocus && referencePosition == previousPosition) stable + 1 else 0
                previousBounds = bounds
                previousFocus = focus
                previousPosition = referencePosition
                stable >= 2
            }
            composeRule.waitForIdle()
            val point = Offset(canvas.left + canvas.width * .2f, canvas.top + canvas.height * .72f)
            val before = position(reference.text)
            val beforeFocus = checkNotNull(navigation.focus())
            pan(Offset(90f, 25f), point)
            composeRule.waitUntil(5_000) { drawingMatchesCamera(canvas, position(reference.text)) }
            val after = position(reference.text)
            val afterFocus = checkNotNull(navigation.focus())
            val evidence = "before=$before/$beforeFocus, after=$after/$afterFocus, canvas=$canvas"
            assertEquals(evidence, beforeFocus.scale, afterFocus.scale, .001f)
            assertEquals(evidence, 90f, (beforeFocus.centerX - afterFocus.centerX) * beforeFocus.scale, 2f)
            assertEquals(evidence, 25f, (beforeFocus.centerY - afterFocus.centerY) * beforeFocus.scale, 2f)
            assertEquals(evidence, before.x + 90f, after.x, 2f)
            assertEquals(evidence, before.y + 25f, after.y, 2f)
        }
    }

    private fun withBoard(initial: BoardSnapshot = defaults, block: Harness.() -> Unit) {
        val context = instrumentation.targetContext
        val database = CanvasDatabase.open(context)
        runBlocking {
            if (database.canvasDao().board(1L) == null) database.canvasDao().putBoard(BoardRow())
            database.canvasDao().replaceAll(1L, initial.texts.map { TextElementRow.fromModel(1L, it) },
                initial.shapes.map { SpatialElementRow.fromModel(1L, it) },
                initial.arrows.map { ArrowElementRow.fromModel(1L, it) },
                initial.ink.flatMap { InkStrokeRow.fromModel(1L, it) })
        }
        database.close()
        showBoardOneAtStartup(context)
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            composeRule.waitUntil(10_000) { composeRule.onAllNodesWithContentDescription("キャンバス")
                .fetchSemanticsNodes().isNotEmpty() }
            composeRule.waitForIdle()
            lateinit var sessions: BoardSessionViewModel
            scenario.onActivity { sessions = ViewModelProvider(it)[BoardSessionViewModel::class.java] }
            // A canvas semantics node can precede its first measurement/initial fit.
            // Each Activity in a multi-launch test must own a ready camera before observation.
            composeRule.waitUntil(10_000) {
                sessions.viewportHistoryFor(1L, initial).focus() != null
            }
            composeRule.waitForIdle()
            val harness = Harness(scenario, sessions, initial)
            sessions.setSaveOperation { id, snapshot ->
                harness.saves.incrementAndGet()
                val durable = CanvasStore.get(context).save(id, snapshot)
                harness.saveGate?.also { harness.heldDurableSave = durable } ?: durable
            }
            harness.block()
        } finally { composeRule.mainClock.autoAdvance = true; scenario.close() }
    }

    private val Harness.navigation get() = sessions.viewportHistoryFor(1L, initial)
    private fun sameFocus(expected: ViewportFocus?, actual: ViewportFocus?) {
        assertNotNull(expected); assertNotNull(actual)
        assertEquals(expected!!.centerX, actual!!.centerX, 3f)
        assertEquals(expected.centerY, actual.centerY, 3f)
        assertEquals(expected.scale, actual.scale, .005f)
    }

    @PrSmoke
    @androidx.test.filters.FlakyTest(bugId = 106)
    @Test fun fiveImeCancelAndConfirmationCyclesReturnCameraWithoutHistoryOrSave() = withBoard {
        val before = settledCamera(imeVisible = false)
        repeat(5) { cycle ->
            openLowerDraft(before)
            when (cycle) {
                1 -> { hideTextIme(); back() }
                2 -> {
                    composeRule.onNodeWithContentDescription("新しいテキスト").performTextReplacement("discard draft")
                    hideTextIme()
                    back()
                    composeRule.onNodeWithText("編集内容を破棄しますか？").assertExists()
                    // Semanticsだけではnative dialogのwindow ownershipを確定できない。
                    waitDiscardWindowOwned()
                    composeRule.onNodeWithText("編集を続ける").performClick()
                    composeRule.onNodeWithText("編集内容を破棄しますか？").assertDoesNotExist()
                    composeRule.waitForIdle()
                    waitEditableImeReady("新しいテキスト")
                    settledCamera(imeVisible = true)
                    hideTextIme()
                    back()
                    composeRule.onNodeWithText("破棄する").performClick()
                }
                else -> click("やめる")
            }
            assertCameraReturned(before)
            unchanged()
        }
    }

    @PrSmoke
    @androidx.test.filters.FlakyTest(bugId = 106)
    @Test fun fiveImeDoneCyclesAndExistingEditKeepWorldPlacementAndUndo() = withBoard {
        val before = settledCamera(imeVisible = false)
        repeat(5) { cycle ->
            openLowerDraft(before)
            val draft = checkNotNull(editor.draft.value)
            composeRule.onNodeWithContentDescription("新しいテキスト").performTextReplacement("saved $cycle")
            click("完了")
            assertCameraReturned(before)
            val created = board.elements.single { it.text == "saved $cycle" }
            assertEquals(draft.x, created.x, 0f)
            assertEquals(draft.y, created.y, 0f)
            assertEquals(board.snapshot(), saved())
            assertEquals(cycle + 1, saves.get())
        }
        val original = board.elements.last()
        composeRule.runOnUiThread {
            editor.draft.value = Draft(original.id, original.x, original.y, original.text,
                original.kind, original.color)
        }
        waitEditableImeReady("テキストを編集")
        settledCamera(imeVisible = true)
        composeRule.onNodeWithContentDescription("テキストを編集").performTextReplacement("edited existing")
        click("完了")
        assertCameraReturned(before)
        val edited = board.elements.single { it.id == original.id }
        assertEquals(original.x, edited.x, 0f)
        assertEquals(original.y, edited.y, 0f)
        assertEquals("edited existing", edited.text)
        assertEquals(board.snapshot(), saved())
        click("戻す")
        assertEquals(original, board.elements.single { it.id == original.id })
        assertTrue(board.canRedo)
        assertCameraReturned(before)
        click("進む")
        assertEquals(edited, board.elements.single { it.id == original.id })
        assertEquals(board.snapshot(), saved())
        assertEquals(8, saves.get())
        assertCameraReturned(before)
    }

    @PrSmoke
    @androidx.test.filters.FlakyTest(bugId = 106)
    @Test fun imeCameraIsIndependentOfRecreationAndWindowFocus() = withBoard {
        val before = settledCamera(imeVisible = false)
        openLowerDraft(before)
        val draft = checkNotNull(editor.draft.value)
        scenario.recreate()
        composeRule.waitForIdle()
        waitEditableImeReady("新しいテキスト")
        val recreated = settledCamera(imeVisible = true)
        sameFocus(before.focus, recreated.focus)
        assertEquals(draft, editor.draft.value)
        hideTextIme()
        lateinit var window: android.app.Dialog
        scenario.onActivity {
            window = android.app.Dialog(it).apply {
                setContentView(android.widget.TextView(it).apply { text = "Window focus fixture" })
                show()
            }
        }
        composeRule.waitUntil(5_000) {
            var focused = true
            scenario.onActivity { focused = it.window.decorView.hasWindowFocus() }
            !focused
        }
        scenario.onActivity { window.dismiss() }
        val returned = settledCamera(imeVisible = false)
        sameFocus(before.focus, returned.focus)
        assertEquals(draft, editor.draft.value)
        click("やめる")
        assertCameraReturned(before)
        unchanged()
    }

    @PrSmoke
    @androidx.test.filters.FlakyTest(bugId = 106)
    @Test fun searchCyclePanPinchAndIndicatorRestoreWithoutContentOrSave() = withBoard {
        val start = navigation.focus()
        search()
        val found = navigation.focus()
        assertNotEquals(start, found)
        click("前の視点へ戻る")
        sameFocus(start, navigation.focus())
        click("次の視点へ進む")
        sameFocus(found, navigation.focus())
        click("倍率を切り替える、")
        val cycle = navigation.focus()
        click("前の視点へ戻る")
        sameFocus(found, navigation.focus())
        click("次の視点へ進む")
        sameFocus(cycle, navigation.focus())
        pan(Offset(-canvas.width * .4f, 30f))
        val panned = navigation.focus()
        click("前の視点へ戻る")
        sameFocus(cycle, navigation.focus())
        click("次の視点へ進む")
        sameFocus(panned, navigation.focus())
        pinch(1.4f)
        click("前の視点へ戻る")
        sameFocus(panned, navigation.focus())
        outsideToLeft()
        val beforeIndicator = navigation.focus()
        click("現在の検索結果、")
        click("前の視点へ戻る")
        sameFocus(beforeIndicator, navigation.focus())
        assertTrue(nodes("1件目、全2件").isNotEmpty())
        unchanged()
    }

    @Test fun blankDoubleTapAndRegionFitHaveOneEntry() {
        withBoard {
            val start = navigation.focus()
            val point = Offset(canvas.left + canvas.width * .18f, canvas.top + canvas.height * .6f)
            val configuration = composeRule.onNodeWithContentDescription("キャンバス")
                .fetchSemanticsNode().layoutInfo.viewConfiguration
            val shifted = point + Offset(8f, 0f)
            assertTrue((shifted - point).getDistance() <
                ViewConfiguration.get(instrumentation.targetContext).scaledDoubleTapSlop)
            val firstDown = SystemClock.uptimeMillis()
            event(MotionEvent.ACTION_DOWN, listOf(point), firstDown)
            val firstUp = event(MotionEvent.ACTION_UP, listOf(point), firstDown)
            Thread.sleep(configuration.doubleTapMinTimeMillis)
            val secondDown = SystemClock.uptimeMillis()
            val secondEvent = event(MotionEvent.ACTION_DOWN, listOf(shifted), secondDown)
            assertTrue("Native pair must satisfy the platform double-tap interval",
                secondEvent - firstUp in configuration.doubleTapMinTimeMillis..configuration.doubleTapTimeoutMillis)
            event(MotionEvent.ACTION_UP, listOf(shifted), secondDown)
            composeRule.waitForIdle()
            composeRule.waitUntil(5_000) { navigation.canBack }
            assertNotEquals(start, navigation.focus())
            assertNull(editor.draft.value)
            click("前の視点へ戻る")
            sameFocus(start, navigation.focus())
            assertFalse(navigation.canBack)
            unchanged()
        }
        withBoard(defaults.copy(shapes = listOf(rectangle, region))) {
            val start = navigation.focus()
            pinch(.02f)
            val far = navigation.focus()
            assertEquals(.15f, checkNotNull(far).scale, 0f)
            click("囲み: Cluster")
            val fitted = checkNotNull(navigation.focus())
            click("前の視点へ戻る")
            sameFocus(far, navigation.focus())
            assertEquals(.15f, checkNotNull(navigation.focus()).scale, 0f)
            click("次の視点へ進む")
            sameFocus(fitted, navigation.focus())
            click("前の視点へ戻る")
            sameFocus(far, navigation.focus())
            click("前の視点へ戻る")
            sameFocus(start, navigation.focus())
            assertFalse(navigation.canBack)
            unchanged()
        }
    }

    @PrSmoke
    @androidx.test.filters.FlakyTest(bugId = 106)
    @Test fun visibleAndOffscreenUndoRedoFocusWithoutExtraContentHistoryOrSave() = withBoard {
        search("Find alpha")
        val visible = navigation.focus()
        composeRule.runOnUiThread { assertTrue(board.edit(note.id, "Changed alpha", note.kind, note.color)) }
        click("戻す")
        sameFocus(visible, navigation.focus())
        assertEquals(initial, board.snapshot())
        assertEquals(1, saves.get())
        click("進む")
        assertEquals("Changed alpha", board.elements.first { it.id == note.id }.text)
        assertEquals(2, saves.get())
        repeat(4) { pan(Offset(-canvas.width * .62f, 0f),
            Offset(canvas.left + canvas.width * .8f, canvas.top + canvas.height * .72f)) }
        val distant = navigation.focus()
        click("戻す")
        assertEquals(initial, board.snapshot())
        assertEquals(3, saves.get())
        assertNotEquals(distant, navigation.focus())
        assertTrue(canvas.contains(position(note.text)))
        click("前の視点へ戻る")
        sameFocus(distant, navigation.focus())
        assertEquals(initial, board.snapshot())
        assertTrue(board.canRedo)
        assertEquals(3, saves.get())
        pan(Offset(50f, 20f))
        assertTrue(board.canRedo)
        click("進む")
        assertEquals("Changed alpha", board.elements.first { it.id == note.id }.text)
        assertEquals(4, saves.get())
    }

    @Test fun recreationAndNativeCancelPreserveSessionAndNextGesture() = withBoard {
        pan(Offset(100f, 30f))
        val panned = navigation.focus()
        scenario.recreate()
        composeRule.waitForIdle()
        sameFocus(panned, navigation.focus())
        assertTrue(navigation.canBack)
        click("前の視点へ戻る")
        assertFalse(navigation.canBack)
        val point = Offset(canvas.left + canvas.width * .2f, canvas.top + canvas.height * .6f)
        val time = SystemClock.uptimeMillis()
        event(MotionEvent.ACTION_DOWN, listOf(point), time)
        event(MotionEvent.ACTION_MOVE, listOf(point + Offset(80f, 20f)), time)
        event(MotionEvent.ACTION_CANCEL, listOf(point + Offset(80f, 20f)), time)
        composeRule.waitForIdle()
        assertFalse(navigation.canBack)
        val canceled = navigation.focus()
        pan(Offset(70f, 20f), point)
        click("前の視点へ戻る")
        sameFocus(canceled, navigation.focus())
        unchanged()
    }

    @androidx.test.filters.FlakyTest(bugId = 106)
    @Test fun staleViewActionRejectsSaveEditorToolAndNextGestureUsesCurrentState() = withBoard {
        pan(Offset(100f, 30f))
        val button = marker("前の視点へ戻る")
        val density = instrumentation.targetContext.resources.displayMetrics.density
        assertTrue(button.boundsInWindow.width >= 48f * density - 1f)
        assertTrue(button.boundsInWindow.height >= 48f * density - 1f)
        assertTrue(marker("次の視点へ進む").config.contains(SemanticsProperties.Disabled))
        val stale = button.config[SemanticsActions.OnClick].action!!
        val camera = navigation.focus()
        val completion = CompletableDeferred<Unit>()
        sessions.setSaveOperation { _, _ -> completion }
        scenario.onActivity {
            sessions.requestSave(1L, initial)
            stale.invoke()
        }
        composeRule.waitForIdle()
        assertTrue(nodes("前の視点へ戻る").isEmpty())
        sameFocus(camera, navigation.focus())
        completion.completeExceptionally(IllegalStateException("bounded fixture"))
        composeRule.waitUntil(5_000) { sessions.saveStateFor(1L, initial).value is BoardSaveState.Failed }
        assertTrue(nodes("前の視点へ戻る").isEmpty())
        sameFocus(camera, navigation.focus())
        sessions.setSaveOperation { _, _ -> CompletableDeferred(Unit) }
        scenario.onActivity { sessions.retrySave(1L) }
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000) { sessions.saveStateFor(1L, initial).value == BoardSaveState.Idle }
        val acknowledgementStale = marker("前の視点へ戻る").config[SemanticsActions.OnClick].action!!
        scenario.onActivity {
            editor.pendingDraftAcknowledgement.value = sessions.requestSave(1L, initial)
            assertEquals(BoardSaveState.Idle, sessions.saveStateFor(1L, initial).value)
            acknowledgementStale.invoke()
            sameFocus(camera, navigation.focus())
        }
        composeRule.waitForIdle()
        assertNull(editor.pendingDraftAcknowledgement.value)
        val editorStale = marker("前の視点へ戻る").config[SemanticsActions.OnClick].action!!
        composeRule.runOnUiThread {
            editor.draft.value = Draft(null, 800f, 1600f, "Draft")
            editorStale.invoke()
        }
        composeRule.waitForIdle()
        sameFocus(camera, navigation.focus())
        assertTrue(nodes("前の視点へ戻る").isEmpty())
        // Observe native IME ownership and dismissal before removing the editor node.
        waitEditableImeReady("新しいテキスト")
        val draftSession = checkNotNull(editor.draft.value).sessionId
        assertTrue(instrumentation.uiAutomation.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        composeRule.waitUntil(5_000) {
            var hidden = false
            scenario.onActivity {
                val insets = checkNotNull(it.window.decorView.rootWindowInsets)
                hidden = !insets.isVisible(WindowInsets.Type.ime()) &&
                    insets.getInsets(WindowInsets.Type.ime()).bottom == 0
            }
            hidden
        }
        assertEquals(draftSession, checkNotNull(editor.draft.value).sessionId)
        composeRule.runOnUiThread { editor.draft.value = null }
        composeRule.waitForIdle()
        val toolCamera = navigation.focus()
        val toolStale = marker("前の視点へ戻る").config[SemanticsActions.OnClick].action!!
        val expand = nodes("図形ツールを開く").first { it.config.contains(SemanticsActions.OnClick) }
            .config[SemanticsActions.OnClick].action!!
        composeRule.runOnUiThread { expand.invoke(); toolStale.invoke() }
        composeRule.waitForIdle()
        sameFocus(toolCamera, navigation.focus())
        assertTrue(nodes("前の視点へ戻る").isEmpty())
        back()
        nextPanAt()
        unchanged()
    }
    @Test fun everyFamilyDeletionAndMultipleUndoTargetsUseOneDisplayNavigation() {
        val families = listOf(note.id, rectangle.id, arrow.id, stroke.id)
        for (id in families) withBoard {
            composeRule.runOnUiThread { assertTrue(board.delete(setOf(id))) }
            val visible = navigation.focus()
            click("戻す")
            sameFocus(visible, navigation.focus())
            assertEquals(initial, board.snapshot())
            assertEquals(1, saves.get())
            click("進む")
            assertFalse("対象をRedoで削除した後の内容", board.snapshot() == initial)
            sameFocus(visible, navigation.focus())
            assertEquals(2, saves.get())
            repeat(4) { pan(Offset(-canvas.width * .62f, 0f),
                Offset(canvas.left + canvas.width * .8f, canvas.top + canvas.height * .72f)) }
            val distant = navigation.focus()
            click("戻す")
            assertEquals(initial, board.snapshot())
            assertEquals(3, saves.get())
            assertNotEquals(distant, navigation.focus())
            click("前の視点へ戻る")
            sameFocus(distant, navigation.focus())
            assertTrue(board.canRedo)
            assertEquals(initial, board.snapshot())
            assertEquals(3, saves.get())
            click("進む")
            assertNotEquals(initial, board.snapshot())
            assertEquals(4, saves.get())
            assertNotEquals(distant, navigation.focus())
            click("前の視点へ戻る")
            sameFocus(distant, navigation.focus())
            assertEquals(4, saves.get())
        }
        withBoard {
            val ids = setOf(rectangle.id, stroke.id)
            composeRule.runOnUiThread { assertTrue(board.moveSelection(ids, 90f, 40f)) }
            repeat(4) { pan(Offset(-canvas.width * .62f, 0f),
                Offset(canvas.left + canvas.width * .8f, canvas.top + canvas.height * .72f)) }
            val distant = navigation.focus()
            click("戻す")
            assertEquals(initial, board.snapshot())
            val geometry = initial.resolveRenderedGeometry(emptyMap(), navigation.viewportState.value.scale,
                instrumentation.targetContext.resources.displayMetrics.density)
            for (id in ids) {
                val bounds = geometry.bounds(id)!!
                val view = navigation.viewportState.value
                val (left, top) = view.worldToScreen(bounds.left, bounds.top)
                val (right, bottom) = view.worldToScreen(bounds.right, bounds.bottom)
                assertTrue(left >= -2f && top >= -2f && right <= canvas.width + 2f && bottom <= canvas.height + 2f)
            }
            click("前の視点へ戻る")
            sameFocus(distant, navigation.focus())
            assertEquals(initial, board.snapshot())
            assertEquals(1, saves.get())
        }
    }
    @androidx.test.filters.FlakyTest(bugId = 106)
    @Test fun activeSearchUndoPreservesResultPositionAndOneChangedTargetNavigation() = withBoard {
        search()
        composeRule.runOnUiThread { assertTrue(board.edit(note.id, "Find revised", note.kind, note.color)) }
        composeRule.waitForIdle()
        click("次の検索結果")
        repeat(4) { pan(Offset(-canvas.width * .62f, 0f),
            Offset(canvas.left + canvas.width * .8f, canvas.top + canvas.height * .72f)) }
        val distant = navigation.focus()
        assertTrue(nodes("2件目、全2件").isNotEmpty())
        click("戻す")
        assertEquals(initial, board.snapshot())
        assertTrue(nodes("2件目、全2件").isNotEmpty())
        assertTrue(canvas.contains(position(note.text)))
        assertEquals(1, saves.get())
        click("前の視点へ戻る")
        sameFocus(distant, navigation.focus())
        assertEquals(initial, board.snapshot())
        assertTrue(board.canRedo)
        assertEquals(1, saves.get())
    }

    @androidx.test.filters.FlakyTest(bugId = 106)
    @Test fun searchHistoryKeepsCurrentResultValidWhenMatchesShrinkAndGrow() {
        withBoard(defaults.copy(texts = listOf(note.copy(text = "Alpha"),
            other.copy(text = "Beta", x = 6000f), reference))) {
            val first = initial.texts.single { it.id == note.id }
            val second = initial.texts.single { it.id == other.id }
            composeRule.runOnUiThread {
                assertTrue(board.edit(first.id, note.text, first.kind, first.color))
            }
            composeRule.waitForIdle()
            val oneMatch = board.snapshot()
            search()
            composeRule.runOnUiThread {
                assertTrue(board.edit(second.id, other.text, second.kind, second.color))
            }
            composeRule.waitForIdle()
            val twoMatches = board.snapshot()
            click("次の検索結果")
            assertTrue(nodes("2件目、全2件").isNotEmpty())
            assertTrue(canvas.contains(position(other.text)))
            val beforeUndo = navigation.focus()

            click("戻す")
            sameFocus(beforeUndo, navigation.focus())
            assertEquals(oneMatch, board.snapshot())
            assertEquals(oneMatch, saved())
            assertEquals(1, saves.get())
            assertTrue(nodes("1件目、全1件").isNotEmpty())
            assertTrue(indicators().any { node -> node.config[SemanticsProperties.ContentDescription]
                .any { it.startsWith("現在の検索結果、1件目、") } })

            click("戻す")
            assertEquals(initial, board.snapshot())
            assertEquals(initial, saved())
            assertEquals(2, saves.get())
            assertTrue(nodes("0件").isNotEmpty())
            assertTrue(indicators().isEmpty())
            assertTrue(canvas.contains(position(first.text)))
            val emptyFocus = navigation.focus()

            click("進む")
            sameFocus(emptyFocus, navigation.focus())
            assertEquals(oneMatch, board.snapshot())
            assertEquals(oneMatch, saved())
            assertEquals(3, saves.get())
            assertTrue(nodes("1件目、全1件").isNotEmpty())

            click("進む")
            assertEquals(twoMatches, board.snapshot())
            assertEquals(twoMatches, saved())
            assertEquals(4, saves.get())
            assertTrue(nodes("1件目、全2件").isNotEmpty())
            assertTrue(indicators().any { node -> node.config[SemanticsProperties.ContentDescription]
                .any { it.startsWith("現在の検索結果、1件目、") } })
            assertFalse(board.canRedo)
        }
    }

    @androidx.test.filters.FlakyTest(bugId = 106)
    @Test fun unchangedOffscreenAttachedArrowDoesNotMoveVisibleColorUndo() {
        val attached = ArrowElement(id = "long-attached", from = ArrowEnd.Attached(note.id, 1f, .5f),
            to = ArrowEnd.Free(6000f, 1600f))
        withBoard(defaults.copy(arrows = listOf(attached))) {
            search("Find alpha")
            composeRule.runOnUiThread {
                assertTrue(board.edit(note.id, note.text, note.kind, TextColor.VERMILION))
            }
            composeRule.waitForIdle()
            assertTrue("Undo直前の変更対象は画面内", canvas.contains(position(note.text)))
            // The edit can re-focus an active search; compare Undo with its own immediate origin.
            val visible = navigation.focus()
            click("戻す")
            sameFocus(visible, navigation.focus())
            assertEquals(initial, board.snapshot())
            assertEquals(1, saves.get())
        }
    }

    @Test fun moveAutoPanCancelAddsNoEntryAndNextPanHasOneEntry() = withBoard {
        click(note.text)
        val start = nodes("要素を移動").single().boundsInWindow.center
        val edge = Offset(canvas.right - 3f, canvas.center.y)
        val time = SystemClock.uptimeMillis()
        var finished = false
        composeRule.mainClock.autoAdvance = false
        try {
            event(MotionEvent.ACTION_DOWN, listOf(start), time)
            repeat(2) { composeRule.mainClock.advanceTimeByFrame() }
            event(MotionEvent.ACTION_MOVE, listOf(edge), time)
            repeat(90) { composeRule.mainClock.advanceTimeByFrame() }
            assertFalse(navigation.canBack)
            assertTrue(nodes("前の視点へ戻る").isEmpty())
            assertEquals(initial, board.snapshot())
            event(MotionEvent.ACTION_CANCEL, listOf(edge), time)
            finished = true
            repeat(4) { composeRule.mainClock.advanceTimeByFrame() }
        } finally {
            if (!finished) event(MotionEvent.ACTION_CANCEL, listOf(edge), time)
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()
        assertFalse(navigation.canBack)
        val canceled = navigation.focus()
        pan(Offset(75f, 20f))
        click("前の視点へ戻る")
        sameFocus(canceled, navigation.focus())
        assertFalse(navigation.canBack)
        unchanged()
    }
}
