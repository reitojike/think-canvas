package com.thinkcanvas.canvas

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.InputDevice
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import com.thinkcanvas.BoardSaveAcknowledgement
import com.thinkcanvas.BoardSaveState
import com.thinkcanvas.BoardSessionViewModel
import com.thinkcanvas.MainActivity
import com.thinkcanvas.data.BoardRow
import com.thinkcanvas.data.CanvasDatabase
import com.thinkcanvas.data.SpatialElementRow
import com.thinkcanvas.data.TextElementRow
import com.thinkcanvas.data.showBoardOneAtStartup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import com.thinkcanvas.test.PrSmoke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
    fun launcherGeometryRecordPreservesStrictBoundsFailure() {
        // #148 の原因再現ではなく、実際の記録・assert 境界を既取得 Rect で検証する。
        val label = "図形ツールを開く"
        val descriptionBounds = Rect(912, 1085, 1038, 1211)
        val controlBounds = Rect(912, 1142, 1038, 1268)
        val original = assertThrows(AssertionError::class.java) {
            assertEquals("$label の description と control は同じ bounds",
                descriptionBounds, controlBounds)
        }
        val records = mutableListOf<String>()
        for (sinkThrows in listOf(false, true)) {
            val actual = assertThrows(AssertionError::class.java) {
                assertControlBounds(label, descriptionBounds, controlBounds, 2, record = { record ->
                    records += record
                    if (sinkThrows) throw AssertionError("記録 sink の失敗")
                })
            }
            assertEquals(original.message, actual.message)
        }
        // 模擬 test 自身の logcat で default sink と既存 artifact への保持を確認する。
        val emitted = assertThrows(AssertionError::class.java) {
            assertControlBounds(label, descriptionBounds, controlBounds, 2)
        }
        assertEquals(original.message, emitted.message)
        assertEquals(listOf(
            "label=$label parentHops=2 description=$descriptionBounds control=$controlBounds",
            "label=$label parentHops=2 description=$descriptionBounds control=$controlBounds",
        ), records)
    }

    @PrSmoke
    @Test
    fun launcherGeometryRecordIgnoresEqualAndOtherControls() {
        val records = mutableListOf<String>()
        val equal = Rect(10, 20, 30, 40)
        assertControlBounds("図形ツールを開く", equal, Rect(equal), 0, records::add)
        val actual = assertThrows(AssertionError::class.java) {
            assertControlBounds("ペン", equal, Rect(10, 21, 30, 41), 1, records::add)
        }
        assertTrue(actual.message!!.startsWith("ペン の description と control は同じ bounds"))
        assertTrue(records.isEmpty())
    }

    @PrSmoke
    @Test
    fun launcherRefreshPreservesStrictPlatformFailures() {
        val label = "図形ツールを開く"
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val rect = Rect(912, 1073, 1038, 1199)
        fun rejected(refresh: (AccessibilityNodeInfo) -> Boolean) {
            val boundsReads = intArrayOf(0)
            val node = platformNodeFixture(label, rect, true, null, boundsReads)
            var refreshCalls = 0
            var actions = 0
            assertThrows(AssertionError::class.java) {
                clickNode(instrumentation, node, label,
                    refresh = { refreshCalls++; refresh(it) },
                    click = { beforeOnClick -> beforeOnClick(); actions++ })
            }
            assertEquals(1, refreshCalls)
            assertEquals(0, boundsReads[0])
            assertEquals(0, actions)
        }
        rejected { false }
        rejected { it.isEnabled = false; true }
        rejected { it.isVisibleToUser = false; true }
        rejected { it.contentDescription = "別の control"; true }
        rejected { it.setSource(android.view.View(instrumentation.targetContext)); true }

        fun rejectedControl(refresh: (AccessibilityNodeInfo) -> Boolean) {
            val boundsReads = intArrayOf(0)
            val control = platformNodeFixture(null, rect, true, null, boundsReads)
            val description = platformNodeFixture(label, rect, false, control, boundsReads)
            var descriptionRefreshes = 0
            var controlRefreshes = 0
            var records = 0
            var actions = 0
            assertThrows(AssertionError::class.java) {
                clickNode(instrumentation, description, label,
                    refresh = {
                        if (it === description) { descriptionRefreshes++; true } else {
                            assertTrue(it === control)
                            controlRefreshes++
                            refresh(it)
                        }
                    }, record = { records++ }, click = { beforeOnClick -> beforeOnClick(); actions++ })
            }
            assertEquals(1, descriptionRefreshes)
            assertEquals(1, controlRefreshes)
            assertEquals(0, boundsReads[0])
            assertEquals(0, records)
            assertEquals(0, actions)
        }
        rejectedControl { false }
        rejectedControl { it.isClickable = false; true }
        rejectedControl { it.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK); true }

        val sameReads = intArrayOf(0)
        val same = platformNodeFixture(label, rect, true, null, sameReads)
        var sameRefreshes = 0
        // 取得時／click前の二段で再検証でき、0hopは各段で1回だけ。
        repeat(2) {
            assertTrue(same === assertPlatformControl(same, label, refresh = { sameRefreshes++; true }))
        }
        assertEquals(2, sameRefreshes)
        assertEquals(4, sameReads[0])
        val controlRect = Rect(913, 1073, 1039, 1199)
        val original = assertThrows(AssertionError::class.java) {
            assertNonEmptyEqualBounds("Option launcher", rect, controlRect)
        }
        assertThrows(AssertionError::class.java) {
            assertNonEmptyEqualBounds("Option launcher", Rect(), Rect())
        }
        assertThrows(IllegalStateException::class.java) {
            uniqueAppDescription(listOf(descriptionForFixture(label, rect), descriptionForFixture(label, rect)))
        }
        assertThrows(IllegalStateException::class.java) {
            assertCurrentAppRoot(true, false, true, 1, 1)
        }
        assertThrows(IllegalStateException::class.java) {
            assertCurrentAppRoot(true, true, true, 2, 1)
        }
        val appProcessNode = descriptionForFixture(label, rect).apply {
            setSource(android.view.View(instrumentation.targetContext), 0)
        }
        val externalNode = descriptionForFixture(label, rect).apply {
            setSource(android.view.View(instrumentation.targetContext), 0)
        }
        assertThrows(IllegalStateException::class.java) {
            assertMappedAppNode(appProcessNode, externalNode, appProcessNode.windowId, "description")
        }
        val boundsReads = intArrayOf(0)
        val control = platformNodeFixture(null, rect, true, null, boundsReads)
        val description = platformNodeFixture(label, rect, false, control, boundsReads)
        var refreshCalls = 0
        var actions = 0
        val actual = assertThrows(AssertionError::class.java) {
            clickNode(instrumentation, description, label,
                refresh = { refreshCalls++; true },
                useAppProcessGeometry = true,
                geometryCheck = { assertNonEmptyEqualBounds("Option launcher", rect, controlRect) },
                click = { beforeOnClick -> beforeOnClick(); actions++ })
        }
        assertEquals(original.message, actual.message)
        assertEquals(2, refreshCalls)
        assertEquals(0, boundsReads[0])
        assertEquals(0, actions)

        var dispatchUptime = 0L
        val dispatchDeadline = 1L
        val dispatchNode = platformNodeFixture(label, rect, true, null, intArrayOf(0))
        assertThrows(IllegalStateException::class.java) {
            clickNode(instrumentation, dispatchNode, label,
                refresh = { true },
                useAppProcessGeometry = true,
                deadline = dispatchDeadline,
                uptimeMillis = { dispatchUptime },
                geometryCheck = {},
                click = { beforeOnClick ->
                    dispatchUptime = dispatchDeadline
                    beforeOnClick()
                    actions++
                })
        }
        assertEquals(0, actions)
    }
    private fun platformNodeFixture(
        label: String?, rect: Rect, clickable: Boolean,
        parent: AccessibilityNodeInfo?, boundsReads: IntArray,
    ): AccessibilityNodeInfo = object : AccessibilityNodeInfo() {
        override fun getParent(): AccessibilityNodeInfo? = parent
        override fun getBoundsInScreen(outBounds: Rect) { boundsReads[0]++; outBounds.set(rect) }
    }.apply {
        setBoundsInScreen(rect)
        contentDescription = label
        isEnabled = true
        isVisibleToUser = true
        isClickable = clickable
        if (clickable) addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
    }

    private fun descriptionForFixture(label: String, rect: Rect): AccessibilityNodeInfo =
        platformNodeFixture(label, rect, false, null, intArrayOf(0))

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
            lateinit var sessions: BoardSessionViewModel
            instrumentation.runOnMainSync { sessions = ViewModelProvider(activity as MainActivity)[BoardSessionViewModel::class.java] }
            val board = sessions.stateFor(1L, BoardSnapshot())
            val store = com.thinkcanvas.data.CanvasStore.get(context)
            val before = board.snapshot()
            val savedBefore = checkNotNull(runBlocking { store.savedBoard(1L) }).snapshot

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
                assertEquals("短い外側tapは選択を変えない", "選択中",
                    waitForRegionState(instrumentation, "選択中"))
                assertNull("未変更の外側tapでeditorが閉じる",
                    findExact(instrumentation.uiAutomation.rootInActiveWindow, "囲みの名前"))
                composeRule.mainClock.advanceTimeBy(configuration.doubleTapTimeoutMillis + 1)
            } finally {
                composeRule.mainClock.autoAdvance = true
            }
            assertEquals("dismissal tap は別操作を起こさない", "選択中",
                waitForRegionState(instrumentation, "選択中"))
            assertEquals("未変更の確定はboardを変更しない", before, board.snapshot())
            assertEquals("未変更の確定は保存しない", savedBefore,
                checkNotNull(runBlocking { store.savedBoard(1L) }).snapshot)
            assertNotNull("閉じたeditorの後でshare actionが戻る",
                waitForActionable(instrumentation, "選択範囲を画像で共有"))

            val (reopenedRegion, reopenedRename) = waitForRegionRenameAction(instrumentation)
            assertTrue(reopenedRegion.performAction(reopenedRename.id))
            clickActionable(instrumentation, "完了")
            waitForGone(instrumentation, "囲みの名前")
            assertEquals("Doneも未変更内容を保存しない", savedBefore,
                checkNotNull(runBlocking { store.savedBoard(1L) }).snapshot)

            clickActionable(instrumentation, "囲み: Cluster")
            assertEquals("別操作による明示解除", "未選択",
                waitForRegionState(instrumentation, "未選択"))
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
    fun staleRegionNameInputAfterOutsideCommitDoesNotReopenEditor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        seedBoard(context)
        showBoardOneAtStartup(context)
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            lateinit var sessions: BoardSessionViewModel
            instrumentation.runOnMainSync { sessions = ViewModelProvider(activity as MainActivity)[BoardSessionViewModel::class.java] }
            val editor = sessions.textEditorFor(1L, BoardSnapshot())
            val store = com.thinkcanvas.data.CanvasStore.get(context)

            clickActionable(instrumentation, "囲み: Cluster")
            val formerPoint = center(bounds(waitForActionable(instrumentation, "選択範囲を画像で共有")))
            val (region, renameAction) = waitForRegionRenameAction(instrumentation)
            assertTrue(region.performAction(renameAction.id))
            waitForGone(instrumentation, "選択範囲を画像で共有")
            composeRule.onNodeWithContentDescription("囲みの名前").performTextReplacement("Changed")
            val staleSetText = checkNotNull(composeRule.onNodeWithContentDescription("囲みの名前")
                .fetchSemanticsNode().config[SemanticsActions.SetText].action)

            val configuration = composeRule.onNodeWithContentDescription("キャンバス")
                .fetchSemanticsNode().layoutInfo.viewConfiguration
            composeRule.mainClock.autoAdvance = false
            try {
                tap(instrumentation, formerPoint.first, formerPoint.second)
                repeat(3) { composeRule.mainClock.advanceTimeByFrame() }
                waitForGone(instrumentation, "囲みの名前")
                assertNull("外側確定後はregion editorを閉じる", editor.regionNameDraft.value)
                awaitSavedRegionName(store, "chrome-lifecycle-region", "Changed")

                instrumentation.runOnMainSync {
                    assertTrue(staleSetText(AnnotatedString("Stale callback")))
                }
                composeRule.mainClock.advanceTimeBy(configuration.doubleTapTimeoutMillis + 1)
            } finally {
                composeRule.mainClock.autoAdvance = true
            }

            assertNull("古いSetText actionでeditorを再生成しない", editor.regionNameDraft.value)
            assertEquals("確定済みregion名を維持する", "Changed",
                runBlocking { store.savedBoard(1L)?.snapshot?.shapes?.singleOrNull { it.id == "chrome-lifecycle-region" }?.name })
            assertEquals("外側tapと古いactionで選択を変えない", "選択中",
                waitForRegionState(instrumentation, "選択中"))
            assertNull("外側tapからtext editorを生成しない",
                findExact(instrumentation.uiAutomation.rootInActiveWindow, "新しいテキスト"))
        } finally {
            composeRule.mainClock.autoAdvance = true
            activity.finish()
            instrumentation.waitForIdleSync()
        }
    }


    @Test
    fun regionNameOutsideTapRejectsRunningAndFailedSave() =
        withPaletteBoard(expanded = false) { instrumentation, _, _, sessions ->
            val board = sessions.stateFor(1L, BoardSnapshot())
            val editor = sessions.textEditorFor(1L, BoardSnapshot())
            val store = com.thinkcanvas.data.CanvasStore.get(instrumentation.targetContext)
            clickActionable(instrumentation, "囲み: Cluster")
            val blockedShare = center(bounds(waitForActionable(instrumentation, "選択範囲を画像で共有")))
            val (region, rename) = waitForRegionRenameAction(instrumentation)
            assertTrue(region.performAction(rename.id))
            waitForGone(instrumentation, "選択範囲を画像で共有")
            composeRule.onNodeWithContentDescription("囲みの名前").performTextReplacement("Blocked rename")

            val blockedBefore = board.snapshot()
            val savedBeforeBlocked = checkNotNull(runBlocking { store.savedBoard(1L) }).snapshot

            // Pending acknowledgement blocks both Done and outside commit before saveState changes.
            val pendingAckCompletion = CompletableDeferred<Unit>()
            val pendingAck = BoardSaveAcknowledgement(1L, Long.MAX_VALUE, pendingAckCompletion)
            instrumentation.runOnMainSync { editor.pendingDraftAcknowledgement.value = pendingAck }
            clickActionable(instrumentation, "完了")
            assertEquals("pending ack中はDoneでregion名を確定しない", "Blocked rename",
                editor.regionNameDraft.value?.name)
            assertEquals("pending ack中のDoneはboardを変更しない", blockedBefore, board.snapshot())
            assertFalse("pending ackを完了させない", pendingAck.isCompleted)
            nativeGesture(instrumentation, listOf(blockedShare))
            assertEquals("pending ack中も外側tap後のdraftを保持する", "Blocked rename",
                editor.regionNameDraft.value?.name)
            assertEquals("pending ack中の外側tapはboardを変更しない", blockedBefore, board.snapshot())
            assertFalse("pending ack中の外側tapでもacknowledgementを完了しない", pendingAck.isCompleted)
            instrumentation.runOnMainSync { editor.pendingDraftAcknowledgement.value = null }

            val blocker = CompletableDeferred<Unit>()
            val saveCalls = AtomicInteger()
            sessions.setSaveOperation { _, _ ->
                saveCalls.incrementAndGet()
                blocker
            }
            lateinit var acknowledgement: BoardSaveAcknowledgement
            instrumentation.runOnMainSync {
                acknowledgement = checkNotNull(sessions.requestSave(1L, blockedBefore))
            }
            val saveState = sessions.saveStateFor(1L, blockedBefore)
            composeRule.waitUntil(5_000) { saveState.value is BoardSaveState.Running }

            fun assertBlockedTap(phase: String) {
                nativeGesture(instrumentation, listOf(blockedShare))
                assertEquals("${phase}中も編集draftを保持する", "Blocked rename",
                    editor.regionNameDraft.value?.name)
                assertEquals("${phase}中はboardを変更しない", blockedBefore, board.snapshot())
                assertEquals("${phase}中は保存済みboardを変更しない", savedBeforeBlocked,
                    checkNotNull(runBlocking { store.savedBoard(1L) }).snapshot)
                assertEquals("${phase}中に余分なsave requestを送らない", 1, saveCalls.get())
                assertFalse("${phase}中はacknowledgementを未完了のまま保つ", acknowledgement.isCompleted)
            }

            assertBlockedTap("Running")
            blocker.completeExceptionally(IllegalStateException("bounded region-name save failure"))
            composeRule.waitUntil(5_000) { saveState.value is BoardSaveState.Failed }
            assertBlockedTap("Failed")

            val successfulRetry = CompletableDeferred<Unit>().also { it.complete(Unit) }
            sessions.setSaveOperation { _, _ -> successfulRetry }
            sessions.retrySave(1L)
            composeRule.waitUntil(5_000) {
                acknowledgement.isCompleted && saveState.value == BoardSaveState.Idle
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
        try {
            assertStableEndChrome(instrumentation)
            val initialInkCount = runBlocking { database.canvasDao().inkStrokes(1).size }
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

    // #112: each responsibility starts from a fresh board/activity.
    @PrSmoke
    @Test
    fun paletteOpeningInvalidatesAdmittedOutsidePointer() = withPaletteBoard(expanded = false) { instrumentation, activity, _, sessions ->
        val blank = paletteCanvasPoint(instrumentation, activity)
        nativeGesture(instrumentation, listOf(blank), afterDown = {
            clickActionable(instrumentation, "図形ツールを開く")
        })
        val configuration = composeRule.onNodeWithContentDescription("キャンバス").fetchSemanticsNode().layoutInfo.viewConfiguration
        composeRule.mainClock.advanceTimeBy(configuration.doubleTapTimeoutMillis + 1)
        assertPaletteOpen(instrumentation)
        assertNull("old UP after opening cannot create text", sessions.textEditorFor(1L, BoardSnapshot()).draft.value)
    }

    @PrSmoke
    @Test
    fun paletteOutsideShortTapOnlyDismissesAndNextTapStartsText() = withPaletteBoard { instrumentation, activity, _, sessions ->
        val board = sessions.stateFor(1L, BoardSnapshot())
        val editor = sessions.textEditorFor(1L, BoardSnapshot())
        val navigation = sessions.viewportHistoryFor(1L, BoardSnapshot())
        val before = board.snapshot()
        val viewBefore = navigation.viewportState.value
        val launcher = "図形ツールを開く"
        val anchor = bounds(waitForActionable(instrumentation, launcher))
        val blank = paletteCanvasPoint(instrumentation, activity)
        val configuration = composeRule.onNodeWithContentDescription("キャンバス").fetchSemanticsNode().layoutInfo.viewConfiguration
        val region = center(bounds(waitForActionable(instrumentation, "囲み: Cluster")))
        val shape = board.shapes.single { it.id == "chrome-lifecycle-region" }
        val scale = navigation.viewportState.value.scale
        val safe = paletteSafeCanvas(instrumentation, activity)
        val chrome = paletteChromeBounds(instrumentation)
        val points = listOf(.5f, .1f, .9f).flatMap { fx -> listOf(.5f, .1f, .9f).map { fy ->
            (region.first + (fx - .5f) * shape.width * scale) to (region.second + (fy - .5f) * shape.height * scale)
        } }
        val point = checkNotNull(points.firstOrNull { (x, y) -> safe.contains(x.toInt(), y.toInt()) && chrome.none { it.contains(x.toInt(), y.toInt()) } }) {
            "No outside region point: points=$points safe=$safe chrome=$chrome"
        }
        nativeGesture(instrumentation, listOf(point))
        assertPaletteClosed(instrumentation)
        assertEquals("outside element tap does not select", "未選択", waitForRegionState(instrumentation, "未選択"))
        assertNull(editor.draft.value)
        assertEquals(before, board.snapshot())
        assertEquals("dismissal does not pan or zoom", viewBefore, navigation.viewportState.value)
        assertEquals(anchor, bounds(waitForActionable(instrumentation, launcher)))

        clickActionable(instrumentation, "囲み: Cluster")
        clickActionable(instrumentation, launcher)
        nativeGesture(instrumentation, listOf(blank))
        assertPaletteClosed(instrumentation)
        composeRule.mainClock.advanceTimeBy(configuration.doubleTapTimeoutMillis + 1)
        assertEquals("dismissal keeps selection", "選択中", waitForRegionState(instrumentation, "選択中"))
        assertNull("dismissal never creates an editor", editor.draft.value)
        assertEquals(before, board.snapshot())
        assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        composeRule.waitForIdle()
        assertEquals("Back clears only selection", "未選択", waitForRegionState(instrumentation, "未選択"))
        clickActionable(instrumentation, launcher)
        composeRule.mainClock.autoAdvance = false
        try {
            nativeGesture(instrumentation, listOf(blank))
            repeat(3) { composeRule.mainClock.advanceTimeByFrame() }
            assertNull(editor.draft.value)
            nativeGesture(instrumentation, listOf(blank))
            composeRule.mainClock.advanceTimeBy(configuration.doubleTapTimeoutMillis + 1)
        } finally { composeRule.mainClock.autoAdvance = true }
        composeRule.waitUntil(5_000) { editor.draft.value != null }
        assertEquals("next tap creates new text", null, editor.draft.value?.id)
        assertEquals("next tap is not double-tap zoom", viewBefore.scale, navigation.viewportState.value.scale)
        composeRule.onNodeWithContentDescription("キャンバス").assertExists()
        composeRule.onNodeWithContentDescription("やめる").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        assertNull(editor.draft.value)
        assertEquals(before, board.snapshot())
    }

    @PrSmoke
    @Test
    fun paletteRegionNameEditorKeepsOutsidePriority() = withPaletteBoard(expanded = false) { instrumentation, activity, _, sessions ->
        val board = sessions.stateFor(1L, BoardSnapshot())
        val editor = sessions.textEditorFor(1L, BoardSnapshot())
        val navigation = sessions.viewportHistoryFor(1L, BoardSnapshot())
        val before = board.snapshot()
        val viewBefore = navigation.viewportState.value
        val store = com.thinkcanvas.data.CanvasStore.get(instrumentation.targetContext)
        val savedBefore = checkNotNull(runBlocking { store.savedBoard(1L) }).snapshot
        val undoAvailableBefore = board.canUndo
        clickActionable(instrumentation, "囲み: Cluster")
        val formerShare = center(bounds(waitForActionable(instrumentation, "選択範囲を画像で共有")))
        val (region, rename) = waitForRegionRenameAction(instrumentation)
        assertTrue(region.performAction(rename.id))
        waitForGone(instrumentation, "選択範囲を画像で共有", recordPostRenameShareDecision = true)
        composeRule.onNodeWithContentDescription("囲みの名前")
            .performTextReplacement("Cluster renamed")
        val renamed = "Cluster renamed"
        assertEquals("editorに変更済みのregion名がある", renamed, editor.regionNameDraft.value?.name)

        val deadline = SystemClock.uptimeMillis() + 4_000L
        val launcher = waitForActionable(instrumentation, "図形ツールを開く", deadline,
            useAppProcessGeometry = true)
        clickNode(instrumentation, launcher, "図形ツールを開く", activity = activity,
            deadline = deadline, useAppProcessGeometry = true)
        assertPaletteOpen(instrumentation)
        val configuration = composeRule.onNodeWithContentDescription("キャンバス")
            .fetchSemanticsNode().layoutInfo.viewConfiguration
        val secondPoint = (formerShare.first + 64f) to formerShare.second
        assertPalettePath(instrumentation, activity, listOf(formerShare, secondPoint))

        fun assertUncommitted() {
            assertEquals("除外gesture後も編集draftを保持する", renamed, editor.regionNameDraft.value?.name)
            assertEquals("除外gestureはboardを変更しない", before, board.snapshot())
            assertEquals("除外gestureは保存しない", savedBefore,
                checkNotNull(runBlocking { store.savedBoard(1L) }).snapshot)
            assertEquals("除外gestureはpanしない", viewBefore, navigation.viewportState.value)
            assertPaletteOpen(instrumentation)
        }

        nativeGesture(instrumentation, listOf(formerShare),
            holdMillis = configuration.longPressTimeoutMillis + 100L)
        assertUncommitted()
        nativeGesture(instrumentation, listOf(formerShare, secondPoint))
        assertUncommitted()
        nativePointerPair(instrumentation, formerShare, secondPoint, cancelFirstPointer = false)
        assertUncommitted()
        nativePointerPair(instrumentation, formerShare, secondPoint)
        assertUncommitted()
        nativeGesture(instrumentation, listOf(formerShare),
            terminal = MotionEvent.ACTION_CANCEL, flags = MotionEvent.FLAG_CANCELED)
        assertUncommitted()

        nativeGesture(instrumentation, listOf(formerShare))
        waitForGone(instrumentation, "囲みの名前")
        assertEquals("外側tapでregion選択を変えない", "選択中",
            waitForRegionState(instrumentation, "選択中"))
        assertPaletteOpen(instrumentation)
        assertNull("dismissal tapはblank textを作らない", editor.draft.value)
        assertEquals("変更済み名は1回のmodel更新", renamed,
            board.snapshot().shapes.single { it.id == "chrome-lifecycle-region" }.name)
        awaitSavedRegionName(store, "chrome-lifecycle-region", renamed)
        assertEquals("変更済みのregion名を保存", renamed,
            runBlocking { store.savedBoard(1L)?.snapshot?.shapes?.singleOrNull { it.id == "chrome-lifecycle-region" }?.name })
        assertEquals("dismissal tap does not pan or zoom", viewBefore, navigation.viewportState.value)

        assertTrue("region rename adds one undo entry", board.canUndo)
        assertTrue(board.undo())
        assertEquals("one undo reverts only the rename", before, board.snapshot())
        assertEquals("rename contributed one history entry", undoAvailableBefore, board.canUndo)
        assertTrue(board.redo())
        assertEquals(renamed, board.snapshot().shapes.single { it.id == "chrome-lifecycle-region" }.name)

        Thread.sleep(configuration.doubleTapTimeoutMillis + 100L)
        composeRule.waitForIdle()
        assertNull("dismissal event cannot start text after its timeout", editor.draft.value)
        nativeGesture(instrumentation, listOf(formerShare))
        assertPaletteClosed(instrumentation)

    }

    @Test
    fun paletteNativeCanceledTouchesKeepMenuAndContent() = withPaletteBoard { instrumentation, activity, database, sessions ->
        val board = sessions.stateFor(1L, BoardSnapshot())
        val before = board.snapshot()
        val store = com.thinkcanvas.data.CanvasStore.get(instrumentation.targetContext)
        val savedBefore = checkNotNull(runBlocking { store.savedBoard(1L) }).snapshot
        val blank = paletteCanvasPoint(instrumentation, activity)
        val selectionBefore = waitForRegionState(instrumentation, "未選択")
        nativeGesture(instrumentation, listOf(blank), terminal = MotionEvent.ACTION_CANCEL,
            flags = MotionEvent.FLAG_CANCELED)
        assertPaletteOpen(instrumentation)
        assertNull(sessions.textEditorFor(1L, BoardSnapshot()).draft.value)
        assertEquals(before, board.snapshot())
        assertEquals(selectionBefore, waitForRegionState(instrumentation, "未選択"))
        assertEquals(savedBefore, checkNotNull(runBlocking { store.savedBoard(1L) }).snapshot)

        // FLAG_CANCELED belongs to POINTER_UP/CANCEL, never to a single-pointer ACTION_UP.
        val second = (blank.first + 40f) to blank.second
        assertPalettePath(instrumentation, activity, listOf(blank, second))
        nativePointerPair(instrumentation, blank, second)
        assertPaletteOpen(instrumentation)
        assertNull(sessions.textEditorFor(1L, BoardSnapshot()).draft.value)
        assertEquals(before, board.snapshot())
        assertEquals(selectionBefore, waitForRegionState(instrumentation, "未選択"))
        assertEquals(savedBefore, checkNotNull(runBlocking { store.savedBoard(1L) }).snapshot)
        assertEquals(0, runBlocking { database.canvasDao().inkStrokes(1).size })

        // After both streams end, a new normal gesture owns only palette dismissal.
        nativeGesture(instrumentation, listOf(blank))
        assertPaletteClosed(instrumentation)
        assertNull(sessions.textEditorFor(1L, BoardSnapshot()).draft.value)
        assertEquals(before, board.snapshot())
        assertEquals(selectionBefore, waitForRegionState(instrumentation, "未選択"))
        assertEquals(savedBefore, checkNotNull(runBlocking { store.savedBoard(1L) }).snapshot)
        assertEquals(0, runBlocking { database.canvasDao().inkStrokes(1).size })
    }

    @Test
    fun paletteLongPressKeepsMenuAndContent() = withPaletteBoard { instrumentation, activity, database, sessions ->
        val before = sessions.stateFor(1L, BoardSnapshot()).snapshot()
        val blank = paletteCanvasPoint(instrumentation, activity)
        val configuration = composeRule.onNodeWithContentDescription("キャンバス").fetchSemanticsNode().layoutInfo.viewConfiguration
        nativeGesture(instrumentation, listOf(blank), holdMillis = configuration.longPressTimeoutMillis + 50)
        assertPaletteOpen(instrumentation)
        assertNull(sessions.textEditorFor(1L, BoardSnapshot()).draft.value)
        assertEquals(before, sessions.stateFor(1L, BoardSnapshot()).snapshot())
        assertEquals(0, runBlocking { database.canvasDao().inkStrokes(1).size })
    }

    @Test
    fun paletteOutsidePanKeepsMenuAndContent() = withPaletteBoard { instrumentation, activity, _, sessions ->
        val board = sessions.stateFor(1L, BoardSnapshot())
        val navigation = sessions.viewportHistoryFor(1L, BoardSnapshot())
        val before = board.snapshot()
        val viewBefore = navigation.viewportState.value
        val blank = paletteCanvasPoint(instrumentation, activity)
        val path = (0..6).map { (blank.first + 100f - it * 20f) to blank.second }
        assertPalettePath(instrumentation, activity, path)
        nativeGesture(instrumentation, path, holdMillis = 180)
        assertPaletteOpen(instrumentation)
        assertTrue("outside drag pans", viewBefore != navigation.viewportState.value)
        assertEquals("pan does not change zoom", viewBefore.scale, navigation.viewportState.value.scale)
        assertEquals(before, board.snapshot())
    }

    @Test
    fun paletteOutsidePinchKeepsMenuAndContent() = withPaletteBoard { instrumentation, activity, _, sessions ->
        val board = sessions.stateFor(1L, BoardSnapshot())
        val navigation = sessions.viewportHistoryFor(1L, BoardSnapshot())
        val before = board.snapshot()
        val scale = navigation.viewportState.value.scale
        val blank = paletteCanvasPoint(instrumentation, activity)
        val x = blank.first + 160f
        assertPalettePath(instrumentation, activity, listOf((x - 70f) to blank.second, (x + 70f) to blank.second))
        nativePinch(instrumentation, x, blank.second)
        assertPaletteOpen(instrumentation)
        assertTrue("outside pinch zooms", navigation.viewportState.value.scale > scale)
        assertEquals(before, board.snapshot())
    }

    @Test
    fun paletteStylusNormalReleasePersistsStroke() = withPaletteBoard { instrumentation, activity, database, sessions ->
        assertPaletteStylusStroke(instrumentation, activity, database, sessions)
    }

    @PrSmoke
    @Test
    fun paletteNativeControlsAndDismissedBoundsRemainActionable() = withPaletteBoard { instrumentation, _, _, sessions ->
        val launcher = "図形ツールを開く"
        val anchor = bounds(waitForActionable(instrumentation, launcher))
        val formerPen = center(bounds(waitForActionable(instrumentation, "ペン")))
        nativeGesture(instrumentation, listOf(center(anchor)))
        assertPaletteClosed(instrumentation)
        val navigation = sessions.viewportHistoryFor(1L, BoardSnapshot())
        val before = navigation.viewportState.value
        nativeGesture(instrumentation, (0..6).map { (formerPen.first - it * 20f) to formerPen.second }, holdMillis = 180)
        assertTrue("former palette hit admits pan", before != navigation.viewportState.value)
        nativeGesture(instrumentation, listOf(center(anchor)))
        assertPaletteOpen(instrumentation)
        nativeGesture(instrumentation, listOf(center(bounds(waitForActionable(instrumentation, "ペン")))))
        waitForGone(instrumentation, launcher)
        clickActionable(instrumentation, "やめる")
        assertEquals(anchor, bounds(waitForActionable(instrumentation, launcher)))
    }

    private fun withPaletteBoard(
        expanded: Boolean = true,
        block: (android.app.Instrumentation, MainActivity, CanvasDatabase, BoardSessionViewModel) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        seedBoard(context)
        showBoardOneAtStartup(context)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val database = CanvasDatabase.open(context)
        try {
            waitForActionable(instrumentation, "図形ツールを開く")
            lateinit var sessions: BoardSessionViewModel
            instrumentation.runOnMainSync { sessions = ViewModelProvider(activity)[BoardSessionViewModel::class.java] }
            if (expanded) {
                clickActionable(instrumentation, "図形ツールを開く")
                assertPaletteOpen(instrumentation)
            }
            block(instrumentation, activity, database, sessions)
        } finally {
            composeRule.mainClock.autoAdvance = true
            database.close()
            activity.finish()
            instrumentation.waitForIdleSync()
        }
    }

    private fun assertPaletteOpen(instrumentation: android.app.Instrumentation) {
        composeRule.onNodeWithContentDescription("ペン").assertExists()
        if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
        assertNotNull(waitForActionable(instrumentation, "ペン"))
    }

    private fun assertPaletteClosed(instrumentation: android.app.Instrumentation) {
        composeRule.onNodeWithContentDescription("ペン").assertDoesNotExist()
        if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
        waitForGone(instrumentation, "ペン")
    }

    private fun paletteSafeCanvas(instrumentation: android.app.Instrumentation, activity: MainActivity): Rect {
        composeRule.waitForIdle()
        instrumentation.waitForIdleSync()
        if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
        val canvas = bounds(checkNotNull(findExact(instrumentation.uiAutomation.rootInActiveWindow, "キャンバス")))
        val display = Rect()
        instrumentation.runOnMainSync {
            val decor = activity.window.decorView
            val origin = IntArray(2).also(decor::getLocationOnScreen)
            display.set(origin[0], origin[1], origin[0] + decor.width, origin[1] + decor.height)
            if (Build.VERSION.SDK_INT >= 30) {
                val insets = checkNotNull(decor.rootWindowInsets).getInsets(android.view.WindowInsets.Type.systemGestures())
                display.left += insets.left; display.top += insets.top
                display.right -= insets.right; display.bottom -= insets.bottom
            }
        }
        assertTrue("live canvas intersects app-owned screen: $canvas / $display", canvas.intersect(display))
        val margin = (8f * instrumentation.targetContext.resources.displayMetrics.density).toInt()
        canvas.inset(margin, margin)
        assertTrue("app-owned canvas is not empty", !canvas.isEmpty)
        return canvas
    }

    private fun paletteChromeBounds(instrumentation: android.app.Instrumentation): List<Rect> {
        val root = instrumentation.uiAutomation.rootInActiveWindow
        val labels = setOf("画像を追加", "ペン", "マーカー", "まとめて選ぶ", "囲み", "矢印", "丸", "四角")
        val rectangles = mutableListOf<Rect>()
        if (findExact(root, "ペン") != null) {
            val buttons = labels.map { bounds(waitForActionable(instrumentation, it)) }
            val seen = mutableSetOf<String>()
            fun collect(node: AccessibilityNodeInfo?) {
                if (node == null) return
                val text = node.text?.toString()
                if (node.isVisibleToUser && text in labels) { seen += requireNotNull(text); rectangles += bounds(node) }
                for (index in 0 until node.childCount) collect(node.getChild(index))
            }
            collect(root)
            assertEquals("native palette label geometry is complete", labels, seen)
            rectangles += buttons
            val envelope = Rect(rectangles.first()).apply { rectangles.drop(1).forEach(::union) }
            val padding = (8f * instrumentation.targetContext.resources.displayMetrics.density).toInt()
            envelope.inset(-padding, -padding)
            rectangles.clear(); rectangles += envelope
        }
        rectangles += bounds(waitForActionable(instrumentation, "図形ツールを開く"))
        rectangles += zoomBounds(instrumentation)
        for (label in listOf("前の視点へ戻る", "次の視点へ進む")) findExact(root, label)?.let { rectangles += bounds(it) }
        return rectangles
    }

    private fun paletteCanvasPoint(instrumentation: android.app.Instrumentation, activity: MainActivity): Pair<Float, Float> {
        val safe = paletteSafeCanvas(instrumentation, activity)
        val canvas = bounds(checkNotNull(findExact(instrumentation.uiAutomation.rootInActiveWindow, "キャンバス")))
        val point = (canvas.left + canvas.width() * .20f) to (canvas.top + canvas.height() * .55f)
        assertTrue("normal fixture point excludes system gestures: $point / $safe", safe.contains(point.first.toInt(), point.second.toInt()))
        assertPalettePath(instrumentation, activity, listOf(point))
        return point
    }

    private fun assertPalettePath(instrumentation: android.app.Instrumentation, activity: MainActivity, path: List<Pair<Float, Float>>) {
        val safe = paletteSafeCanvas(instrumentation, activity)
        val chrome = paletteChromeBounds(instrumentation)
        path.forEach { (x, y) ->
            assertTrue("normal input must be in live app-owned canvas: ($x,$y) / $safe", safe.contains(x.toInt(), y.toInt()))
            assertTrue("normal input must not hit chrome: ($x,$y) / $chrome", chrome.none { it.contains(x.toInt(), y.toInt()) })
        }
    }

    private fun assertPaletteStylusStroke(instrumentation: android.app.Instrumentation, activity: MainActivity, database: CanvasDatabase, sessions: BoardSessionViewModel) {
        val board = sessions.stateFor(1L, BoardSnapshot())
        val before = runBlocking { database.canvasDao().inkStrokes(1).size }
        val modelBefore = board.snapshot().ink.sumOf { it.strokes.size }
        val blank = paletteCanvasPoint(instrumentation, activity)
        val path = listOf(blank, (blank.first + 30f) to blank.second, (blank.first + 60f) to blank.second)
        assertPalettePath(instrumentation, activity, path)
        nativeGesture(instrumentation, path, toolType = MotionEvent.TOOL_TYPE_STYLUS)
        awaitInkCount(database, before + 1)
        assertEquals("normal native stylus UP commits exactly one model stroke", modelBefore + 1, board.snapshot().ink.sumOf { it.strokes.size })
        val stroke = board.snapshot().ink.flatMap { it.strokes }.last()
        assertEquals(InkInputType.STYLUS, stroke.inputType)
        assertTrue("stroke retains movement", stroke.points.size >= 2)
        assertTrue(stroke.points.zipWithNext().all { (a, b) -> a.elapsedMillis <= b.elapsedMillis })
        assertEquals("STYLUS", runBlocking { database.canvasDao().inkStrokes(1).last().inputType })
        assertPaletteOpen(instrumentation)
        assertNull(sessions.textEditorFor(1L, BoardSnapshot()).draft.value)
    }

    private fun nativeGesture(
        instrumentation: android.app.Instrumentation,
        points: List<Pair<Float, Float>>,
        terminal: Int = MotionEvent.ACTION_UP,
        flags: Int = 0,
        holdMillis: Long = 0,
        toolType: Int = MotionEvent.TOOL_TYPE_FINGER,
        afterDown: (() -> Unit)? = null,
    ) {
        require(flags and MotionEvent.FLAG_CANCELED == 0 || terminal == MotionEvent.ACTION_CANCEL) {
            "Single-pointer FLAG_CANCELED requires ACTION_CANCEL"
        }
        val down = SystemClock.uptimeMillis()
        val samples = if (points.size == 1) points + points else points
        samples.forEachIndexed { index, (x, y) ->
            if (index == samples.lastIndex && holdMillis > 0) Thread.sleep(holdMillis)
            val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; this.toolType = toolType })
            val coordinates = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
            val action = when (index) { 0 -> MotionEvent.ACTION_DOWN; samples.lastIndex -> terminal
                else -> MotionEvent.ACTION_MOVE }
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, properties, coordinates,
                0, 0, 1f, 1f, 0, 0,
                if (toolType == MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN,
                if (index == samples.lastIndex) flags else 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
            if (index == 0) afterDown?.invoke()
            Thread.sleep(35)
        }
        instrumentation.waitForIdleSync()
        if (composeRule.mainClock.autoAdvance) composeRule.waitForIdle()
    }

    private fun nativePointerPair(
        instrumentation: android.app.Instrumentation,
        first: Pair<Float, Float>, second: Pair<Float, Float>,
        cancelFirstPointer: Boolean = true,
    ) {
        val downTime = SystemClock.uptimeMillis()
        var previousTime = downTime
        fun send(action: Int, ids: List<Int>, points: List<Pair<Float, Float>>, flags: Int = 0) {
            assertEquals(ids.size, points.size)
            assertEquals(ids.size, ids.toSet().size)
            val properties = Array(ids.size) { index -> MotionEvent.PointerProperties().apply {
                id = ids[index]; toolType = MotionEvent.TOOL_TYPE_FINGER
            } }
            val coordinates = Array(points.size) { index -> MotionEvent.PointerCoords().apply {
                x = points[index].first; y = points[index].second; pressure = 1f; size = 1f
            } }
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, ids.size,
                properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags)
            try {
                assertEquals(downTime, event.downTime)
                assertTrue(event.eventTime >= previousTime)
                previousTime = event.eventTime
                assertEquals(InputDevice.SOURCE_TOUCHSCREEN, event.source)
                assertEquals(flags, event.flags)
                assertEquals(action and MotionEvent.ACTION_MASK, event.actionMasked)
                assertEquals((action and MotionEvent.ACTION_POINTER_INDEX_MASK) shr MotionEvent.ACTION_POINTER_INDEX_SHIFT,
                    event.actionIndex)
                ids.forEachIndexed { index, id ->
                    assertEquals(id, event.getPointerId(index))
                    assertEquals(MotionEvent.TOOL_TYPE_FINGER, event.getToolType(index))
                }
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally { event.recycle() }
            Thread.sleep(35)
        }
        send(MotionEvent.ACTION_DOWN, listOf(0), listOf(first))
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            listOf(0, 1), listOf(first, second))
        // Release id0/index0, optionally marking it canceled, then normally release remaining id1/index0.
        send(MotionEvent.ACTION_POINTER_UP, listOf(0, 1), listOf(first, second),
            if (cancelFirstPointer) MotionEvent.FLAG_CANCELED else 0)
        send(MotionEvent.ACTION_UP, listOf(1), listOf(second))
        instrumentation.waitForIdleSync()
        composeRule.waitForIdle()
    }

    private fun nativePinch(instrumentation: android.app.Instrumentation, x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, spread: Float, count: Int) {
            val properties = Array(count) { MotionEvent.PointerProperties().apply {
                id = it; toolType = MotionEvent.TOOL_TYPE_FINGER
            } }
            val coordinates = Array(count) { MotionEvent.PointerCoords().apply {
                this.x = x + if (it == 0) -spread else spread; this.y = y; pressure = 1f
            } }
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, properties,
                coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
            Thread.sleep(35)
        }
        send(MotionEvent.ACTION_DOWN, 40f, 1)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 40f, 2)
        send(MotionEvent.ACTION_MOVE, 70f, 2)
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 70f, 2)
        send(MotionEvent.ACTION_UP, 70f, 1)
        composeRule.waitForIdle()
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

    private fun findActionable(
        node: AccessibilityNodeInfo?,
        label: String,
        useAppProcessGeometry: Boolean = false,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        val ownsClick = node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
        if (node.contentDescription?.toString() == label &&
            (label != "囲み: Cluster" || ownsClick)) {
            assertPlatformControl(node, label, checkExternalGeometry = !useAppProcessGeometry)
            return node
        }
        for (index in 0 until node.childCount) {
            findActionable(node.getChild(index), label, useAppProcessGeometry)?.let { return it }
        }
        return null
    }

    private data class RefreshBounds(val before: Rect, val after: Rect)

    // Layer A: exact description と同じ control の platform exposure / geometry を確認する。
    private fun assertPlatformControl(
        description: AccessibilityNodeInfo,
        label: String,
        refresh: (AccessibilityNodeInfo) -> Boolean = AccessibilityNodeInfo::refresh,
        record: (String) -> Unit = { Log.e("ConditionalGeometry", it) },
        checkExternalGeometry: Boolean = true,
    ): AccessibilityNodeInfo {
        // 一度とはこの Layer A 検証ごと。取得時と clickNode の直前で各々再検証する。
        val descriptionRefreshBounds = if (label == "図形ツールを開く") {
            refreshLauncherInfo(description, label, "description", refresh, checkExternalGeometry)
        } else null
        assertEquals(label, description.contentDescription?.toString())
        assertTrue("$label の description は有効", description.isEnabled)
        assertTrue("$label の description は利用者に見える", description.isVisibleToUser)
        var node: AccessibilityNodeInfo? = description
        var parentHops = 0
        while (node != null && !node.isClickable) {
            node = node.parent
            parentHops++
        }
        val actionable = checkNotNull(node) { "$label に対応する clickable node がありません" }
        val controlRefreshBounds = if (label == "図形ツールを開く" && actionable !== description) {
            refreshLauncherInfo(actionable, label, "control", refresh, checkExternalGeometry)
        } else null
        assertTrue("$label の control は有効", actionable.isEnabled)
        assertTrue("$label の control は利用者に見える", actionable.isVisibleToUser)
        assertTrue("$label の control は clickable", actionable.isClickable)
        assertTrue("$label の control は ACTION_CLICK を公開する",
            actionable.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
        if (checkExternalGeometry) {
            val descriptionBounds = bounds(description)
            val controlBounds = bounds(actionable)
            assertControlBounds(label, descriptionBounds, controlBounds, parentHops, record,
                descriptionRefreshBounds, controlRefreshBounds)
        }
        return actionable
    }

    private fun assertLauncherAppProcessBounds(
        instrumentation: android.app.Instrumentation,
        activity: MainActivity,
        externalDescription: AccessibilityNodeInfo,
        externalControl: AccessibilityNodeInfo,
        deadline: Long,
        uptimeMillis: () -> Long = { SystemClock.uptimeMillis() },
    ) {
        check(Build.VERSION.SDK_INT >= 34) { "App-process accessibility geometry requires API 34+" }
        remainingActionableTime(deadline, uptimeMillis)
        instrumentation.runOnMainSync {
            remainingActionableTime(deadline, uptimeMillis)
            val decor = activity.window.decorView
            val externalWindowId = externalDescription.windowId
            check(externalWindowId == externalControl.windowId) { "External nodes belong to different windows" }
            val root = decor.createAccessibilityNodeInfo()
            assertCurrentAppRoot(
                activity.lifecycle.currentState == Lifecycle.State.RESUMED,
                decor.isAttachedToWindow && decor.windowToken != null,
                decor.hasWindowFocus(),
                root.windowId,
                externalWindowId,
            )
            root.setQueryFromAppProcessEnabled(decor, true)
            try {

                val matches = mutableListOf<AccessibilityNodeInfo>()
                fun visit(node: AccessibilityNodeInfo) {
                    if (node.contentDescription?.toString() == "図形ツールを開く") matches += node
                    for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
                }
                visit(root)
                val appDescription = uniqueAppDescription(matches)
                assertMappedAppNode(appDescription, externalDescription, externalWindowId, "description")
                check(appDescription.isEnabled && appDescription.isVisibleToUser) {
                    "App-process launcher description is disabled or hidden"
                }

                var appControl: AccessibilityNodeInfo? = appDescription
                while (appControl != null && !appControl.isClickable) appControl = appControl.parent
                val matchedControl = checkNotNull(appControl) { "No app-process clickable launcher ancestor" }
                assertMappedAppNode(matchedControl, externalControl, externalWindowId, "control")
                check(matchedControl.isEnabled && matchedControl.isVisibleToUser && matchedControl.isClickable &&
                    matchedControl.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                    "App-process launcher control is disabled, hidden, or has no click action"
                }

                val descriptionBounds = bounds(appDescription)
                val controlBounds = bounds(matchedControl)
                assertNonEmptyEqualBounds("Option launcher", descriptionBounds, controlBounds)
            } finally {
                root.setQueryFromAppProcessEnabled(decor, false)
            }
        }
        remainingActionableTime(deadline, uptimeMillis)
    }

    private fun assertCurrentAppRoot(
        resumed: Boolean,
        attached: Boolean,
        focused: Boolean,
        rootWindowId: Int,
        externalWindowId: Int,
    ) {
        check(resumed) { "MainActivity is not resumed" }
        check(attached) { "MainActivity decor is detached" }
        check(focused) { "MainActivity window is not focused" }
        check(rootWindowId == externalWindowId) { "App-process root belongs to a different window" }
    }

    private fun uniqueAppDescription(matches: List<AccessibilityNodeInfo>): AccessibilityNodeInfo {
        check(matches.size == 1) { "Expected one app-process launcher label, found ${matches.size}" }
        return matches.single()
    }

    private fun assertMappedAppNode(
        appNode: AccessibilityNodeInfo,
        externalNode: AccessibilityNodeInfo,
        expectedWindowId: Int,
        role: String,
    ) {
        check(appNode.windowId == expectedWindowId && externalNode.windowId == expectedWindowId &&
            appNode == externalNode) {
            "App-process launcher $role identity does not match the external node"
        }
    }

    private fun assertNonEmptyEqualBounds(label: String, descriptionBounds: Rect, controlBounds: Rect) {
        assertFalse("$label app-process description bounds must be nonempty: $descriptionBounds",
            descriptionBounds.isEmpty)
        assertFalse("$label app-process control bounds must be nonempty: $controlBounds", controlBounds.isEmpty)
        assertEquals("$label app-process description and control bounds must match",
            descriptionBounds, controlBounds)
    }

    private fun refreshLauncherInfo(
        node: AccessibilityNodeInfo,
        label: String,
        role: String,
        refresh: (AccessibilityNodeInfo) -> Boolean,
        captureBounds: Boolean,
    ): RefreshBounds? {
        val before = AccessibilityNodeInfo(node)
        assertTrue("$label の $role を一度 refresh できる", refresh(node))
        val after = AccessibilityNodeInfo(node)
        assertTrue("$label の $role identity は変わらない", before == after)
        assertEquals("$label の $role description は変わらない",
            before.contentDescription?.toString(), after.contentDescription?.toString())
        assertEquals("$label の $role enabled は変わらない", before.isEnabled, after.isEnabled)
        assertEquals("$label の $role visible は変わらない", before.isVisibleToUser, after.isVisibleToUser)
        assertEquals("$label の $role clickable は変わらない", before.isClickable, after.isClickable)
        assertEquals("$label の $role ACTION_CLICK は変わらない",
            before.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK },
            after.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
        // 既存コピーだけを読む。診断値の取得失敗は元の geometry oracle を妨げない。
        return if (captureBounds) {
            try { RefreshBounds(bounds(before), bounds(after)) } catch (_: Throwable) { null }
        } else null
    }

    private fun assertControlBounds(
        label: String,
        descriptionBounds: Rect,
        controlBounds: Rect,
        parentHops: Int,
        record: (String) -> Unit = { Log.e("ConditionalGeometry", it) },
        descriptionRefreshBounds: RefreshBounds? = null,
        controlRefreshBounds: RefreshBounds? = null,
    ) {
        if (label == "図形ツールを開く" && descriptionBounds != controlBounds) {
            // 既取得値だけを記録する。記録 failure は native oracle の failure を置き換えない。
            try {
                val refreshRecord = if (descriptionRefreshBounds != null && controlRefreshBounds != null) {
                    " descriptionBefore=${descriptionRefreshBounds.before}" +
                        " descriptionAfter=${descriptionRefreshBounds.after}" +
                        " controlBefore=${controlRefreshBounds.before}" +
                        " controlAfter=${controlRefreshBounds.after}"
                } else ""
                record("label=$label parentHops=$parentHops description=$descriptionBounds control=$controlBounds$refreshRecord")
            } catch (_: Throwable) {
                // 元の exact assertion は guard の外で必ず実行する。
            }
        }
        assertEquals("$label の description と control は同じ bounds",
            descriptionBounds, controlBounds)
    }

    private fun waitForActionable(
        instrumentation: android.app.Instrumentation,
        label: String,
        sharedDeadline: Long? = null,
        useAppProcessGeometry: Boolean = false,
    ): AccessibilityNodeInfo {
        if (label == "図形ツールを開く" || label == "ペン") {
            // Geometry is asserted only after bounded platform idle and a fresh tree.
            val deadline = sharedDeadline ?: (SystemClock.uptimeMillis() + 4_000L)
            val automation = instrumentation.uiAutomation
            while (SystemClock.uptimeMillis() < deadline) {
                composeRule.waitForIdle()
                instrumentation.waitForIdleSync()
                val remaining = deadline - SystemClock.uptimeMillis()
                if (remaining <= 0L) break
                automation.waitForIdle(minOf(100L, remaining), remaining)
                if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
                val root = automation.rootInActiveWindow
                if (root?.refresh() == true) {
                    // A geometry assertion failure is never caught or retried.
                    findActionable(root, label, useAppProcessGeometry)?.let {
                        if (useAppProcessGeometry) remainingActionableTime(deadline)
                        return it
                    }
                }
            }
            error("操作可能なアクセシビリティ要素が見つかりません: $label")
        }
        var matchedNode: AccessibilityNodeInfo? = null
        composeRule.waitUntil(
            conditionDescription = "操作可能なアクセシビリティ要素が見つかりません: $label",
            timeoutMillis = 4_000L,
        ) {
            composeRule.waitForIdle()
            instrumentation.waitForIdleSync()
            matchedNode = findActionable(instrumentation.uiAutomation.rootInActiveWindow, label)
            matchedNode != null
        }
        return checkNotNull(matchedNode)
    }

    private fun waitForAnyActionable(
        instrumentation: android.app.Instrumentation,
        labels: List<String>,
    ): AccessibilityNodeInfo {
        var matchedNode: AccessibilityNodeInfo? = null
        composeRule.waitUntil(
            conditionDescription = "操作可能なアクセシビリティ要素が見つかりません: ${labels.joinToString()}",
            timeoutMillis = 4_000L,
        ) {
            composeRule.waitForIdle()
            instrumentation.waitForIdleSync()
            val root = instrumentation.uiAutomation.rootInActiveWindow
            matchedNode = labels.firstNotNullOfOrNull { label -> findActionable(root, label) }
            matchedNode != null
        }
        return checkNotNull(matchedNode)
    }

    private fun waitForRegionState(
        instrumentation: android.app.Instrumentation,
        expected: String,
    ): String {
        var matchedState: String? = null
        composeRule.waitUntil(
            conditionDescription = "囲みの stateDescription が $expected になりません",
            timeoutMillis = 4_000L,
        ) {
            composeRule.waitForIdle()
            instrumentation.waitForIdleSync()
            matchedState = findActionable(instrumentation.uiAutomation.rootInActiveWindow, "囲み: Cluster")
                ?.stateDescription?.toString()
            matchedState == expected
        }
        return checkNotNull(matchedState)
    }

    private fun waitForRegionRenameAction(
        instrumentation: android.app.Instrumentation,
    ): Pair<AccessibilityNodeInfo, AccessibilityNodeInfo.AccessibilityAction> {
        var matchedAction: Pair<AccessibilityNodeInfo, AccessibilityNodeInfo.AccessibilityAction>? = null
        composeRule.waitUntil(
            conditionDescription = "囲み: Cluster の名前編集 accessibility action が見つかりません",
            timeoutMillis = 4_000L,
        ) {
            composeRule.waitForIdle()
            instrumentation.waitForIdleSync()
            val region = findActionable(instrumentation.uiAutomation.rootInActiveWindow, "囲み: Cluster")
            val action = region?.actionList?.firstOrNull { it.label?.toString() == "囲みの名前を編集" }
            matchedAction = if (region != null && action != null) region to action else null
            matchedAction != null
        }
        return checkNotNull(matchedAction)
    }

    private fun clickActionable(instrumentation: android.app.Instrumentation, label: String) {
        clickNode(instrumentation, waitForActionable(instrumentation, label), label)
    }

    private fun clickNode(
        instrumentation: android.app.Instrumentation,
        node: AccessibilityNodeInfo,
        label: String,
        refresh: (AccessibilityNodeInfo) -> Boolean = AccessibilityNodeInfo::refresh,
        record: (String) -> Unit = { Log.e("ConditionalGeometry", it) },
        activity: MainActivity? = null,
        deadline: Long? = null,
        uptimeMillis: () -> Long = { SystemClock.uptimeMillis() },
        useAppProcessGeometry: Boolean = false,
        geometryCheck: (() -> Unit)? = null,
        click: (beforeOnClick: () -> Unit) -> Unit = { beforeOnClick ->
            composeRule.onNode(hasContentDescription(label) and hasClickAction())
                .performSemanticsAction(SemanticsActions.OnClick) { onClick ->
                    beforeOnClick()
                    onClick()
                }
        },
    ) {
        val acceptanceDeadline = if (label == "図形ツールを開く") {
            deadline ?: (uptimeMillis() + 4_000L)
        } else null
        val actionable = assertPlatformControl(node, label, refresh, record,
            checkExternalGeometry = !useAppProcessGeometry)
        if (useAppProcessGeometry) {
            check(label == "図形ツールを開く") { "App-process geometry is limited to the launcher" }
            if (geometryCheck != null) {
                geometryCheck()
            } else if (Build.VERSION.SDK_INT >= 34) {
                assertLauncherAppProcessBounds(
                    instrumentation,
                    checkNotNull(activity) { "API 34+ launcher geometry requires the resumed MainActivity" },
                    node,
                    actionable,
                    checkNotNull(acceptanceDeadline),
                    uptimeMillis,
                )
            } else {
                Log.i("ConditionalGeometry", "label=$label appProcessBounds=unavailable api=${Build.VERSION.SDK_INT}")
            }
            remainingActionableTime(checkNotNull(acceptanceDeadline), uptimeMillis)
        }
        // Layer B: Layer A で確認した exact control の OnClick を実行する。
        click {
            if (useAppProcessGeometry) remainingActionableTime(checkNotNull(acceptanceDeadline), uptimeMillis)
        }
        composeRule.waitForIdle()
        instrumentation.waitForIdleSync()
    }

    private fun waitForGone(
        instrumentation: android.app.Instrumentation,
        label: String,
        recordPostRenameShareDecision: Boolean = false,
    ) {
        repeat(40) {
            composeRule.waitForIdle()
            instrumentation.waitForIdleSync()
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val gone = findExact(root, label) == null
            if (gone) {
                // 元の消失判定の成功sampleだけを、return前に記録する。
                if (recordPostRenameShareDecision) {
                    Log.i("ConditionalAbsence", "postRenameShare rootNull=${root == null} gone=$gone")
                }
                return
            }
            Thread.sleep(100)
        }
        error("非表示になったアクセシビリティ要素が残っています: $label")
    }

    private fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)

    private fun remainingActionableTime(deadline: Long): Long =
        remainingActionableTime(deadline) { SystemClock.uptimeMillis() }

    private fun remainingActionableTime(deadline: Long, uptimeMillis: () -> Long): Long =
        (deadline - uptimeMillis()).also {
            check(it > 0L) { "Launcher acceptance exceeded its shared 4-second deadline" }
        }

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

    private fun awaitSavedRegionName(
        store: com.thinkcanvas.data.CanvasStore,
        regionId: String,
        name: String,
    ) {
        composeRule.waitUntil(5_000) {
            runBlocking {
                store.savedBoard(1L)?.snapshot?.shapes?.singleOrNull { it.id == regionId }?.name == name
            }
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
