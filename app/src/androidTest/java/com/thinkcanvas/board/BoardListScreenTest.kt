package com.thinkcanvas.board

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class BoardListScreenTest {
    @get:Rule val composeRule = createEmptyComposeRule()
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

    private fun awaitDescription(value: String, substring: Boolean = false) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithContentDescription(value, substring = substring)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(composeRule.onAllNodesWithContentDescription(value, substring = substring)
            .fetchSemanticsNodes().isNotEmpty())
    }

    private fun awaitText(value: String, substring: Boolean = false) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(value, substring = substring)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(composeRule.onAllNodesWithText(value, substring = substring)
            .fetchSemanticsNodes().isNotEmpty())
    }

    private fun tapCanvasCenter() {
        composeRule.onNodeWithContentDescription("キャンバス")
            .performTouchInput { click() }
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
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            awaitDescription("ボード1、", substring = true)
            composeRule.onNodeWithContentDescription("ボード1、", substring = true).performClick()
            awaitText("‹ ボード1")
            composeRule.onNodeWithContentDescription("ボード一覧を開く").performClick()
            awaitDescription("ボード1、", substring = true)
            composeRule.onNode(hasScrollAction()).performScrollToNode(
                hasContentDescription("ボード20、", substring = true))
            composeRule.onNodeWithContentDescription("ボード20、", substring = true).performClick()
            awaitText("‹ ボード20")
        } finally { scenario.close() }
    }

    @Test fun emptyBoardShowsExactlyOneCanonicalAccessibleHint() {
        seed(listOf(BoardRow(1, "空のボード", 10)))
        assertTrue(context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
            .edit().putLong("lastOpenedBoardId", 1).commit())
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            awaitText("‹ 空のボード")
            composeRule.waitForIdle()
            val canonicalHint = context.getString(com.thinkcanvas.R.string.empty_hint)
            val matchingVisibleHints = mutableListOf<AccessibilityNodeInfo>()
            fun collectVisibleHints(node: AccessibilityNodeInfo?) {
                if (node == null) return
                if (node.isVisibleToUser && node.text?.toString() == canonicalHint) {
                    matchingVisibleHints += node
                }
                for (index in 0 until node.childCount) collectVisibleHints(node.getChild(index))
            }
            val accessibilityRoot = requireNotNull(automation.rootInActiveWindow) {
                "Android accessibility surface was unavailable after Compose readiness"
            }
            collectVisibleHints(accessibilityRoot)
            assertEquals("canonical resource-backed hint is accessible exactly once",
                1, matchingVisibleHints.size)
            assertEquals(canonicalHint, matchingVisibleHints.single().text.toString())
        } finally { scenario.close() }
    }

    @Test fun deletionRequiresConfirmationAndKeepsOtherBoard() {
        seed(listOf(BoardRow(1, "残す", 10), BoardRow(42, "消す", 20)))
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            awaitDescription("消す、", substring = true)
            composeRule.onNodeWithContentDescription("消す、", substring = true)
                .performSemanticsAction(SemanticsActions.OnLongClick)
            composeRule.onNodeWithText("削除").performClick()
            awaitText("「消す」を削除しますか？")
            val database = CanvasDatabase.open(context)
            try {
                assertNotNull(runBlocking { database.canvasDao().board(42) })
                assertNotNull(runBlocking { database.canvasDao().board(1) })
            } finally { database.close() }
            composeRule.onNodeWithText("キャンセル").performClick()
            composeRule.onNodeWithContentDescription("消す、", substring = true)
                .performSemanticsAction(SemanticsActions.OnLongClick)
            composeRule.onNodeWithText("削除").performClick()
            awaitText("「消す」を削除しますか？")
            composeRule.onNodeWithText("削除").performClick()
            composeRule.waitUntil(5_000) {
                val database = CanvasDatabase.open(context)
                try { runBlocking { database.canvasDao().board(42) == null } }
                finally { database.close() }
            }
            val result = CanvasDatabase.open(context)
            try {
                assertEquals(null, runBlocking { result.canvasDao().board(42) })
                assertNotNull(runBlocking { result.canvasDao().board(1) })
            } finally { result.close() }
        } finally { scenario.close() }
    }

    @Test fun guideAppearsOnlyOnInitialEmptyBoardAndCanBeReplayed() {
        seed(listOf(BoardRow(1, "最初のボード", 10)))
        assertTrue(context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
            .edit().putLong("lastOpenedBoardId", 1).putLong("guideEligibleBoardId", 1)
            .putBoolean("guideDismissed", false).commit())
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            composeRule.onNodeWithText("基本の操作").assertIsDisplayed()
            composeRule.onNodeWithText("はじめる").performClick()
            composeRule.waitUntil(5_000) {
                context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
                    .getBoolean("guideDismissed", false)
            }
            composeRule.onNodeWithContentDescription("ボード一覧を開く").performClick()
            awaitDescription("最初のボード、", substring = true)
            composeRule.onNodeWithContentDescription("新しいボード").performClick()
            awaitText("‹ 無題のボード")
            assertTrue(composeRule.onAllNodesWithText("基本の操作").fetchSemanticsNodes().isEmpty())
            composeRule.onNodeWithContentDescription("ボード一覧を開く").performClick()
            awaitDescription("無題のボード、", substring = true)
            composeRule.onNodeWithText("使い方").performClick()
            composeRule.onNodeWithText("基本の操作").assertIsDisplayed()
        } finally { scenario.close() }
    }

    @Test fun guideCtaCanBeScrolledToAndActivatedAtLargeFontInCompactHeight() {
        val previousFontScale = shell("settings get system font_scale").trim()
        val previousSizeOutput = shell("wm size")
        val previousSizeOverride = Regex("Override size: (\\d+x\\d+)")
            .find(previousSizeOutput)?.groupValues?.get(1)
        val previousPhysicalSize = requireNotNull(Regex("Physical size: (\\d+x\\d+)")
            .find(previousSizeOutput)).groupValues[1]
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            shell("wm size 900x1100")
            shell("settings put system font_scale 2.0")
            seed(listOf(BoardRow(1, "最初のボード", 10)))
            assertTrue(context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
                .edit().putLong("lastOpenedBoardId", 1).putLong("guideEligibleBoardId", 1)
                .putBoolean("guideDismissed", false).commit())
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            composeRule.onNodeWithText("基本の操作").assertIsDisplayed()
            composeRule.onNodeWithText("はじめる").assertIsNotDisplayed()
            composeRule.onNodeWithText("はじめる").performScrollTo().assertIsDisplayed().performClick()
            composeRule.waitUntil(5_000) {
                context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
                    .getBoolean("guideDismissed", false)
            }
            assertTrue(composeRule.onAllNodesWithText("基本の操作").fetchSemanticsNodes().isEmpty())
            assertTrue("CTA activation persists dismissal through onStart",
                context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
                    .getBoolean("guideDismissed", false))
        } finally {
            scenario?.close()
            shell("settings put system font_scale $previousFontScale")
            if (previousSizeOverride != null) shell("wm size $previousSizeOverride")
            else shell("wm size reset")
            assertEquals(previousFontScale, shell("settings get system font_scale").trim())
            val restoredSize = shell("wm size")
            assertTrue("original display size restored",
                restoredSize.contains("Physical size: $previousPhysicalSize"))
            assertEquals(previousSizeOverride,
                Regex("Override size: (\\d+x\\d+)").find(restoredSize)?.groupValues?.get(1))
            composeRule.waitForIdle()
            Thread.sleep(500)
        }
    }

    @Test fun lastOpenedBoardReturnsAfterActivityRestart() {
        seed(listOf(BoardRow(1, "一つ目", 10), BoardRow(2, "二つ目", 20)))
        val first = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            awaitDescription("二つ目、", substring = true)
            composeRule.onNodeWithContentDescription("二つ目、", substring = true).performClick()
            awaitText("‹ 二つ目")
        } finally { first.close() }
        val reopened = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try { awaitText("‹ 二つ目") } finally { reopened.close() }
    }

    @Test fun systemBackReturnsFromBoardToList() {
        seed(listOf(BoardRow(1, "戻る対象", 10)))
        assertTrue(context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
            .edit().putLong("lastOpenedBoardId", 1).commit())
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            awaitText("‹ 戻る対象")
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService
                .GLOBAL_ACTION_BACK))
            awaitDescription("戻る対象、", substring = true)
            scenario.onActivity { assertFalse(it.isFinishing) }
        } finally { scenario.close() }
    }

    @Test fun listPageSurvivesActivityRecreation() {
        seed(listOf(BoardRow(1, "再作成対象", 10)))
        assertTrue(context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
            .edit().putLong("lastOpenedBoardId", 1).commit())
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            awaitText("‹ 再作成対象")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            awaitDescription("再作成対象、", substring = true)
            scenario.recreate()
            awaitDescription("再作成対象、", substring = true)
        } finally { scenario.close() }
    }

    @Test fun openingBoardCompletesAfterActivityRecreationWithoutStartingTwice() {
        seed(listOf(BoardRow(1, "開くA", 10), BoardRow(2, "開くB", 20)))
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        lateinit var vm: BoardListActionViewModel
        scenario.onActivity { activity -> vm = listActions(activity) }
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
            awaitDescription("開くA、", substring = true)
            composeRule.onNodeWithContentDescription("開くA、", substring = true).performClick()
            runBlocking { withTimeout(5_000) { assertEquals(BoardListAction.Open(1), started.await()) } }
            scenario.recreate()
            assertFalse(vm.start(BoardListAction.Open(1)))
            release.complete(Unit)
            awaitText("‹ 開くA")
            assertEquals(1, calls)
            assertEquals(1L, context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
                .getLong("lastOpenedBoardId", -1))
        } finally { scenario.close() }
    }

    @Test fun createCompletesOnceAfterActivityRecreation() {
        seed(listOf(BoardRow(1, "既存", 10)))
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        lateinit var vm: BoardListActionViewModel
        scenario.onActivity { activity -> vm = listActions(activity) }
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
            awaitDescription("新しいボード")
            composeRule.onNodeWithContentDescription("新しいボード").performClick()
            runBlocking { withTimeout(5_000) { assertEquals(BoardListAction.Create, started.await()) } }
            scenario.recreate()
            assertFalse(vm.start(BoardListAction.Create))
            release.complete(Unit)
            awaitText("‹ 無題のボード")
            val database = CanvasDatabase.open(context)
            try { assertEquals(2, runBlocking { database.canvasDao().boards().size }) }
            finally { database.close() }
            assertEquals(createdId, context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
                .getLong("lastOpenedBoardId", -1))
            assertEquals(1, calls)
        } finally { scenario.close() }
    }

    @Test fun runningCreateRemainsTheOnlyAllowedListActionAfterRecreation() {
        seed(emptyList())
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        lateinit var vm: BoardListActionViewModel
        scenario.onActivity { activity -> vm = listActions(activity) }
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
            awaitDescription("新しいボード")
            composeRule.onNodeWithContentDescription("新しいボード").performClick()
            runBlocking { withTimeout(5_000) { assertEquals(BoardListAction.Create, started.await()) } }
            scenario.recreate()
            assertTrue(vm.state.value is BoardListActionState.Running)
            assertFalse(vm.start(BoardListAction.Create))
            release.complete(Unit)
            awaitText("‹ 無題のボード")
            val database = CanvasDatabase.open(context)
            try { assertEquals(1, runBlocking { database.canvasDao().boards().size }) }
            finally { database.close() }
            assertEquals(1, calls)
        } finally { scenario.close() }
    }

    @Test fun renameReloadsTheListAfterActivityRecreation() {
        seed(listOf(BoardRow(11, "変更前", 10)))
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        lateinit var vm: BoardListActionViewModel
        scenario.onActivity { activity -> vm = listActions(activity) }
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
            awaitDescription("変更前、", substring = true)
            composeRule.onNodeWithContentDescription("変更前、", substring = true)
                .performSemanticsAction(SemanticsActions.OnLongClick)
            composeRule.onNodeWithText("名前を変える").performClick()
            val nameField = composeRule.onNode(hasSetTextAction())
            nameField.performTextClearance()
            nameField.performTextInput("変更後")
            var previousBounds: Rect? = null
            var stableBounds = 0
            composeRule.waitUntil(5_000) {
                var imeVisible = false
                scenario.onActivity {
                    imeVisible = it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
                }
                val nativeSave = find(automation.rootInActiveWindow) {
                    it.isVisibleToUser && it.text?.toString() == "保存"
                }
                val bounds = Rect().also { nativeSave?.getBoundsInScreen(it) }
                stableBounds = if (imeVisible && !bounds.isEmpty && bounds == previousBounds)
                    stableBounds + 1 else 0
                previousBounds = bounds
                stableBounds >= 2
            }
            nameField.assertTextEquals("変更後")
            composeRule.onNodeWithText("保存").assertIsDisplayed()
            composeRule.onNodeWithText("保存").performClick()
            runBlocking { withTimeout(5_000) {
                assertEquals(BoardListAction.Rename(11, "変更後"), started.await())
            } }
            scenario.recreate()
            assertFalse(vm.start(BoardListAction.Rename(11, "変更後")))
            release.complete(Unit)
            awaitDescription("変更後、", substring = true)
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithContentDescription("変更前、", substring = true)
                    .fetchSemanticsNodes().isEmpty()
            }
            assertEquals(1, calls)
        } finally { scenario.close() }
    }

    @Test fun duplicateReloadsExactlyOneCopyAfterActivityRecreation() {
        seed(listOf(BoardRow(12, "複製元", 10)))
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        lateinit var vm: BoardListActionViewModel
        scenario.onActivity { activity -> vm = listActions(activity) }
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
            awaitDescription("複製元、", substring = true)
            composeRule.onNodeWithContentDescription("複製元、", substring = true)
                .performSemanticsAction(SemanticsActions.OnLongClick)
            composeRule.onNodeWithText("複製").performClick()
            runBlocking { withTimeout(5_000) { assertEquals(BoardListAction.Duplicate(12), started.await()) } }
            scenario.recreate()
            assertFalse(vm.start(BoardListAction.Duplicate(12)))
            release.complete(Unit)
            awaitDescription("複製元 のコピー、", substring = true)
            val database = CanvasDatabase.open(context)
            try { assertEquals(2, runBlocking { database.canvasDao().boards().size }) }
            finally { database.close() }
            assertEquals(1, calls)
        } finally { scenario.close() }
    }

    @Test fun deleteDoesNotRestoreTheDeletedBoardAfterActivityRecreation() {
        seed(listOf(BoardRow(13, "削除対象", 10)))
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        lateinit var vm: BoardListActionViewModel
        scenario.onActivity { activity -> vm = listActions(activity) }
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
            awaitDescription("削除対象、", substring = true)
            composeRule.onNodeWithContentDescription("削除対象、", substring = true)
                .performSemanticsAction(SemanticsActions.OnLongClick)
            composeRule.onNodeWithText("削除").performClick()
            awaitText("「削除対象」を削除しますか？")
            val beforeConfirmation = CanvasDatabase.open(context)
            try { assertNotNull(runBlocking { beforeConfirmation.canvasDao().board(13) }) }
            finally { beforeConfirmation.close() }
            composeRule.onNodeWithText("削除").performClick()
            runBlocking { withTimeout(5_000) { assertEquals(BoardListAction.Delete(13), started.await()) } }
            scenario.recreate()
            assertFalse(vm.start(BoardListAction.Delete(13)))
            release.complete(Unit)
            awaitText("ボードはまだありません")
            val database = CanvasDatabase.open(context)
            try { assertEquals(null, runBlocking { database.canvasDao().board(13) }) }
            finally { database.close() }
            assertEquals(1, calls)
        } finally { scenario.close() }
    }

    @Test fun actionFailureIsShownAfterActivityRecreation() {
        seed(listOf(BoardRow(14, "失敗する操作", 10)))
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        lateinit var vm: BoardListActionViewModel
        scenario.onActivity { vm = listActions(it) }
        val started = CompletableDeferred<BoardListAction>()
        val release = CompletableDeferred<Unit>()
        vm.execute = { action ->
            started.complete(action)
            release.await()
            error("再作成後も残る失敗")
        }
        try {
            awaitDescription("失敗する操作、", substring = true)
            composeRule.onNodeWithContentDescription("失敗する操作、", substring = true).performClick()
            runBlocking { withTimeout(5_000) { started.await() } }
            scenario.recreate()
            release.complete(Unit)
            awaitText("再作成後も残る失敗")
            assertTrue(vm.state.value is BoardListActionState.Failed)
        } finally { scenario.close() }
    }

    @Test fun failedActionSystemBackRecoversListAndAllowsAnotherAction() {
        seed(listOf(BoardRow(14, "失敗する操作", 10)))
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        lateinit var vm: BoardListActionViewModel
        scenario.onActivity { vm = listActions(it) }
        vm.execute = { error("一覧復旧後に再試行") }
        try {
            awaitDescription("失敗する操作、", substring = true)
            composeRule.onNodeWithContentDescription("失敗する操作、", substring = true).performClick()
            awaitText("一覧復旧後に再試行")
            assertTrue(vm.state.value is BoardListActionState.Failed)
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService
                .GLOBAL_ACTION_BACK))
            composeRule.waitUntil(5_000) { vm.state.value == BoardListActionState.Idle }
            awaitDescription("失敗する操作、", substring = true)
            assertEquals(BoardListActionState.Idle, vm.state.value)

            val started = CompletableDeferred<BoardListAction>()
            val release = CompletableDeferred<Unit>()
            vm.execute = { action -> started.complete(action); release.await(); commitListAction(action) }
            composeRule.onNodeWithContentDescription("失敗する操作、", substring = true).performClick()
            runBlocking { withTimeout(5_000) {
                assertEquals(BoardListAction.Open(14), started.await())
            } }
            release.complete(Unit)
            awaitText("‹ 失敗する操作")
            assertEquals(BoardListActionState.Idle, vm.state.value)
        } finally { scenario.close() }
    }

    @Test fun failedActionExplicitCloseRestoresListBeforeAcknowledgement() {
        seed(listOf(BoardRow(17, "閉じて復旧", 10)))
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        lateinit var vm: BoardListActionViewModel
        scenario.onActivity { vm = listActions(it) }
        vm.execute = { error("明示閉じるの確認") }
        try {
            awaitDescription("閉じて復旧、", substring = true)
            composeRule.onNodeWithContentDescription("閉じて復旧、", substring = true).performClick()
            awaitText("明示閉じるの確認")
            assertTrue(vm.state.value is BoardListActionState.Failed)
            composeRule.onNodeWithText("閉じる").performClick()
            composeRule.waitUntil(5_000) { vm.state.value == BoardListActionState.Idle }
            awaitDescription("閉じて復旧、", substring = true)
            assertEquals(BoardListActionState.Idle, vm.state.value)
        } finally { scenario.close() }
    }

    @Test fun failedActionRecoveryReadFailureKeepsFailureRetryable() {
        seed(listOf(BoardRow(18, "一覧読込を再試行", 10)))
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        lateinit var vm: BoardListActionViewModel
        scenario.onActivity { vm = listActions(it) }
        vm.execute = { error("一覧復旧前の失敗") }
        val database = CanvasDatabase.open(context)
        try {
            awaitDescription("一覧読込を再試行、", substring = true)
            composeRule.onNodeWithContentDescription("一覧読込を再試行、", substring = true)
                .performClick()
            awaitText("一覧復旧前の失敗")
            runBlocking {
                database.canvasDao().putElements(listOf(TextElementRow(
                    id = "invalid-recovery-row", boardId = 18, text = "invalid",
                    kind = "INVALID_KIND", color = "INK", x = 0f, y = 0f,
                )))
            }
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService
                .GLOBAL_ACTION_BACK))
            awaitText("No enum constant", substring = true)
            assertTrue(vm.state.value is BoardListActionState.Failed)

            runBlocking { database.canvasDao().clearElements(18) }
            composeRule.onNodeWithText("閉じる").performClick()
            awaitDescription("一覧読込を再試行、", substring = true)
            assertEquals(BoardListActionState.Idle, vm.state.value)
        } finally {
            runCatching { runBlocking { database.canvasDao().clearElements(18) } }
            scenario.close()
            database.close()
        }
    }

    @Test fun failureOutsideDismissalAndRecreationKeepFailureUntilRecovery() {
        seed(listOf(BoardRow(15, "外側で閉じる", 10)))
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        lateinit var vm: BoardListActionViewModel
        scenario.onActivity { vm = listActions(it) }
        vm.execute = { error("再作成しても消えない失敗") }
        try {
            awaitDescription("外側で閉じる、", substring = true)
            composeRule.onNodeWithContentDescription("外側で閉じる、", substring = true).performClick()
            awaitText("再作成しても消えない失敗")
            scenario.recreate()
            awaitText("再作成しても消えない失敗")
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
            composeRule.waitUntil(5_000) { vm.state.value == BoardListActionState.Idle }
            awaitDescription("外側で閉じる、", substring = true)
            assertEquals(BoardListActionState.Idle, vm.state.value)
        } finally { scenario.close() }
    }

    @Test fun completedOpenWaitsForStartedSurvivingActivityBeforeConsume() {
        seed(listOf(BoardRow(16, "保存境界の結果", 10)))
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        lateinit var vm: BoardListActionViewModel
        scenario.onActivity { vm = listActions(it) }
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
            scenario.onActivity { activity ->
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
            composeRule.waitUntil(5_000) {
                vm.state.value is BoardListActionState.Completed
            }
            assertTrue(vm.state.value is BoardListActionState.Completed)
            scenario.recreate()
            awaitText("‹ 保存境界の結果")
            assertEquals(BoardListActionState.Idle, vm.state.value)
        } finally { scenario.close() }
    }

    @Test fun boardUndoHistorySurvivesReopenAndActivityRecreation() {
        seed(listOf(BoardRow(1, "セッション履歴", 10)))
        assertTrue(context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
            .edit().putLong("lastOpenedBoardId", 1).commit())
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            awaitText("‹ セッション履歴")
            lateinit var session: BoardSessionViewModel
            scenario.onActivity { session = ViewModelProvider(it)[BoardSessionViewModel::class.java] }
            val state = session.stateFor(1L, BoardSnapshot())
            scenario.onActivity {
                state.create("同一セッションの編集", TextKind.BODY, TextColor.INK, 24f, 32f)
            }
            runBlocking { CanvasStore.get(context).save(1L, state.snapshot()).await() }
            awaitDescription("同一セッションの編集", substring = true)

            composeRule.onNodeWithContentDescription("ボード一覧を開く").performClick()
            awaitDescription("セッション履歴、", substring = true)
            composeRule.onNodeWithContentDescription("セッション履歴、", substring = true).performClick()
            awaitDescription("同一セッションの編集", substring = true)
            assertTrue(session.stateFor(1L, BoardSnapshot()).canUndo)

            scenario.recreate()
            awaitDescription("同一セッションの編集", substring = true)
            lateinit var restoredState: BoardState
            scenario.onActivity { recreated ->
                val restoredSession = ViewModelProvider(recreated)[BoardSessionViewModel::class.java]
                restoredState = restoredSession.stateFor(1L, BoardSnapshot())
            }
            assertSame(state, restoredState)
            assertTrue(restoredState.canUndo)
            assertTrue(restoredState.undo())
            assertFalse(restoredState.canUndo)
            assertTrue(restoredState.canRedo)
        } finally { scenario.close() }
    }

    @Test fun pendingBoardSaveSurvivesRecreationAndBlocksLeaving() {
        seed(listOf(BoardRow(21, "保存中のボード", 10)))
        assertTrue(context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
            .edit().putLong("lastOpenedBoardId", 21).commit())
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        lateinit var session: BoardSessionViewModel
        scenario.onActivity { session = ViewModelProvider(it)[BoardSessionViewModel::class.java] }
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
            scenario.onActivity { session.requestSave(21L, board.snapshot()) }
            runBlocking { withTimeout(5_000) { started.await() } }
            awaitText("保存中", substring = true)
            scenario.recreate()
            awaitText("保存中", substring = true)
            scenario.onActivity {
                assertEquals(1, calls)
                assertTrue(session.saveStateFor(21L, board.snapshot()).value is
                    com.thinkcanvas.BoardSaveState.Running)
            }

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            awaitText("‹ 保存中のボード")
            assertTrue(composeRule.onAllNodesWithContentDescription("保存中のボード、", substring = true)
                .fetchSemanticsNodes().isEmpty())

            lateinit var latest: BoardSnapshot
            scenario.onActivity {
                board.create("再作成後の最新", TextKind.BODY, TextColor.INK, 30f, 40f)
                latest = board.snapshot()
                session.requestSave(21L, latest)
            }
            releaseFirst.complete(Unit)
            runBlocking { withTimeout(5_000) { startedSecond.await() } }
            awaitText("保存中", substring = true)
            scenario.onActivity {
                assertEquals(2, calls)
                assertEquals(latest, savedSnapshots.last())
                assertTrue(session.saveStateFor(21L, latest).value is
                    com.thinkcanvas.BoardSaveState.Running)
            }
            releaseSecond.complete(Unit)
            composeRule.waitUntil(5_000) {
                session.saveStateFor(21L, latest).value == com.thinkcanvas.BoardSaveState.Idle
            }
            assertEquals(2, calls)
        } finally {
            releaseFirst.complete(Unit)
            releaseSecond.complete(Unit)
            scenario.close()
        }
    }

    @Test fun failedSaveAndRetryRemainObservableAcrossRecreation() {
        seed(listOf(BoardRow(22, "再試行するボード", 10)))
        assertTrue(context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
            .edit().putLong("lastOpenedBoardId", 22).commit())
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        lateinit var session: BoardSessionViewModel
        scenario.onActivity { session = ViewModelProvider(it)[BoardSessionViewModel::class.java] }
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
            scenario.onActivity { session.requestSave(22L, board.snapshot()) }
            runBlocking { withTimeout(5_000) { firstStarted.await() } }
            awaitText("保存中", substring = true)
            scenario.recreate()
            awaitText("保存中", substring = true)
            first.completeExceptionally(IllegalStateException("controlled failure"))
            awaitDescription("保存できません。再試行")
            assertSame(board, session.stateFor(22L, BoardSnapshot()))
            assertTrue(session.saveStateFor(22L, board.snapshot()).value is
                com.thinkcanvas.BoardSaveState.Failed)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            awaitText("‹ 再試行するボード")
            assertTrue(composeRule.onAllNodesWithContentDescription("再試行するボード、", substring = true)
                .fetchSemanticsNodes().isEmpty())

            val retryStarted = CompletableDeferred<Unit>()
            val retry = CompletableDeferred<Unit>()
            session.setSaveOperation { _, _ ->
                calls++
                retryStarted.complete(Unit)
                retry
            }
            composeRule.onNodeWithContentDescription("保存できません。再試行").performClick()
            runBlocking { withTimeout(5_000) { retryStarted.await() } }
            awaitText("保存中", substring = true)
            scenario.recreate()
            awaitText("保存中", substring = true)
            scenario.onActivity {
                assertEquals(2, calls)
                assertTrue(session.saveStateFor(22L, board.snapshot()).value is
                    com.thinkcanvas.BoardSaveState.Running)
            }
            retry.complete(Unit)
            composeRule.waitUntil(5_000) {
                session.saveStateFor(22L, board.snapshot()).value == com.thinkcanvas.BoardSaveState.Idle
            }
            assertEquals(2, calls)
        } finally {
            first.complete(Unit)
            scenario.close()
        }
    }

    @Test fun systemBackDoesNotLeaveAnUncommittedDraft() {
        seed(listOf(BoardRow(1, "編集中", 10)))
        assertTrue(context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
            .edit().putLong("lastOpenedBoardId", 1).commit())
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            awaitText("‹ 編集中")
            tapCanvasCenter()
            awaitDescription("新しいテキスト")
            composeRule.onNodeWithContentDescription("新しいテキスト").performTextReplacement("未確定")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            composeRule.waitUntil(5_000) {
                var hidden = false
                scenario.onActivity {
                    val insets = checkNotNull(it.window.decorView.rootWindowInsets)
                    hidden = !insets.isVisible(android.view.WindowInsets.Type.ime()) &&
                        insets.getInsets(android.view.WindowInsets.Type.ime()).bottom == 0
                }
                hidden
            }
            composeRule.waitForIdle()
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService
                .GLOBAL_ACTION_BACK))
            awaitText("編集内容を破棄しますか？")
            composeRule.onNodeWithText("編集を続ける").performClick()
            awaitDescription("新しいテキスト")
            composeRule.onNodeWithContentDescription("新しいテキスト").assertTextEquals("未確定")
            scenario.onActivity { assertTrue(!it.isFinishing) }
        } finally { scenario.close() }
    }

    @Test fun newTextImmediateSaveClosesOnceAndPersistsOneElementAfterRepeatedDone() {
        seed(listOf(BoardRow(1, "即時保存", 10)))
        assertTrue(context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
            .edit().putLong("lastOpenedBoardId", 1).commit())
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            awaitText("‹ 即時保存")
            lateinit var sessions: BoardSessionViewModel
            scenario.onActivity { sessions = ViewModelProvider(it)[BoardSessionViewModel::class.java] }
            sessions.setSaveOperation { boardId, snapshot -> CanvasStore.get(context).save(boardId, snapshot) }

            tapCanvasCenter()
            awaitDescription("新しいテキスト")
            composeRule.onNode(hasSetTextAction()).performTextInput("一度だけ保存")
            val doneAction = requireNotNull(composeRule.onNodeWithText("完了")
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            composeRule.runOnIdle {
                assertTrue(doneAction())
                assertTrue(doneAction())
            }

            awaitDescription("一度だけ保存", substring = true)
            assertTrue(composeRule.onAllNodesWithContentDescription("新しいテキスト")
                .fetchSemanticsNodes().isEmpty())
            val database = CanvasDatabase.open(context)
            try {
                composeRule.waitUntil(5_000) {
                    runBlocking { database.canvasDao().elements(1L).size == 1 }
                }
                val committed = runBlocking { database.canvasDao().elements(1L) }
                assertEquals(1, committed.size)
                assertEquals("一度だけ保存", committed.single().text)
                assertTrue(sessions.stateFor(1L, BoardSnapshot()).elements.size == 1)
            } finally { database.close() }
        } finally { scenario.close() }
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
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            awaitText("‹ 既存編集")
            lateinit var sessions: BoardSessionViewModel
            scenario.onActivity { sessions = ViewModelProvider(it)[BoardSessionViewModel::class.java] }
            sessions.setSaveOperation { boardId, snapshot -> CanvasStore.get(context).save(boardId, snapshot) }
            awaitDescription("編集前", substring = true)
            composeRule.onNodeWithContentDescription("編集前", substring = true).performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("編集前", substring = true).performClick()
            composeRule.onNode(hasSetTextAction()).performTextClearance()
            composeRule.onNode(hasSetTextAction()).performTextInput("編集後")
            composeRule.onNodeWithText("完了").performClick()

            awaitDescription("編集後", substring = true)
            assertTrue(composeRule.onAllNodesWithContentDescription("テキストを編集")
                .fetchSemanticsNodes().isEmpty())
            val saved = CanvasDatabase.open(context)
            try {
                composeRule.waitUntil(5_000) {
                    runBlocking { saved.canvasDao().elements(1L).single().text == "編集後" }
                }
                assertEquals("編集後", runBlocking { saved.canvasDao().elements(1L).single().text })
            } finally { saved.close() }
        } finally { scenario.close() }
    }

    @Test fun failedDraftSaveKeepsContinuationUntilRetrySucceeds() {
        seed(listOf(BoardRow(1, "再試行編集", 10)))
        assertTrue(context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
            .edit().putLong("lastOpenedBoardId", 1).commit())
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            awaitText("‹ 再試行編集")
            lateinit var sessions: BoardSessionViewModel
            scenario.onActivity { sessions = ViewModelProvider(it)[BoardSessionViewModel::class.java] }
            val failure = CompletableDeferred<Unit>()
            var calls = 0
            sessions.setSaveOperation { boardId, snapshot ->
                if (calls++ == 0) failure else CanvasStore.get(context).save(boardId, snapshot)
            }

            tapCanvasCenter()
            awaitDescription("新しいテキスト")
            composeRule.onNode(hasSetTextAction()).performTextInput("失敗後に保存")
            composeRule.onNodeWithText("完了").performClick()
            composeRule.waitUntil(5_000) {
                sessions.saveStateFor(1L, BoardSnapshot()).value is com.thinkcanvas.BoardSaveState.Running
            }
            failure.completeExceptionally(IllegalStateException("expected first save failure"))
            awaitText("再試行")
            awaitDescription("新しいテキスト")
            assertTrue(sessions.saveStateFor(1L, BoardSnapshot()).value is com.thinkcanvas.BoardSaveState.Failed)
            composeRule.onNodeWithText("再試行").performClick()

            awaitDescription("失敗後に保存", substring = true)
            assertTrue(composeRule.onAllNodesWithContentDescription("新しいテキスト")
                .fetchSemanticsNodes().isEmpty())
            val database = CanvasDatabase.open(context)
            try {
                composeRule.waitUntil(5_000) {
                    runBlocking { database.canvasDao().elements(1L).size == 1 }
                }
                assertEquals("失敗後に保存", runBlocking { database.canvasDao().elements(1L).single().text })
                assertEquals(2, calls)
            } finally { database.close() }
        } finally { scenario.close() }
    }

    @Test fun regionNameEditBlocksSystemBackAndBoardListNavigation() {
        seed(listOf(BoardRow(1, "囲み編集中", 10)), listOf(SpatialElementRow(
            id = "region-edit", boardId = 1, kind = ShapeKind.REGION.name,
            x = 0f, y = 0f, width = 320f, height = 220f,
            color = "INK", name = "編集対象")))
        assertTrue(context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
            .edit().putLong("lastOpenedBoardId", 1).commit())
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            awaitText("‹ 囲み編集中")
            awaitDescription("囲み: 編集対象")
            val (region, rename) = waitForCustomAction("囲みの名前を編集")
            assertTrue(region.performAction(rename.id))
            awaitDescription("囲みの名前")
            composeRule.onNodeWithContentDescription("囲みの名前").performTextInput("入力途中")
            composeRule.onNodeWithContentDescription("ボード一覧を開く").assertIsNotEnabled()
            repeat(2) {
                assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService
                    .GLOBAL_ACTION_BACK))
                composeRule.waitForIdle()
            }
            awaitText("‹ 囲み編集中")
            awaitDescription("囲みの名前")
            scenario.onActivity { assertTrue(!it.isFinishing) }
        } finally { scenario.close() }
    }

    @Test fun listShareLoadingRejectsOpenCreateMutationAndSecondShare() {
        seed(listOf(BoardRow(1, "共有元", 10), BoardRow(2, "開けない別案", 5)))
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        lateinit var vm: BoardListActionViewModel
        scenario.onActivity { vm = listActions(it) }
        val (queueStarted, releaseQueue) = blockCanvasStoreQueue()
        val database = CanvasDatabase.open(context)
        try {
            runBlocking { withTimeout(5_000) { queueStarted.await() } }
            awaitDescription("共有元、", substring = true)
            composeRule.onNodeWithContentDescription("共有元、", substring = true)
                .performSemanticsAction(SemanticsActions.OnLongClick)
            composeRule.onNodeWithText("画像で共有")
                .performSemanticsAction(SemanticsActions.OnClick) { click ->
                    composeRule.runOnUiThread {
                        assertTrue(click())
                        assertTrue(click())
                    }
                }
            composeRule.waitForIdle()
            assertEquals(BoardListActionState.Idle, vm.state.value)

            composeRule.onNodeWithContentDescription("開けない別案、", substring = true).performClick()
            composeRule.onNodeWithContentDescription("新しいボード").performClick()
            composeRule.onNodeWithContentDescription("共有元、", substring = true)
                .performSemanticsAction(SemanticsActions.OnLongClick)
            composeRule.onNodeWithText("複製").performClick()
            assertEquals(BoardListActionState.Idle, vm.state.value)
            assertEquals(2, runBlocking { database.canvasDao().boards().size })
            assertEquals(-1L, context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
                .getLong("lastOpenedBoardId", -1))

            releaseQueue.complete(Unit)
            awaitDescription("共有元 の共有画像プレビュー")
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService
                .GLOBAL_ACTION_BACK))
            awaitDescription("開けない別案、", substring = true)
            composeRule.onNodeWithContentDescription("開けない別案、", substring = true).performClick()
            awaitText("‹ 開けない別案")
        } finally {
            releaseQueue.complete(Unit)
            scenario.close()
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
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            awaitDescription("共有する案、", substring = true)
            composeRule.onNodeWithContentDescription("共有する案、", substring = true)
                .performSemanticsAction(SemanticsActions.OnLongClick)
            composeRule.onNodeWithText("画像で共有").performClick()
            awaitDescription("共有する案 の共有画像プレビュー")
            awaitText("画像を保存")
            awaitText("ほかのアプリ")
            // プレビュー枠だけでなく、Bitmap 公開後の描画を確認してから操作する。
            composeRule.waitUntil(5_000) {
                !composeRule.onNodeWithText("コピー").fetchSemanticsNode()
                    .config.contains(SemanticsProperties.Disabled)
            }
            composeRule.onNodeWithText("コピー").assertIsEnabled()
            val firstPreview = composeRule.onNodeWithContentDescription("共有する案 の共有画像プレビュー")
                .assertIsDisplayed().captureToImage()
            assertTrue(firstPreview.width > 0 && firstPreview.height > 0)
            composeRule.onNodeWithText("コピー").performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithContentDescription("共有する案 の共有画像プレビュー")
                    .fetchSemanticsNodes().isEmpty()
            }
            composeRule.waitForIdle()
            awaitDescription("共有する案、", substring = true)
            val returnedCard = composeRule.onNodeWithContentDescription("共有する案、", substring = true)
                .assertIsDisplayed().captureToImage()
            assertTrue(returnedCard.width > 0 && returnedCard.height > 0)
            composeRule.onNodeWithContentDescription("共有する案、", substring = true)
                .performSemanticsAction(SemanticsActions.OnLongClick)
            composeRule.onNodeWithText("画像で共有").performClick()
            awaitDescription("共有する案 の共有画像プレビュー")
            composeRule.waitUntil(5_000) {
                !composeRule.onNodeWithText("コピー").fetchSemanticsNode()
                    .config.contains(SemanticsProperties.Disabled)
            }
            composeRule.onNodeWithText("コピー").assertIsEnabled()
            composeRule.onNodeWithText("画像を保存").assertIsEnabled()
            composeRule.onNodeWithText("ほかのアプリ").assertIsEnabled()
            val secondPreview = composeRule.onNodeWithContentDescription("共有する案 の共有画像プレビュー")
                .assertIsDisplayed().captureToImage()
            assertEquals(firstPreview.width, secondPreview.width)
            assertEquals(firstPreview.height, secondPreview.height)
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService
                .GLOBAL_ACTION_BACK))
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithContentDescription("共有する案 の共有画像プレビュー")
                    .fetchSemanticsNodes().isEmpty()
            }
            composeRule.waitForIdle()
            awaitDescription("共有する案、", substring = true)
            assertEquals(before, runBlocking { database.canvasDao().board(1) })
            assertEquals(otherBefore, runBlocking { database.canvasDao().board(2) })
            assertEquals(listOf(text), runBlocking { database.canvasDao().elements(1) })
        } finally { scenario.close(); database.close() }
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
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            awaitDescription("選択する対象")
            val actions = composeRule.onNodeWithContentDescription("選択する対象")
                .fetchSemanticsNode().config[SemanticsActions.CustomActions]
            val action = actions.firstOrNull { it.label == "選択に追加" }
                ?: error("選択操作が見つかりません")
            composeRule.runOnUiThread { assertTrue(action.action()) }
            awaitDescription("選択範囲を画像で共有")
            composeRule.onNodeWithContentDescription("選択範囲を画像で共有").performClick()
            awaitDescription("選択するボード の共有画像プレビュー")
            awaitText("画像を保存")
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService
                .GLOBAL_ACTION_BACK))
            assertEquals(listOf(selected, outside), runBlocking { database.canvasDao().elements(1) })
        } finally { scenario.close(); database.close() }
    }

    @Test fun renameAndDuplicateKeepIndependentContent() {
        seed(listOf(BoardRow(1, "元", 10)))
        val database = CanvasDatabase.open(context)
        val text = TextElementRow(id = "source", boardId = 1, text = "複製する内容",
            kind = "BODY", color = "INK", x = 5f, y = 7f)
        runBlocking { database.canvasDao().replaceAll(1, listOf(text), emptyList(), emptyList()) }
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            awaitDescription("元、", substring = true)
            composeRule.onNodeWithContentDescription("元、", substring = true)
                .performSemanticsAction(SemanticsActions.OnLongClick)
            composeRule.onNodeWithText("名前を変える").performClick()
            composeRule.onNode(hasSetTextAction()).performTextClearance()
            composeRule.onNode(hasSetTextAction()).performTextInput("新しい名前")
            composeRule.onNodeWithText("保存").performClick()
            awaitDescription("新しい名前、", substring = true)
            composeRule.onNodeWithContentDescription("新しい名前、", substring = true)
                .performSemanticsAction(SemanticsActions.OnLongClick)
            composeRule.onNodeWithText("複製").performClick()
            awaitDescription("新しい名前 のコピー、", substring = true)
            val copied = runBlocking { database.canvasDao().boards().first { it.id != 1L } }
            assertEquals("新しい名前 のコピー", copied.name)
            val copyText = runBlocking { database.canvasDao().elements(copied.id).single() }
            assertEquals("複製する内容", copyText.text)
            assertNotEquals(text.id, copyText.id)
            assertEquals(listOf(text), runBlocking { database.canvasDao().elements(1) })
        } finally { scenario.close(); database.close() }
    }

}
