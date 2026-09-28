package com.thinkcanvas.canvas

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thinkcanvas.MainActivity
import com.thinkcanvas.data.BoardRow
import com.thinkcanvas.data.CanvasDatabase
import com.thinkcanvas.data.SpatialElementRow
import com.thinkcanvas.data.TextElementRow
import com.thinkcanvas.data.showBoardOneAtStartup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConditionalChromeLifecycleTest {
    @Test
    fun selectionShareRowStopsExcludingCanvasWhenHiddenAndReturnsAtCurrentBounds() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        seedBoard(context)
        showBoardOneAtStartup(context)
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val automation = instrumentation.uiAutomation
        try {
            click(instrumentation, "囲み: Cluster")
            val originalShareBounds = bounds(waitFor(instrumentation, "選択範囲を画像で共有"))
            val formerPaddingPoint = originalShareBounds.centerX().toFloat() to
                (originalShareBounds.bottom + 3f * context.resources.displayMetrics.density)

            tap(instrumentation, context.resources.displayMetrics.widthPixels * .15f,
                context.resources.displayMetrics.heightPixels * .3f)
            assertTrue("選択解除で共有行が消える", find(automation.rootInActiveWindow,
                "選択範囲を画像で共有") == null)
            tap(instrumentation, formerPaddingPoint.first, formerPaddingPoint.second)
            waitFor(instrumentation, "完了")
            click(instrumentation, "やめる")

            click(instrumentation, "囲み: Cluster")
            val currentShareBounds = bounds(waitFor(instrumentation, "選択範囲を画像で共有"))
            assertNotNull("再表示で現在の共有行がアクセシビリティに復帰する",
                find(automation.rootInActiveWindow, "選択範囲を画像で共有"))
            tap(instrumentation, currentShareBounds.centerX().toFloat(),
                currentShareBounds.bottom + 3f * context.resources.displayMetrics.density)
            assertTrue("再表示中の共有行は canvas input を除外する",
                find(automation.rootInActiveWindow, "完了") == null)

            click(instrumentation, "名前")
            assertTrue("名前編集で共有行が消える", find(automation.rootInActiveWindow,
                "選択範囲を画像で共有") == null)
            waitFor(instrumentation, "完了")
            click(instrumentation, "完了")
            tap(instrumentation, context.resources.displayMetrics.widthPixels * .15f,
                context.resources.displayMetrics.heightPixels * .3f)
            assertTrue("名前編集遷移後の選択解除でも共有行が消える",
                find(automation.rootInActiveWindow, "選択範囲を画像で共有") == null)
            tap(instrumentation, formerPaddingPoint.first, formerPaddingPoint.second)
            waitFor(instrumentation, "完了")
        } finally {
            activity.finish()
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun hiddenCollapsedAndExpandedToolsLocationsAdmitInkAndToolsCanReturn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        seedBoard(context)
        showBoardOneAtStartup(context)
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val database = CanvasDatabase.open(context)
        try {
            val collapsed = bounds(waitFor(instrumentation, "図形ツールを開く"))
            click(instrumentation, "図形ツールを開く")
            val markerInExpandedTools = bounds(waitFor(instrumentation, "マーカー"))
            click(instrumentation, "ペン")
            draw(instrumentation, collapsed.centerX().toFloat(), collapsed.centerY().toFloat())
            awaitInkCount(database, 1)

            click(instrumentation, "やめる")
            waitFor(instrumentation, "図形ツールを開く")
            click(instrumentation, "図形ツールを開く")
            val reShownMarker = bounds(waitFor(instrumentation, "マーカー"))
            assertTrue("再表示後の expanded tool bounds が有効",
                reShownMarker.width() > 0 && reShownMarker.height() > 0)
            click(instrumentation, "マーカー")
            draw(instrumentation, reShownMarker.centerX().toFloat(), reShownMarker.centerY().toFloat())
            awaitInkCount(database, 2)
            assertEquals(2, runBlocking { database.canvasDao().inkStrokes(1).size })

            click(instrumentation, "やめる")
            waitFor(instrumentation, "図形ツールを開く")
            click(instrumentation, "図形ツールを開く")
            assertTrue("再表示された tools の expanded 領域は新しい layout を持つ",
                markerInExpandedTools.width() > 0)
        } finally {
            database.close()
            activity.finish()
            instrumentation.waitForIdleSync()
        }
    }

    private fun seedBoard(context: android.content.Context) {
        val database = CanvasDatabase.open(context)
        runBlocking {
            if (database.canvasDao().board(1) == null) database.canvasDao().putBoard(BoardRow())
            val text = TextElement(id = "chrome-lifecycle-text", text = "Canvas note", x = 500f, y = 1200f)
            val region = ShapeElement(id = "chrome-lifecycle-region", kind = ShapeKind.REGION,
                x = 400f, y = 1000f, width = 500f, height = 400f, name = "Cluster")
            database.canvasDao().replaceAll(1, listOf(TextElementRow.fromModel(1, text)),
                listOf(SpatialElementRow.fromModel(1, region)), emptyList())
        }
        database.close()
    }

    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.contentDescription?.toString()?.startsWith(label) == true) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
        return null
    }

    private fun waitFor(instrumentation: android.app.Instrumentation, label: String): AccessibilityNodeInfo {
        repeat(40) {
            instrumentation.waitForIdleSync()
            find(instrumentation.uiAutomation.rootInActiveWindow, label)?.let { return it }
            Thread.sleep(100)
        }
        error("アクセシビリティ要素が見つかりません: $label")
    }

    private fun click(instrumentation: android.app.Instrumentation, label: String) {
        var node: AccessibilityNodeInfo? = waitFor(instrumentation, label)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("$label を操作できる", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        instrumentation.waitForIdleSync()
    }

    private fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)

    private fun tap(instrumentation: android.app.Instrumentation, x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
            Thread.sleep(50)
        }
        instrumentation.waitForIdleSync()
    }

    private fun draw(instrumentation: android.app.Instrumentation, x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        val points = listOf(x to y, (x - 30f) to (y + 30f), (x - 60f) to (y + 60f))
        points.forEachIndexed { index, (px, py) ->
            val action = when (index) {
                0 -> MotionEvent.ACTION_DOWN
                points.lastIndex -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, px, py, 0)
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
            Thread.sleep(45)
        }
    }

    private fun awaitInkCount(database: CanvasDatabase, count: Int) {
        repeat(40) {
            if (runBlocking { database.canvasDao().inkStrokes(1).size } == count) return
            Thread.sleep(100)
        }
        assertEquals(count, runBlocking { database.canvasDao().inkStrokes(1).size })
    }
}
