package com.thinkcanvas.canvas

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import com.thinkcanvas.MainActivity
import com.thinkcanvas.data.CanvasDatabase
import com.thinkcanvas.data.BoardRow
import com.thinkcanvas.data.showBoardOneAtStartup
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import com.thinkcanvas.test.PrSmoke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InkGestureTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    @PrSmoke
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
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        val database = CanvasDatabase.open(context)
        try {
        composeRule.waitForIdle()
        val width = context.resources.displayMetrics.widthPixels.toFloat()
        val height = context.resources.displayMetrics.heightPixels.toFloat()
        val automation = instrumentation.uiAutomation

        fun click(description: String) {
            composeRule.onNodeWithContentDescription(description).performClick()
            composeRule.waitForIdle()
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

        assertEquals(0, runBlocking { database.canvasDao().inkStrokes(1).size })
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithContentDescription("倍率を切り替える、", substring = true)
                .fetchSemanticsNodes().any { node ->
                (if (SemanticsProperties.ContentDescription in node.config)
                    node.config[SemanticsProperties.ContentDescription] else emptyList()).any { description ->
                    description.substringAfter("、", "").substringBefore('%').toIntOrNull()
                        ?.let { it > 100 } == true
                } == true
            }
        }
        repeat(10) {
            send(MotionEvent.ACTION_DOWN, (width * .2f) to bottom)
            send(MotionEvent.ACTION_MOVE, (width * .35f) to (bottom + 40f))
            send(MotionEvent.ACTION_UP, (width * .5f) to (bottom + 80f))
        }
        composeRule.waitUntil(10_000) {
            runBlocking { database.canvasDao().inkStrokes(1).size } == 10
        }
        assertEquals(10, runBlocking { database.canvasDao().inkStrokes(1).size })
        composeRule.waitForIdle()
        val canvas = composeRule.onNodeWithContentDescription("キャンバス")
        val canvasNode = canvas.fetchSemanticsNode()
        val boundsInWindow = canvasNode.boundsInWindow
        val windowOrigin = IntArray(2)
        scenario.onActivity { it.window.decorView.getLocationOnScreen(windowOrigin) }
        val image = canvas.captureToImage().asAndroidBitmap()
        val screenX = width * .35f
        val screenY = bottom + 40f
        val nodeScreenLeft = windowOrigin[0] + boundsInWindow.left
        val nodeScreenTop = windowOrigin[1] + boundsInWindow.top
        val localX = screenX - nodeScreenLeft
        val localY = screenY - nodeScreenTop
        val pixelX = localX.roundToInt()
        val pixelY = localY.roundToInt()
        val searchLeft = (pixelX - 16).coerceAtLeast(0)
        val searchTop = (pixelY - 16).coerceAtLeast(0)
        val searchRight = (pixelX + 16).coerceAtMost(image.width - 1)
        val searchBottom = (pixelY + 16).coerceAtMost(image.height - 1)

        fun saveInkDiagnostics(visibleInk: Boolean?, darkest: String): String {
            val zoomLabels = composeRule.onAllNodesWithContentDescription("倍率を切り替える、", substring = true)
                .fetchSemanticsNodes().flatMap { node ->
                    if (SemanticsProperties.ContentDescription in node.config)
                        node.config[SemanticsProperties.ContentDescription] else emptyList()
                }
            val text = listOf(
                "display=${width}x$height",
                "Pscreen=($screenX, $screenY)",
                "decorViewScreenOrigin=(${windowOrigin[0]}, ${windowOrigin[1]})",
                "node.boundsInWindow=(${boundsInWindow.left}, ${boundsInWindow.top}, " +
                    "${boundsInWindow.right}, ${boundsInWindow.bottom})",
                "nodeScreenRect=($nodeScreenLeft, $nodeScreenTop, " +
                    "${windowOrigin[0] + boundsInWindow.right}, ${windowOrigin[1] + boundsInWindow.bottom})",
                "Plocal=($localX, $localY)",
                "PlocalPx=($pixelX, $pixelY)",
                "bitmap=${image.width}x${image.height}",
                "searchRect=($searchLeft, $searchTop, $searchRight, $searchBottom)",
                "darkest=$darkest",
                "visibleInk=$visibleInk",
                "dbInkStrokes=${runBlocking { database.canvasDao().inkStrokes(1).size }}",
                "zoomLabels=$zoomLabels",
            ).joinToString("\n", postfix = "\n")
            return runCatching {
                val storage = PlatformTestStorageRegistry.getInstance()
                storage.openOutputFile("issue40/ink-after-zoom-failure.png").use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                storage.openOutputFile("issue40/ink-after-zoom-failure.txt").use {
                    it.write(text.toByteArray())
                }
                text
            }.getOrElse { "$text(diagnostic write failed: $it)" }
        }

        if (image.width <= 0 || image.height <= 0 || pixelX !in 0 until image.width ||
            pixelY !in 0 until image.height || searchLeft > searchRight || searchTop > searchBottom) {
            throw AssertionError("INK_ORACLE_COORDINATE_MAPPING_FAILURE: " +
                saveInkDiagnostics(null, "n/a"))
        }
        var darkestX = searchLeft
        var darkestY = searchTop
        var darkestColor = image.getPixel(searchLeft, searchTop)
        var visibleInk = false
        for (x in searchLeft..searchRight) {
            for (y in searchTop..searchBottom) {
                val color = image.getPixel(x, y)
                if (Color.red(color) < 100 && Color.green(color) < 100 && Color.blue(color) < 100)
                    visibleInk = true
                if (Color.red(color) + Color.green(color) + Color.blue(color) <
                    Color.red(darkestColor) + Color.green(darkestColor) + Color.blue(darkestColor)) {
                    darkestX = x
                    darkestY = y
                    darkestColor = color
                }
            }
        }
        if (!visibleInk) saveInkDiagnostics(false, "($darkestX, $darkestY) rgb=(" +
            "${Color.red(darkestColor)}, ${Color.green(darkestColor)}, ${Color.blue(darkestColor)})")
        assertTrue("拡大後の線が入力位置に描かれる", visibleInk)
        image.recycle()
        click("やめる")
        currentToolType = MotionEvent.TOOL_TYPE_STYLUS
        repeat(10) {
            send(MotionEvent.ACTION_DOWN, (width * .2f) to (bottom + 180f))
            send(MotionEvent.ACTION_MOVE, (width * .35f) to (bottom + 220f))
            send(MotionEvent.ACTION_UP, (width * .5f) to (bottom + 260f))
        }
        composeRule.waitUntil(10_000) {
            runBlocking { database.canvasDao().inkStrokes(1).size } == 20
        }
        assertEquals(20, runBlocking { database.canvasDao().inkStrokes(1).size })
        } finally {
            database.close()
            scenario.close()
        }
    }
}
