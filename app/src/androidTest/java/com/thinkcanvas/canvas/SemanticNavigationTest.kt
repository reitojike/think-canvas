package com.thinkcanvas.canvas

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.platform.app.InstrumentationRegistry
import com.thinkcanvas.MainActivity
import com.thinkcanvas.data.CanvasDatabase
import com.thinkcanvas.data.TextElementRow
import com.thinkcanvas.data.SpatialElementRow
import com.thinkcanvas.data.BoardRow
import com.thinkcanvas.data.showBoardOneAtStartup
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matcher
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
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cleanDatabase = CanvasDatabase.open(context)
        runBlocking {
            if (cleanDatabase.canvasDao().board(1) == null) cleanDatabase.canvasDao().putBoard(BoardRow())
            cleanDatabase.canvasDao().replaceAll(1, emptyList(), emptyList(), emptyList())
        }
        cleanDatabase.close()
        showBoardOneAtStartup(context)
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            awaitInitialZoom("100%  近")
            assertA11yActionable("倍率を切り替える、100%  近")
            activateViaSemantics("倍率を切り替える")
            waitForZoom("50%  中")
            assertA11yActionable("倍率を切り替える、50%  中")
            activateViaSemantics("倍率を切り替える")
            waitForZoom("25%  遠")
            assertA11yActionable("倍率を切り替える、25%  遠")
            activateViaSemantics("倍率を切り替える")
            waitForZoom("100%  近")

            assertA11yActionable("ボード内を検索")
            activateViaSemantics("ボード内を検索")
            awaitA11y("ボード内を探す")
            awaitEditableA11y()
            awaitA11y("前の検索結果")
            awaitA11y("次の検索結果")
            assertA11yActionable("検索を閉じる")
            activateViaSemantics("検索を閉じる")
            awaitA11y("ボード内を検索")

            onView(isRoot()).perform(blankDoubleTap())
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

    private fun findA11y(
        node: AccessibilityNodeInfo?, prefix: String, clickable: Boolean,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.contentDescription?.toString()?.startsWith(prefix) == true &&
            (!clickable || node.isClickable)) return node
        for (index in 0 until node.childCount) {
            findA11y(node.getChild(index), prefix, clickable)?.let { return it }
        }
        return null
    }

    private fun awaitInitialZoom(label: String) {
        val prefix = "倍率を切り替える、$label"
        try {
            composeRule.waitUntil(20_000) {
                composeRule.onAllNodesWithContentDescription(prefix)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (cause: ComposeTimeoutException) {
            throw AssertionError("初期Compose UIが ready になりません: $label", cause)
        }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 20_000L
        for (observation in 0 until 40) {
            if (observation > 0 && SystemClock.uptimeMillis() >= deadline) break
            instrumentation.waitForIdleSync()
            if (findA11y(instrumentation.uiAutomation.rootInActiveWindow, prefix, false) != null) return
            val remaining = deadline - SystemClock.uptimeMillis()
            if (remaining <= 0L) break
            if (observation < 39) Thread.sleep(minOf(500L, remaining))
        }
        error("初期platform accessibilityが ready になりません: $label")
    }

    private fun waitForZoom(label: String) {
        val prefix = "倍率を切り替える、$label"
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            // waitUntil advances the Compose test clock; the platform tree stays the oracle.
            composeRule.waitUntil(5_000) {
                instrumentation.waitForIdleSync()
                findA11y(instrumentation.uiAutomation.rootInActiveWindow, prefix, false) != null
            }
        } catch (cause: ComposeTimeoutException) {
            throw IllegalStateException("倍率表示が $label になりません", cause)
        }
    }

    private fun awaitA11y(
        prefix: String, clickable: Boolean = false,
        attempts: Int = 40, pollMillis: Long = 100,
    ): AccessibilityNodeInfo {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(attempts) {
            instrumentation.waitForIdleSync()
            findA11y(instrumentation.uiAutomation.rootInActiveWindow, prefix, clickable)
                ?.let { return it }
            Thread.sleep(pollMillis)
        }
        error("Accessibility node が見つかりません: $prefix; 現在の倍率=${currentOpeningZoom()}")
    }

    private fun awaitCanvasAfterSearchIme(scenario: ActivityScenario<MainActivity>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var previousBounds: Rect? = null
        var stableBounds = 0
        composeRule.waitUntil(5_000) {
            var imeHidden = false
            scenario.onActivity {
                val insets = checkNotNull(it.window.decorView.rootWindowInsets)
                imeHidden = !insets.isVisible(WindowInsets.Type.ime()) &&
                    insets.getInsets(WindowInsets.Type.ime()).bottom == 0
            }
            val bounds = Rect()
            findA11y(instrumentation.uiAutomation.rootInActiveWindow, "キャンバス", false)
                ?.getBoundsInScreen(bounds)
            stableBounds = if (imeHidden && !bounds.isEmpty && bounds == previousBounds)
                stableBounds + 1 else 0
            previousBounds = bounds
            stableBounds >= 2
        }
        composeRule.waitForIdle()
    }

    private fun findEditableA11y(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (index in 0 until node.childCount) {
            findEditableA11y(node.getChild(index))?.let { return it }
        }
        return null
    }

    private fun awaitEditableA11y(attempts: Int = 40): AccessibilityNodeInfo {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(attempts) {
            instrumentation.waitForIdleSync()
            findEditableA11y(instrumentation.uiAutomation.rootInActiveWindow)?.let { return it }
            Thread.sleep(100)
        }
        error("検索入力欄が見つかりません")
    }

    // Layer A: the platform tree must expose the control as actionable; no platform action is performed.
    private fun assertA11yActionable(prefix: String) {
        var node: AccessibilityNodeInfo? = awaitA11y(prefix)
        while (node != null && !node.isClickable) node = node.parent
        val actionable = checkNotNull(node) { "$prefix に clickable な platform node がありません" }
        assertTrue("$prefix は有効", actionable.isEnabled)
        assertTrue("$prefix は利用者に見える", actionable.isVisibleToUser)
        assertTrue("$prefix は ACTION_CLICK を公開する",
            actionable.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
    }

    // Layer B: run the app's accessibility OnClick semantics action; the platform tree checks the result.
    private fun activateViaSemantics(prefix: String) {
        composeRule.onNode(hasContentDescription(prefix, substring = true) and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
    }

    // Layer A: the platform tree must expose a fresh, editable search field with ACTION_SET_TEXT; no platform action is performed.
    private fun assertA11ySetTextActionable() {
        val editable = awaitEditableA11y()
        assertTrue("検索欄は有効", editable.isEnabled)
        assertTrue("検索欄は利用者に見える", editable.isVisibleToUser)
        assertTrue("検索欄は ACTION_SET_TEXT を公開する",
            editable.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT })
    }

    // Layer B: run the app's SetText semantics action; the platform tree checks the result.
    private fun replaceSearchTextViaSemantics(term: String) {
        composeRule.onNode(hasSetTextAction()).performTextReplacement(term)
        composeRule.waitForIdle()
    }

    // One deterministic touchscreen double-tap: a single injectMotionEventSequence with DOWN/UP at 0/40/80/120ms.
    // Timestamps end at "now" so no event is stamped in the future.
    private fun blankDoubleTap() = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isRoot()
        override fun getDescription() = "blank-canvas double tap as one motion event sequence"
        override fun perform(uiController: UiController, view: View) {
            val location = IntArray(2).also(view::getLocationOnScreen)
            val tapX = location[0] + view.width * 0.5f
            val tapY = location[1] + view.height * 0.38f
            val start = SystemClock.uptimeMillis() - 120
            val events = listOf(
                Triple(start, start, MotionEvent.ACTION_DOWN),
                Triple(start, start + 40, MotionEvent.ACTION_UP),
                Triple(start + 80, start + 80, MotionEvent.ACTION_DOWN),
                Triple(start + 80, start + 120, MotionEvent.ACTION_UP),
            ).map { (downTime, eventTime, action) ->
                val properties = arrayOf(MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                })
                val coords = arrayOf(MotionEvent.PointerCoords().apply {
                    x = tapX
                    y = tapY
                    pressure = 1f
                    size = 1f
                })
                MotionEvent.obtain(
                    downTime, eventTime, action, 1, properties, coords,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
                )
            }
            try {
                assertTrue("double tap の motion event sequence を注入できる",
                    uiController.injectMotionEventSequence(events))
            } finally { events.forEach { it.recycle() } }
        }
    }

    private fun clickA11y(prefix: String, clickable: Boolean = false, attempts: Int = 40) {
        var node: AccessibilityNodeInfo? = awaitA11y(prefix, clickable = clickable, attempts = attempts)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("$prefix を支援技術から操作できる",
            node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
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
            awaitDescription("Idea note", substring = true)
            assertA11yActionable("倍率を切り替える")
            activateViaSemantics("倍率を切り替える")
            awaitA11y("倍率を切り替える、50%  中")
            assertA11yActionable("倍率を切り替える")
            activateViaSemantics("倍率を切り替える")
            awaitA11y("倍率を切り替える、25%  遠")
            assertTrue(composeRule.onAllNodesWithContentDescription("Idea note", substring = true)
                .fetchSemanticsNodes().isEmpty())
            assertA11yActionable("ボード内を検索")
            activateViaSemantics("ボード内を検索")
            awaitA11y("ボード内を探す")
            assertA11ySetTextActionable()
            replaceSearchTextViaSemantics("Idea")
            awaitA11y("1件目、全2件")
            awaitDescription("Idea note", substring = true) // 遠景で隠れる本文も検索中は見える
            assertA11yActionable("次の検索結果")
            activateViaSemantics("次の検索結果")
            awaitA11y("2件目、全2件")
            assertA11yActionable("前の検索結果")
            activateViaSemantics("前の検索結果")
            awaitA11y("1件目、全2件")
            assertA11yActionable("次の検索結果")
            activateViaSemantics("次の検索結果")
            awaitA11y("2件目、全2件")
            assertA11yActionable("次の検索結果")
            activateViaSemantics("次の検索結果")
            awaitA11y("1件目、全2件")
            awaitA11y("倍率を切り替える、80%  近")
            assertA11yActionable("倍率を切り替える")
            activateViaSemantics("倍率を切り替える")
            awaitA11y("倍率を切り替える、50%  中")
            awaitA11y("1件目、全2件")
            assertA11ySetTextActionable()
            replaceSearchTextViaSemantics("absent")
            awaitA11y("0件")
            assertA11yActionable("検索を閉じる")
            activateViaSemantics("検索を閉じる")
            awaitCanvasAfterSearchIme(scenario)
            assertA11yActionable("倍率を切り替える")
            activateViaSemantics("倍率を切り替える")
            awaitA11y("倍率を切り替える、25%  遠")
            assertTrue(composeRule.onAllNodesWithContentDescription("Idea note", substring = true)
                .fetchSemanticsNodes().isEmpty())
            assertA11yActionable("囲み: Cluster")
            activateViaSemantics("囲み: Cluster")
            awaitDescription("Idea note", substring = true)
            val persisted = CanvasDatabase.open(context)
            try {
                assertEquals(2, runBlocking { persisted.canvasDao().elements(1).size })
                assertEquals(1, runBlocking { persisted.canvasDao().spatialElements(1).size })
            } finally { persisted.close() }
        } finally { scenario.close() }
    }
}
