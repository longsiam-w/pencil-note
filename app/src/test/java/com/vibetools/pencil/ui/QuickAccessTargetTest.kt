package com.vibetools.pencil.ui

import org.junit.Assert.*
import org.junit.Test

class QuickAccessTargetTest {
    @Test fun `starts on main and cannot target hidden secondary`() {
        val state=QuickAccessTarget()
        assertFalse(state.secondary)
        state.toggle()
        assertFalse(state.secondary)
    }
    @Test fun `split mode toggles target in both directions`() {
        val state=QuickAccessTarget()
        state.setSplitEnabled(true)
        state.toggle();assertTrue(state.secondary)
        state.toggle();assertFalse(state.secondary)
    }
    @Test fun `leaving split restores main and reopening does not silently target secondary`() {
        val state=QuickAccessTarget()
        state.setSplitEnabled(true);state.toggle()
        state.setSplitEnabled(false);assertFalse(state.secondary)
        state.setSplitEnabled(true);assertFalse(state.secondary)
    }
    @Test fun `refreshing enabled state preserves selected target`() {
        val state=QuickAccessTarget()
        state.setSplitEnabled(true);state.toggle();state.setSplitEnabled(true)
        assertTrue(state.secondary)
    }
}
