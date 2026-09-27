package com.thinkcanvas.board

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thinkcanvas.MainActivity
import com.thinkcanvas.data.BoardRow
import com.thinkcanvas.data.CanvasDatabase
import com.thinkcanvas.data.TextElementRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BoardListScreenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation

    private fun seed(boards: List<BoardRow>) = runBlocking {
        val database = CanvasDatabase.open(context)
        val dao = database.canvasDao()
        dao.boards().forEach { dao.deleteBoard(it.id) }
        boards.forEach { dao.putBoard(it) }
        database.close()
        assertTrue(context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)
            .edit().putBoolean("initialized", true).putBoolean("guideDismissed", true)
            .remove("lastOpenedBoardId").commit())
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

    @Test fun twentyCardsCanScrollAndOpenFirstAndLast() {
        seed((1L..20L).map { BoardRow(it, "ボード$it", 21L - it) })
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            act(waitFor("ボード1、", byDescription = true), AccessibilityNodeInfo.ACTION_CLICK)
            waitFor("‹ ボード1")
            act(waitFor("ボード一覧を開く", byDescription = true), AccessibilityNodeInfo.ACTION_CLICK)
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

    @Test fun deletionRequiresConfirmationAndKeepsOtherBoard() {
        seed(listOf(BoardRow(1, "残す", 10), BoardRow(42, "消す", 20)))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            act(waitFor("消す、", byDescription = true), AccessibilityNodeInfo.ACTION_LONG_CLICK)
            waitFor("画像で共有")
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
            act(waitFor("選択範囲を画像で共有", byDescription = true),
                AccessibilityNodeInfo.ACTION_CLICK)
            waitFor("選択するボード の共有画像プレビュー", byDescription = true)
            waitFor("画像を保存")
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService
                .GLOBAL_ACTION_BACK))
            assertEquals(listOf(selected, outside), runBlocking { database.canvasDao().elements(1) })
        } finally { activity.finish(); database.close() }
    }
}
