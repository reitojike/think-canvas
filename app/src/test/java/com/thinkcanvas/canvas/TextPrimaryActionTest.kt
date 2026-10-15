package com.thinkcanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Test

class TextPrimaryActionTest {
    @Test fun ordinaryTextEditsWithoutASelectionTap() {
        assertEquals(TextPrimaryAction.EDIT, textPrimaryAction("text", emptySet()))
    }

    @Test fun aSingleSelectionDoesNotAddAnExtraEditTap() {
        for (selection in listOf(setOf("text"), setOf("other-text"), setOf("shape"))) {
            assertEquals(TextPrimaryAction.EDIT, textPrimaryAction("text", selection))
        }
    }

    @Test fun multipleSelectionTogglesTheTargetWithoutEditingOrMutatingTheInput() {
        val selected = setOf("text", "shape")
        assertEquals(TextPrimaryAction.REMOVE_FROM_SELECTION, textPrimaryAction("text", selected))
        assertEquals(TextPrimaryAction.ADD_TO_SELECTION, textPrimaryAction("other-text", selected))
        assertEquals(setOf("text", "shape"), selected)
    }

    @Test fun returningToOneSelectionRestoresOrdinaryEditing() {
        val selected = setOf("first", "second", "shape")
        assertEquals(TextPrimaryAction.REMOVE_FROM_SELECTION, textPrimaryAction("first", selected))
        val remaining = selected - "first" - "shape"
        assertEquals(TextPrimaryAction.EDIT, textPrimaryAction("second", remaining))
        assertEquals(TextPrimaryAction.EDIT, textPrimaryAction("first", remaining))
    }
}
