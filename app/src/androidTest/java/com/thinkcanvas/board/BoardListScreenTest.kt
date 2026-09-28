package com.thinkcanvas.board

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import com.thinkcanvas.BoardSessionViewModel
import com.thinkcanvas.BoardListAction
import com.thinkcanvas.BoardListActionOutcome
import com.thinkcanvas.BoardListActionState
import com.thinkcanvas.BoardListActionViewModel
import com.thinkcanvas.MainActivity
import com.thinkcanvas.canvas.BoardSnapshot
import com.thinkcanvas.canvas.BoardState
import com.thinkcanvas.canvas.TextColor
import com.thinkcanvas.canvas.TextKind
import com.thinkcanvas.canvas.ShapeKind
import com.thinkcanvas.data.BoardRow
import com.thinkcanvas.data.CanvasDatabase
import com.thinkcanvas.data.CanvasStore
import com.thinkcanvas.data.StoredBoard
import com.thinkcanvas.data.TextElementRow
import com.thinkcanvas.data.SpatialElementRow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class BoardListScreenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation

    private fun listActions(activity: MainActivity) =
        ViewModelProvider(activity)[BoardListActionViewModel::class.java]

    private suspend fun commitListAction(action: BoardListAction): BoardListActionOutcome {
        val database = CanvasDatabase.open(context)
        val dao = database.canvasDao()
        return try {
            when (action) {
                is BoardListAction.Open -> {
                    val row = requireNotNull(dao.board(action.boardId))
                    context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
                        .edit().putLong("lastOpenedBoardId", row.id).commit()
                    BoardListActionOutcome.OpenBoard(StoredBoard(row, BoardSnapshot()))
                }
                BoardListAction.Create -> {
                    val row = dao.createBoard()
                    context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
                        .edit().putLong("lastOpenedBoardId", row.id).commit()
                    BoardListActionOutcome.OpenBoard(StoredBoard(row, BoardSnapshot()))
                }
                is BoardListAction.Rename -> {
                    check(dao.renameBoard(action.boardId, action.name, System.currentTimeMillis()) == 1)
                    BoardListActionOutcome.ReloadList
                }
                is BoardListAction.Duplicate -> {
                    val source = requireNotNull(dao.board(action.boardId))
                    dao.createBoard("${source.name} のコピー")
                    BoardListActionOutcome.ReloadList
                }
                is BoardListAction.Delete -> {
                    check(dao.deleteBoard(action.boardId))
                    BoardListActionOutcome.ReloadList
                }
            }
        } finally { database.close() }
    }

    private fun finishResumedActivities() {
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>()
                .forEach { it.finish() }
        }
    }

    private fun seed(boards: List<BoardRow>, shapes: List<SpatialElementRow> = emptyList()) = runBlocking {
        val database = CanvasDatabase.open(context)
        val dao = database.canvasDao()
        dao.boards().forEach { dao.deleteBoard(it.id) }
        boards.forEach { dao.putBoard(it) }
        dao.putSpatialElements(shapes)
        database.close()
        assertTrue(context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
            .edit().putBoolean("initialized", true).putBoolean("guideDismissed", true)
            .remove("lastOpenedBoardId").commit())
    }

    private fun blockCanvasStoreQueue(): Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>> {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val field = CanvasStore::class.java.getDeclaredField("operations").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val queue = field.get(CanvasStore.get(context)) as kotlinx.coroutines.channels.Channel<suspend () -> Unit>
        assertTrue(queue.trySend {
            started.complete(Unit)
            release.await()
        }.isSuccess)
        return started to release
    }

    private fun find(node: AccessibilityNodeInfo?, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (match(node)) return node
        for (index in 0 until node.childCount) {
            find(node.getChild(index), match)?.let { return it }
        }
        return null
    }

    private fun waitFor(label: String, byDescription: Boolean = false): AccessibilityNodeInfo {
        repeat(40) {
            instrumentation.waitForIdleSync()
            find(automation.rootInActiveWindow) { node ->
                val value = if (byDescription) node.contentDescription else node.text
                value?.toString()?.startsWith(label) == true
            }?.let { return it }
            Thread.sleep(100)
        }
        error("表示が見つかりません: $label")
    }

    private fun waitForExact(label: String): AccessibilityNodeInfo {
        repeat(40) {
            instrumentation.waitForIdleSync()
            find(automation.rootInActiveWindow) { it.text?.toString() == label }
                ?.let { return it }
            Thread.sleep(100)
        }
        error("表示が見つかりません: $label")
    }

    private fun waitForCustomAction(label: String): Pair<AccessibilityNodeInfo, AccessibilityNodeInfo.AccessibilityAction> {
        repeat(40) {
            instrumentation.waitForIdleSync()
            var result: Pair<AccessibilityNodeInfo, AccessibilityNodeInfo.AccessibilityAction>? = null
            find(automation.rootInActiveWindow) { node ->
                val action = node.actionList.firstOrNull { it.label?.toString() == label }
                if (action != null) result = node to action
                action != null
            }
            result?.let { return it }
            Thread.sleep(100)
        }
        error("操作が見つかりません: $label")
    }

    private fun waitForEditable(): AccessibilityNodeInfo {
        repeat(40) {
            instrumentation.waitForIdleSync()
            find(automation.rootInActiveWindow) { it.isEditable }?.let { return it }
            Thread.sleep(100)
        }
        error("名前入力が見つかりません")
    }

    private fun act(node: AccessibilityNodeInfo, action: Int) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !(if (action == AccessibilityNodeInfo.ACTION_CLICK)
                target.isClickable else target.isLongClickable)) target = target.parent
        assertNotNull("操作できる要素", target)
        assertTrue(target!!.performAction(action))
    }

    private fun swipeUp() {
        val width = context.resources.displayMetrics.widthPixels.toFloat()
        val height = context.resources.displayMetrics.heightPixels.toFloat()
        val x = width * .5f
        val start = height * .78f
        val end = height * .26f
        val downTime = SystemClock.uptimeMillis()
        for (step in 0..8) {
            val action = when (step) {
                0 -> MotionEvent.ACTION_DOWN
                8 -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val y = start + (end - start) * step / 8f
            val event = MotionEvent.obtain(downTime, downTime + step * 25L,
                action, x, y, 0)
            assertTrue(automation.injectInputEvent(event, true))
            event.recycle()
        }
        instrumentation.waitForIdleSync()
    }

    private fun shell(command: String): String {
        val descriptor = automation.executeShellCommand(command)
        return try {
            FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
        } finally {
            descriptor.close()
        }
    }

    @Test fun twentyCardsCanScrollAndOpenFirstAndLast() {
        seed((1L..20L).map { BoardRow(it, "ボード$it", 21L - it) })
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            act(waitFor("ボード1、", byDescription = true), AccessibilityNodeInfo.ACTION_CLICK)
            waitFor("‹ ボード1")
            act(waitFor("ボード一覧を開く", byDescription = true),
                AccessibilityNodeInfo.ACTION_CLICK)
            waitFor("ボード1、", byDescription = true)
            var last = find(automation.rootInActiveWindow) {
                it.contentDescription?.toString()?.startsWith("ボード20、") == true
            }
            repeat(15) {
                if (last != null) return@repeat
                swipeUp()
                last = find(automation.rootInActiveWindow) {
                    it.contentDescription?.toString()?.startsWith("ボード20、") == true
                }
            }
            act(requireNotNull(last), AccessibilityNodeInfo.ACTION_CLICK)
            waitFor("‹ ボード20")
        } finally { activity.finish() }
    }

    @Test fun emptyBoardShowsExactlyOneCanonicalAccessibleHint() {
        seed(listOf(BoardRow(1, "空のボード", 10)))
        assertTrue(context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
            .edit().putLong("lastOpenedBoardId", 1).commit())
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            waitFor("‹ 空のボード")
            val canonicalHint = context.getString(com.thinkcanvas.R.string.empty_hint)
            val matchingVisibleHints = mutableListOf<AccessibilityNodeInfo>()
            fun collectVisibleHints(node: AccessibilityNodeInfo?) {
                if (node == null) return
                if (node.isVisibleToUser && node.text?.toString() == canonicalHint) {
                    matchingVisibleHints += node
                }
                for (index in 0 until node.childCount) collectVisibleHints(node.getChild(index))
            }
            collectVisibleHints(automation.rootInActiveWindow)
            assertEquals("canonical resource-backed hint is accessible exactly once",
                1, matchingVisibleHints.size)
            assertEquals(canonicalHint, matchingVisibleHints.single().text.toString())
        } finally { activity.finish() }
    }

    @Test fun deletionRequiresConfirmationAndKeepsOtherBoard() {
        seed(listOf(BoardRow(1, "残す", 10), BoardRow(42, "消す", 20)))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            act(waitFor("消す、", byDescription = true), AccessibilityNodeInfo.ACTION_LONG_CLICK)
            waitFor("名前を変える")
            act(waitFor("削除"), AccessibilityNodeInfo.ACTION_CLICK)
            waitFor("「消す」を削除しますか？")
            val database = CanvasDatabase.open(context)
            assertNotNull(runBlocking { database.canvasDao().board(42) })
            database.close()
            act(waitFor("キャンセル"), AccessibilityNodeInfo.ACTION_CLICK)
            act(waitFor("消す、", byDescription = true), AccessibilityNodeInfo.ACTION_LONG_CLICK)
            act(waitFor("削除"), AccessibilityNodeInfo.ACTION_CLICK)
            waitFor("「消す」を削除しますか？")
            act(waitFor("削除"), AccessibilityNodeInfo.ACTION_CLICK)
            val result = CanvasDatabase.open(context)
            repeat(30) {
                if (runBlocking { result.canvasDao().board(42) } == null) return@repeat
                Thread.sleep(100)
            }
            assertEquals(null, runBlocking { result.canvasDao().board(42) })
            assertNotNull(runBlocking { result.canvasDao().board(1) })
            result.close()
        } finally { activity.finish() }
    }

    @Test fun guideAppearsOnlyOnInitialEmptyBoardAndCanBeReplayed() {
        seed(listOf(BoardRow(1, "最初のボード", 10)))
        assertTrue(context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
            .edit().putLong("lastOpenedBoardId", 1).putLong("guideEligibleBoardId", 1)
            .putBoolean("guideDismissed", false).commit())
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            waitFor("基本の操作")
            act(waitFor("はじめる"), AccessibilityNodeInfo.ACTION_CLICK)
            act(waitFor("ボード一覧を開く", byDescription = true), AccessibilityNodeInfo.ACTION_CLICK)
            act(waitFor("新しいボード", byDescription = true), AccessibilityNodeInfo.ACTION_CLICK)
            waitFor("‹ 無題のボード")
            val activeGuide = find(automation.rootInActiveWindow) { it.text?.toString() == "基本の操作" }
            assertEquals(null, activeGuide)
            act(waitFor("ボード一覧を開く", byDescription = true), AccessibilityNodeInfo.ACTION_CLICK)
            act(waitFor("使い方"), AccessibilityNodeInfo.ACTION_CLICK)
            waitFor("基本の操作")
        } finally { activity.finish() }
    }

    @Test fun guideCtaCanBeScrolledToAndActivatedAtLargeFontInCompactHeight() {
        val previousFontScale = shell("settings get system font_scale").trim()
        val previousSizeOutput = shell("wm size")
        val previousSizeOverride = Regex("Override size: (\\d+x\\d+)")
            .find(previousSizeOutput)?.groupValues?.get(1)
        val previousPhysicalSize = requireNotNull(Regex("Physical size: (\\d+x\\d+)")
            .find(previousSizeOutput)).groupValues[1]
        var activity: MainActivity? = null
        try {
            shell("wm size 900x1100")
            shell("settings put system font_scale 2.0")
            seed(listOf(BoardRow(1, "最初のボード", 10)))
            assertTrue(context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
                .edit().putLong("lastOpenedBoardId", 1).putLong("guideEligibleBoardId", 1)
                .putBoolean("guideDismissed", false).commit())
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            waitFor("基本の操作")

            var startButton = find(automation.rootInActiveWindow) {
                it.text?.toString() == "はじめる"
            }
            assertFalse("CTA should begin outside the compact sheet viewport",
                startButton?.isVisibleToUser == true)
            var scrollContainer = find(automation.rootInActiveWindow) { it.isScrollable }
            assertNotNull("guide content exposes vertical scrolling", scrollContainer)
            var swipeCount = 0
            for (step in 0 until 12) {
                if (startButton?.isVisibleToUser == true) break
                assertNotNull("guide remains vertically scrollable", scrollContainer)
                swipeUp()
                swipeCount += 1
                instrumentation.waitForIdleSync()
                val root = automation.rootInActiveWindow
                startButton = find(root) { it.text?.toString() == "はじめる" }
                scrollContainer = find(root) { it.isScrollable }
                assertNotNull("guide remains vertically scrollable", scrollContainer)
            }
            assertTrue("test exercises vertical scrolling", swipeCount > 0)
            val reachableButton = requireNotNull(startButton) {
                "CTA enters the accessibility tree after scrolling"
            }
            assertTrue("CTA becomes visible through vertical scrolling", reachableButton.isVisibleToUser)
            act(reachableButton, AccessibilityNodeInfo.ACTION_CLICK)
            for (attempt in 0 until 40) {
                instrumentation.waitForIdleSync()
                if (find(automation.rootInActiveWindow) { it.text?.toString() == "基本の操作" } == null &&
                    context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
                        .getBoolean("guideDismissed", false)) break
                Thread.sleep(100)
            }
            assertEquals(null, find(automation.rootInActiveWindow) { it.text?.toString() == "基本の操作" })
            assertTrue("CTA activation persists dismissal through onStart",
                context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
                    .getBoolean("guideDismissed", false))
        } finally {
            activity?.finish()
            instrumentation.waitForIdleSync()
            shell("settings put system font_scale $previousFontScale")
            if (previousSizeOverride != null) shell("wm size $previousSizeOverride")
            else shell("wm size reset")
            assertEquals(previousFontScale, shell("settings get system font_scale").trim())
            val restoredSize = shell("wm size")
            assertTrue("original display size restored",
                restoredSize.contains("Physical size: $previousPhysicalSize"))
            assertEquals(previousSizeOverride,
                Regex("Override size: (\\d+x\\d+)").find(restoredSize)?.groupValues?.get(1))
            instrumentation.waitForIdleSync()
            Thread.sleep(500)
        }
    }

    @Test fun lastOpenedBoardReturnsAfterActivityRestart() {
        seed(listOf(BoardRow(1, "一つ目", 10), BoardRow(2, "二つ目", 20)))
        val first = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            act(waitFor("二つ目、", byDescription = true), AccessibilityNodeInfo.ACTION_CLICK)
            waitFor("‹ 二つ目")
        } finally { first.finish() }
        instrumentation.waitForIdleSync()
        val reopened = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try { waitFor("‹ 二つ目") } finally { reopened.finish() }
    }

    @Test fun systemBackReturnsFromBoardToList() {
        seed(listOf(BoardRow(1, "戻る対象", 10)))
        assertTrue(context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
            .edit().putLong("lastOpenedBoardId", 1).commit())
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            waitFor("‹ 戻る対象")
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService
                .GLOBAL_ACTION_BACK))
            waitFor("戻る対象、", byDescription = true)
            assertTrue(!activity.isFinishing)
        } finally { activity.finish() }
    }

    @Test fun listPageSurvivesActivityRecreation() {
        seed(listOf(BoardRow(1, "再作成対象", 10)))
        assertTrue(context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
            .edit().putLong("lastOpenedBoardId", 1).commit())
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            waitFor("‹ 再作成対象")
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService
                .GLOBAL_ACTION_BACK))
            waitFor("再作成対象、", byDescription = true)
            instrumentation.runOnMainSync { activity.recreate() }
            waitFor("再作成対象、", byDescription = true)
        } finally {
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>()
                    .forEach { it.finish() }
            }
        }
    }

    @Test fun openingBoardCompletesAfterActivityRecreationWithoutStartingTwice() {
        seed(listOf(BoardRow(1, "開くA", 10), BoardRow(2, "開くB", 20)))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val vm = listActions(activity as MainActivity)
        val started = CompletableDeferred<BoardListAction>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        vm.execute = { action ->
            calls++
            started.complete(action)
            release.await()
            commitListAction(action)
        }
        try {
            act(waitFor("開くA、", byDescription = true), AccessibilityNodeInfo.ACTION_CLICK)
            runBlocking { withTimeout(5_000) { assertEquals(BoardListAction.Open(1), started.await()) } }
            instrumentation.runOnMainSync { activity.recreate() }
            assertFalse(vm.start(BoardListAction.Open(1)))
            release.complete(Unit)
            waitFor("‹ 開くA")
            assertEquals(1, calls)
            assertEquals(1L, context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
                .getLong("lastOpenedBoardId", -1))
        } finally { finishResumedActivities() }
    }

    @Test fun createCompletesOnceAfterActivityRecreation() {
        seed(listOf(BoardRow(1, "既存", 10)))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val vm = listActions(activity as MainActivity)
        val started = CompletableDeferred<BoardListAction>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        var createdId = -1L
        vm.execute = { action ->
            calls++
            started.complete(action)
            release.await()
            val result = commitListAction(action)
            if (result is BoardListActionOutcome.OpenBoard) createdId = result.board.details.id
            result
        }
        try {
            act(waitFor("新しいボード", byDescription = true), AccessibilityNodeInfo.ACTION_CLICK)
            runBlocking { withTimeout(5_000) { assertEquals(BoardListAction.Create, started.await()) } }
            instrumentation.runOnMainSync { activity.recreate() }
            assertFalse(vm.start(BoardListAction.Create))
            release.complete(Unit)
            waitFor("‹ 無題のボード")
            val database = CanvasDatabase.open(context)
            assertEquals(2, runBlocking { database.canvasDao().boards().size })
            database.close()
            assertEquals(createdId, context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
                .getLong("lastOpenedBoardId", -1))
            assertEquals(1, calls)
        } finally { finishResumedActivities() }
    }

    @Test fun runningCreateRemainsTheOnlyAllowedListActionAfterRecreation() {
        seed(emptyList())
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val vm = listActions(activity as MainActivity)
        val started = CompletableDeferred<BoardListAction>()
        val release = CompletableDeferred<Unit>()
        vm.execute = { action ->
            started.complete(action)
            release.await()
            commitListAction(action)
        }
        try {
            act(waitFor("新しいボード", byDescription = true), AccessibilityNodeInfo.ACTION_CLICK)
            runBlocking { withTimeout(5_000) { started.await() } }
            instrumentation.runOnMainSync { activity.recreate() }
            assertTrue(vm.state.value is BoardListActionState.Running)
            assertFalse(vm.start(BoardListAction.Create))
            release.complete(Unit)
            waitFor("‹ 無題のボード")
            val database = CanvasDatabase.open(context)
            assertEquals(1, runBlocking { database.canvasDao().boards().size })
            database.close()
        } finally { finishResumedActivities() }
    }

    @Test fun renameReloadsTheListAfterActivityRecreation() {
        seed(listOf(BoardRow(11, "変更前", 10)))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val vm = listActions(activity as MainActivity)
        val started = CompletableDeferred<BoardListAction>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        vm.execute = { action ->
            calls++
            started.complete(action)
            release.await()
            commitListAction(action)
        }
        try {
            act(waitFor("変更前、", byDescription = true), AccessibilityNodeInfo.ACTION_LONG_CLICK)
            act(waitFor("名前を変える"), AccessibilityNodeInfo.ACTION_CLICK)
            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "変更後")
            }
            assertTrue(waitForEditable().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            act(waitFor("保存"), AccessibilityNodeInfo.ACTION_CLICK)
            runBlocking { withTimeout(5_000) {
                assertEquals(BoardListAction.Rename(11, "変更後"), started.await())
            } }
            instrumentation.runOnMainSync { activity.recreate() }
            assertFalse(vm.start(BoardListAction.Rename(11, "変更後")))
            release.complete(Unit)
            waitFor("変更後、", byDescription = true)
            assertEquals(null, find(automation.rootInActiveWindow) {
                it.contentDescription?.toString()?.startsWith("変更前、") == true
            })
            assertEquals(1, calls)
        } finally { finishResumedActivities() }
    }

    @Test fun duplicateReloadsExactlyOneCopyAfterActivityRecreation() {
        seed(listOf(BoardRow(12, "複製元", 10)))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val vm = listActions(activity as MainActivity)
        val started = CompletableDeferred<BoardListAction>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        vm.execute = { action ->
            calls++
            started.complete(action)
            release.await()
            commitListAction(action)
        }
        try {
            act(waitFor("複製元、", byDescription = true), AccessibilityNodeInfo.ACTION_LONG_CLICK)
            act(waitForExact("複製"), AccessibilityNodeInfo.ACTION_CLICK)
            runBlocking { withTimeout(5_000) { assertEquals(BoardListAction.Duplicate(12), started.await()) } }
            instrumentation.runOnMainSync { activity.recreate() }
            assertFalse(vm.start(BoardListAction.Duplicate(12)))
            release.complete(Unit)
            waitFor("複製元 のコピー、", byDescription = true)
            val database = CanvasDatabase.open(context)
            assertEquals(2, runBlocking { database.canvasDao().boards().size })
            database.close()
            assertEquals(1, calls)
        } finally { finishResumedActivities() }
    }

    @Test fun deleteDoesNotRestoreTheDeletedBoardAfterActivityRecreation() {
        seed(listOf(BoardRow(13, "削除対象", 10)))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val vm = listActions(activity as MainActivity)
        val started = CompletableDeferred<BoardListAction>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        vm.execute = { action ->
            calls++
            started.complete(action)
            release.await()
            commitListAction(action)
        }
        try {
            act(waitFor("削除対象、", byDescription = true), AccessibilityNodeInfo.ACTION_LONG_CLICK)
            act(waitForExact("削除"), AccessibilityNodeInfo.ACTION_CLICK)
            act(waitForExact("削除"), AccessibilityNodeInfo.ACTION_CLICK)
            runBlocking { withTimeout(5_000) { assertEquals(BoardListAction.Delete(13), started.await()) } }
            instrumentation.runOnMainSync { activity.recreate() }
            assertFalse(vm.start(BoardListAction.Delete(13)))
            release.complete(Unit)
            waitFor("ボードはまだありません")
            val database = CanvasDatabase.open(context)
            assertEquals(null, runBlocking { database.canvasDao().board(13) })
            database.close()
            assertEquals(1, calls)
        } finally { finishResumedActivities() }
    }

    @Test fun actionFailureIsShownAfterActivityRecreation() {
        seed(listOf(BoardRow(14, "失敗する操作", 10)))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val vm = listActions(activity as MainActivity)
        val started = CompletableDeferred<BoardListAction>()
        val release = CompletableDeferred<Unit>()
        vm.execute = { action ->
            started.complete(action)
            release.await()
            error("再作成後も残る失敗")
        }
        try {
            act(waitFor("失敗する操作、", byDescription = true), AccessibilityNodeInfo.ACTION_CLICK)
            runBlocking { withTimeout(5_000) { started.await() } }
            instrumentation.runOnMainSync { activity.recreate() }
            release.complete(Unit)
            waitFor("再作成後も残る失敗")
            assertTrue(vm.state.value is BoardListActionState.Failed)
        } finally { finishResumedActivities() }
    }

    @Test fun failedActionSystemBackRecoversListAndAllowsAnotherAction() {
        seed(listOf(BoardRow(14, "失敗する操作", 10)))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val vm = listActions(activity as MainActivity)
        vm.execute = { error("一覧復旧後に再試行") }
        try {
            act(waitFor("失敗する操作、", byDescription = true), AccessibilityNodeInfo.ACTION_CLICK)
            waitFor("一覧復旧後に再試行")
            assertTrue(vm.state.value is BoardListActionState.Failed)
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService
                .GLOBAL_ACTION_BACK))
            waitFor("失敗する操作、", byDescription = true)
            assertEquals(BoardListActionState.Idle, vm.state.value)

            val started = CompletableDeferred<BoardListAction>()
            val release = CompletableDeferred<Unit>()
            vm.execute = { action -> started.complete(action); release.await(); commitListAction(action) }
            act(waitFor("失敗する操作、", byDescription = true), AccessibilityNodeInfo.ACTION_CLICK)
            runBlocking { withTimeout(5_000) {
                assertEquals(BoardListAction.Open(14), started.await())
            } }
            release.complete(Unit)
            waitFor("‹ 失敗する操作")
            assertEquals(BoardListActionState.Idle, vm.state.value)
        } finally { activity.finish() }
    }

    @Test fun failedActionExplicitCloseRestoresListBeforeAcknowledgement() {
        seed(listOf(BoardRow(17, "閉じて復旧", 10)))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val vm = listActions(activity as MainActivity)
        vm.execute = { error("明示閉じるの確認") }
        try {
            act(waitFor("閉じて復旧、", byDescription = true), AccessibilityNodeInfo.ACTION_CLICK)
            waitFor("明示閉じるの確認")
            assertTrue(vm.state.value is BoardListActionState.Failed)
            act(waitForExact("閉じる"), AccessibilityNodeInfo.ACTION_CLICK)
            waitFor("閉じて復旧、", byDescription = true)
            assertEquals(BoardListActionState.Idle, vm.state.value)
        } finally { activity.finish() }
    }

    @Test fun failedActionRecoveryReadFailureKeepsFailureRetryable() {
        seed(listOf(BoardRow(18, "一覧読込を再試行", 10)))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val vm = listActions(activity as MainActivity)
        vm.execute = { error("一覧復旧前の失敗") }
        val database = CanvasDatabase.open(context)
        try {
            act(waitFor("一覧読込を再試行、", byDescription = true),
                AccessibilityNodeInfo.ACTION_CLICK)
            waitFor("一覧復旧前の失敗")
            runBlocking {
                database.canvasDao().putElements(listOf(TextElementRow(
                    id = "invalid-recovery-row", boardId = 18, text = "invalid",
                    kind = "INVALID_KIND", color = "INK", x = 0f, y = 0f,
                )))
            }
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService
                .GLOBAL_ACTION_BACK))
            waitFor("No enum constant")
            assertTrue(vm.state.value is BoardListActionState.Failed)

            runBlocking { database.canvasDao().clearElements(18) }
            act(waitForExact("閉じる"), AccessibilityNodeInfo.ACTION_CLICK)
            waitFor("一覧読込を再試行、", byDescription = true)
            assertEquals(BoardListActionState.Idle, vm.state.value)
        } finally {
            runCatching { runBlocking { database.canvasDao().clearElements(18) } }
            activity.finish()
            database.close()
        }
    }

    @Test fun failureOutsideDismissalAndRecreationKeepFailureUntilRecovery() {
        seed(listOf(BoardRow(15, "外側で閉じる", 10)))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val vm = listActions(activity as MainActivity)
        vm.execute = { error("再作成しても消えない失敗") }
        try {
            act(waitFor("外側で閉じる、", byDescription = true), AccessibilityNodeInfo.ACTION_CLICK)
            waitFor("再作成しても消えない失敗")
            instrumentation.runOnMainSync { activity.recreate() }
            waitFor("再作成しても消えない失敗")
            assertTrue(vm.state.value is BoardListActionState.Failed)

            val width = context.resources.displayMetrics.widthPixels.toFloat()
            val height = context.resources.displayMetrics.heightPixels.toFloat()
            val downTime = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    width * .98f, height * .5f, 0)
                assertTrue(automation.injectInputEvent(event, true))
                event.recycle()
            }
            waitFor("外側で閉じる、", byDescription = true)
            assertEquals(BoardListActionState.Idle, vm.state.value)
        } finally { finishResumedActivities() }
    }

    @Test fun completedOpenWaitsForStartedSurvivingActivityBeforeConsume() {
        seed(listOf(BoardRow(16, "保存境界の結果", 10)))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val vm = listActions(activity as MainActivity)
        val started = CompletableDeferred<BoardListAction>()
        val release = CompletableDeferred<Unit>()
        vm.execute = { action ->
            started.complete(action)
            release.await()
            commitListAction(action)
        }
        try {
            assertTrue(vm.start(BoardListAction.Open(16)))
            runBlocking { withTimeout(5_000) { assertEquals(BoardListAction.Open(16), started.await()) } }
            instrumentation.runOnMainSync {
                var owner: Class<*>? = activity.javaClass
                var saveState: java.lang.reflect.Method? = null
                while (owner != null && saveState == null) {
                    saveState = owner.declaredMethods.firstOrNull {
                        it.name == "onSaveInstanceState" &&
                            it.parameterTypes.contentEquals(arrayOf(android.os.Bundle::class.java))
                    }
                    owner = owner.superclass
                }
                requireNotNull(saveState).apply { isAccessible = true }
                    .invoke(activity, android.os.Bundle())
                (activity.lifecycle as LifecycleRegistry).handleLifecycleEvent(
                    Lifecycle.Event.ON_STOP)
            }
            release.complete(Unit)
            repeat(40) {
                if (vm.state.value is BoardListActionState.Completed) return@repeat
                Thread.sleep(50)
            }
            assertTrue(vm.state.value is BoardListActionState.Completed)
            instrumentation.runOnMainSync { activity.recreate() }
            waitFor("‹ 保存境界の結果")
            assertEquals(BoardListActionState.Idle, vm.state.value)
        } finally { finishResumedActivities() }
    }

    @Test fun boardUndoHistorySurvivesReopenAndActivityRecreation() {
        seed(listOf(BoardRow(1, "セッション履歴", 10)))
        assertTrue(context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
            .edit().putLong("lastOpenedBoardId", 1).commit())
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            waitFor("‹ セッション履歴")
            val session = ViewModelProvider(activity as MainActivity)[BoardSessionViewModel::class.java]
            val state = session.stateFor(1L, BoardSnapshot())
            instrumentation.runOnMainSync {
                state.create("同一セッションの編集", TextKind.BODY, TextColor.INK, 24f, 32f)
            }
            runBlocking { CanvasStore.get(context).save(1L, state.snapshot()).await() }
            waitFor("同一セッションの編集", byDescription = true)

            act(waitFor("ボード一覧を開く", byDescription = true),
                AccessibilityNodeInfo.ACTION_CLICK)
            act(waitFor("セッション履歴、", byDescription = true),
                AccessibilityNodeInfo.ACTION_CLICK)
            waitFor("同一セッションの編集", byDescription = true)
            assertTrue(session.stateFor(1L, BoardSnapshot()).canUndo)

            instrumentation.runOnMainSync { activity.recreate() }
            waitFor("同一セッションの編集", byDescription = true)
            val restoredStates = mutableListOf<BoardState>()
            instrumentation.runOnMainSync {
                val recreated = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().single()
                val restoredSession = ViewModelProvider(recreated)[BoardSessionViewModel::class.java]
                restoredStates += restoredSession.stateFor(1L, BoardSnapshot())
            }
            val restoredState = restoredStates.single()
            assertSame(state, restoredState)
            assertTrue(restoredState.canUndo)
            assertTrue(restoredState.undo())
            assertFalse(restoredState.canUndo)
            assertTrue(restoredState.canRedo)
        } finally {
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>()
                    .forEach { it.finish() }
            }
        }
    }

    @Test fun pendingBoardSaveSurvivesRecreationAndBlocksLeaving() {
        seed(listOf(BoardRow(21, "保存中のボード", 10)))
        assertTrue(context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
            .edit().putLong("lastOpenedBoardId", 21).commit())
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val session = ViewModelProvider(activity)[BoardSessionViewModel::class.java]
        val board = session.stateFor(21L, BoardSnapshot())
        val started = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val startedSecond = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val savedSnapshots = mutableListOf<BoardSnapshot>()
        var calls = 0
        session.setSaveOperation { _, snapshot ->
            calls++
            savedSnapshots += snapshot
            if (calls == 1) {
                started.complete(Unit)
                releaseFirst
            } else {
                startedSecond.complete(Unit)
                releaseSecond
            }
        }
        try {
            instrumentation.runOnMainSync { session.requestSave(21L, board.snapshot()) }
            runBlocking { withTimeout(5_000) { started.await() } }
            waitFor("保存中")
            instrumentation.runOnMainSync { activity.recreate() }
            waitFor("保存中")
            instrumentation.runOnMainSync {
                assertEquals(1, calls)
                assertTrue(session.saveStateFor(21L, board.snapshot()).value is
                    com.thinkcanvas.BoardSaveState.Running)
            }

            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService
                .GLOBAL_ACTION_BACK))
            waitFor("‹ 保存中のボード")
            assertEquals(null, find(automation.rootInActiveWindow) {
                it.contentDescription?.toString()?.startsWith("保存中のボード、") == true
            })

            lateinit var latest: BoardSnapshot
            instrumentation.runOnMainSync {
                board.create("再作成後の最新", TextKind.BODY, TextColor.INK, 30f, 40f)
                latest = board.snapshot()
                session.requestSave(21L, latest)
            }
            releaseFirst.complete(Unit)
            runBlocking { withTimeout(5_000) { startedSecond.await() } }
            waitFor("保存中")
            instrumentation.runOnMainSync {
                assertEquals(2, calls)
                assertEquals(latest, savedSnapshots.last())
                assertTrue(session.saveStateFor(21L, latest).value is
                    com.thinkcanvas.BoardSaveState.Running)
            }
            releaseSecond.complete(Unit)
            instrumentation.runOnMainSync {
                assertEquals(com.thinkcanvas.BoardSaveState.Idle,
                    session.saveStateFor(21L, latest).value)
            }
            assertEquals(2, calls)
        } finally {
            releaseFirst.complete(Unit)
            releaseSecond.complete(Unit)
            finishResumedActivities()
        }
    }

    @Test fun failedSaveAndRetryRemainObservableAcrossRecreation() {
        seed(listOf(BoardRow(22, "再試行するボード", 10)))
        assertTrue(context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
            .edit().putLong("lastOpenedBoardId", 22).commit())
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val session = ViewModelProvider(activity)[BoardSessionViewModel::class.java]
        val board = session.stateFor(22L, BoardSnapshot())
        val firstStarted = CompletableDeferred<Unit>()
        val first = CompletableDeferred<Unit>()
        var calls = 0
        session.setSaveOperation { _, _ ->
            calls++
            firstStarted.complete(Unit)
            first
        }
        try {
            instrumentation.runOnMainSync { session.requestSave(22L, board.snapshot()) }
            runBlocking { withTimeout(5_000) { firstStarted.await() } }
            waitFor("保存中")
            instrumentation.runOnMainSync { activity.recreate() }
            waitFor("保存中")
            first.completeExceptionally(IllegalStateException("controlled failure"))
            waitFor("保存できません。再試行", byDescription = true)
            assertSame(board, session.stateFor(22L, BoardSnapshot()))
            assertTrue(session.saveStateFor(22L, board.snapshot()).value is
                com.thinkcanvas.BoardSaveState.Failed)
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService
                .GLOBAL_ACTION_BACK))
            waitFor("‹ 再試行するボード")
            assertEquals(null, find(automation.rootInActiveWindow) {
                it.contentDescription?.toString()?.startsWith("再試行するボード、") == true
            })

            val retryStarted = CompletableDeferred<Unit>()
            val retry = CompletableDeferred<Unit>()
            session.setSaveOperation { _, _ ->
                calls++
                retryStarted.complete(Unit)
                retry
            }
            act(waitFor("保存できません。再試行", byDescription = true),
                AccessibilityNodeInfo.ACTION_CLICK)
            runBlocking { withTimeout(5_000) { retryStarted.await() } }
            waitFor("保存中")
            instrumentation.runOnMainSync { activity.recreate() }
            waitFor("保存中")
            instrumentation.runOnMainSync {
                assertEquals(2, calls)
                assertTrue(session.saveStateFor(22L, board.snapshot()).value is
                    com.thinkcanvas.BoardSaveState.Running)
            }
            retry.complete(Unit)
            instrumentation.runOnMainSync {
                assertEquals(com.thinkcanvas.BoardSaveState.Idle,
                    session.saveStateFor(22L, board.snapshot()).value)
            }
            instrumentation.runOnMainSync { assertEquals(2, calls) }
        } finally {
            first.complete(Unit)
            finishResumedActivities()
        }
    }

    @Test fun systemBackDoesNotLeaveAnUncommittedDraft() {
        seed(listOf(BoardRow(1, "編集中", 10)))
        assertTrue(context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
            .edit().putLong("lastOpenedBoardId", 1).commit())
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            waitFor("‹ 編集中")
            val width = context.resources.displayMetrics.widthPixels.toFloat()
            val height = context.resources.displayMetrics.heightPixels.toFloat()
            val downTime = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    width * .5f, height * .5f, 0)
                assertTrue(automation.injectInputEvent(event, true))
                event.recycle()
            }
            waitFor("新しいテキスト", byDescription = true)
            repeat(2) {
                assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService
                    .GLOBAL_ACTION_BACK))
                instrumentation.waitForIdleSync()
                waitFor("新しいテキスト", byDescription = true)
            }
            assertTrue(!activity.isFinishing)
        } finally { activity.finish() }
    }

    @Test fun newTextImmediateSaveClosesOnceAndPersistsOneElementAfterRepeatedDone() {
        seed(listOf(BoardRow(1, "即時保存", 10)))
        assertTrue(context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
            .edit().putLong("lastOpenedBoardId", 1).commit())
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            waitFor("‹ 即時保存")
            val sessions = ViewModelProvider(activity as MainActivity)[BoardSessionViewModel::class.java]
            sessions.setSaveOperation { boardId, snapshot -> CanvasStore.get(context).save(boardId, snapshot) }

            val width = context.resources.displayMetrics.widthPixels.toFloat()
            val height = context.resources.displayMetrics.heightPixels.toFloat()
            val downTime = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    width * .5f, height * .5f, 0)
                assertTrue(automation.injectInputEvent(event, true))
                event.recycle()
            }
            waitFor("新しいテキスト", byDescription = true)
            val textField = waitForEditable()
            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "一度だけ保存")
            }
            assertTrue(textField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            val done = waitForExact("完了")
            act(done, AccessibilityNodeInfo.ACTION_CLICK)
            done.performAction(AccessibilityNodeInfo.ACTION_CLICK)

            waitFor("一度だけ保存", byDescription = true)
            assertEquals(null, find(automation.rootInActiveWindow) {
                it.contentDescription?.toString() == "新しいテキスト"
            })
            val database = CanvasDatabase.open(context)
            try {
                repeat(40) {
                    if (runBlocking { database.canvasDao().elements(1L) }.size == 1) return@repeat
                    Thread.sleep(100)
                }
                val committed = runBlocking { database.canvasDao().elements(1L) }
                assertEquals(1, committed.size)
                assertEquals("一度だけ保存", committed.single().text)
                assertTrue(sessions.stateFor(1L, BoardSnapshot()).elements.size == 1)
            } finally { database.close() }
        } finally { activity.finish() }
    }

    @Test fun existingTextImmediateSaveClosesEditor() {
        seed(listOf(BoardRow(1, "既存編集", 10)))
        val database = CanvasDatabase.open(context)
        runBlocking {
            database.canvasDao().replaceAll(1L, listOf(TextElementRow(
                id = "existing-draft", boardId = 1L, text = "編集前", kind = "BODY",
                color = "INK", x = 0f, y = 0f,
            )), emptyList(), emptyList())
        }
        database.close()
        assertTrue(context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
            .edit().putLong("lastOpenedBoardId", 1).commit())
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            waitFor("‹ 既存編集")
            val sessions = ViewModelProvider(activity as MainActivity)[BoardSessionViewModel::class.java]
            sessions.setSaveOperation { boardId, snapshot -> CanvasStore.get(context).save(boardId, snapshot) }
            act(waitFor("編集前", byDescription = true), AccessibilityNodeInfo.ACTION_CLICK)
            instrumentation.waitForIdleSync()
            act(waitFor("編集前", byDescription = true), AccessibilityNodeInfo.ACTION_CLICK)
            val textField = waitForEditable()
            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "編集後")
            }
            assertTrue(textField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            act(waitForExact("完了"), AccessibilityNodeInfo.ACTION_CLICK)

            waitFor("編集後", byDescription = true)
            assertEquals(null, find(automation.rootInActiveWindow) {
                it.contentDescription?.toString() == "テキストを編集"
            })
            val saved = CanvasDatabase.open(context)
            try {
                repeat(40) {
                    if (runBlocking { saved.canvasDao().elements(1L).single().text } == "編集後") return@repeat
                    Thread.sleep(100)
                }
                assertEquals("編集後", runBlocking { saved.canvasDao().elements(1L).single().text })
            } finally { saved.close() }
        } finally { activity.finish() }
    }

    @Test fun failedDraftSaveKeepsContinuationUntilRetrySucceeds() {
        seed(listOf(BoardRow(1, "再試行編集", 10)))
        assertTrue(context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
            .edit().putLong("lastOpenedBoardId", 1).commit())
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            waitFor("‹ 再試行編集")
            val sessions = ViewModelProvider(activity as MainActivity)[BoardSessionViewModel::class.java]
            val failure = CompletableDeferred<Unit>()
            var calls = 0
            sessions.setSaveOperation { boardId, snapshot ->
                if (calls++ == 0) failure else CanvasStore.get(context).save(boardId, snapshot)
            }

            val width = context.resources.displayMetrics.widthPixels.toFloat()
            val height = context.resources.displayMetrics.heightPixels.toFloat()
            val downTime = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    width * .5f, height * .5f, 0)
                assertTrue(automation.injectInputEvent(event, true))
                event.recycle()
            }
            waitFor("新しいテキスト", byDescription = true)
            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "失敗後に保存")
            }
            assertTrue(waitForEditable().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            act(waitForExact("完了"), AccessibilityNodeInfo.ACTION_CLICK)
            repeat(40) {
                if (sessions.saveStateFor(1L, BoardSnapshot()).value is com.thinkcanvas.BoardSaveState.Running)
                    return@repeat
                Thread.sleep(100)
            }
            failure.completeExceptionally(IllegalStateException("expected first save failure"))
            waitForExact("再試行")
            waitFor("新しいテキスト", byDescription = true)
            assertTrue(sessions.saveStateFor(1L, BoardSnapshot()).value is com.thinkcanvas.BoardSaveState.Failed)
            act(waitForExact("再試行"), AccessibilityNodeInfo.ACTION_CLICK)

            waitFor("失敗後に保存", byDescription = true)
            assertEquals(null, find(automation.rootInActiveWindow) {
                it.contentDescription?.toString() == "新しいテキスト"
            })
            val database = CanvasDatabase.open(context)
            try {
                repeat(40) {
                    if (runBlocking { database.canvasDao().elements(1L).size } == 1) return@repeat
                    Thread.sleep(100)
                }
                assertEquals("失敗後に保存", runBlocking { database.canvasDao().elements(1L).single().text })
                assertEquals(2, calls)
            } finally { database.close() }
        } finally { activity.finish() }
    }

    @Test fun regionNameEditBlocksSystemBackAndBoardListNavigation() {
        seed(listOf(BoardRow(1, "囲み編集中", 10)), listOf(SpatialElementRow(
            id = "region-edit", boardId = 1, kind = ShapeKind.REGION.name,
            x = 0f, y = 0f, width = 320f, height = 220f,
            color = "INK", name = "編集対象")))
        assertTrue(context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
            .edit().putLong("lastOpenedBoardId", 1).commit())
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            waitFor("‹ 囲み編集中")
            val (region, rename) = waitForCustomAction("囲みの名前を編集")
            assertTrue(region.performAction(rename.id))
            val field = waitForEditable()
            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    "入力途中")
            }
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            assertFalse("board navigation is unavailable during a region-name edit",
                waitFor("ボード一覧を開く", byDescription = true).isClickable)
            repeat(2) {
                assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService
                    .GLOBAL_ACTION_BACK))
                instrumentation.waitForIdleSync()
            }
            waitFor("‹ 囲み編集中")
            waitFor("囲みの名前", byDescription = true)
            assertTrue(!activity.isFinishing)
        } finally { activity.finish() }
    }

    @Test fun listShareLoadingRejectsOpenCreateMutationAndSecondShare() {
        seed(listOf(BoardRow(1, "共有元", 10), BoardRow(2, "開けない別案", 5)))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val vm = listActions(activity as MainActivity)
        val (queueStarted, releaseQueue) = blockCanvasStoreQueue()
        val database = CanvasDatabase.open(context)
        try {
            runBlocking { withTimeout(5_000) { queueStarted.await() } }
            act(waitFor("共有元、", byDescription = true), AccessibilityNodeInfo.ACTION_LONG_CLICK)
            val shareButton = waitFor("画像で共有")
            instrumentation.runOnMainSync {
                assertTrue(shareButton.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                assertTrue(shareButton.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }
            Thread.sleep(200)
            assertEquals(BoardListActionState.Idle, vm.state.value)

            act(waitFor("開けない別案、", byDescription = true), AccessibilityNodeInfo.ACTION_CLICK)
            act(waitFor("新しいボード", byDescription = true), AccessibilityNodeInfo.ACTION_CLICK)
            act(waitFor("共有元、", byDescription = true), AccessibilityNodeInfo.ACTION_LONG_CLICK)
            act(waitForExact("複製"), AccessibilityNodeInfo.ACTION_CLICK)
            assertEquals(BoardListActionState.Idle, vm.state.value)
            assertEquals(2, runBlocking { database.canvasDao().boards().size })
            assertEquals(-1L, context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
                .getLong("lastOpenedBoardId", -1))

            releaseQueue.complete(Unit)
            waitFor("共有元 の共有画像プレビュー", byDescription = true)
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService
                .GLOBAL_ACTION_BACK))
            act(waitFor("開けない別案、", byDescription = true), AccessibilityNodeInfo.ACTION_CLICK)
            waitFor("‹ 開けない別案")
        } finally {
            releaseQueue.complete(Unit)
            activity.finish()
            database.close()
        }
    }

    @Test fun listShareOffersPreviewAndCopyWithoutChangingSavedContent() {
        seed(listOf(BoardRow(1, "共有する案", 10), BoardRow(2, "別の案", 5)))
        val database = CanvasDatabase.open(context)
        val text = TextElementRow(id = "saved", boardId = 1, text = "共有する内容",
            kind = "BODY", color = "INK", x = 20f, y = 30f)
        runBlocking { database.canvasDao().replaceAll(1, listOf(text), emptyList(), emptyList()) }
        val before = runBlocking { database.canvasDao().board(1) }
        val otherBefore = runBlocking { database.canvasDao().board(2) }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            act(waitFor("共有する案、", byDescription = true), AccessibilityNodeInfo.ACTION_LONG_CLICK)
            act(waitFor("画像で共有"), AccessibilityNodeInfo.ACTION_CLICK)
            waitFor("共有する案 の共有画像プレビュー", byDescription = true)
            waitFor("画像を保存")
            waitFor("ほかのアプリ")
            act(waitFor("コピー"), AccessibilityNodeInfo.ACTION_CLICK)
            waitFor("共有する案、", byDescription = true)
            act(waitFor("共有する案、", byDescription = true), AccessibilityNodeInfo.ACTION_LONG_CLICK)
            act(waitFor("画像で共有"), AccessibilityNodeInfo.ACTION_CLICK)
            waitFor("共有する案 の共有画像プレビュー", byDescription = true)
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService
                .GLOBAL_ACTION_BACK))
            waitFor("共有する案、", byDescription = true)
            assertEquals(before, runBlocking { database.canvasDao().board(1) })
            assertEquals(otherBefore, runBlocking { database.canvasDao().board(2) })
            assertEquals(listOf(text), runBlocking { database.canvasDao().elements(1) })
        } finally { activity.finish(); database.close() }
    }

    @Test fun selectedTextOpensOnlyItsOwnImagePreview() {
        seed(listOf(BoardRow(1, "選択するボード", 10)))
        val database = CanvasDatabase.open(context)
        val selected = TextElementRow(id = "selected", boardId = 1, text = "選択する対象",
            kind = "BODY", color = "INK", x = 10f, y = 10f)
        val outside = TextElementRow(id = "outside", boardId = 1, text = "選択しない対象",
            kind = "BODY", color = "INK", x = 400f, y = 400f)
        runBlocking { database.canvasDao().replaceAll(1, listOf(selected, outside),
            emptyList(), emptyList()) }
        assertTrue(context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
            .edit().putLong("lastOpenedBoardId", 1).commit())
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val node = waitFor("選択する対象", byDescription = true)
            val action = node.actionList.firstOrNull { it.label?.toString() == "選択に追加" }
                ?: error("選択操作が見つかりません")
            assertTrue(node.performAction(action.id))
            val shareSelection = waitFor("選択範囲を画像で共有", byDescription = true)
            act(shareSelection, AccessibilityNodeInfo.ACTION_CLICK)
            waitFor("選択するボード の共有画像プレビュー", byDescription = true)
            waitFor("画像を保存")
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService
                .GLOBAL_ACTION_BACK))
            assertEquals(listOf(selected, outside), runBlocking { database.canvasDao().elements(1) })
        } finally { activity.finish(); database.close() }
    }


    @Test fun renameAndDuplicateKeepIndependentContent() {
        seed(listOf(BoardRow(1, "元", 10)))
        val database = CanvasDatabase.open(context)
        val text = TextElementRow(id = "source", boardId = 1, text = "複製する内容",
            kind = "BODY", color = "INK", x = 5f, y = 7f)
        runBlocking { database.canvasDao().replaceAll(1, listOf(text), emptyList(), emptyList()) }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            act(waitFor("元、", byDescription = true), AccessibilityNodeInfo.ACTION_LONG_CLICK)
            act(waitFor("名前を変える"), AccessibilityNodeInfo.ACTION_CLICK)
            val field = waitForEditable()
            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    "新しい名前")
            }
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            act(waitFor("保存"), AccessibilityNodeInfo.ACTION_CLICK)
            act(waitFor("新しい名前、", byDescription = true), AccessibilityNodeInfo.ACTION_LONG_CLICK)
            act(waitFor("複製"), AccessibilityNodeInfo.ACTION_CLICK)
            waitFor("新しい名前 のコピー、", byDescription = true)
            val copied = runBlocking { database.canvasDao().boards().first { it.id != 1L } }
            assertEquals("新しい名前 のコピー", copied.name)
            val copyText = runBlocking { database.canvasDao().elements(copied.id).single() }
            assertEquals("複製する内容", copyText.text)
            assertNotEquals(text.id, copyText.id)
            assertEquals(listOf(text), runBlocking { database.canvasDao().elements(1) })
        } finally { activity.finish(); database.close() }
    }

}
