package com.thinkcanvas.canvas

import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thinkcanvas.MainActivity
import com.thinkcanvas.data.CanvasDatabase
import com.thinkcanvas.data.TextElementRow
import com.thinkcanvas.data.SpatialElementRow
import com.thinkcanvas.data.BoardRow
import com.thinkcanvas.data.showBoardOneAtStartup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SemanticNavigationTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    @Test fun openingFitKeepsFarBodyOnlyExtentAfterBodyBecomesHidden() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val database = CanvasDatabase.open(context)
        runBlocking {
            if (database.canvasDao().board(1) == null) database.canvasDao().putBoard(BoardRow())
            database.canvasDao().replaceAll(1, listOf(
                TextElementRow.fromModel(1, TextElement(id = "far-body-a", text = "First body", x = 120f, y = 120f)),
                TextElementRow.fromModel(1, TextElement(id = "far-body-b", text = "Second body", x = 2_100f, y = 1_100f)),
                TextElementRow.fromModel(1, TextElement(id = "far-body-c", text = "Third body", x = 4_200f, y = 2_300f)),
            ), emptyList(), emptyList())
        }
        database.close()
        showBoardOneAtStartup(context)
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            val zoomMatcher = hasContentDescription("倍率を切り替える、", substring = true)
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodes(zoomMatcher).fetchSemanticsNodes().firstOrNull()?.let { node ->
                    val description = (if (SemanticsProperties.ContentDescription in node.config)
                        node.config[SemanticsProperties.ContentDescription] else emptyList()).joinToString()
                    description.substringAfter("、", "").substringBefore('%').toIntOrNull()?.let { it < 50 } == true &&
                        composeRule.onAllNodes(hasContentDescription("First body") or hasText("First body"))
                            .fetchSemanticsNodes().isEmpty()
                } == true
            }
            composeRule.waitForIdle()
            val zoomNode = composeRule.onAllNodes(zoomMatcher).fetchSemanticsNodes().single()
            val zoom = (if (SemanticsProperties.ContentDescription in zoomNode.config)
                zoomNode.config[SemanticsProperties.ContentDescription] else emptyList()).joinToString()
            assertTrue("FAR-scale BODY-only content stays in the opening fit: $zoom",
                zoom.contains("%") && zoom.substringAfter("、", "").substringBefore('%').toInt() < 50)
            assertTrue("FAR still hides ordinary BODY text",
                composeRule.onAllNodes(hasContentDescription("First body") or hasText("First body"))
                    .fetchSemanticsNodes().isEmpty())
        } finally {
            scenario.close()
        }
    }

    @Test fun openingFitIncludesHiddenBodiesAlongsideShapes() {
        val scenario = openBoardWithContent(
            listOf(
                TextElement(id = "mixed-body-left", text = "Left body", x = 80f, y = 120f),
                TextElement(id = "mixed-body-right", text = "Right body", x = 4_000f, y = 1_600f),
            ), listOf(ShapeElement(id = "mixed-shape", kind = ShapeKind.RECTANGLE,
                x = 1_900f, y = 900f, width = 180f, height = 120f)))
        try {
            val zoom = awaitOpeningZoom()
            assertTrue("hidden BODY extents keep the mixed board fitted: $zoom",
                zoom.substringBefore('%').toInt() < 50)
            assertTrue("FAR still hides ordinary BODY text",
                composeRule.onAllNodes(hasContentDescription("Left body") or hasText("Left body"))
                    .fetchSemanticsNodes().isEmpty())
        } finally { scenario.close() }
    }

    @Test fun openingFitViewportSettlesAfterSemanticTierChanges() {
        val scenario = openBoardWithContent(
            listOf(
                TextElement(id = "settle-body-a", text = "Alpha", x = 100f, y = 200f),
                TextElement(id = "settle-body-b", text = "Beta", x = 1_600f, y = 1_100f),
                TextElement(id = "settle-body-c", text = "Gamma", x = 3_600f, y = 2_000f),
            ), emptyList())
        try {
            val settled = awaitOpeningZoom()
            repeat(8) {
                Thread.sleep(100)
                composeRule.waitForIdle()
                assertEquals("opening-fit does not bounce between semantic tiers", settled,
                    currentOpeningZoom())
            }
            assertTrue("iteration converges to a FAR opening fit: $settled",
                settled.substringBefore('%').toInt() < 50)
        } finally { scenario.close() }
    }

    @Test fun zoomCycleAndSearchControlsAreAccessibleWithoutSaving() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val cleanDatabase = CanvasDatabase.open(context)
        runBlocking {
            if (cleanDatabase.canvasDao().board(1) == null) cleanDatabase.canvasDao().putBoard(BoardRow())
            cleanDatabase.canvasDao().replaceAll(1, emptyList(), emptyList(), emptyList())
        }
        cleanDatabase.close()
        showBoardOneAtStartup(context)
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            fun waitForZoom(label: String) = awaitDescription("倍率を切り替える、$label")
            fun click(description: String) {
                awaitDescription(description, substring = true)
                composeRule.onNodeWithContentDescription(description, substring = true).performClick()
            }

            waitForZoom("100%  近")
            click("倍率を切り替える")
            waitForZoom("50%  中")
            click("倍率を切り替える")
            waitForZoom("25%  遠")
            click("倍率を切り替える")
            waitForZoom("100%  近")

            click("ボード内を検索")
            awaitDescription("ボード内を探す")
            awaitDescription("前の検索結果")
            awaitDescription("次の検索結果")
            click("検索を閉じる")
            awaitDescription("ボード内を検索")

            composeRule.onNodeWithContentDescription("キャンバス")
                .performTouchInput { doubleClick() }
            waitForZoom("50%  中")

            val database = CanvasDatabase.open(context)
            try {
                assertEquals(0, runBlocking { database.canvasDao().elements(1).size })
                assertEquals(0, runBlocking { database.canvasDao().spatialElements(1).size })
            } finally { database.close() }
        } finally { scenario.close() }
    }

    private fun openBoardWithContent(
        texts: List<TextElement>, shapes: List<ShapeElement>,
    ): ActivityScenario<MainActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = CanvasDatabase.open(context)
        runBlocking {
            if (database.canvasDao().board(1) == null) database.canvasDao().putBoard(BoardRow())
            database.canvasDao().replaceAll(1, texts.map { TextElementRow.fromModel(1, it) },
                shapes.map { SpatialElementRow.fromModel(1, it) }, emptyList())
        }
        database.close()
        showBoardOneAtStartup(context)
        return ActivityScenario.launch(Intent(context, MainActivity::class.java))
    }

    private fun currentOpeningZoom(): String? {
        val node = composeRule.onAllNodesWithContentDescription("倍率を切り替える、", substring = true)
            .fetchSemanticsNodes().firstOrNull() ?: return null
        val descriptions = if (SemanticsProperties.ContentDescription in node.config)
            node.config[SemanticsProperties.ContentDescription] else emptyList()
        return descriptions.firstOrNull()?.substringAfter("、", "")
    }

    private fun awaitOpeningZoom(): String {
        composeRule.waitUntil(10_000) {
            currentOpeningZoom()?.substringBefore('%')?.toIntOrNull()?.let { it < 50 } == true
        }
        composeRule.waitForIdle()
        return requireNotNull(currentOpeningZoom())
    }

    private fun awaitDescription(value: String, substring: Boolean = false) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithContentDescription(value, substring = substring)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun farRegionFitsAndSearchFindsSavedText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val body = TextElement(id = "search-body",
            text = "Idea note\nMore detail\nAnother line", x = 450f, y = 1040f)
        val secondBody = TextElement(id = "search-second", text = "Second idea", x = 900f, y = 1400f)
        val region = ShapeElement(id = "search-region", kind = ShapeKind.REGION,
            x = 400f, y = 1000f, width = 450f, height = 400f, name = "Cluster")
        val database = CanvasDatabase.open(context)
        runBlocking {
            if (database.canvasDao().board(1) == null) database.canvasDao().putBoard(BoardRow())
            database.canvasDao().replaceAll(1,
                listOf(body, secondBody).map { TextElementRow.fromModel(1, it) },
                listOf(SpatialElementRow.fromModel(1, region)), emptyList())
        }
        database.close()
        showBoardOneAtStartup(context)
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            fun click(prefix: String) {
                awaitDescription(prefix, substring = true)
                composeRule.onNodeWithContentDescription(prefix, substring = true).performClick()
            }
            awaitDescription("Idea note", substring = true)
            click("倍率を切り替える")
            awaitDescription("倍率を切り替える、50%  中")
            click("倍率を切り替える")
            awaitDescription("倍率を切り替える、25%  遠")
            assertTrue(composeRule.onAllNodesWithContentDescription("Idea note", substring = true)
                .fetchSemanticsNodes().isEmpty())
            click("ボード内を検索")
            awaitDescription("ボード内を探す")
            fun search(term: String) {
                val field = composeRule.onNodeWithContentDescription("ボード内を探す")
                field.performTextClearance()
                field.performTextInput(term)
            }
            search("Idea")
            awaitDescription("1件目、全2件")
            awaitDescription("Idea note", substring = true) // 遠景で隠れる本文も検索中は見える
            click("次の検索結果")
            awaitDescription("2件目、全2件")
            click("次の検索結果")
            awaitDescription("1件目、全2件")
            awaitDescription("倍率を切り替える、80%  近")
            click("倍率を切り替える")
            awaitDescription("倍率を切り替える、50%  中")
            awaitDescription("1件目、全2件")
            search("absent")
            awaitDescription("0件")
            click("検索を閉じる")
            click("倍率を切り替える")
            awaitDescription("倍率を切り替える、25%  遠")
            assertTrue(composeRule.onAllNodesWithContentDescription("Idea note", substring = true)
                .fetchSemanticsNodes().isEmpty())
            awaitDescription("囲み: Cluster")
            composeRule.onNode(hasContentDescription("囲み: Cluster") and hasClickAction())
                .performClick()
            awaitDescription("Idea note", substring = true)
            val persisted = CanvasDatabase.open(context)
            try {
                assertEquals(2, runBlocking { persisted.canvasDao().elements(1).size })
                assertEquals(1, runBlocking { persisted.canvasDao().spatialElements(1).size })
            } finally { persisted.close() }
        } finally { scenario.close() }
    }
}
