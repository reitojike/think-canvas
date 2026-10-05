package com.thinkcanvas.canvas

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import com.thinkcanvas.BoardSessionViewModel
import com.thinkcanvas.BoardSaveState
import com.thinkcanvas.MainActivity
import com.thinkcanvas.R
import com.thinkcanvas.data.BoardRow
import com.thinkcanvas.data.CanvasDatabase
import com.thinkcanvas.data.TextElementRow
import com.thinkcanvas.data.SpatialElementRow
import com.thinkcanvas.data.showBoardOneAtStartup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Spec 002 の long-press release / long-press drag の判定を、実 pointer 入力で確認する。 */
@RunWith(AndroidJUnit4::class)
class LongPressGestureTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    private val leftText = "Left note"
    private val rightText = "Right note"
    private val leftX = 200f
    private val rightX = 1_400f

    private class Harness(
        val context: Context,
        val scenario: ActivityScenario<MainActivity>,
        val database: CanvasDatabase,
        val sessions: BoardSessionViewModel,
        val feedback: MutableList<HapticFeedbackType>,
    )

    private fun withBoard(shapes: List<ShapeElement> = emptyList(), block: Harness.() -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val seed = CanvasDatabase.open(context)
        runBlocking {
            if (seed.canvasDao().board(1) == null) seed.canvasDao().putBoard(BoardRow())
            seed.canvasDao().replaceAll(1, listOf(
                TextElementRow.fromModel(1, TextElement(id = "left", text = leftText, x = leftX, y = 300f)),
                TextElementRow.fromModel(1, TextElement(id = "right", text = rightText, x = rightX, y = 300f)),
            ), shapes.map { SpatialElementRow.fromModel(1L, it) }, emptyList())
        }
        seed.close()
        showBoardOneAtStartup(context)
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        val database = CanvasDatabase.open(context)
        try {
            // board の復元は Compose の idle 管理外の IO を含むため、seed した内容の表示を待つ。
            composeRule.waitUntil(10_000) {
                listOf(leftText, rightText).all {
                    composeRule.onAllNodesWithContentDescription(it).fetchSemanticsNodes().isNotEmpty()
                }
            }
            composeRule.waitForIdle()
            lateinit var sessions: BoardSessionViewModel
            val feedback = CopyOnWriteArrayList<HapticFeedbackType>()
            val recorder = object : HapticFeedback {
                override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                    feedback.add(hapticFeedbackType)
                }
            }
            scenario.onActivity { activity ->
                sessions = ViewModelProvider(activity)[BoardSessionViewModel::class.java]
                activity.setContent {
                    CompositionLocalProvider(LocalHapticFeedback provides recorder) {
                        MaterialTheme {
                            val initial = BoardSnapshot()
                            CanvasScreen(sessions.stateFor(1L, initial), "Haptic regression",
                                sessions.textEditorFor(1L, initial), sessions.saveStateFor(1L, initial),
                                { sessions.requestSave(1L, it) }, { sessions.retrySave(1L) }, {}, {},
                                viewportHistory = sessions.viewportHistoryFor(1L, initial))
                        }
                    }
                }
            }
            composeRule.waitForIdle()
            Harness(context, scenario, database, sessions, feedback).block()
        } finally {
            database.close()
            scenario.close()
        }
    }

    private fun Harness.rows() = runBlocking { database.canvasDao().elements(1) }

    private fun Harness.screenLeftTop(text: String): Pair<Float, Float> {
        val origin = IntArray(2)
        scenario.onActivity { it.window.decorView.getLocationOnScreen(origin) }
        val bounds = composeRule.onNodeWithContentDescription(text).fetchSemanticsNode().boundsInWindow
        return (origin[0] + bounds.left) to (origin[1] + bounds.top)
    }

    private fun Harness.centerOf(text: String): Pair<Float, Float> {
        val origin = IntArray(2)
        scenario.onActivity { it.window.decorView.getLocationOnScreen(origin) }
        val bounds = composeRule.onNodeWithContentDescription(text).fetchSemanticsNode().boundsInWindow
        return (origin[0] + bounds.center.x) to (origin[1] + bounds.center.y)
    }

    /** 現在の画面倍率。2 要素の world 間隔と画面上の間隔から求める。 */
    private fun Harness.scale(): Float =
        (screenLeftTop(rightText).first - screenLeftTop(leftText).first) / (rightX - leftX)

    private fun Harness.blankPoint(): Pair<Float, Float> {
        val a = centerOf(leftText)
        val b = centerOf(rightText)
        return ((a.first + b.first) / 2f) to a.second
    }

    /** DOWN → long-press timeout 経過 → [steps] の MOVE → UP を touchscreen の指として注入する。 */
    private fun Harness.longPress(
        down: Pair<Float, Float>,
        steps: List<Pair<Float, Float>>,
        whileHeld: () -> Unit = {},
        held: Boolean = true,
        toolType: Int = MotionEvent.TOOL_TYPE_FINGER,
        canceled: Boolean = false,
    ) {
        composeRule.waitUntil(10_000) { sessions.saveStateFor(1L, BoardSnapshot()).value == BoardSaveState.Idle }
        feedback.clear()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var downTime = 0L
        fun send(action: Int, position: Pair<Float, Float>) {
            val properties = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0
                this.toolType = toolType
            })
            val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
                x = position.first
                y = position.second
                pressure = 1f
                size = 1f
            })
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) downTime = now
            val event = MotionEvent.obtain(downTime, now, action, 1, properties, coordinates,
                0, 0, 1f, 1f, 0, 0,
                if (toolType == MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS
                else InputDevice.SOURCE_TOUCHSCREEN, 0)
            assertTrue(automation.injectInputEvent(event, true))
            event.recycle()
            Thread.sleep(35)
        }
        send(MotionEvent.ACTION_DOWN, down)
        // 長押し成立を待つ。余裕は test の送信間隔であり、製品の閾値ではない。
        if (held) Thread.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 200L)
        whileHeld()
        steps.forEach { send(MotionEvent.ACTION_MOVE, it) }
        send(if (canceled) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, steps.lastOrNull() ?: down)
        composeRule.waitForIdle()
    }

    private fun Harness.slop() = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private fun Harness.jitter(from: Pair<Float, Float>) = listOf(
        (from.first + slop() / 3f) to from.second,
        (from.first + slop() / 3f) to (from.second - slop() / 4f),
        from,
    )

    private fun Harness.dragSteps(from: Pair<Float, Float>, distance: Float) =
        (1..8).map { (from.first + distance * it / 8f) to from.second }

    private fun Harness.assertNoTextDraft() {
        listOf(R.string.new_text, R.string.edit_text).forEach {
            assertEquals(0, composeRule.onAllNodesWithContentDescription(context.getString(it))
                .fetchSemanticsNodes().size)
        }
    }

    private fun Harness.click(description: String) {
        composeRule.waitUntil(10_000) { sessions.saveStateFor(1L, BoardSnapshot()).value == BoardSaveState.Idle }
        composeRule.onNodeWithContentDescription(description).performClick()
        composeRule.waitForIdle()
    }

    private fun Harness.awaitRows(predicate: (List<TextElementRow>) -> Boolean) {
        composeRule.waitUntil(10_000) { predicate(rows()) }
    }

    @Test
    fun elementLongPressReleaseOpensMenuAndDeleteIsReachable() = withBoard {
        val before = rows()
        val target = centerOf(leftText)
        val leftBefore = screenLeftTop(leftText)
        longPress(target, jitter(target))
        assertEquals(listOf(HapticFeedbackType.LongPress), feedback.toList())
        assertEquals(before, rows())
        assertEquals(leftBefore, screenLeftTop(leftText))
        assertNoTextDraft()
        composeRule.onNodeWithContentDescription(context.getString(R.string.menu_delete)).assertExists()
        click(context.getString(R.string.menu_delete))
        awaitRows { it.map(TextElementRow::id) == listOf("right") }
        click(context.getString(R.string.undo))
        awaitRows { it == before }
    }

    @Test
    fun elementLongPressDragMovesAndUndoRedoApply() = withBoard {
        val before = rows()
        val scale = scale()
        val target = centerOf(leftText)
        val distance = slop() * 5f
        longPress(target, dragSteps(target, distance))
        assertEquals(listOf(HapticFeedbackType.LongPress), feedback.toList())
        awaitRows { rows -> rows.first { it.id == "left" }.x != leftX }
        val moved = rows()
        assertEquals(leftX + distance / scale, moved.first { it.id == "left" }.x, 1f)
        assertEquals(300f, moved.first { it.id == "left" }.y, 1f)
        assertEquals(before.first { it.id == "right" }, moved.first { it.id == "right" })
        assertEquals(0, composeRule.onAllNodesWithContentDescription(
            context.getString(R.string.menu_delete)).fetchSemanticsNodes().size)
        click(context.getString(R.string.undo))
        awaitRows { it == before }
        click(context.getString(R.string.redo))
        awaitRows { it == moved }
    }

    @Test
    fun blankLongPressReleaseDoesNothing() = withBoard {
        val before = rows()
        val leftBefore = screenLeftTop(leftText)
        val rightBefore = screenLeftTop(rightText)
        val point = blankPoint()
        longPress(point, jitter(point), whileHeld = {
            // 長押し成立は gap preview ではなく案内表示で視認できる（FR-013）。
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("ドラッグして余白を作る").fetchSemanticsNodes().isNotEmpty()
            }
        })
        assertEquals(listOf(HapticFeedbackType.LongPress), feedback.toList())
        Thread.sleep(500)
        assertEquals(before, rows())
        assertEquals(leftBefore, screenLeftTop(leftText))
        assertEquals(rightBefore, screenLeftTop(rightText))
        assertNoTextDraft()
        assertEquals(0, composeRule.onAllNodesWithContentDescription(
            context.getString(R.string.menu_delete)).fetchSemanticsNodes().size)
    }

    @Test
    fun blankLongPressDragInsertsGapAsOneUndoableOperation() = withBoard {
        val before = rows()
        val scale = scale()
        val point = blankPoint()
        val distance = slop() * 5f
        longPress(point, dragSteps(point, distance))
        assertEquals(listOf(HapticFeedbackType.LongPress), feedback.toList())
        awaitRows { rows -> rows.first { it.id == "right" }.x != rightX }
        val after = rows()
        assertEquals(before.first { it.id == "left" }, after.first { it.id == "left" })
        assertEquals(rightX + distance / scale, after.first { it.id == "right" }.x, 1f)
        assertEquals(300f, after.first { it.id == "right" }.y, 1f)
        click(context.getString(R.string.undo))
        awaitRows { it == before }
        click(context.getString(R.string.redo))
        awaitRows { it == after }
    }

    @Test
    fun touchAndStylusStrokeCompletionIsSilentAndUndoable() = withBoard {
        val board = sessions.stateFor(1L, BoardSnapshot())
        val before = board.snapshot()
        click(context.getString(R.string.tool_open))
        click("ペン")
        val point = blankPoint()
        listOf(MotionEvent.TOOL_TYPE_FINGER, MotionEvent.TOOL_TYPE_STYLUS).forEachIndexed { index, input ->
            longPress(point, dragSteps(point, slop() * 5f), held = false, toolType = input)
            composeRule.waitUntil(10_000) {
                runBlocking { database.canvasDao().inkStrokes(1L).size } == index + 1
            }
            assertEquals(emptyList<HapticFeedbackType>(), feedback.toList())
        }
        val after = board.snapshot()
        assertEquals(listOf(InkInputType.TOUCH, InkInputType.STYLUS),
            after.ink.flatMap { it.strokes }.map { it.inputType })
        click(context.getString(R.string.undo))
        click(context.getString(R.string.undo))
        composeRule.waitUntil(10_000) { board.snapshot() == before }
        click(context.getString(R.string.redo))
        click(context.getString(R.string.redo))
        composeRule.waitUntil(10_000) { board.snapshot() == after }
    }

    @Test
    fun shapeCreationConfirmsOnceAndPreservesUndoRedo() = withBoard {
        val board = sessions.stateFor(1L, BoardSnapshot())
        val before = board.snapshot()
        click(context.getString(R.string.tool_open))
        click(context.getString(R.string.tool_rectangle))
        val point = blankPoint()
        longPress(point, dragSteps(point, slop() * 5f), held = false)
        composeRule.waitUntil(10_000) { runBlocking { database.canvasDao().spatialElements(1L).size } == 1 }
        assertEquals(listOf(HapticFeedbackType.Confirm), feedback.toList())
        val after = board.snapshot()
        assertEquals(ShapeKind.RECTANGLE, after.shapes.single().kind)
        click(context.getString(R.string.undo))
        composeRule.waitUntil(10_000) { board.snapshot() == before }
        click(context.getString(R.string.redo))
        composeRule.waitUntil(10_000) { board.snapshot() == after }
    }

    @Test
    fun arrowCreationFailureAndCancellationStaySilent() = withBoard {
        val board = sessions.stateFor(1L, BoardSnapshot())
        val before = board.snapshot()
        click(context.getString(R.string.tool_open))
        click(context.getString(R.string.tool_arrow))
        val point = blankPoint()
        longPress(point, emptyList(), held = false)
        assertEquals(emptyList<HapticFeedbackType>(), feedback.toList())
        assertEquals(before, board.snapshot())
        click(context.getString(R.string.tool_open))
        click(context.getString(R.string.tool_arrow))
        longPress(point, dragSteps(point, slop() * 5f), held = false, canceled = true)
        assertEquals(emptyList<HapticFeedbackType>(), feedback.toList())
        assertEquals(before, board.snapshot())
    }

    @Test
    fun arrowCreationAndEndpointChangesConfirmOnce() = withBoard {
        val board = sessions.stateFor(1L, BoardSnapshot())
        click(context.getString(R.string.tool_open))
        click(context.getString(R.string.tool_arrow))
        val point = blankPoint()
        // 端点と曲げhandleの48dp hit領域が重ならない長さで、端点操作だけを検証する。
        longPress(point, dragSteps(point, slop() * 12f), held = false)
        composeRule.waitUntil(10_000) { runBlocking { database.canvasDao().arrows(1L).size } == 1 }
        assertEquals(1, board.arrows.size)
        assertEquals(listOf(HapticFeedbackType.Confirm), feedback.toList())
        val created = board.snapshot()
        composeRule.waitUntil(10_000) { sessions.saveStateFor(1L, BoardSnapshot()).value == BoardSaveState.Idle }
        val endpoint = centerOf("終点を接続・付け替え")
        longPress(endpoint, dragSteps(endpoint, slop() * 5f), held = false)
        composeRule.waitUntil(10_000) {
            runBlocking { database.canvasDao().arrows(1L).single().toModel() } == board.arrows.single()
        }
        assertTrue(created.arrows.single().to != board.arrows.single().to)
        assertEquals(listOf(HapticFeedbackType.Confirm), feedback.toList())
        val changed = board.snapshot()
        composeRule.waitUntil(10_000) { sessions.saveStateFor(1L, BoardSnapshot()).value == BoardSaveState.Idle }
        val canceledEndpoint = centerOf("終点を接続・付け替え")
        longPress(canceledEndpoint, emptyList(), held = false, canceled = true)
        assertEquals(changed, board.snapshot())
        assertEquals(emptyList<HapticFeedbackType>(), feedback.toList())
        click(context.getString(R.string.undo))
        composeRule.waitUntil(10_000) { board.snapshot() == created }
        click(context.getString(R.string.redo))
        composeRule.waitUntil(10_000) { board.snapshot() == changed }
    }

    @Test
    fun attachedArrowCreationConfirmsWithoutDuplicateFeedback() = withBoard {
        val board = sessions.stateFor(1L, BoardSnapshot())
        click(context.getString(R.string.tool_open))
        click(context.getString(R.string.tool_arrow))
        val start = centerOf(leftText)
        longPress(start, listOf(blankPoint()), held = false)
        composeRule.waitUntil(10_000) { runBlocking { database.canvasDao().arrows(1L).size } == 1 }
        assertTrue(board.arrows.single().from is ArrowEnd.Attached)
        assertEquals(listOf(HapticFeedbackType.Confirm), feedback.toList())
    }

    @Test
    fun ellipseCreationConfirmsOnce() = assertShapeCreationFeedback(R.string.tool_ellipse, ShapeKind.ELLIPSE)

    @Test
    fun regionCreationConfirmsOnce() = assertShapeCreationFeedback(R.string.tool_region, ShapeKind.REGION)

    private fun assertShapeCreationFeedback(tool: Int, kind: ShapeKind) = withBoard {
        val board = sessions.stateFor(1L, BoardSnapshot())
        click(context.getString(R.string.tool_open))
        click(context.getString(tool))
        val point = blankPoint()
        longPress(point, dragSteps(point, slop() * 5f), held = false)
        composeRule.waitUntil(10_000) { runBlocking { database.canvasDao().spatialElements(1L).size } == 1 }
        assertEquals(kind, board.shapes.single().kind)
        assertEquals(listOf(HapticFeedbackType.Confirm), feedback.toList())
    }

    @Test
    fun regionEntryAndExitKeepGuidanceWithoutExtraFeedback() = withBoard(shapes = listOf(
        ShapeElement(id = "region", kind = ShapeKind.REGION, name = "Cluster",
            x = 600f, y = 220f, width = 600f, height = 300f),
    )) {
        val before = rows()
        val target = centerOf(leftText)
        val distance = 600f * scale()
        longPress(target, dragSteps(target, distance))
        awaitRows { it.first { row -> row.id == "left" }.x > 700f }
        assertEquals(listOf(HapticFeedbackType.LongPress), feedback.toList())
        composeRule.onAllNodesWithText("Clusterに入りました").fetchSemanticsNodes().also { assertTrue(it.isNotEmpty()) }
        composeRule.waitUntil(10_000) { sessions.saveStateFor(1L, BoardSnapshot()).value == BoardSaveState.Idle }
        val movedTarget = centerOf(leftText)
        longPress(movedTarget, dragSteps(movedTarget, -distance))
        awaitRows { it.first { row -> row.id == "left" }.x < 300f }
        assertEquals(listOf(HapticFeedbackType.LongPress), feedback.toList())
        composeRule.onAllNodesWithText("Clusterから出ました").fetchSemanticsNodes().also { assertTrue(it.isNotEmpty()) }
        click(context.getString(R.string.undo))
        click(context.getString(R.string.undo))
        awaitRows { it == before }
    }
}
