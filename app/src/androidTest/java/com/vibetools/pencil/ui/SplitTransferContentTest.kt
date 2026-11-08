package com.vibetools.pencil.ui

import android.graphics.PointF
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibetools.pencil.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SplitTransferContentTest {
    @Test fun movingRemovesOnlyCapturedMembershipAndUpdatesDestinationReferences() {
        val stroke=Stroke("move",Tool.PEN,0xff000000.toInt(),4f,points=mutableListOf(PointF(10f,20f),PointF(30f,40f)))
        val link=StoredLink(id="link",noteId="source",x=10f,y=20f,kind="internal",value="note")
        val content=SplitTransferContent(CanvasClip(strokes=mutableListOf(stroke),texts=mutableListOf(),links=mutableListOf(link)),emptyList(),emptyList())
        val source=NoteDocument("source",strokes=mutableListOf(stroke,stroke.copy(id="keep")),links=mutableListOf(link))
        val after=content.removeFrom(source)
        assertEquals(listOf("keep"),after.strokes.map{it.id});assertTrue(after.links.isEmpty())
        assertEquals(2,source.strokes.size)
        val target=content.appendTo(NoteDocument("target"),2f,100f,200f)
        assertEquals(120f,target.strokes.single().points.first().x,0f)
        assertEquals(240f,target.strokes.single().points.first().y,0f)
        assertEquals(8f,target.strokes.single().width,0f)
        assertEquals("target",target.links.single().noteId)
        assertEquals(10f,stroke.points.first().x,0f)
    }
    @Test fun renderedBoundsIncludePenRadiusAndFullTextNotOnlyAnchor() {
        val content=SplitTransferContent(CanvasClip(strokes=mutableListOf(Stroke("s",Tool.PEN,0,20f,points=mutableListOf(PointF(100f,100f),PointF(200f,100f)))),texts=mutableListOf(TextBox("text","Long text outside tiny selection",190f,100f,30f))),emptyList(),emptyList())
        val b=content.bounds()
        assertEquals(90f,b.left,0f);assertTrue(b.right>210f);assertTrue(b.top<100f);assertTrue(b.bottom>100f)
    }
    @Test fun foldedContentsAndEmphasisMoveTogetherWithUniqueIdentityPreserved() {
        val fold=FoldedNote(id="fold",x=10f,y=20f,expanded=true,texts=listOf(TextBox("t","inside",100f,200f,20f)))
        val star=EmphasisStar(x=30f,y=40f)
        val content=SplitTransferContent(CanvasClip(strokes=mutableListOf(),texts=mutableListOf()),listOf(fold),listOf(star))
        val result=content.appendTo(NoteDocument("target"),.5f,20f,30f)
        assertEquals("fold",result.foldedNotes.single().id)
        assertEquals(70f,result.foldedNotes.single().texts.single().x,0f)
        assertEquals(star.id,result.emphasisStars.single().id)
        assertTrue(content.removeFrom(NoteDocument("source",foldedNotes=mutableListOf(fold),emphasisStars=mutableListOf(star))).foldedNotes.isEmpty())
    }
}
