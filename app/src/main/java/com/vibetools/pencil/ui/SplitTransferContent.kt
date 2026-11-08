package com.vibetools.pencil.ui

import android.graphics.*
import com.vibetools.pencil.model.*

/** Immutable membership captured at the start of a move, including non-stroke objects. */
data class SplitTransferContent(val clip:CanvasClip,val folds:List<FoldedNote>,val stars:List<EmphasisStar>) {
    fun empty()=clip.strokes.isEmpty()&&clip.texts.isEmpty()&&clip.images.isEmpty()&&clip.links.isEmpty()&&clip.summaries.isEmpty()&&folds.isEmpty()&&stars.isEmpty()
    fun document(id:String)=NoteDocument(id,strokes=clip.strokes.toMutableList(),texts=clip.texts.toMutableList(),images=clip.images.toMutableList(),links=clip.links.toMutableList(),summaries=clip.summaries.toMutableList(),foldedNotes=folds.toMutableList(),emphasisStars=stars.toMutableList())
    fun bounds():RectF {
        val r=RectF();var initialized=false
        fun include(b:RectF){if(!initialized){r.set(b);initialized=true}else r.union(b)}
        fun content(doc:NoteDocument){
            doc.strokes.forEach{s->if(s.points.isNotEmpty()){val pad=s.width/2;include(RectF(s.points.minOf{it.x}-pad,s.points.minOf{it.y}-pad,s.points.maxOf{it.x}+pad,s.points.maxOf{it.y}+pad))}}
            doc.texts.forEach{t->val p=Paint().apply{textSize=t.fontSize;typeface=Typeface.DEFAULT_BOLD};val lines=t.text.lines();include(RectF(t.x,t.y+p.fontMetrics.ascent,t.x+(lines.maxOfOrNull{p.measureText(it)}?:0f),t.y+p.fontSpacing*(lines.size-1)+p.fontMetrics.descent))}
            doc.images.forEach{include(RectF(it.x,it.y,it.x+it.width,it.y+it.height))}
            doc.links.forEach{l->include(if(l.kind.startsWith("teleport_"))TeleportDrawing.bounds(l)else RectF(l.x-6,l.y-24,l.x+Paint().apply{textSize=14f}.measureText(l.label.take(28))+24,l.y+8))}
            doc.summaries.forEach{include(RectF(it.x,it.y,it.x+32,it.y+32))}
        }
        content(document(""));folds.forEach{include(FoldedNoteLabel.bounds(it));if(it.expanded)content(NoteDocument("",strokes=it.strokes.toMutableList(),texts=it.texts.toMutableList(),images=it.images.toMutableList(),links=it.links.toMutableList(),summaries=it.summaries.toMutableList()))};stars.forEach{include(EmphasisStarDrawing.bounds(it))}
        return r
    }
    fun removeFrom(doc:NoteDocument)=doc.copy(
        strokes=doc.strokes.filterNot{s->clip.strokes.any{it.id==s.id}}.toMutableList(),texts=doc.texts.filterNot{s->clip.texts.any{it.id==s.id}}.toMutableList(),
        images=doc.images.filterNot{s->clip.images.any{it.id==s.id}}.toMutableList(),links=doc.links.filterNot{s->clip.links.any{it.id==s.id}}.toMutableList(),summaries=doc.summaries.filterNot{s->clip.summaries.any{it.id==s.id}}.toMutableList(),foldedNotes=doc.foldedNotes.filterNot{s->folds.any{it.id==s.id}}.toMutableList(),emphasisStars=doc.emphasisStars.filterNot{s->stars.any{it.id==s.id}}.toMutableList())
    fun appendTo(doc:NoteDocument,scale:Float,dx:Float,dy:Float):NoteDocument {
        fun x(v:Float)=v*scale+dx
        fun y(v:Float)=v*scale+dy
        fun stroke(s:Stroke)=s.copy(width=s.width*scale,points=s.points.map{PointF(x(it.x),y(it.y))}.toMutableList())
        fun text(t:TextBox)=t.copy(x=x(t.x),y=y(t.y),fontSize=t.fontSize*scale)
        fun image(i:CanvasImage)=i.copy(x=x(i.x),y=y(i.y),width=i.width*scale,height=i.height*scale)
        fun link(l:StoredLink)=l.copy(noteId=doc.noteId,x=x(l.x),y=y(l.y))
        fun summary(s:StoredSummary)=s.copy(noteId=doc.noteId,x=x(s.x),y=y(s.y))
        return doc.copy(strokes=(doc.strokes+clip.strokes.map(::stroke)).toMutableList(),texts=(doc.texts+clip.texts.map(::text)).toMutableList(),images=(doc.images+clip.images.map(::image)).toMutableList(),links=(doc.links+clip.links.map(::link)).toMutableList(),summaries=(doc.summaries+clip.summaries.map(::summary)).toMutableList(),foldedNotes=(doc.foldedNotes+folds.map{it.copy(x=x(it.x),y=y(it.y),strokes=it.strokes.map(::stroke),texts=it.texts.map(::text),images=it.images.map(::image),links=it.links.map(::link),summaries=it.summaries.map(::summary))}).toMutableList(),emphasisStars=(doc.emphasisStars+stars.map{it.copy(x=x(it.x),y=y(it.y),size=it.size*scale)}).toMutableList())
    }
    companion object {
        fun capture(canvas:com.vibetools.pencil.canvas.CanvasGlView,doc:NoteDocument,imageIds:Set<String>,linkIds:Set<String>,summaryIds:Set<String>,foldIds:Set<String>,starIds:Set<String>)=SplitTransferContent(
            (canvas.movingClip()?:CanvasClip(strokes=mutableListOf(),texts=mutableListOf())).copy(images=doc.images.filter{it.id in imageIds}.map{it.copy()}.toMutableList(),links=doc.links.filter{it.id in linkIds}.map{it.copy()}.toMutableList(),summaries=doc.summaries.filter{it.id in summaryIds}.map{it.copy()}.toMutableList()),doc.foldedNotes.filter{it.id in foldIds}.map{it.deepCopy()},doc.emphasisStars.filter{it.id in starIds}.map{it.copy()})
    }
}
