package com.vibetools.pencil.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.vibetools.pencil.model.NoteDocument

object DocumentRasterizer {
    fun render(note: NoteDocument, region: RectF, width: Int, height: Int, background: Bitmap? = null, includeBackground: Boolean = true, paperColor: Int = Color.WHITE): Bitmap {
        val bitmap=Bitmap.createBitmap(width.coerceAtLeast(1),height.coerceAtLeast(1),Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap)
        // AI screenshots must be opaque; transparent PNGs are decoded as black by some vision APIs.
        canvas.drawColor(paperColor)
        val sx=bitmap.width/region.width().coerceAtLeast(.0001f);val sy=bitmap.height/region.height().coerceAtLeast(.0001f)
        canvas.save();canvas.scale(sx,sy);canvas.translate(-region.left,-region.top);canvas.clipRect(region)
        if(includeBackground&&background!=null&&!note.backgroundHidden)canvas.drawBitmap(background,null,RectF(0f,0f,background.width.toFloat(),background.height.toFloat()),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        drawContent(canvas,note,region)
        canvas.restore();return bitmap
    }

    /** Draw directly onto a PDF canvas so strokes and text retain vector detail. */
    fun drawContent(canvas:Canvas,note:NoteDocument,region:RectF){
        val groups=note.foldedNotes.filter{it.expanded}
        val visible=note.copy(strokes=(note.strokes+groups.flatMap{it.strokes}).toMutableList(),texts=(note.texts+groups.flatMap{it.texts}).toMutableList(),images=(note.images+groups.flatMap{it.images}).toMutableList(),links=(note.links+groups.flatMap{it.links}).toMutableList(),summaries=(note.summaries+groups.flatMap{it.summaries}).toMutableList())
        val imagePaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        visible.images.filter{it.x+it.width>=region.left&&it.x<=region.right&&it.y+it.height>=region.top&&it.y<=region.bottom}.forEach{item->android.graphics.BitmapFactory.decodeFile(item.path)?.let{image->canvas.drawBitmap(image,null,RectF(item.x,item.y,item.x+item.width,item.y+item.height),imagePaint);image.recycle()}}
        visible.strokes.forEach{s->if(s.points.size>1){val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=s.color;alpha=s.alpha;strokeWidth=s.width;style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND};val path=Path();path.moveTo(s.points.first().x,s.points.first().y);s.points.drop(1).forEach{path.lineTo(it.x,it.y)};canvas.drawPath(path,paint)}}
        visible.texts.forEach{t->val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=t.color;textSize=t.fontSize;typeface=android.graphics.Typeface.DEFAULT_BOLD};var y=t.y;t.text.split('\n').forEach{line->canvas.drawText(line,t.x,y,paint);y+=paint.fontSpacing}}
        visible.links.filter{it.x in region.left..region.right&&it.y in region.top..region.bottom}.forEach{l->val linkPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(0,100,165);textSize=14f};canvas.drawText(l.label.take(28),l.x,l.y,linkPaint)}
        visible.summaries.filter{it.x in region.left..region.right&&it.y in region.top..region.bottom}.forEach{s->val summaryPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(49,95,130)};canvas.drawRoundRect(RectF(s.x,s.y,s.x+32f,s.y+32f),7f,7f,summaryPaint);summaryPaint.color=Color.WHITE;summaryPaint.textSize=18f;summaryPaint.typeface=android.graphics.Typeface.DEFAULT_BOLD;canvas.drawText("Σ",s.x+8f,s.y+23f,summaryPaint)}
        note.foldedNotes.forEach{com.vibetools.pencil.model.FoldedNoteLabel.draw(canvas,it)}
        note.emphasisStars.forEach{com.vibetools.pencil.model.EmphasisStarDrawing.draw(canvas,it)}
    }

    fun contentBounds(source:NoteDocument):RectF { val groups=source.foldedNotes.filter{it.expanded};val note=source.copy(strokes=(source.strokes+groups.flatMap{it.strokes}).toMutableList(),texts=(source.texts+groups.flatMap{it.texts}).toMutableList(),images=(source.images+groups.flatMap{it.images}).toMutableList(),links=(source.links+groups.flatMap{it.links}).toMutableList(),summaries=(source.summaries+groups.flatMap{it.summaries}).toMutableList());val xs=note.strokes.flatMap{it.points}.map{it.x}+note.texts.map{it.x}+note.links.map{it.x}+note.summaries.map{it.x}+note.images.flatMap{listOf(it.x,it.x+it.width)}+source.foldedNotes.flatMap{val r=com.vibetools.pencil.model.FoldedNoteLabel.bounds(it);listOf(r.left,r.right)}+source.emphasisStars.flatMap{listOf(it.x,it.x+it.size)};val ys=note.strokes.flatMap{it.points}.map{it.y}+note.texts.map{it.y}+note.links.map{it.y}+note.summaries.map{it.y}+note.images.flatMap{listOf(it.y,it.y+it.height)}+source.foldedNotes.flatMap{val r=com.vibetools.pencil.model.FoldedNoteLabel.bounds(it);listOf(r.top,r.bottom)}+source.emphasisStars.flatMap{listOf(it.y,it.y+it.size)};return if(xs.isEmpty()||ys.isEmpty())RectF(0f,0f,1600f,1200f)else RectF(xs.minOrNull()!!-20,ys.minOrNull()!!-20,xs.maxOrNull()!!+100,ys.maxOrNull()!!+100)}
}
