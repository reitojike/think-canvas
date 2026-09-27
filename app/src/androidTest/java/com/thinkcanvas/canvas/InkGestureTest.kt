package com.thinkcanvas.canvas

import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thinkcanvas.MainActivity
import com.thinkcanvas.data.CanvasDatabase
import com.thinkcanvas.data.BoardRow
import com.thinkcanvas.data.showBoardOneAtStartup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InkGestureTest {
    @Test
    fun secondFingerCancelsWetInkAndSingleFingerStillDraws() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val cleanDatabase = CanvasDatabase.open(context)
        runBlocking {
            if (cleanDatabase.canvasDao().board(1) == null)
                cleanDatabase.canvasDao().putBoard(BoardRow())
            cleanDatabase.canvasDao().replaceAll(1, emptyList(), emptyList(), emptyList())
        }
        cleanDatabase.close()
        showBoardOneAtStartup(context)
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        instrumentation.waitForIdleSync()
        Thread.sleep(700)
        val width = context.resources.displayMetrics.widthPixels.toFloat()
        val height = context.resources.displayMetrics.heightPixels.toFloat()
        val automation = instrumentation.uiAutomation

        fun find(node: AccessibilityNodeInfo?, description: String): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.contentDescription?.toString() == description) return node
            for (index in 0 until node.childCount) {
                find(node.getChild(index), description)?.let { return it }
            }
            return null
        }

        fun percentTexts(node: AccessibilityNodeInfo?): List<String> {
            if (node == null) return emptyList()
            val own = node.text?.toString()?.takeIf { '%' in it }?.let(::listOf).orEmpty()
            return own + (0 until node.childCount).flatMap { percentTexts(node.getChild(it)) }
        }

        fun click(description: String) {
            val node = (1..20).firstNotNullOfOrNull {
                val found = find(automation.rootInActiveWindow, description)
                if (found == null) Thread.sleep(100)
                found
            } ?: error("操作が見つかりません: $description")
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) target = target.parent
            assertTrue(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            instrumentation.waitForIdleSync()
        }

        var gestureDownTime = 0L
        var currentToolType = MotionEvent.TOOL_TYPE_FINGER
        fun send(action: Int, vararg positions: Pair<Float, Float>) {
            val properties = Array(positions.size) { index ->
                MotionEvent.PointerProperties().apply {
                    id = index
                    toolType = currentToolType
                }
            }
            val coordinates = Array(positions.size) { index ->
                MotionEvent.PointerCoords().apply {
                    x = positions[index].first
                    y = positions[index].second
                    pressure = 1f
                    size = 1f
                }
            }
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) gestureDownTime = now
            val event = MotionEvent.obtain(gestureDownTime, now, action, positions.size,
                properties, coordinates, 0, 0, 1f, 1f, 0, 0,
                if (currentToolType == MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS
                else InputDevice.SOURCE_TOUCHSCREEN, 0)
            assertTrue(automation.injectInputEvent(event, true))
            event.recycle()
            Thread.sleep(35)
        }

        click("図形ツールを開く")
        click("ペン")

        val top = height * .38f
        val bottom = height * .48f
        repeat(10) {
            send(MotionEvent.ACTION_DOWN, (width * .2f) to top)
            send(MotionEvent.ACTION_MOVE, (width * .3f) to (top + 60f))
            send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                (width * .3f) to (top + 60f), (width * .7f) to top)
            send(MotionEvent.ACTION_MOVE, (width * .25f) to (top + 80f),
                (width * .75f) to (top + 80f))
            send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                (width * .25f) to (top + 80f), (width * .75f) to (top + 80f))
            send(MotionEvent.ACTION_UP, (width * .25f) to (top + 80f))
        }

        val database = CanvasDatabase.open(context)
        assertEquals(0, runBlocking { database.canvasDao().inkStrokes(1).size })
        var zoomLabels = emptyList<String>()
        for (attempt in 1..20) {
            instrumentation.waitForIdleSync()
            zoomLabels = percentTexts(automation.rootInActiveWindow)
            if (zoomLabels.any { it.substringBefore('%').toIntOrNull()?.let { value -> value > 100 } == true })
                break
            Thread.sleep(50)
        }
        assertTrue("倍率表示: $zoomLabels", zoomLabels.any {
            it.substringBefore('%').toIntOrNull()?.let { value -> value > 100 } == true
        })
        repeat(10) {
            send(MotionEvent.ACTION_DOWN, (width * .2f) to bottom)
            send(MotionEvent.ACTION_MOVE, (width * .35f) to (bottom + 40f))
            send(MotionEvent.ACTION_UP, (width * .5f) to (bottom + 80f))
        }
        for (attempt in 1..30) {
            if (runBlocking { database.canvasDao().inkStrokes(1).size } == 10) break
            Thread.sleep(100)
        }
        assertEquals(10, runBlocking { database.canvasDao().inkStrokes(1).size })
        instrumentation.waitForIdleSync()
        val screenshot = requireNotNull(automation.takeScreenshot())
        val centerX = (width * .35f).toInt()
        val centerY = (bottom + 40f).toInt()
        val visibleInk = (centerX - 16..centerX + 16).any { x ->
            (centerY - 16..centerY + 16).any { y ->
                val color = screenshot.getPixel(x, y)
                Color.red(color) < 100 && Color.green(color) < 100 && Color.blue(color) < 100
            }
        }
        assertTrue("拡大後の線が入力位置に描かれる", visibleInk)
        screenshot.recycle()
        click("やめる")
        currentToolType = MotionEvent.TOOL_TYPE_STYLUS
        repeat(10) {
            send(MotionEvent.ACTION_DOWN, (width * .2f) to (bottom + 180f))
            send(MotionEvent.ACTION_MOVE, (width * .35f) to (bottom + 220f))
            send(MotionEvent.ACTION_UP, (width * .5f) to (bottom + 260f))
        }
        for (attempt in 1..30) {
            if (runBlocking { database.canvasDao().inkStrokes(1).size } == 20) break
            Thread.sleep(100)
        }
        assertEquals(20, runBlocking { database.canvasDao().inkStrokes(1).size })
        database.close()
        activity.finish()
    }
}
