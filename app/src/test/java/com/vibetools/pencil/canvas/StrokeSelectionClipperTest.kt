package com.vibetools.pencil.canvas

import org.junit.Assert.*
import org.junit.Test

class StrokeSelectionClipperTest {
    private fun p(x:Double,y:Double)=StrokeSelectionClipper.Point(x,y)
    private val box=listOf(p(0.0,0.0),p(10.0,0.0),p(10.0,10.0),p(0.0,10.0))
    @Test fun crossingWithBothEndpointsOutside(){
        val parts=StrokeSelectionClipper.split(listOf(p(-5.0,5.0),p(15.0,5.0)),box)
        assertEquals(listOf(false,true,false),parts.map{it.inside})
        assertEquals(p(0.0,5.0),parts[1].points.first())
        assertEquals(p(10.0,5.0),parts[1].points.last())
    }
    @Test fun multipleCrossingsRemainSeparate(){
        val parts=StrokeSelectionClipper.split(listOf(p(-5.0,5.0),p(15.0,5.0),p(-5.0,5.0)),box)
        assertEquals(listOf(false,true,false,true,false),parts.map{it.inside})
    }
    @Test fun closedLassoAndTangent(){
        val triangle=listOf(p(0.0,0.0),p(10.0,0.0),p(5.0,10.0),p(0.0,0.0))
        assertEquals(listOf(false,true,false),StrokeSelectionClipper.split(listOf(p(-5.0,5.0),p(15.0,5.0)),triangle).map{it.inside})
        assertFalse(StrokeSelectionClipper.split(listOf(p(-5.0,5.0),p(5.0,-5.0)),box).any{it.inside})
    }
    @Test fun insideAndBoundaryStrokes(){
        assertTrue(StrokeSelectionClipper.split(listOf(p(2.0,2.0),p(8.0,8.0)),box).single().inside)
        assertTrue(StrokeSelectionClipper.split(listOf(p(0.0,0.0),p(10.0,0.0)),box).single().inside)
    }
}
