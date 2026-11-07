package com.vibetools.pencil.canvas

import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibetools.pencil.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Requires an Android device; tests actual View/tool state rather than just geometry rules. */
@RunWith(AndroidJUnit4::class)
class SplitCanvasEditingTest {
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun view() = CanvasGlView(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test fun sharedWritingAndErasingConfigurationDoesNotShareDocument() = onMain {
        val main=view();val secondary=view()
        main.setWriteTool(Tool.HIGHLIGHTER);main.setColor(0xff336699.toInt());main.setStrokeWidth(120f)
        secondary.copyToolsFrom(main)
        assertEquals(CanvasGlView.Mode.WRITE,secondary.currentMode())
        assertEquals(main.currentTool(),secondary.currentTool())
        assertEquals(main.currentColor(),secondary.currentColor())
        assertEquals(main.currentStrokeWidth(),secondary.currentStrokeWidth(),0f)
        main.setEraserTool(EraserTool.SAND_ERASER);main.setEraserSize(32f);secondary.copyToolsFrom(main)
        assertEquals(CanvasGlView.Mode.ERASE,secondary.currentMode())
        assertEquals(main.currentEraserTool(),secondary.currentEraserTool())
        assertEquals(32f,secondary.currentEraserSize(),0f)
        assertTrue(main.getStrokes().isEmpty());assertTrue(secondary.getStrokes().isEmpty())
    }

    @Test fun sharedSelectionStyleAndTextSize() = onMain {
        val main=view();val secondary=view()
        main.setSelectionStyle(CanvasGlView.SelectionStyle.FREEFORM);main.setTextFontSize(32f)
        secondary.copyToolsFrom(main)
        assertEquals(CanvasGlView.Mode.SELECT,secondary.currentMode())
        assertEquals(CanvasGlView.SelectionStyle.FREEFORM,secondary.currentSelectionStyle())
        assertEquals(32f,secondary.currentTextFontSize(),0f)
    }

    @Test fun cancelledWritingRemovesPartialStrokeWithoutUndoEntry() = onMain {
        val secondary=view();secondary.setWriteTool(Tool.PEN);secondary.beginSplitGesture()
        fun send(action:Int,x:Float) { val e=MotionEvent.obtain(0,10,action,x,30f,0);secondary.onTouchEvent(e);e.recycle() }
        send(MotionEvent.ACTION_DOWN,10f);send(MotionEvent.ACTION_MOVE,80f)
        assertFalse(secondary.getStrokes().isEmpty())
        send(MotionEvent.ACTION_CANCEL,80f)
        assertTrue(secondary.getStrokes().isEmpty());assertFalse(secondary.canUndo())
    }

    @Test fun cancelledObjectDragRestoresExtrasEvenInDragMode() = onMain {
        val secondary=view();secondary.setMode(CanvasGlView.Mode.DRAG)
        var extras=DocumentExtras(mutableListOf(),mutableListOf(),mutableListOf(),mutableListOf(),emphasisStars=mutableListOf(EmphasisStar(x=10f,y=20f)))
        secondary.setExtrasHistory({extras.copy(emphasisStars=extras.emphasisStars.map{it.copy()}.toMutableList())}) { extras=it }
        secondary.beginSplitGesture()
        extras.emphasisStars[0]=extras.emphasisStars[0].copy(x=900f)
        secondary.cancelSplitGesture()
        assertEquals(10f,extras.emphasisStars[0].x,0f)
    }
}
