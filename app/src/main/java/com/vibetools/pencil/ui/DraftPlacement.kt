package com.vibetools.pencil.ui

import android.graphics.Paint
import android.graphics.RectF
import com.vibetools.pencil.model.*

object DraftPlacement {
    fun emptyViewport(note:NoteDocument,width:Float,height:Float):RectF {
        val occupied=mutableListOf<RectF>()
        fun content(strokes:List<Stroke>,texts:List<TextBox>,images:List<CanvasImage>,links:List<StoredLink>,summaries:List<StoredSummary>){
            strokes.filter{it.points.isNotEmpty()}.forEach{s->val pad=s.width/2f+2f;occupied+=RectF(s.points.minOf{it.x}-pad,s.points.minOf{it.y}-pad,s.points.maxOf{it.x}+pad,s.points.maxOf{it.y}+pad)}
            texts.forEach{t->val p=Paint().apply{textSize=t.fontSize;typeface=android.graphics.Typeface.DEFAULT_BOLD};val lines=t.text.split('\n');occupied+=RectF(t.x,t.y+p.ascent(),t.x+(lines.maxOfOrNull{p.measureText(it)}?:0f).coerceAtLeast(1f),t.y+p.descent()+p.fontSpacing*(lines.size-1))}
            images.forEach{occupied+=RectF(it.x,it.y,it.x+it.width,it.y+it.height)}
            links.forEach{l->occupied+=if(l.kind.startsWith("teleport_"))TeleportDrawing.bounds(l)else RectF(l.x-8f,l.y-28f,l.x+Paint().apply{textSize=14f}.measureText(l.label)+24f,l.y+12f)}
            summaries.forEach{occupied+=RectF(it.x,it.y,it.x+32f,it.y+32f)}
        }
        content(note.strokes,note.texts,note.images,note.links,note.summaries)
        note.foldedNotes.forEach{occupied+=FoldedNoteLabel.bounds(it);content(it.strokes,it.texts,it.images,it.links,it.summaries)}
        note.emphasisStars.forEach{occupied+=EmphasisStarDrawing.bounds(it)}
        // Search adjacent to occupied edges, so sparse areas are reused before extending the page.
        val gap=24f
        val xs=(listOf(0f)+occupied.map{it.right+gap}).filter{it>=0f&&it.isFinite()}.distinct().sorted()
        val ys=(listOf(0f)+occupied.map{it.bottom+gap}).filter{it>=0f&&it.isFinite()}.distinct().sorted()
        for(y in ys)for(x in xs){val r=RectF(x,y,x+width,y+height);if(occupied.none{RectF.intersects(r,it)})return r}
        val bottom=(occupied.maxOfOrNull{it.bottom}?:0f).coerceAtLeast(0f)+gap
        return RectF(0f,bottom,width,bottom+height)
    }
}
