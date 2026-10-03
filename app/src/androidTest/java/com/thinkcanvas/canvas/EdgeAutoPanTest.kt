package com.thinkcanvas.canvas

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import com.thinkcanvas.BoardSaveState
import com.thinkcanvas.BoardSessionViewModel
import com.thinkcanvas.MainActivity
import com.thinkcanvas.data.BoardRow
import com.thinkcanvas.data.CanvasDatabase
import com.thinkcanvas.data.CanvasStore
import com.thinkcanvas.data.TextElementRow
import com.thinkcanvas.data.SpatialElementRow
import com.thinkcanvas.data.ArrowElementRow
import com.thinkcanvas.data.InkStrokeRow
import com.thinkcanvas.data.showBoardOneAtStartup
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/** 固定した指・別要素・内容・Roomを独立に観測するSpec008の操作回帰。 */
@RunWith(AndroidJUnit4::class)
class EdgeAutoPanTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val moving = TextElement(id = "edge-moving", text = "Carry this", x = 500f, y = 1200f)
    private val fixed = TextElement(id = "edge-fixed", text = "Fixed reference", x = 1300f, y = 1800f)
    private val defaults get() = BoardSnapshot(texts = listOf(moving, fixed))
    private val rectangle = ShapeElement(id = "edge-rectangle", kind = ShapeKind.RECTANGLE,
        x = 720f, y = 1450f, width = 300f, height = 140f, name = "Movable")
    private val stroke = InkElement(id = "edge-ink", kind = InkKind.PEN, strokes = listOf(
        InkStroke(id = "edge-stroke", startedAt = 1L, endedAt = 101L, inputType = InkInputType.TOUCH,
            points = listOf(InkPoint(600f, 1400f, 0L), InkPoint(780f, 1400f, 100L)))))
    private val freeArrow = ArrowElement(id = "edge-free", from = ArrowEnd.Free(750f, 1300f),
        to = ArrowEnd.Free(1000f, 1550f))

    private inner class Harness(val scenario: ActivityScenario<MainActivity>,
                               val sessions: BoardSessionViewModel, val database: CanvasDatabase) {
        val board get() = sessions.stateFor(1L, BoardSnapshot())
        val saves = AtomicInteger()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val canvas get() = composeRule.onNodeWithContentDescription("キャンバス")
            .fetchSemanticsNode().boundsInWindow
        fun bounds(label: String) = composeRule.onNodeWithContentDescription(label)
            .fetchSemanticsNode().boundsInWindow
        fun position(label: String) = composeRule.onNodeWithContentDescription(label)
            .fetchSemanticsNode().positionInWindow
        fun frames(count: Int) { repeat(count) { composeRule.mainClock.advanceTimeByFrame() } }
        fun savedSnapshot() = runBlocking { checkNotNull(CanvasStore.get(instrumentation.targetContext).savedBoard(1L)).snapshot }
        fun select(label: String) {
            val nodes = composeRule.onAllNodesWithContentDescription(label).fetchSemanticsNodes()
            val node = nodes.first { it.config.contains(SemanticsActions.OnClick) }
            composeRule.runOnUiThread { assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke()) }
            composeRule.waitForIdle()
        }
        fun action(label: String, action: String) {
            val node = composeRule.onAllNodesWithContentDescription(label).fetchSemanticsNodes()
                .first { it.config.contains(SemanticsActions.CustomActions) }
            composeRule.runOnUiThread {
                assertTrue(node.config[SemanticsActions.CustomActions].single { it.label == action }.action())
            }
            composeRule.waitForIdle()
        }
        inner class Gesture {
            val downTime = SystemClock.uptimeMillis()
            var point = Offset.Zero
            var ended = false
            fun event(action: Int, positions: List<Offset>, stylus: Boolean = false,
                      activity: MainActivity? = null) {
                point = positions.first()
                val origin = IntArray(2)
                if (activity == null) scenario.onActivity { it.window.decorView.getLocationOnScreen(origin) }
                val properties = Array(positions.size) { index -> MotionEvent.PointerProperties().apply {
                    id = index
                    toolType = if (stylus && index == positions.lastIndex) MotionEvent.TOOL_TYPE_STYLUS
                        else MotionEvent.TOOL_TYPE_FINGER
                } }
                val coords = Array(positions.size) { index -> MotionEvent.PointerCoords().apply {
                    x = positions[index].x + origin[0]; y = positions[index].y + origin[1]
                    pressure = 1f; size = 1f
                } }
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    positions.size, properties, coords, 0, 0, 1f, 1f, 0, 0,
                    if (stylus) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN, 0)
                try {
                    if (activity == null) {
                        assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
                        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) ended = true
                    } else activity.dispatchTouchEvent(event)
                } finally { event.recycle() }
            }
            fun send(action: Int, at: Offset = point) = event(action, listOf(at))
        }
        fun drag(start: Offset, edge: Offset, block: (Gesture) -> Unit) {
            val gesture = Gesture()
            composeRule.mainClock.autoAdvance = false
            try {
                gesture.send(MotionEvent.ACTION_DOWN, start)
                frames(2)
                gesture.send(MotionEvent.ACTION_MOVE, edge)
                frames(12)
                block(gesture)
            } finally {
                if (!gesture.ended) gesture.send(MotionEvent.ACTION_CANCEL)
                composeRule.mainClock.autoAdvance = true
            }
            composeRule.waitForIdle()
        }
        fun startAndEdge(): Pair<Offset, Offset> {
            select(moving.text)
            val area = canvas
            return bounds("要素を移動").center to Offset(area.right - 3f, area.center.y)
        }
        fun assertUnchanged(before: BoardSnapshot) {
            assertEquals(before, board.snapshot())
            assertEquals(before, savedSnapshot())
            assertEquals(0, saves.get())
            assertFalse(board.canUndo)
        }
        fun assertNextPanWorks() {
            val before = position(fixed.text)
            val content = board.snapshot()
            val area = canvas
            val start = Offset(area.left + area.width * .22f, area.top + area.height * .72f)
            val gesture = Gesture()
            try {
                gesture.send(MotionEvent.ACTION_DOWN, start)
                gesture.send(MotionEvent.ACTION_MOVE, start + Offset(110f, 35f))
                gesture.send(MotionEvent.ACTION_UP)
            } finally { if (!gesture.ended) gesture.send(MotionEvent.ACTION_CANCEL) }
            composeRule.waitForIdle()
            val after = position(fixed.text)
            assertEquals("次のnative pan x: $before → $after", before.x + 110f, after.x, 2f)
            assertEquals("次のnative pan y: $before → $after", before.y + 35f, after.y, 2f)
            assertEquals(content, board.snapshot())
        }
        fun waitSaved() {
            composeRule.waitUntil(10_000) {
                saves.get() == 1 && sessions.saveStateFor(1L, defaults).value == BoardSaveState.Idle &&
                    savedSnapshot() == board.snapshot()
            }
        }
        fun assertOneHistory(before: BoardSnapshot, after: BoardSnapshot) {
            assertEquals(1, saves.get())
            assertEquals(after, savedSnapshot())
            val camera = position(fixed.text)
            composeRule.runOnUiThread { assertTrue(board.undo()) }
            assertEquals(before, board.snapshot())
            assertFalse(board.canUndo)
            composeRule.waitForIdle()
            assertEquals("content Undoでcameraは戻さない", camera, position(fixed.text))
            composeRule.runOnUiThread { assertTrue(board.redo()) }
            assertEquals(after, board.snapshot())
            assertFalse(board.canRedo)
        }
        fun pinch(factor: Float) {
            // Small, distinct native contacts keep both pointers inside the canvas even at 300%.
            val anchor = position(moving.text) + Offset(4f, 4f)
            val gesture = Gesture()
            val initial = listOf(anchor - Offset(6f, 0f), anchor + Offset(6f, 0f))
            val changed = listOf(anchor - Offset(6f * factor, 0f), anchor + Offset(6f * factor, 0f))
            try {
                gesture.send(MotionEvent.ACTION_DOWN, initial.first())
                gesture.event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), initial)
                gesture.event(MotionEvent.ACTION_MOVE, changed)
                gesture.event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), changed)
                gesture.send(MotionEvent.ACTION_UP)
            } finally { if (!gesture.ended) gesture.send(MotionEvent.ACTION_CANCEL) }
            composeRule.waitForIdle()
        }
        fun send(action: Int, point: Offset, downTime: Long) {
            val origin = IntArray(2)
            scenario.onActivity { it.window.decorView.getLocationOnScreen(origin) }
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                point.x + origin[0], point.y + origin[1], 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        fun stationaryDrag(cancel: Boolean = false) {
            val selected = composeRule.onNodeWithContentDescription(moving.text).fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.StateDescription) == "選択中"
            if (!selected) composeRule.onNodeWithContentDescription(moving.text).performClick()
            val start = bounds("要素を移動").center
            val area = canvas
            val edge = Offset(area.right - 3f, area.top + area.height * .45f)
            val initial = board.snapshot()
            val fixedBefore = position(fixed.text)
            val downTime = SystemClock.uptimeMillis()
            var terminalSent = false
            var finalPreview = Offset.Zero
            composeRule.mainClock.autoAdvance = false
            try {
                send(MotionEvent.ACTION_DOWN, start, downTime)
                frames(2)
                send(MotionEvent.ACTION_MOVE, edge, downTime)
                frames(4)
                val targetBefore = position(moving.text)
                frames(90)
                val targetAfter = position(moving.text)
                val fixedAfter = position(fixed.text)
                assertTrue("静止した指の保持中にもcameraが進む", fixedAfter.x < fixedBefore.x - 100f)
                assertEquals("finger offset x", targetBefore.x, targetAfter.x, 3f)
                assertEquals("finger offset y", targetBefore.y, targetAfter.y, 3f)
                assertEquals("UP前の内容", initial, board.snapshot())
                assertEquals(0, saves.get())
                assertFalse(board.canUndo)
                // Return to the central stop band, then retain the move until release.
                val center = area.center
                send(MotionEvent.ACTION_MOVE, center, downTime)
                frames(4)
                val stopped = position(fixed.text)
                frames(20)
                assertEquals(stopped, position(fixed.text))
                finalPreview = position(moving.text)
                send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, center, downTime)
                terminalSent = true
                frames(3)
            } finally {
                if (!terminalSent) send(MotionEvent.ACTION_CANCEL, edge, downTime)
                composeRule.mainClock.autoAdvance = true
            }
            composeRule.waitForIdle()
            if (cancel) {
                assertEquals(initial, board.snapshot())
                assertEquals(0, saves.get())
                assertFalse(board.canUndo)
            } else {
                composeRule.waitUntil(10_000) {
                    saves.get() == 1 && sessions.saveStateFor(1L, BoardSnapshot()).value == BoardSaveState.Idle &&
                        runBlocking { database.canvasDao().elements(1L).map { it.toModel() } } == board.elements
                }
                val after = board.snapshot()
                val committedPosition = position(moving.text)
                assertEquals("preview→commit x", finalPreview.x, committedPosition.x, 2f)
                assertEquals("preview→commit y", finalPreview.y, committedPosition.y, 2f)
                assertNotEquals(initial, after)
                assertEquals(fixed, after.texts.single { it.id == fixed.id })
                assertEquals(1, saves.get())
                assertEquals(after.texts, runBlocking { database.canvasDao().elements(1L).map { it.toModel() } })
                composeRule.runOnUiThread { assertTrue(board.undo()) }
                assertEquals(initial, board.snapshot())
                assertFalse("一回のUndo", board.canUndo)
                composeRule.runOnUiThread { assertTrue(board.redo()) }
                assertEquals(after, board.snapshot())
                assertFalse(board.canRedo)
            }
        }
    }

    private fun withBoard(snapshot: BoardSnapshot = defaults, profile: EdgeAutoPanProfile? = null,
                          block: Harness.() -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val seed = CanvasDatabase.open(context)
        runBlocking {
            if (seed.canvasDao().board(1L) == null) seed.canvasDao().putBoard(BoardRow())
            seed.canvasDao().replaceAll(1L, snapshot.texts.map { TextElementRow.fromModel(1L, it) },
                snapshot.shapes.map { SpatialElementRow.fromModel(1L, it) },
                snapshot.arrows.map { ArrowElementRow.fromModel(1L, it) },
                snapshot.ink.flatMap { InkStrokeRow.fromModel(1L, it) })
        }
        seed.close()
        showBoardOneAtStartup(context)
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        val database = CanvasDatabase.open(context)
        try {
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithContentDescription("キャンバス").fetchSemanticsNodes().isNotEmpty()
            }
            lateinit var sessions: BoardSessionViewModel
            scenario.onActivity { sessions = ViewModelProvider(it)[BoardSessionViewModel::class.java] }
            val harness = Harness(scenario, sessions, database)
            if (profile != null) {
                scenario.onActivity { activity -> activity.setContent {
                    MaterialTheme {
                        CanvasScreen(harness.board, "Prototype",
                            sessions.textEditorFor(1L, snapshot), sessions.saveStateFor(1L, snapshot),
                            { sessions.requestSave(1L, it) }, { sessions.retrySave(1L) }, {}, {}, profile)
                    }
                } }
                composeRule.waitForIdle()
            }
            sessions.setSaveOperation { id, snapshot ->
                harness.saves.incrementAndGet()
                CanvasStore.get(context).save(id, snapshot)
            }
            harness.block()
        } finally {
            composeRule.mainClock.autoAdvance = true
            scenario.close()
            database.close()
        }
    }

    @Test fun stationaryPointerPreservesOffsetStopsInCenterAndCommitsOnce() = withBoard { stationaryDrag() }
    @Test fun nativeCancelDiscardsPreviewWithoutSavingOrHistory() = withBoard { stationaryDrag(cancel = true) }

    @Test fun backRejectsQueuedFrameAndSameTurnStaleMoveAndUp() = withBoard {
        val before = board.snapshot()
        val (start, edge) = startAndEdge()
        drag(start, edge) { gesture ->
            val stopped = position(fixed.text)
            scenario.onActivity {
                it.onBackPressedDispatcher.onBackPressed()
                gesture.event(MotionEvent.ACTION_MOVE, listOf(edge - Offset(40f, 0f)), activity = it)
                gesture.event(MotionEvent.ACTION_UP, listOf(edge), activity = it)
            }
            frames(30)
            assertEquals(stopped, position(fixed.text))
            assertEquals(before, board.snapshot())
        }
        assertUnchanged(before)
        assertNextPanWorks()
    }

    @Test fun secondFingerCancelsMoveAndHandsOffToPinchWithoutSaving() = withBoard {
        val before = board.snapshot()
        val (start, edge) = startAndEdge()
        drag(start, edge) { gesture ->
            val second = edge - Offset(280f, 100f)
            gesture.event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(edge, second))
            frames(3)
            val stopped = position(fixed.text)
            frames(25)
            assertEquals(stopped, position(fixed.text))
            gesture.event(MotionEvent.ACTION_MOVE, listOf(edge - Offset(60f, 0f), second - Offset(100f, 30f)))
            frames(3)
            assertNotEquals("pinchへhandoff", stopped, position(fixed.text))
            gesture.event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                listOf(edge - Offset(60f, 0f), second - Offset(100f, 30f)))
            gesture.send(MotionEvent.ACTION_UP)
            frames(3)
        }
        assertUnchanged(before)
        assertNextPanWorks()
    }

    @Test fun stylusTakeoverCancelsMoveAndCommitsOnlyTheStroke() = withBoard {
        val before = board.snapshot()
        val (start, edge) = startAndEdge()
        drag(start, edge) { gesture ->
            val pen = canvas.center
            gesture.event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                listOf(edge, pen), stylus = true)
            frames(3)
            val stopped = position(fixed.text)
            frames(25)
            assertEquals(stopped, position(fixed.text))
            gesture.event(MotionEvent.ACTION_MOVE, listOf(edge, pen + Offset(80f, 40f)), stylus = true)
            gesture.event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                listOf(edge, pen + Offset(80f, 40f)), stylus = true)
            gesture.send(MotionEvent.ACTION_UP)
            frames(3)
        }
        composeRule.waitUntil(10_000) { saves.get() == 1 && savedSnapshot().ink.isNotEmpty() }
        assertEquals(before.texts, board.elements)
        assertEquals(1, board.ink.size)
        assertEquals(InkInputType.STYLUS, board.ink.single().strokes.single().inputType)
        assertEquals(board.snapshot(), savedSnapshot())
        composeRule.runOnUiThread { assertTrue(board.undo()) }
        assertEquals(before, board.snapshot())
        assertFalse(board.canUndo)
    }

    @Test fun saveBlockCancelsMoveAndRejectsOldUpEvenBeforeRecomposition() {
        for (pendingOnly in listOf(false, true)) withBoard {
            val before = board.snapshot()
            val completion = if (pendingOnly) CompletableDeferred(Unit) else CompletableDeferred<Unit>()
            sessions.setSaveOperation { _, _ -> completion }
            val (start, edge) = startAndEdge()
            drag(start, edge) { gesture ->
                val stopped = position(fixed.text)
                scenario.onActivity {
                    val acknowledgement = sessions.requestSave(1L, before)
                    if (pendingOnly) {
                        assertEquals(BoardSaveState.Idle, sessions.saveStateFor(1L, before).value)
                        sessions.textEditorFor(1L, before).pendingDraftAcknowledgement.value = acknowledgement
                    }
                    gesture.event(MotionEvent.ACTION_UP, listOf(edge), activity = it)
                }
                frames(30)
                assertEquals(stopped, position(fixed.text))
                assertEquals(before, board.snapshot())
            }
            assertFalse(board.canUndo)
            assertEquals(0, saves.get())
            if (!pendingOnly) {
                completion.completeExceptionally(IllegalStateException("bounded save failure"))
                composeRule.waitUntil(5_000) { sessions.saveStateFor(1L, before).value is BoardSaveState.Failed }
            }
            assertEquals(before, savedSnapshot())
        }
    }

    @Test fun stopAndRecreationDoNotRestoreTheMoveOrTicker() {
        for (recreate in listOf(false, true)) withBoard {
            val before = board.snapshot()
            val (start, edge) = startAndEdge()
            drag(start, edge) { gesture ->
                if (recreate) scenario.recreate()
                else {
                    scenario.moveToState(Lifecycle.State.CREATED)
                    scenario.moveToState(Lifecycle.State.RESUMED)
                }
                frames(8)
                gesture.send(MotionEvent.ACTION_UP)
                frames(8)
                val stopped = position(fixed.text)
                frames(30)
                assertEquals(stopped, position(fixed.text))
            }
            assertUnchanged(before)
            assertNextPanWorks()
        }
    }

    @Test fun multiSelectionTranslatesTextShapeAndFreeArrowTogether() = withBoard(
        defaults.copy(shapes = listOf(rectangle), arrows = listOf(freeArrow))) {
        select(moving.text)
        action("四角: Movable", "選択に追加")
        action("矢印", "選択に追加")
        val before = board.snapshot()
        val start = bounds("要素を移動").center
        val edge = Offset(canvas.right - 3f, start.y)
        var preview = Offset.Zero
        drag(start, edge) { gesture ->
            frames(40)
            assertEquals(before, board.snapshot())
            assertEquals(0, saves.get())
            preview = position(moving.text)
            gesture.send(MotionEvent.ACTION_UP)
            frames(3)
        }
        waitSaved()
        val after = board.snapshot()
        val noteAfter = after.texts.single { it.id == moving.id }
        val dx = noteAfter.x - moving.x
        val dy = noteAfter.y - moving.y
        assertTrue(dx > 100f)
        assertEquals(rectangle.x + dx, after.shapes.single().x, .001f)
        assertEquals(rectangle.y + dy, after.shapes.single().y, .001f)
        val from = after.arrows.single().from as ArrowEnd.Free
        val to = after.arrows.single().to as ArrowEnd.Free
        assertEquals(750f + dx, from.x, .001f)
        assertEquals(1300f + dy, from.y, .001f)
        assertEquals(1000f + dx, to.x, .001f)
        assertEquals(1550f + dy, to.y, .001f)
        assertEquals(fixed, after.texts.single { it.id == fixed.id })
        assertEquals(preview.x, position(moving.text).x, 2f)
        assertEquals(preview.y, position(moving.text).y, 2f)
        assertOneHistory(before, after)
    }

    @Test fun regionMoveKeepsInitialNestedContentsInkAndAttachedArrowRules() {
        val region = ShapeElement(id = "edge-region", kind = ShapeKind.REGION,
            x = 400f, y = 1000f, width = 500f, height = 550f, name = "Cluster")
        val nested = ShapeElement(id = "edge-nested", kind = ShapeKind.REGION,
            x = 450f, y = 1150f, width = 300f, height = 300f, name = "Nested")
        val attached = ArrowElement(id = "edge-attached", from = ArrowEnd.Attached(moving.id, 1f, .5f),
            to = ArrowEnd.Attached(fixed.id, 0f, .5f))
        withBoard(defaults.copy(shapes = listOf(region, nested, rectangle),
            arrows = listOf(attached, freeArrow), ink = listOf(stroke))) {
            select("囲み: Cluster")
            val before = board.snapshot()
            val start = bounds("移動").center
            val edge = Offset(canvas.right - 3f, start.y)
            drag(start, edge) { gesture ->
                frames(50)
                assertEquals(before, board.snapshot())
                gesture.send(MotionEvent.ACTION_UP)
                frames(3)
            }
            waitSaved()
            val after = board.snapshot()
            val movedRegion = after.shapes.single { it.id == region.id }
            val dx = movedRegion.x - region.x
            val dy = movedRegion.y - region.y
            assertTrue(dx > 100f)
            assertEquals(moving.x + dx, after.texts.single { it.id == moving.id }.x, .001f)
            assertEquals(moving.y + dy, after.texts.single { it.id == moving.id }.y, .001f)
            for (shape in listOf(nested, rectangle)) {
                assertEquals(shape.x + dx, after.shapes.single { it.id == shape.id }.x, .001f)
                assertEquals(shape.y + dy, after.shapes.single { it.id == shape.id }.y, .001f)
            }
            stroke.strokes.single().points.zip(after.ink.single().strokes.single().points).forEach { (old, new) ->
                assertEquals(old.x + dx, new.x, .001f); assertEquals(old.y + dy, new.y, .001f)
                assertEquals(old.elapsedMillis, new.elapsedMillis)
            }
            assertEquals(fixed, after.texts.single { it.id == fixed.id })
            assertEquals("自由端の非選択矢印は固定", freeArrow, after.arrows.single { it.id == freeArrow.id })
            assertEquals("接続先へ描画時に追従", attached, after.arrows.single { it.id == attached.id })
            assertOneHistory(before, after)
            scenario.recreate()
            composeRule.waitForIdle()
            assertEquals(after, sessions.stateFor(1L, defaults).snapshot())
            assertEquals(after, savedSnapshot())
        }
    }

    @Test fun longPressMoveAdmitsTextShapeAndInkOnlyAfterSlop() {
        for ((label, snapshot) in listOf(
            moving.text to defaults,
            "四角: Movable" to defaults.copy(shapes = listOf(rectangle)),
            "ペンの線" to defaults.copy(ink = listOf(stroke)),
        )) withBoard(snapshot) {
            val before = board.snapshot()
            val start = bounds(label).center
            val edge = Offset(canvas.right - 3f, start.y)
            val gesture = Gesture()
            composeRule.mainClock.autoAdvance = false
            try {
                gesture.send(MotionEvent.ACTION_DOWN, start)
                SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 150L)
                frames(5)
                val camera = position(fixed.text)
                frames(20)
                assertEquals("長押し成立だけではpanしない", camera, position(fixed.text))
                gesture.send(MotionEvent.ACTION_MOVE, edge)
                frames(35)
                assertTrue(position(fixed.text).x < camera.x - 100f)
                assertEquals(before, board.snapshot())
                gesture.send(MotionEvent.ACTION_UP)
                frames(3)
            } finally {
                if (!gesture.ended) gesture.send(MotionEvent.ACTION_CANCEL)
                composeRule.mainClock.autoAdvance = true
            }
            composeRule.waitForIdle()
            waitSaved()
            assertEquals(fixed, board.elements.single { it.id == fixed.id })
            assertOneHistory(before, board.snapshot())
        }
    }

    @Test fun boundedProfilesCarryBeyondViewportAndStopBeforeDrop() {
        val results = JSONArray()
        for ((name, profile) in listOf("A" to EdgeAutoPanProfile.PrototypeA, "B" to EdgeAutoPanProfile.PrototypeB)) {
            for (direction in listOf("right", "down", "corner")) withBoard(profile = profile) {
                val before = board.snapshot()
                select(moving.text)
                val start = bounds("要素を移動").center
                val area = canvas
                val edge = when (direction) {
                    "right" -> Offset(area.right - 3f, area.center.y)
                    "down" -> Offset(area.center.x, area.bottom - 3f)
                    else -> Offset(area.right - 3f, area.bottom - 3f)
                }
                var travel = Offset.Zero
                var error = Offset.Zero
                var finalPreview = Offset.Zero
                drag(start, edge) { gesture ->
                    val fixedBefore = position(fixed.text)
                    val targetBefore = position(moving.text)
                    frames(180)
                    travel = position(fixed.text) - fixedBefore
                    error = position(moving.text) - targetBefore
                    if (direction != "down") assertTrue("一回でcanvas幅を超える", -travel.x > area.width)
                    if (direction != "right") assertTrue("一回でcanvas高さを超える", -travel.y > area.height)
                    assertEquals(0f, error.x, 3f); assertEquals(0f, error.y, 3f)
                    assertEquals(before, board.snapshot())
                    assertEquals(0, saves.get())
                    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                        PlatformTestStorageRegistry.getInstance().openOutputFile("edge-prototype-$name-$direction.png").use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                    }
                    gesture.send(MotionEvent.ACTION_MOVE, area.center)
                    frames(4)
                    val stopped = position(fixed.text)
                    frames(30)
                    assertEquals(stopped, position(fixed.text))
                    finalPreview = position(moving.text)
                    gesture.send(MotionEvent.ACTION_UP)
                    frames(3)
                }
                waitSaved()
                assertEquals(finalPreview.x, position(moving.text).x, 2f)
                assertEquals(finalPreview.y, position(moving.text).y, 2f)
                assertOneHistory(before, board.snapshot())
                results.put(JSONObject().put("profile", name).put("direction", direction)
                    .put("model", android.os.Build.MODEL).put("density", instrumentation.targetContext.resources.displayMetrics.density)
                    .put("canvasWidth", area.width).put("canvasHeight", area.height)
                    .put("holdFrames", 180).put("panX", travel.x).put("panY", travel.y)
                    .put("offsetErrorX", error.x).put("offsetErrorY", error.y)
                    .put("saveCount", saves.get()).put("centerStopDrift", 0))
            }
        }
        PlatformTestStorageRegistry.getInstance().openOutputFile("edge-auto-pan-prototype.json").use {
            it.write(results.toString(2).toByteArray(Charsets.UTF_8))
        }
    }

    @Test fun boundedProfilesKeepMultiSelectionTogetherOnDrop() {
        val results = JSONArray()
        for ((name, profile) in listOf("A" to EdgeAutoPanProfile.PrototypeA, "B" to EdgeAutoPanProfile.PrototypeB)) {
            withBoard(defaults.copy(shapes = listOf(rectangle), arrows = listOf(freeArrow)), profile) {
                select(moving.text)
                action("四角: Movable", "選択に追加")
                action("矢印", "選択に追加")
                val before = board.snapshot()
                val start = bounds("要素を移動").center
                val area = canvas
                val edge = Offset(area.right - 3f, start.y)
                var pan = 0f
                drag(start, edge) { gesture ->
                    val reference = position(fixed.text)
                    frames(90)
                    pan = position(fixed.text).x - reference.x
                    assertEquals(before, board.snapshot())
                    gesture.send(MotionEvent.ACTION_MOVE, area.center)
                    frames(4)
                    val stopped = position(fixed.text)
                    frames(20)
                    assertEquals(stopped, position(fixed.text))
                    gesture.send(MotionEvent.ACTION_UP)
                    frames(3)
                }
                waitSaved()
                val after = board.snapshot()
                val dx = after.texts.single { it.id == moving.id }.x - moving.x
                val dy = after.texts.single { it.id == moving.id }.y - moving.y
                assertEquals(rectangle.x + dx, after.shapes.single().x, .001f)
                assertEquals(rectangle.y + dy, after.shapes.single().y, .001f)
                val from = after.arrows.single().from as ArrowEnd.Free
                assertEquals(750f + dx, from.x, .001f)
                assertEquals(1300f + dy, from.y, .001f)
                assertOneHistory(before, after)
                results.put(JSONObject().put("profile", name).put("family", "multi")
                    .put("panX", pan).put("deltaWorldX", dx).put("deltaWorldY", dy)
                    .put("saveCount", saves.get()).put("relativeError", 0).put("centerStopDrift", 0))
            }
        }
        PlatformTestStorageRegistry.getInstance().openOutputFile("edge-auto-pan-multi-prototype.json").use {
            it.write(results.toString(2).toByteArray(Charsets.UTF_8))
        }
    }

    @Test fun pinchLimitsKeepTheMoveAnchorAndReleasePositionConsistent() {
        for (percent in listOf(15, 300)) withBoard {
            select(moving.text)
            if (percent == 15) pinch(.01f) else repeat(8) { pinch(2f) }
            val zoom = composeRule.onNode(hasContentDescription("倍率を切り替える、", substring = true))
                .fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
            assertTrue(zoom, zoom.contains("、$percent%"))
            stationaryDrag()
        }
    }

    @Test fun tapAndSlopJitterDoNotStartAutoPan() {
        withBoard {
            select(moving.text)
            val before = board.snapshot()
            val camera = position(fixed.text)
            val gesture = Gesture()
            gesture.send(MotionEvent.ACTION_DOWN, bounds("要素を移動").center)
            gesture.send(MotionEvent.ACTION_UP)
            composeRule.waitForIdle()
            composeRule.mainClock.autoAdvance = false
            try { frames(40); assertEquals(camera, position(fixed.text)) }
            finally { composeRule.mainClock.autoAdvance = true }
            assertUnchanged(before)
        }
        withBoard(defaults.copy(shapes = listOf(rectangle))) {
            select("四角: Movable")
            val before = board.snapshot()
            val start = bounds("移動").center
            val camera = position(fixed.text)
            val jitter = android.view.ViewConfiguration.get(instrumentation.targetContext).scaledTouchSlop * .25f
            val gesture = Gesture()
            composeRule.mainClock.autoAdvance = false
            try {
                gesture.send(MotionEvent.ACTION_DOWN, start)
                gesture.send(MotionEvent.ACTION_MOVE, start + Offset(jitter, 0f))
                frames(40)
                assertEquals(camera, position(fixed.text))
                assertEquals(before, board.snapshot())
                val preview = position("四角: Movable")
                gesture.send(MotionEvent.ACTION_UP)
                frames(3)
                assertEquals(preview.x, position("四角: Movable").x, 2f)
            } finally {
                if (!gesture.ended) gesture.send(MotionEvent.ACTION_CANCEL)
                composeRule.mainClock.autoAdvance = true
            }
            composeRule.waitForIdle()
            waitSaved()
            assertOneHistory(before, board.snapshot())
        }
    }

    @Test fun editorAndDiscardDialogDoNotAdmitTheMoveTicker() = withBoard {
        select(moving.text)
        composeRule.onNodeWithContentDescription(moving.text).performClick()
        composeRule.waitUntil(10_000) {
            var ready = false
            scenario.onActivity {
                val view = it.window.decorView.findFocus()
                val input = it.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ready = view != null && input.isActive(view) && input.isAcceptingText &&
                    it.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
            }
            ready
        }
        val before = board.snapshot()
        val area = canvas
        val start = Offset(area.right - 180f, area.top + area.height * .15f)
        val edge = Offset(area.right - 3f, start.y)
        drag(start, edge) { gesture ->
            val camera = position(fixed.text)
            frames(40)
            assertEquals(camera, position(fixed.text))
            gesture.send(MotionEvent.ACTION_UP)
            frames(3)
        }
        assertEquals(moving.text, sessions.textEditorFor(1L, before).draft.value?.text)
        composeRule.onNodeWithContentDescription("テキストを編集").performTextReplacement("Changed")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        composeRule.waitUntil(5_000) {
            var hidden = false
            scenario.onActivity { hidden = it.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == false }
            hidden
        }
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithText("編集内容を破棄しますか？").assertExists()
        val camera = position(fixed.text)
        composeRule.mainClock.autoAdvance = false
        try { frames(40); assertEquals(camera, position(fixed.text)) }
        finally { composeRule.mainClock.autoAdvance = true }
        assertUnchanged(before)
        composeRule.onNodeWithText("破棄する").performClick()
        composeRule.waitForIdle()
        assertUnchanged(before)
    }

    @Test fun panToolsAndGapDoNotStartTheMoveTicker() {
        for (tool in listOf("pan", "四角", "丸", "囲み", "矢印", "まとめて選ぶ", "ペン", "マーカー", "gap")) withBoard {
            val before = board.snapshot()
            if (tool != "pan" && tool != "gap") {
                composeRule.onNodeWithContentDescription("図形ツールを開く").performClick()
                composeRule.onNodeWithContentDescription(tool).performClick()
            }
            val area = canvas
            val start = Offset(area.left + area.width * .2f, area.top + area.height * .55f)
            val edge = Offset(area.right - 3f, start.y)
            val gesture = Gesture()
            composeRule.mainClock.autoAdvance = false
            try {
                gesture.send(MotionEvent.ACTION_DOWN, start)
                if (tool == "gap") SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 150L)
                frames(3)
                gesture.send(MotionEvent.ACTION_MOVE, edge)
                frames(4)
                val camera = position(fixed.text)
                frames(40)
                assertEquals("${tool}にはmove tickerを作らない", camera, position(fixed.text))
                assertEquals(before, board.snapshot())
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                gesture.send(MotionEvent.ACTION_CANCEL)
                frames(3)
            } finally {
                if (!gesture.ended) gesture.send(MotionEvent.ACTION_CANCEL)
                composeRule.mainClock.autoAdvance = true
            }
            composeRule.waitForIdle()
            assertUnchanged(before)
        }
    }

    @Test fun resizeAndArrowHandlesDoNotStartTheMoveTicker() {
        for (handle in listOf("サイズ変更", "始点を接続・付け替え", "終点を接続・付け替え", "曲がりを変更")) {
            withBoard(defaults.copy(shapes = listOf(rectangle), arrows = listOf(freeArrow))) {
                select(if (handle == "サイズ変更") "四角: Movable" else "矢印")
                val before = board.snapshot()
                val start = bounds(handle).center
                val edge = Offset(canvas.right - 3f, start.y)
                drag(start, edge) { gesture ->
                    val camera = position(fixed.text)
                    frames(40)
                    assertEquals("${handle}にはmove tickerを作らない", camera, position(fixed.text))
                    assertEquals(before, board.snapshot())
                    scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                    gesture.send(MotionEvent.ACTION_CANCEL)
                    frames(3)
                }
                assertUnchanged(before)
            }
        }
    }

    @Test fun accessibilityMoveRetainsItsOneStepAndDoesNotStartTheTicker() = withBoard {
        val before = board.snapshot()
        val camera = position(fixed.text)
        action(moving.text, "右に移動")
        waitSaved()
        assertEquals(moving.x + 16f, board.elements.single { it.id == moving.id }.x, .001f)
        assertEquals(camera, position(fixed.text))
        composeRule.mainClock.autoAdvance = false
        try { frames(40); assertEquals(camera, position(fixed.text)) }
        finally { composeRule.mainClock.autoAdvance = true }
        assertOneHistory(before, board.snapshot())
    }
}
