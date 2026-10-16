package com.thinkcanvas.board

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thinkcanvas.MainActivity
import com.thinkcanvas.data.BoardRow
import com.thinkcanvas.data.CanvasDatabase
import com.thinkcanvas.data.CanvasStore
import com.thinkcanvas.test.PrSmoke
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class GuideDismissalTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences get() = context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE)

    private fun seed(openBoard: Boolean = true) = runBlocking {
        val database = CanvasDatabase.open(context)
        try {
            val dao = database.canvasDao()
            dao.boards().forEach { dao.deleteBoard(it.id) }
            dao.putBoard(BoardRow(1, "案内のボード", 10))
        } finally { database.close() }
        assertTrue(preferences.edit().putBoolean("initialized", true)
            .putBoolean("guideDismissed", false).putLong("guideEligibleBoardId", 1)
            .putLong("lastOpenedBoardId", if (openBoard) 1L else -1L).commit())
    }

    private fun owner(activity: MainActivity) = ViewModelProvider(activity)[GuideViewModel::class.java]
    private fun launch() = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
    private fun awaitText(text: String) = composeRule.waitUntilExactlyOneExists(hasText(text), 5_000L)
    private fun awaitClosed() = composeRule.waitUntil(5_000) {
        composeRule.onAllNodesWithText("基本の操作").fetchSemanticsNodes().isEmpty()
    }
    private fun back() {
        assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        composeRule.waitForIdle()
    }

    @PrSmoke
    @Test fun queuedCommitKeepsGuideThroughBackAndRecreationUntilSuccess() {
        seed()
        val scenario = launch()
        val release = CompletableDeferred<Unit>()
        lateinit var firstOwner: GuideViewModel
        var attempts = 0
        try {
            awaitText("‹ 案内のボード")
            awaitText("基本の操作")
            scenario.onActivity {
                firstOwner = owner(it)
                firstOwner.save = { attempts++; CanvasStore.get(context).dismissGuide() }
            }
            // Use the existing Store queue boundary, without a production diagnostic hook.
            val started = CompletableDeferred<Unit>()
            val field = CanvasStore::class.java.getDeclaredField("operations").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val queue = field.get(CanvasStore.get(context)) as Channel<suspend () -> Unit>
            assertTrue(queue.trySend { started.complete(Unit); release.await() }.isSuccess)
            runBlocking { withTimeout(5_000) { started.await() } }

            lateinit var dismiss: () -> Boolean
            composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
                .performSemanticsAction(SemanticsActions.Dismiss) { dismiss = it }
            composeRule.onNodeWithText("はじめる").performSemanticsAction(SemanticsActions.OnClick) { click ->
                composeRule.runOnUiThread {
                    assertTrue(click()); assertTrue(click())
                    dismiss() // Start and dismiss occur before the busy UI can recompose.
                }
            }
            composeRule.onNodeWithText("はじめる").assertIsNotEnabled()
            scenario.onActivity { assertTrue(firstOwner.state.saving) }
            assertEquals(1, attempts)
            assertFalse(preferences.getBoolean("guideDismissed", false))
            back()
            composeRule.onNodeWithText("基本の操作").assertIsDisplayed()

            scenario.recreate()
            scenario.onActivity {
                assertSame(firstOwner, owner(it))
                assertTrue(firstOwner.state.saving)
                assertFalse(it.isFinishing)
            }
            awaitText("基本の操作")
            back()
            composeRule.onNodeWithText("基本の操作").assertIsDisplayed()
            release.complete(Unit)
            awaitText("‹ 案内のボード")
            awaitClosed()
            assertTrue(preferences.getBoolean("guideDismissed", false))
            assertFalse(preferences.contains("guideEligibleBoardId"))
            assertEquals(1, attempts)
            scenario.recreate()
            awaitText("‹ 案内のボード")
            awaitClosed()
        } finally { release.complete(Unit); scenario.close() }
    }

    @PrSmoke
    @Test fun replayFailureAndRetryKeepSameOwnerAcrossRecreation() {
        seed(openBoard = false)
        val scenario = launch()
        val failed = CompletableDeferred<Unit>()
        lateinit var firstOwner: GuideViewModel
        var attempts = 0
        try {
            awaitText("使い方")
            composeRule.onNodeWithText("使い方").performClick()
            awaitText("基本の操作")
            scenario.onActivity {
                firstOwner = owner(it)
                firstOwner.save = {
                    attempts++
                    if (attempts == 1) failed.await() else CanvasStore.get(context).dismissGuide()
                }
            }
            composeRule.onNodeWithText("はじめる").performClick()
            failed.completeExceptionally(IllegalStateException("案内の保存失敗"))
            awaitText("案内の保存失敗")
            scenario.recreate()
            awaitText("案内の保存失敗")
            scenario.onActivity {
                assertSame(firstOwner, owner(it))
                assertTrue(firstOwner.state.visible)
                assertFalse(firstOwner.state.saving)
                assertEquals(1, attempts)
            }
            assertFalse(preferences.getBoolean("guideDismissed", false))
            composeRule.onNodeWithText("閉じる").performClick()
            composeRule.onNodeWithText("基本の操作").assertIsDisplayed()
            composeRule.onNodeWithText("はじめる").performClick()
            awaitClosed()
            assertEquals(2, attempts)
            assertTrue(preferences.getBoolean("guideDismissed", false))
        } finally { failed.complete(Unit); scenario.close() }
    }

    @Test fun backBeforeStartDoesNotPersistAndInitialGuideReturnsAfterRecreation() {
        seed()
        val scenario = launch()
        try {
            awaitText("‹ 案内のボード")
            awaitText("基本の操作")
            back()
            awaitClosed()
            assertFalse(preferences.getBoolean("guideDismissed", false))
            // Restoring the still-eligible initial board may show the guide again, as before.
            scenario.recreate()
            awaitText("‹ 案内のボード")
            awaitText("基本の操作")
            back()
            awaitClosed()
        } finally { scenario.close() }
    }
}
