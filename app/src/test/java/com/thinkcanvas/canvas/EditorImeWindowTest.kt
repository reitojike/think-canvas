package com.thinkcanvas.canvas

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorImeWindowTest {
    private class Request(val modern: Boolean) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val window = MutableStateFlow(true)
        val control = MutableStateFlow(true)
        val frames = Channel<Unit>(Channel.UNLIMITED)
        var current = true
        var inputReady = true
        var frameCount = 0
        var controlCount = 0
        val result = scope.async {
            awaitEditorImeWindow(
                isCurrent = { current },
                awaitWindowOwnership = { window.first { it } },
                awaitImeControl = {
                    controlCount++
                    if (modern) control.first { it }
                },
                awaitFrame = { frameCount++; frames.receive() },
                hasWindowFocus = { window.value },
                isInputReady = { inputReady },
            )
        }
        fun loseWindowDuringFrame() {
            assertEquals(1, frameCount)
            window.value = false
            assertTrue(frames.trySend(Unit).isSuccess)
        }
        fun close() { scope.cancel() }
    }

    @Test fun legacyLateLossWaitsForReturnAndCompletesOnce() = runBlocking {
        val request = Request(modern = false)
        try {
            request.loseWindowDuringFrame()
            assertTrue("A temporary focus loss must not finish the request", request.result.isActive)
            assertEquals(1, request.frameCount)
            request.window.value = true
            assertEquals(2, request.frameCount)
            request.frames.send(Unit)
            assertTrue(request.result.await())
            request.window.value = false
            request.window.value = true
            assertEquals(2, request.frameCount)
        } finally { request.close() }
    }

    @Test fun modernLateLossRechecksControlAndSurvivesAnotherLoss() = runBlocking {
        val request = Request(modern = true)
        try {
            request.loseWindowDuringFrame()
            assertTrue(request.result.isActive)
            request.control.value = false
            request.window.value = true
            assertEquals(2, request.controlCount)
            assertEquals(1, request.frameCount)
            request.control.value = true
            assertEquals(2, request.frameCount)
            request.window.value = false
            request.frames.send(Unit)
            assertTrue(request.result.isActive)
            request.window.value = true
            assertEquals(3, request.controlCount)
            request.frames.send(Unit)
            assertTrue(request.result.await())
        } finally { request.close() }
    }

    @Test fun endedOrBlockedRequestDoesNotResumeAfterLateLoss() = runBlocking {
        for (modern in listOf(false, true)) {
            val request = Request(modern)
            try {
                request.loseWindowDuringFrame()
                assertTrue(request.result.isActive)
                request.current = false
                request.window.value = true
                assertFalse(request.result.await())
                assertEquals(1, request.controlCount)
                assertEquals(1, request.frameCount)
            } finally { request.close() }
        }
    }

    @Test fun canceledRequestCannotCompleteOnLaterWindowReturn() {
        for (modern in listOf(false, true)) {
            val request = Request(modern)
            try {
                request.loseWindowDuringFrame()
                assertTrue(request.result.isActive)
                request.result.cancel()
                request.window.value = true
                assertTrue(request.result.isCancelled)
                assertEquals(1, request.frameCount)
            } finally { request.close() }
        }
    }

    @Test fun restoredWindowWaitsForEditableInputConnectionBeforeShowingIme() = runBlocking {
        val request = Request(modern = true)
        try {
            request.inputReady = false
            request.frames.send(Unit)
            assertTrue("Window ownership alone does not make the editor ready", request.result.isActive)
            assertEquals(2, request.frameCount)
            request.inputReady = true
            request.frames.send(Unit)
            assertTrue(request.result.await())
        } finally { request.close() }
    }
}
