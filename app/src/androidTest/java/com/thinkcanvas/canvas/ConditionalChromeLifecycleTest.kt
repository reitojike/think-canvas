package com.thinkcanvas.canvas

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thinkcanvas.MainActivity
import com.thinkcanvas.data.BoardRow
import com.thinkcanvas.data.CanvasDatabase
import com.thinkcanvas.data.SpatialElementRow
import com.thinkcanvas.data.TextElementRow
import com.thinkcanvas.data.showBoardOneAtStartup
import kotlinx.coroutines.runBlocking
import com.thinkcanvas.test.PrSmoke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConditionalChromeLifecycleTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    @PrSmoke
    @Test
    fun regionNameTransitionRemovesFormerSelectionSharePointUntilReselected() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        seedBoard(context)
        showBoardOneAtStartup(context)
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            clickActionable(instrumentation, "囲み: Cluster")
            assertEquals("囲みが選択されている", "選択中",
                waitForRegionState(instrumentation, "選択中"))

            val shareButton = waitForActionable(instrumentation, "選択範囲を画像で共有")
            val shareCenter = center(bounds(shareButton))

            val (region, renameAction) = waitForRegionRenameAction(instrumentation)
            assertTrue("囲み名編集のアクセシビリティ操作を実行できる",
                region.performAction(renameAction.id))
            composeRule.waitForIdle()
            instrumentation.waitForIdleSync()
            waitForGone(instrumentation, "選択範囲を画像で共有")
            assertNotNull("共有行が消えた状態で囲み名 editor が active",
                findExact(instrumentation.uiAutomation.rootInActiveWindow, "囲みの名前"))
            assertEquals("former point tap 前も囲みは選択中", "選択中",
                waitForRegionState(instrumentation, "選択中"))

            val configuration = composeRule.onNodeWithContentDescription("キャンバス")
                .fetchSemanticsNode().layoutInfo.viewConfiguration
            composeRule.mainClock.autoAdvance = false
            try {
                tap(instrumentation, shareCenter.first, shareCenter.second)
                repeat(3) { composeRule.mainClock.advanceTimeByFrame() }
                assertEquals("confirmed single 前は選択を維持する", "選択中",
                    waitForRegionState(instrumentation, "選択中"))
                composeRule.mainClock.advanceTimeBy(configuration.doubleTapTimeoutMillis + 1)
            } finally {
                composeRule.mainClock.autoAdvance = true
            }
            assertEquals("former share Button center から囲み選択が解除される", "未選択",
                waitForRegionState(instrumentation, "未選択"))
            assertNotNull("選択解除後も囲み名 editor は active",
                findExact(instrumentation.uiAutomation.rootInActiveWindow, "囲みの名前"))

            clickActionable(instrumentation, "完了")
            waitForGone(instrumentation, "選択範囲を画像で共有")
            clickActionable(instrumentation, "囲み: Cluster")
            assertEquals("再選択後に囲みが選択中", "選択中",
                waitForRegionState(instrumentation, "選択中"))
            assertNotNull("再選択後に共有 Button が再表示される",
                waitForActionable(instrumentation, "選択範囲を画像で共有"))
        } finally {
            activity.finish()
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun pendingBlankSingleRejectsRegionNameInputChange() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        seedBoard(context)
        showBoardOneAtStartup(context)
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            clickActionable(instrumentation, "囲み: Cluster")
            val formerPoint = center(bounds(waitForActionable(instrumentation, "選択範囲を画像で共有")))
            val (region, renameAction) = waitForRegionRenameAction(instrumentation)
            assertTrue(region.performAction(renameAction.id))
            waitForGone(instrumentation, "選択範囲を画像で共有")
            val changeName = checkNotNull(composeRule.onNodeWithContentDescription("囲みの名前")
                .fetchSemanticsNode().config[SemanticsActions.SetText].action)
            val configuration = composeRule.onNodeWithContentDescription("キャンバス")
                .fetchSemanticsNode().layoutInfo.viewConfiguration
            composeRule.mainClock.autoAdvance = false
            try {
                tap(instrumentation, formerPoint.first, formerPoint.second)
                repeat(3) { composeRule.mainClock.advanceTimeByFrame() }
                assertEquals("pending 中は選択を維持する", "選択中",
                    waitForRegionState(instrumentation, "選択中"))
                instrumentation.runOnMainSync { assertTrue(changeName(AnnotatedString("Changed"))) }
                composeRule.mainClock.advanceTimeBy(configuration.doubleTapTimeoutMillis + 1)
            } finally {
                composeRule.mainClock.autoAdvance = true
            }
            assertEquals("囲み名変更で old single の選択解除を取消す", "選択中",
                waitForRegionState(instrumentation, "選択中"))
            composeRule.onNodeWithContentDescription("囲みの名前").assertTextEquals("Changed")
            assertNull("old single から text editor を生成しない",
                findExact(instrumentation.uiAutomation.rootInActiveWindow, "新しいテキスト"))
        } finally {
            composeRule.mainClock.autoAdvance = true
            activity.finish()
            instrumentation.waitForIdleSync()
        }
    }

    @PrSmoke
    @Test
    fun collapsedAndExpandedToolFormerCentersAdmitPersistedInkAndAllowReentry() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        seedBoard(context)
        showBoardOneAtStartup(context)
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val database = CanvasDatabase.open(context)
        val initialInkCount = runBlocking { database.canvasDao().inkStrokes(1).size }
        try {
            assertStableEndChrome(instrumentation)
            val collapsedLauncher = waitForActionable(instrumentation, "図形ツールを開く")
            val collapsedCenter = center(bounds(collapsedLauncher))
            clickNode(instrumentation, collapsedLauncher, "図形ツールを開く")

            val collapsedPen = waitForActionable(instrumentation, "ペン")
            clickNode(instrumentation, collapsedPen, "ペン")
            waitForGone(instrumentation, "図形ツールを開く")
            draw(instrumentation, collapsedCenter.first, collapsedCenter.second)
            awaitInkCount(database, initialInkCount + 1)
            assertEquals("collapsed launcher の旧位置から始めた ink が保存される",
                initialInkCount + 1, runBlocking { database.canvasDao().inkStrokes(1).size })

            clickActionable(instrumentation, "やめる")
            val reenteredLauncher = waitForActionable(instrumentation, "図形ツールを開く")
            clickNode(instrumentation, reenteredLauncher, "図形ツールを開く")
            val expandedTool = waitForAnyActionable(instrumentation, listOf("ペン", "マーカー"))
            val expandedCenter = center(bounds(expandedTool))
            clickNode(instrumentation, expandedTool, requireNotNull(expandedTool.contentDescription).toString())
            waitForGone(instrumentation, "図形ツールを開く")
            draw(instrumentation, expandedCenter.first, expandedCenter.second)
            awaitInkCount(database, initialInkCount + 2)
            assertEquals("expanded Pen/Marker の旧位置から始めた ink が保存される",
                initialInkCount + 2, runBlocking { database.canvasDao().inkStrokes(1).size })

            clickActionable(instrumentation, "やめる")
            val finalLauncher = waitForActionable(instrumentation, "図形ツールを開く")
            clickNode(instrumentation, finalLauncher, "図形ツールを開く")
            val finalPen = waitForAnyActionable(instrumentation, listOf("ペン", "マーカー"))
            clickNode(instrumentation, finalPen, requireNotNull(finalPen.contentDescription).toString())
            waitForGone(instrumentation, "図形ツールを開く")
            clickActionable(instrumentation, "やめる")
            assertNotNull("ink mode 終了後に actionable launcher が再表示される",
                waitForActionable(instrumentation, "図形ツールを開く"))
        } finally {
            database.close()
            activity.finish()
            instrumentation.waitForIdleSync()
        }
    }

    // Issue #107: launcher anchor、展開中の視点control、48dp target の非重複と閉じた palette の hit 解放。
    private fun assertStableEndChrome(instrumentation: android.app.Instrumentation) {
        val launcher = "図形ツールを開く"
        val anchor = bounds(waitForActionable(instrumentation, launcher))
        assertNull("履歴がない間は視点controlを表示しない",
            findExact(instrumentation.uiAutomation.rootInActiveWindow, "前の視点へ戻る"))
        clickActionable(instrumentation, launcher)
        val formerPen = center(bounds(waitForActionable(instrumentation, "ペン")))
        assertEquals("展開で launcher が動かない", anchor, bounds(waitForActionable(instrumentation, launcher)))
        assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        waitForGone(instrumentation, "ペン")
        assertEquals("Back で閉じた後も同じ launcher 位置", anchor,
            bounds(waitForActionable(instrumentation, launcher)))

        pan(instrumentation, formerPen.first, formerPen.second)
        val history = bounds(waitForActionable(instrumentation, "前の視点へ戻る"))
        assertEquals("視点履歴の成立で launcher が動かない", anchor,
            bounds(waitForActionable(instrumentation, launcher)))

        clickActionable(instrumentation, launcher)
        val tools = listOf("画像を追加", "ペン", "マーカー", "まとめて選ぶ", "囲み", "矢印", "丸", "四角")
            .map { bounds(waitForActionable(instrumentation, it)) }
        assertEquals("展開中も launcher は同じ位置", anchor, bounds(waitForActionable(instrumentation, launcher)))
        assertEquals("palette 展開だけでは視点controlを隠さない", history,
            bounds(waitForActionable(instrumentation, "前の視点へ戻る")))
        val targets = tools + listOf(anchor, history, zoomBounds(instrumentation))
        val minimum = 48f * instrumentation.targetContext.resources.displayMetrics.density - 1f
        targets.forEach { assertTrue("48dp target: $it", it.width() >= minimum && it.height() >= minimum) }
        targets.forEachIndexed { index, rect ->
            targets.drop(index + 1).forEach { other ->
                assertTrue("chrome target が重ならない: $rect / $other", !Rect.intersects(rect, other))
            }
        }
        clickActionable(instrumentation, "前の視点へ戻る")
        // Issue #107: Back 前に読んだ disabled の forward ancestor が UiAutomation cache に残り得るため、
        // 取得直前に一度だけ cache を更新し、fresh root から既存の厳密な検証を行う。
        // clearCache() の false は cache が存在しない (読み取りは常に fresh) ことを表す。
        if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
        assertNotNull("palette 展開中の視点 back を受理する",
            waitForActionable(instrumentation, "次の視点へ進む"))
        clickActionable(instrumentation, launcher)
        waitForGone(instrumentation, "ペン")
        assertEquals("launcher で閉じた後も同じ位置", anchor, bounds(waitForActionable(instrumentation, launcher)))
    }

    private fun zoomBounds(instrumentation: android.app.Instrumentation): Rect {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.contentDescription?.startsWith("倍率を切り替える、") == true) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        repeat(40) {
            composeRule.waitForIdle()
            instrumentation.waitForIdleSync()
            find(instrumentation.uiAutomation.rootInActiveWindow)?.let { return bounds(it) }
            Thread.sleep(100)
        }
        error("倍率 control が見つかりません")
    }

    private fun pan(instrumentation: android.app.Instrumentation, x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        val points = (0..6).map { (x - it * 50f) to y }
        points.forEachIndexed { index, (px, py) ->
            val action = if (index == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, px, py, 0)
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
            Thread.sleep(16)
        }
        // 慣性を起こさず、視点履歴へ1回だけ記録する。
        Thread.sleep(180)
        val (endX, endY) = points.last()
        val up = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, endX, endY, 0)
        assertTrue(instrumentation.uiAutomation.injectInputEvent(up, true))
        up.recycle()
        instrumentation.waitForIdleSync()
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

    private fun findExact(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) {
            findExact(node.getChild(index), label)?.let { return it }
        }
        return null
    }

    private fun findActionable(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val ownsClick = node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
        if (node.contentDescription?.toString() == label &&
            (label != "囲み: Cluster" || ownsClick)) {
            assertPlatformControl(node, label)
            return node
        }
        for (index in 0 until node.childCount) {
            findActionable(node.getChild(index), label)?.let { return it }
        }
        return null
    }

    // Layer A: exact description と同じ control の platform exposure / geometry を確認する。
    private fun assertPlatformControl(
        description: AccessibilityNodeInfo,
        label: String,
    ): AccessibilityNodeInfo {
        assertEquals(label, description.contentDescription?.toString())
        assertTrue("$label の description は有効", description.isEnabled)
        assertTrue("$label の description は利用者に見える", description.isVisibleToUser)
        var node: AccessibilityNodeInfo? = description
        while (node != null && !node.isClickable) node = node.parent
        val actionable = checkNotNull(node) { "$label に対応する clickable node がありません" }
        assertTrue("$label の control は有効", actionable.isEnabled)
        assertTrue("$label の control は利用者に見える", actionable.isVisibleToUser)
        assertTrue("$label の control は clickable", actionable.isClickable)
        assertTrue("$label の control は ACTION_CLICK を公開する",
            actionable.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
        assertEquals("$label の description と control は同じ bounds",
            bounds(description), bounds(actionable))
        return actionable
    }

    private fun waitForActionable(
        instrumentation: android.app.Instrumentation,
        label: String,
    ): AccessibilityNodeInfo {
        repeat(40) {
            composeRule.waitForIdle()
            instrumentation.waitForIdleSync()
            findActionable(instrumentation.uiAutomation.rootInActiveWindow, label)?.let { return it }
            Thread.sleep(100)
        }
        error("操作可能なアクセシビリティ要素が見つかりません: $label")
    }

    private fun waitForAnyActionable(
        instrumentation: android.app.Instrumentation,
        labels: List<String>,
    ): AccessibilityNodeInfo {
        repeat(40) {
            composeRule.waitForIdle()
            instrumentation.waitForIdleSync()
            val root = instrumentation.uiAutomation.rootInActiveWindow
            for (label in labels) findActionable(root, label)?.let { return it }
            Thread.sleep(100)
        }
        error("操作可能なアクセシビリティ要素が見つかりません: ${labels.joinToString()}")
    }

    private fun waitForRegionState(
        instrumentation: android.app.Instrumentation,
        expected: String,
    ): String {
        repeat(40) {
            composeRule.waitForIdle()
            instrumentation.waitForIdleSync()
            val state = findActionable(instrumentation.uiAutomation.rootInActiveWindow, "囲み: Cluster")
                ?.stateDescription?.toString()
            if (state == expected) return state
            Thread.sleep(100)
        }
        error("囲みの stateDescription が $expected になりません")
    }

    private fun waitForRegionRenameAction(
        instrumentation: android.app.Instrumentation,
    ): Pair<AccessibilityNodeInfo, AccessibilityNodeInfo.AccessibilityAction> {
        repeat(40) {
            composeRule.waitForIdle()
            instrumentation.waitForIdleSync()
            val region = findActionable(instrumentation.uiAutomation.rootInActiveWindow, "囲み: Cluster")
            val action = region?.actionList?.firstOrNull { it.label?.toString() == "囲みの名前を編集" }
            if (region != null && action != null) return region to action
            Thread.sleep(100)
        }
        error("囲み: Cluster の名前編集 accessibility action が見つかりません")
    }

    private fun clickActionable(instrumentation: android.app.Instrumentation, label: String) {
        clickNode(instrumentation, waitForActionable(instrumentation, label), label)
    }

    private fun clickNode(
        instrumentation: android.app.Instrumentation,
        node: AccessibilityNodeInfo,
        label: String,
    ) {
        assertPlatformControl(node, label)
        // Layer B: Layer A で確認した exact control の OnClick を実行する。
        composeRule.onNode(hasContentDescription(label) and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        instrumentation.waitForIdleSync()
    }

    private fun waitForGone(instrumentation: android.app.Instrumentation, label: String) {
        repeat(40) {
            composeRule.waitForIdle()
            instrumentation.waitForIdleSync()
            if (findExact(instrumentation.uiAutomation.rootInActiveWindow, label) == null) return
            Thread.sleep(100)
        }
        error("非表示になったアクセシビリティ要素が残っています: $label")
    }

    private fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)

    private fun center(rect: Rect): Pair<Float, Float> =
        rect.exactCenterX() to rect.exactCenterY()

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
