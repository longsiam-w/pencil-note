package com.vibetools.pencil.ui

import org.junit.Assert.*
import org.junit.Test

class SplitGeometryTest {
    @Test fun `divider clamps to thirty and seventy percent`() {
        assertEquals(.3f,SplitGeometry.ratio(-1f),0f)
        assertEquals(.7f,SplitGeometry.ratio(2f),0f)
        assertEquals(300,SplitGeometry.divider(1000,.1f))
        assertEquals(700,SplitGeometry.divider(1000,.9f))
        assertEquals(.5f,SplitGeometry.ratio(Float.NaN),0f)
    }
    @Test fun `entire rectangle must be in receiving pane`() {
        assertTrue(SplitGeometry.fullyInside(600f,10f,900f,200f,600f,0f,1000f,500f))
        assertFalse(SplitGeometry.fullyInside(599f,10f,900f,200f,600f,0f,1000f,500f))
        assertFalse(SplitGeometry.fullyInside(600f,-1f,900f,200f,600f,0f,1000f,500f))
        assertFalse(SplitGeometry.fullyInside(600f,10f,1001f,200f,600f,0f,1000f,500f))
    }
    @Test fun `reverse move validates left pane and vertical edges`() {
        assertTrue(SplitGeometry.fullyInside(0f,0f,300f,400f,0f,0f,300f,400f))
        assertFalse(SplitGeometry.fullyInside(0f,0f,301f,400f,0f,0f,300f,400f))
        assertFalse(SplitGeometry.fullyInside(0f,0f,300f,401f,0f,0f,300f,400f))
    }
    @Test fun `empty and invalid bounds cannot transfer`() {
        assertFalse(SplitGeometry.fullyInside(10f,0f,10f,20f,0f,0f,100f,100f))
        assertFalse(SplitGeometry.fullyInside(Float.NaN,0f,20f,20f,0f,0f,100f,100f))
    }
    @Test fun `ordinary gestures follow moved divider instead of midpoint`() {
        val guard=SplitGestureGuard()
        guard.begin(550f,1000,700)
        assertEquals(0,guard.owner)
        assertTrue(guard.accept(listOf(650f),1000,700))
        assertFalse(guard.accept(listOf(700f),1000,700))
        guard.begin(350f,1000,300)
        assertEquals(1,guard.owner)
        assertFalse(guard.accept(listOf(299f),1000,300))
    }
}
