package com.thinkcanvas.canvas

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SemanticNavigationTest {
    @Test fun openingFitKeepsFarBodyOnlyExtentAfterBodyBecomesHidden() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = openBoardWithContent(
            listOf(
                TextElement(id = "far-body-a", text = "First body", x = 120f, y = 120f),
                TextElement(id = "far-body-b", text = "Second body", x = 2_100f, y = 1_100f),
                TextElement(id = "far-body-c", text = "Third body", x = 4_200f, y = 2_300f),
            ), emptyList())
        try {
            val zoom = awaitStableOpeningZoom(instrumentation)
            assertTrue("FAR-scale BODY-only content stays in the opening fit: $zoom",
                zoom.startsWith("15%") || zoom.substringBefore('%').toInt() < 50)
            assertTrue("FAR still hides ordinary BODY text",
                findNode(instrumentation.uiAutomation.rootInActiveWindow, "First body") == null)
        } finally {
            activity.finish()
            instrumentation.waitForIdleSync()
        }
    }

    @Test fun openingFitIncludesHiddenBodiesAlongsideShapes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = openBoardWithContent(
            listOf(
                TextElement(id = "mixed-body-left", text = "Left body", x = 80f, y = 120f),
                TextElement(id = "mixed-body-right", text = "Right body", x = 4_000f, y = 1_600f),
            ), listOf(ShapeElement(id = "mixed-shape", kind = ShapeKind.RECTANGLE,
                x = 1_900f, y = 900f, width = 180f, height = 120f)))
        try {
            val zoom = awaitStableOpeningZoom(instrumentation)
            assertTrue("hidden BODY extents keep the mixed board fitted: $zoom",
                zoom.substringBefore('%').toInt() < 50)
            assertTrue("FAR still hides ordinary BODY text",
                findNode(instrumentation.uiAutomation.rootInActiveWindow, "Left body") == null)
        } finally {
            activity.finish()
            instrumentation.waitForIdleSync()
        }
    }

    @Test fun openingFitViewportSettlesAfterSemanticTierChanges() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = openBoardWithContent(
            listOf(
                TextElement(id = "settle-body-a", text = "Alpha", x = 100f, y = 200f),
                TextElement(id = "settle-body-b", text = "Beta", x = 1_600f, y = 1_100f),
                TextElement(id = "settle-body-c", text = "Gamma", x = 3_600f, y = 2_000f),
            ), emptyList())
        try {
            val settled = awaitStableOpeningZoom(instrumentation)
            repeat(8) {
                Thread.sleep(100)
                instrumentation.waitForIdleSync()
                assertEquals("opening-fit does not bounce between semantic tiers", settled,
                    currentOpeningZoom(instrumentation))
            }
            assertTrue("iteration converges to a FAR opening fit: $settled",
                settled.substringBefore('%').toInt() < 50)
        } finally {
            activity.finish()
            instrumentation.waitForIdleSync()
        }
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
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val automation = instrumentation.uiAutomation

        fun find(node: AccessibilityNodeInfo?, description: String): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.contentDescription?.toString()?.startsWith(description) == true) return node
            for (index in 0 until node.childCount)
                find(node.getChild(index), description)?.let { return it }
            return null
        }
        fun waitFor(description: String): AccessibilityNodeInfo {
            repeat(30) {
                instrumentation.waitForIdleSync()
                find(automation.rootInActiveWindow, description)?.let { return it }
                Thread.sleep(100)
            }
            error("操作が見つかりません: $description")
        }
        fun click(description: String) {
            var node: AccessibilityNodeInfo? = waitFor(description)
            while (node != null && !node.isClickable) node = node.parent
            assertTrue("$description を操作できる", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        }
        fun waitForZoom(label: String) {
            repeat(30) {
                instrumentation.waitForIdleSync()
                if (find(automation.rootInActiveWindow, "倍率を切り替える、$label") != null) return
                Thread.sleep(50)
            }
            error("倍率表示が $label になりません")
        }

        waitForZoom("100%  近")
        click("倍率を切り替える")
        waitForZoom("50%  中")
        click("倍率を切り替える")
        waitForZoom("25%  遠")
        click("倍率を切り替える")
        waitForZoom("100%  近")

        click("ボード内を検索")
        assertNotNull(waitFor("ボード内を探す"))
        assertNotNull(waitFor("前の検索結果"))
        assertNotNull(waitFor("次の検索結果"))
        click("検索を閉じる")
        assertNotNull(waitFor("ボード内を検索"))

        val width = context.resources.displayMetrics.widthPixels.toFloat()
        val height = context.resources.displayMetrics.heightPixels.toFloat()
        fun touchTap(x: Float, y: Float) {
            val downTime = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    x, y, 0)
                assertTrue(automation.injectInputEvent(event, true))
                event.recycle()
                Thread.sleep(40)
            }
        }
        touchTap(width * .5f, height * .38f)
        touchTap(width * .5f, height * .38f)
        waitForZoom("50%  中")

        val database = CanvasDatabase.open(context)
        assertEquals(0, runBlocking { database.canvasDao().elements(1).size })
        assertEquals(0, runBlocking { database.canvasDao().spatialElements(1).size })
        database.close()
        activity.finish()
        instrumentation.waitForIdleSync()
    }

    private fun openBoardWithContent(texts: List<TextElement>, shapes: List<ShapeElement>): MainActivity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val database = CanvasDatabase.open(context)
        runBlocking {
            if (database.canvasDao().board(1) == null) database.canvasDao().putBoard(BoardRow())
            database.canvasDao().replaceAll(1, texts.map { TextElementRow.fromModel(1, it) },
                shapes.map { SpatialElementRow.fromModel(1, it) }, emptyList())
        }
        database.close()
        showBoardOneAtStartup(context)
        return instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    }

    private fun findNode(node: AccessibilityNodeInfo?, prefix: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.contentDescription?.toString()?.startsWith(prefix) == true) return node
        for (index in 0 until node.childCount)
            findNode(node.getChild(index), prefix)?.let { return it }
        return null
    }

    private fun currentOpeningZoom(instrumentation: android.app.Instrumentation): String? =
        findNode(instrumentation.uiAutomation.rootInActiveWindow, "倍率を切り替える、")
            ?.contentDescription?.toString()?.removePrefix("倍率を切り替える、")

    private fun awaitStableOpeningZoom(instrumentation: android.app.Instrumentation): String {
        var previous: String? = null
        var stableSamples = 0
        repeat(60) {
            instrumentation.waitForIdleSync()
            val current = currentOpeningZoom(instrumentation)
            if (current != null && current == previous) stableSamples++ else stableSamples = 0
            if (stableSamples >= 4) return current!!
            previous = current
            Thread.sleep(100)
        }
        error("初期表示倍率が安定しません: $previous")
    }

    @Test fun farRegionFitsAndSearchFindsSavedText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
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
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val automation = instrumentation.uiAutomation
        fun find(node: AccessibilityNodeInfo?, prefix: String, clickable: Boolean = false): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.contentDescription?.toString()?.startsWith(prefix) == true &&
                (!clickable || node.isClickable)) return node
            for (index in 0 until node.childCount)
                find(node.getChild(index), prefix, clickable)?.let { return it }
            return null
        }
        fun waitFor(prefix: String, clickable: Boolean = false): AccessibilityNodeInfo {
            repeat(40) {
                instrumentation.waitForIdleSync()
                find(automation.rootInActiveWindow, prefix, clickable)?.let { return it }
                Thread.sleep(100)
            }
            error("要素が見つかりません: $prefix")
        }
        fun click(prefix: String) {
            var node: AccessibilityNodeInfo? = waitFor(prefix)
            while (node != null && !node.isClickable) node = node.parent
            assertTrue("$prefix を操作できる", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        }
        waitFor("Idea note")
        click("倍率を切り替える")
        waitFor("倍率を切り替える、50%  中")
        click("倍率を切り替える")
        waitFor("倍率を切り替える、25%  遠")
        assertTrue(find(automation.rootInActiveWindow, "Idea note") == null)
        click("ボード内を検索")
        waitFor("ボード内を探す")
        fun editable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isEditable) return node
            for (index in 0 until node.childCount)
                editable(node.getChild(index))?.let { return it }
            return null
        }
        val field = editable(automation.rootInActiveWindow)
        assertNotNull("検索入力欄が見つかりません", field)
        fun search(term: String) {
            val input = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, term)
            }
            assertTrue(field!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, input))
        }
        search("Idea")
        waitFor("1件目、全2件")
        waitFor("Idea note") // 遠景で隠れる本文も検索中は見える
        click("次の検索結果")
        waitFor("2件目、全2件")
        click("次の検索結果")
        waitFor("1件目、全2件")
        waitFor("倍率を切り替える、80%  近")
        click("倍率を切り替える")
        waitFor("倍率を切り替える、50%  中")
        waitFor("1件目、全2件")
        search("absent")
        waitFor("0件")
        click("検索を閉じる")
        click("倍率を切り替える")
        waitFor("倍率を切り替える、25%  遠")
        assertTrue(find(automation.rootInActiveWindow, "Idea note") == null)
        click("囲み: Cluster")
        waitFor("Idea note")
        val persisted = CanvasDatabase.open(context)
        assertEquals(2, runBlocking { persisted.canvasDao().elements(1).size })
        assertEquals(1, runBlocking { persisted.canvasDao().spatialElements(1).size })
        persisted.close()
        activity.finish()
        instrumentation.waitForIdleSync()
    }
}
