package com.thinkcanvas.canvas

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thinkcanvas.BoardSaveState
import com.thinkcanvas.MainActivity
import com.thinkcanvas.data.*
import com.thinkcanvas.image.seedImage
import com.thinkcanvas.test.PrSmoke
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 種類が混在した選択集合を、pointerとsemanticsの双方から観測する。 */
@RunWith(AndroidJUnit4::class)
class MultiSelectionTapTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val labels = listOf("Extra", "四角", "丸", "囲み: Group", "矢印", "ペンの線", "マーカーの線", "Picture")
    private val ids = listOf("extra", "rect", "ellipse", "region", "arrow", "pen", "marker", "image")

    private inner class Harness(val scenario: ActivityScenario<MainActivity>, val board: BoardState,
                                val editor: TextEditorSession, val navigation: ViewportHistory) {
        var saves = 0
        var shared = emptySet<String>()
        val before = board.snapshot()
        fun node(label: String) = rule.onAllNodesWithContentDescription(label).filter(hasClickAction()).onFirst()
        fun action(label: String, name: String) {
            val actions = node(label).fetchSemanticsNode().config[SemanticsActions.CustomActions]
            rule.runOnUiThread { assertTrue(actions.single { it.label == name }.action()) }
            rule.waitForIdle()
        }
        fun seedSelection() {
            action("First", "選択に追加")
            action("Second", "選択に追加")
            assertSelection(setOf("first", "second"))
        }
        fun click(label: String, pointer: Boolean) {
            if (!pointer) node(label).performClick()
            else {
                val canvas = rule.onNodeWithContentDescription("キャンバス")
                val origin = canvas.fetchSemanticsNode().boundsInRoot.topLeft
                val center = if (label == "囲み: Group") {
                    val region = board.shapes.single { it.id == "region" }
                    val (x, y) = navigation.viewportState.value.worldToScreen(region.x, region.y + region.height / 2)
                    origin + Offset(x, y)
                } else node(label).fetchSemanticsNode().boundsInRoot.center
                canvas.performTouchInput { click(center - origin) }
            }
            rule.waitForIdle()
        }
        fun assertSelection(expected: Set<String>) {
            rule.onNodeWithContentDescription("選択範囲を画像で共有").performClick()
            assertEquals(expected, shared)
            for ((label, id) in (listOf("First", "Second") + labels).zip(listOf("first", "second") + ids)) {
                val nodes = rule.onAllNodesWithContentDescription(label).filter(hasClickAction()).fetchSemanticsNodes()
                if (nodes.isNotEmpty()) assertEquals(label, if (id in expected) "選択中" else "未選択",
                    nodes.first().config[SemanticsProperties.StateDescription])
            }
        }
        fun assertUnchanged() {
            assertEquals(before, board.snapshot())
            assertEquals(0, saves)
            assertFalse(board.canUndo)
            assertFalse(board.canRedo)
            assertNull(editor.draft.value)
            assertNull(editor.regionNameDraft.value)
        }
    }

    private fun withBoard(block: Harness.() -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = seedImage(context, "Picture").copy(id = "image", x = 170f, y = 530f, width = 70f, height = 35f)
        val texts = listOf(TextElement("first", "First", x = 30f, y = 190f),
            TextElement("second", "Second", x = 170f, y = 190f), TextElement("extra", "Extra", x = 30f, y = 270f))
        val database = CanvasDatabase.open(context)
        runBlocking { database.canvasDao().replaceAll(1, texts.map { TextElementRow.fromModel(1, it) }, emptyList(), emptyList()) }
        database.close()
        showBoardOneAtStartup(context)
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("First").fetchSemanticsNodes().isNotEmpty() }
            val shapes = listOf(ShapeElement("rect", ShapeKind.RECTANGLE, 170f, 270f, 70f, 40f),
                ShapeElement("ellipse", ShapeKind.ELLIPSE, 30f, 350f, 70f, 40f),
                ShapeElement("region", ShapeKind.REGION, 170f, 350f, 75f, 70f, name = "Group"))
            fun stroke(id: String, kind: InkKind, x: Float, y: Float) = InkElement(id, kind, listOf(
                InkStroke(startedAt = 1, endedAt = 101, inputType = InkInputType.TOUCH,
                    points = listOf(InkPoint(x, y, 0), InkPoint(x + 70f, y, 100)))))
            val board = BoardState(texts, shapes, listOf(ArrowElement("arrow", ArrowEnd.Free(30f, 460f), ArrowEnd.Free(100f, 460f))),
                listOf(stroke("pen", InkKind.PEN, 170f, 460f), stroke("marker", InkKind.MARKER, 30f, 550f)), listOf(image))
            val editor = TextEditorSession()
            val navigation = ViewportHistory()
            val metrics = context.resources.displayMetrics
            navigation.resize(metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat(), metrics.density)
            navigation.initialize(Viewport(scale = metrics.density))
            val harness = Harness(scenario, board, editor, navigation)
            scenario.onActivity { activity -> activity.setContent { MaterialTheme {
                CanvasScreen(board, "Selection regression", editor, MutableStateFlow(BoardSaveState.Idle),
                    { harness.saves++; null }, {}, {}, { harness.shared = it }, viewportHistory = navigation)
            } } }
            rule.waitForIdle()
            harness.block()
        } finally { scenario.close() }
    }

    private fun exerciseKinds(pointer: Boolean) = withBoard {
        seedSelection()
        for ((label, id) in labels.zip(ids)) {
            click(label, pointer)
            assertSelection(setOf("first", "second", id))
            rule.onNodeWithText("3個を選択").assertExists()
            click(label, pointer)
            assertSelection(setOf("first", "second"))
            rule.onNodeWithText("3個を選択").assertDoesNotExist()
            rule.onNodeWithText("2個を選択").assertExists()
            assertUnchanged()
        }
    }

    @PrSmoke
    @Test fun pointerAddsAndRemovesEveryOrdinaryElementKindWithoutSaving() = exerciseKinds(pointer = true)

    @PrSmoke
    @Test fun semanticsAddsAndRemovesEveryOrdinaryElementKindWithoutSaving() = exerciseKinds(pointer = false)

    @PrSmoke
    @Test fun blankTapClearsSelectionAndItsPreviousCount() = withBoard {
        seedSelection()
        click("四角", pointer = false)
        assertSelection(setOf("first", "second", "rect"))
        val canvas = rule.onNodeWithContentDescription("キャンバス")
        canvas.performTouchInput { click(Offset(width * .85f, height * .75f)) }
        // Advance only the existing double-tap confirmation, not the 1.8s guidance expiry.
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        rule.onNodeWithContentDescription("選択範囲を画像で共有").assertDoesNotExist()
        rule.onNodeWithText("3個を選択").assertDoesNotExist()
        assertUnchanged()
    }

    @Test fun returningToSingleSelectionRestoresTextEditingAndRegionRename() = withBoard {
        seedSelection()
        click("Second", pointer = false)
        assertSelection(setOf("first"))
        click("First", pointer = true)
        assertEquals("first", editor.draft.value?.id)
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        click("囲み: Group", pointer = true)
        assertSelection(setOf("region"))
        click("囲み: Group", pointer = false)
        assertEquals("region", editor.regionNameDraft.value?.id)
        assertEquals(before, board.snapshot())
        assertEquals(0, saves)
        assertFalse(board.canUndo)
    }

    @Test fun farRegionStillNavigatesInsteadOfTogglingSelection() = withBoard {
        seedSelection()
        for (pointer in listOf(false, true)) {
            rule.runOnUiThread { navigation.viewportState.value = Viewport(scale = .2f, panX = 300f, panY = 600f) }
            rule.waitForIdle()
            val beforeView = navigation.viewportState.value
            click("囲み: Group", pointer)
            rule.waitUntil(5_000) { navigation.viewportState.value != beforeView }
            rule.waitForIdle()
            assertSelection(setOf("first", "second"))
            assertUnchanged()
        }
    }
}
