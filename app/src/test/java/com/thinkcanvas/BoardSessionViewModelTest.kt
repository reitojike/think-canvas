package com.thinkcanvas

import com.thinkcanvas.canvas.BoardSnapshot
import com.thinkcanvas.canvas.TextColor
import com.thinkcanvas.canvas.TextKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardSessionViewModelTest {
    @Test fun reopeningBoardReusesUndoAndRedoHistory() {
        val sessions = BoardSessionViewModel()
        val state = sessions.stateFor(1L, BoardSnapshot())
        state.create("committed edit", TextKind.BODY, TextColor.INK, 10f, 20f)

        val reopened = sessions.stateFor(1L, state.snapshot())

        assertSame(state, reopened)
        assertTrue(reopened.canUndo)
        assertTrue(reopened.undo())
        assertTrue(reopened.elements.isEmpty())
        assertTrue(reopened.canRedo)
        assertTrue(reopened.redo())
        assertEquals("committed edit", reopened.elements.single().text)
    }

    @Test fun boardHistoriesRemainIsolatedAcrossSwitches() {
        val sessions = BoardSessionViewModel()
        val boardA = sessions.stateFor(1L, BoardSnapshot())
        val boardB = sessions.stateFor(2L, BoardSnapshot())
        boardA.create("A edit", TextKind.BODY, TextColor.INK, 0f, 0f)
        boardB.create("B edit", TextKind.BODY, TextColor.INK, 0f, 0f)

        assertTrue(sessions.stateFor(1L, boardA.snapshot()).undo())
        assertTrue(boardA.elements.isEmpty())
        assertEquals("B edit", sessions.stateFor(2L, boardB.snapshot()).elements.single().text)
        assertTrue(sessions.stateFor(2L, boardB.snapshot()).canUndo)
        assertTrue(sessions.stateFor(2L, boardB.snapshot()).undo())
        assertTrue(boardB.elements.isEmpty())
        assertTrue(sessions.stateFor(1L, BoardSnapshot()).canRedo)
    }

    @Test fun deletingBoardDiscardsOnlyItsSessionState() {
        val sessions = BoardSessionViewModel()
        val boardA = sessions.stateFor(1L, BoardSnapshot())
        val boardB = sessions.stateFor(2L, BoardSnapshot())
        boardA.create("A edit", TextKind.BODY, TextColor.INK, 0f, 0f)
        boardB.create("B edit", TextKind.BODY, TextColor.INK, 0f, 0f)

        sessions.discard(1L)

        val replacementA = sessions.stateFor(1L, BoardSnapshot())
        assertNotSame(boardA, replacementA)
        assertFalse(replacementA.canUndo)
        assertTrue(sessions.stateFor(2L, boardB.snapshot()).canUndo)
        assertSame(boardB, sessions.stateFor(2L, BoardSnapshot()))
    }

    @Test fun duplicateAndNewBoardStartWithIndependentEmptyHistory() {
        val sessions = BoardSessionViewModel()
        val source = sessions.stateFor(1L, BoardSnapshot())
        source.create("source content", TextKind.BODY, TextColor.INK, 3f, 4f)
        source.create("second edit", TextKind.TITLE, TextColor.VERMILION, 5f, 6f)
        source.undo()

        val duplicateSnapshot = source.snapshot()
        val duplicate = sessions.stateFor(2L, duplicateSnapshot)
        val newBoard = sessions.stateFor(3L, BoardSnapshot())

        assertEquals(duplicateSnapshot, duplicate.snapshot())
        assertFalse(duplicate.canUndo)
        assertFalse(duplicate.canRedo)
        assertTrue(newBoard.elements.isEmpty())
        assertFalse(newBoard.canUndo)
        assertTrue(source.canRedo)
    }

    @Test fun newSessionRestoresContentsWithoutRestoringHistory() {
        val firstSession = BoardSessionViewModel()
        val original = firstSession.stateFor(1L, BoardSnapshot())
        original.create("persisted content", TextKind.BODY, TextColor.INK, 7f, 8f)
        val persisted = original.snapshot()

        val nextSession = BoardSessionViewModel()
        val restored = nextSession.stateFor(1L, persisted)

        assertEquals(persisted, restored.snapshot())
        assertFalse(restored.canUndo)
        assertFalse(restored.canRedo)
    }
}
