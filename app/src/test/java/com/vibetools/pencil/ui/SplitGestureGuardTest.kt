package com.vibetools.pencil.ui

import org.junit.Assert.*
import org.junit.Test

class SplitGestureGuardTest {
    @Test fun `same pane multi pointer samples are accepted`() {
        val guard = SplitGestureGuard()
        guard.begin(20f, 1000)
        assertEquals(0, guard.owner)
        assertTrue(guard.accept(listOf(30f, 499f, 90f), 1000))
    }
    @Test fun `pointers on opposite panes invalidate entire stream`() {
        val guard = SplitGestureGuard()
        guard.begin(20f, 1000)
        assertFalse(guard.accept(listOf(20f, 700f), 1000))
        assertFalse(guard.accept(listOf(20f), 1000))
    }
    @Test fun `crossing then returning remains invalid until a new down`() {
        val guard = SplitGestureGuard()
        guard.begin(700f, 1000)
        assertFalse(guard.accept(listOf(499f), 1000))
        assertFalse(guard.accept(listOf(700f), 1000))
        guard.begin(700f, 1000)
        assertTrue(guard.accept(listOf(600f, 800f), 1000))
    }
    @Test fun `midpoint belongs only to right pane including odd widths`() {
        val guard = SplitGestureGuard()
        guard.begin(500f, 1001)
        assertEquals(1, guard.owner)
        assertFalse(guard.accept(listOf(499.9f), 1001))
    }
    @Test fun `out of bounds or nonfinite samples invalidate stream`() {
        for (x in listOf(-1f, 1000f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val guard = SplitGestureGuard()
            guard.begin(600f, 1000)
            assertFalse(guard.accept(listOf(x), 1000))
        }
    }
    @Test fun `historical crossing sample invalidates otherwise valid event`() {
        val guard = SplitGestureGuard()
        guard.begin(50f, 1000)
        assertFalse(guard.accept(listOf(60f, 501f, 70f), 1000))
    }
    @Test fun `reset removes previous owner`() {
        val guard = SplitGestureGuard()
        guard.begin(50f, 1000); guard.reset()
        assertNull(guard.owner)
        assertFalse(guard.accept(listOf(50f), 1000))
    }
    @Test fun `main switching to secondary clears secondary by id not name`() {
        assertNull(SplitNoteIdentity.secondaryAfterMainChange("a", "a"))
        assertEquals("b", SplitNoteIdentity.secondaryAfterMainChange("a", "b"))
        assertNull(SplitNoteIdentity.secondaryAfterMainChange("a", null))
    }
}
