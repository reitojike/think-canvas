package com.thinkcanvas.canvas

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorExitTest {
    private val original = TextElement(id = "note", text = "Original", x = 12f, y = 34f)
    private val edit = Draft(original.id, original.x, original.y, original.text)

    @Test fun newEmptyInputClosesEvenAfterKindOrColorChoiceButWhitespaceNeedsConfirmation() {
        val empty = Draft(null, 12f, 34f, kind = TextKind.TITLE, color = TextColor.VERMILION)
        assertFalse(empty.hasUncommittedChanges(null))
        assertTrue(empty.copy(text = " ").hasUncommittedChanges(null))
        assertTrue(empty.copy(text = "note").hasUncommittedChanges(null))
    }

    @Test fun existingEditChecksContentKindAndColorAndAcceptsRevertedChanges() {
        assertFalse(edit.hasUncommittedChanges(original))
        assertTrue(edit.copy(text = "").hasUncommittedChanges(original))
        assertTrue(edit.copy(kind = TextKind.TITLE).hasUncommittedChanges(original))
        assertTrue(edit.copy(color = TextColor.VERMILION).hasUncommittedChanges(original))
        assertFalse(edit.copy(text = "changed").copy(text = original.text).hasUncommittedChanges(original))
        assertFalse(edit.hasUncommittedChanges(original.copy(x = 99f, y = -5f)))
    }

    @Test fun regionNameCanBeClearedAndRevertedWithoutChangingEditorIdentity() {
        val region = RegionNameDraft("region", "Cluster")
        assertFalse(region.hasUncommittedChanges)
        val cleared = region.copy(name = "")
        assertTrue(cleared.hasUncommittedChanges)
        assertEquals(region.sessionId, cleared.sessionId)
        assertFalse(cleared.copy(name = region.originalName).hasUncommittedChanges)
        assertFalse(RegionNameDraft("new-region", "").hasUncommittedChanges)
    }
}
