package com.vibetools.pencil.canvas

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.vibetools.pencil.model.CanvasImage
import com.vibetools.pencil.model.StoredLink
import com.vibetools.pencil.model.StoredSummary
import com.vibetools.pencil.model.TextBox

class NoteOverlayView @JvmOverloads constructor(context:Context,attrs:AttributeSet?=null,defStyleAttr:Int=0):View(context,attrs,defStyleAttr){
    private var pageBackground:Bitmap? = null;private var selectionPath:List<PointF> = emptyList();private var strokes:List<com.vibetools.pencil.model.Stroke> = emptyList();private var texts:List<TextBox> = emptyList();private var links:List<StoredLink> = emptyList();private var images:List<CanvasImage> = emptyList();private var summaries:List<StoredSummary> = emptyList()
    private var foldedNotes:List<com.vibetools.pencil.model.FoldedNote> = emptyList()
    fun setFoldedNotes(notes:List<com.vibetools.pencil.model.FoldedNote>){foldedNotes=notes.toList();postInvalidateOnAnimation()}
    private var emphasisStars:List<com.vibetools.pencil.model.EmphasisStar> = emptyList()
    fun setEmphasisStars(stars:List<com.vibetools.pencil.model.EmphasisStar>){emphasisStars=stars.toList();postInvalidateOnAnimation()}
    private var panX=0f;private var panY=0f;private var zoom=1f
    private data class StrokeCache(val pointCount:Int,val minX:Float,val minY:Float,val maxX:Float,val maxY:Float,val path:Path)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG);private val tagRect=RectF();private val bitmapCache=mutableMapOf<String,Bitmap>();private val strokeCache=mutableMapOf<String,StrokeCache>()
    init{setWillNotDraw(false);isClickable=false;isFocusable=false}
    fun updateContent(newTexts:List<TextBox>,newLinks:List<StoredLink>,newImages:List<CanvasImage> = images,newSummaries:List<StoredSummary> = summaries,newStrokes:List<com.vibetools.pencil.model.Stroke> = strokes){texts=newTexts;links=newLinks;images=newImages;summaries=newSummaries;strokes=newStrokes;val valid=(images+foldedNotes.flatMap{it.images}).map{it.path}.toSet();bitmapCache.keys.filter{it !in valid}.forEach{key->bitmapCache.remove(key)?.takeIf{!it.isRecycled}?.recycle()};strokeCache.clear();postInvalidateOnAnimation()}
    fun updateTransform(x:Float,y:Float,z:Float){panX=x;panY=y;zoom=z;postInvalidateOnAnimation()}
    fun setPageBackground(bitmap:Bitmap?){pageBackground=bitmap;postInvalidateOnAnimation()}
    fun setSelectionPath(path:List<PointF>){selectionPath=path;postInvalidateOnAnimation()}
    override fun onDetachedFromWindow(){bitmapCache.values.forEach{if(!it.isRecycled)it.recycle()};bitmapCache.clear();strokeCache.clear();pageBackground=null;super.onDetachedFromWindow()}
    private fun strokeBounds(stroke:com.vibetools.pencil.model.Stroke):RectF{var minX=Float.MAX_VALUE;var minY=Float.MAX_VALUE;var maxX=-Float.MAX_VALUE;var maxY=-Float.MAX_VALUE;stroke.points.forEach{minX=minOf(minX,it.x);minY=minOf(minY,it.y);maxX=maxOf(maxX,it.x);maxY=maxOf(maxY,it.y)};val pad=stroke.width.coerceAtLeast(1f);return RectF(minX-pad,minY-pad,maxX+pad,maxY+pad)}
    private fun cachedStroke(stroke:com.vibetools.pencil.model.Stroke):StrokeCache{val old=strokeCache[stroke.id];if(old!=null&&old.pointCount==stroke.points.size)return old;val path=Path();val first=stroke.points.first();path.moveTo(first.x,first.y);stroke.points.drop(1).forEach{path.lineTo(it.x,it.y)};val r=strokeBounds(stroke);return StrokeCache(stroke.points.size,r.left,r.top,r.right,r.bottom,path).also{strokeCache[stroke.id]=it}}
    private fun visible(r:RectF,b:RectF)=r.right>=b.left&&r.left<=b.right&&r.bottom>=b.top&&r.top<=b.bottom
    override fun onDraw(canvas:Canvas){super.onDraw(canvas)
        val expanded=foldedNotes.filter{it.expanded}
        val images=this.images+expanded.flatMap{it.images}
        val texts=this.texts+expanded.flatMap{it.texts}
        val strokes=this.strokes+expanded.flatMap{it.strokes}
        val links=this.links+expanded.flatMap{it.links}
        val summaries=this.summaries+expanded.flatMap{it.summaries}
        val worldViewport=RectF(-panX,-panY,-panX+width/zoom,-panY+height/zoom)
        pageBackground?.takeIf{!it.isRecycled}?.let{bitmap->paint.alpha=255;paint.style=Paint.Style.FILL;val left=panX*zoom;val top=panY*zoom;canvas.drawBitmap(bitmap,null,RectF(left,top,left+bitmap.width*zoom,top+bitmap.height*zoom),paint)}
        images.filter{visible(worldViewport,RectF(it.x,it.y,it.x+it.width,it.y+it.height))}.forEach{item->val bitmap=bitmapCache[item.path]?:BitmapFactory.decodeFile(item.path)?.also{bitmapCache[item.path]=it};if(bitmap!=null){paint.alpha=255;val left=(item.x+panX)*zoom;val top=(item.y+panY)*zoom;canvas.drawBitmap(bitmap,null,RectF(left,top,left+item.width*zoom,top+item.height*zoom),paint)}}
        texts.filter{visible(worldViewport,RectF(it.x,it.y-it.fontSize,it.x+paint.measureText(it.text),it.y+it.fontSize*2f))}.forEach{text->paint.color=text.color;paint.style=Paint.Style.FILL;paint.alpha=255;paint.style=Paint.Style.FILL;paint.textSize=text.fontSize*zoom;paint.typeface=Typeface.DEFAULT_BOLD;val x=(text.x+panX)*zoom;var y=(text.y+panY)*zoom;text.text.split('\n').forEach{line->canvas.drawText(line,x,y,paint);y+=paint.fontSpacing}}
        strokes.forEach{stroke->if(stroke.points.size>1){val cached=cachedStroke(stroke);if(visible(worldViewport,RectF(cached.minX,cached.minY,cached.maxX,cached.maxY))){paint.color=stroke.color;paint.alpha=stroke.alpha;paint.style=Paint.Style.STROKE;paint.strokeWidth=stroke.width;paint.strokeCap=Paint.Cap.ROUND;paint.strokeJoin=Paint.Join.ROUND;canvas.save();canvas.scale(zoom,zoom);canvas.translate(panX,panY);canvas.drawPath(cached.path,paint);canvas.restore()}}}
        links.filter{!it.kind.startsWith("teleport_")&&visible(worldViewport,RectF(it.x-80f,it.y-40f,it.x+240f,it.y+40f))}.forEach{link->val label=link.label.take(28);paint.typeface=Typeface.DEFAULT;paint.textSize=12f*zoom;paint.strokeWidth=1f;paint.color=Color.rgb(0,100,165);val x=(link.x+panX)*zoom;val y=(link.y+panY)*zoom;val w=paint.measureText(label)+16f*zoom;paint.alpha=45;tagRect.set(x-6f*zoom,y-paint.textSize-6f*zoom,x+w,y+6f*zoom);canvas.drawRoundRect(tagRect,6f*zoom,6f*zoom,paint);paint.alpha=255;canvas.drawText(label,x,y,paint)}
        summaries.filter{visible(worldViewport,RectF(it.x,it.y,it.x+32f,it.y+32f))}.forEach{s->val x=(s.x+panX)*zoom;val y=(s.y+panY)*zoom;val size=32f*zoom;paint.color=Color.rgb(49,95,130);paint.alpha=220;paint.style=Paint.Style.FILL;tagRect.set(x,y,x+size,y+size);canvas.drawRoundRect(tagRect,7f*zoom,7f*zoom,paint);paint.color=Color.WHITE;paint.textSize=17f*zoom;paint.typeface=Typeface.DEFAULT;canvas.drawText("Σ",x+8f*zoom,y+22f*zoom,paint)}
        canvas.save();canvas.scale(zoom,zoom);canvas.translate(panX,panY)
        links.filter{it.kind.startsWith("teleport_")&&visible(worldViewport,com.vibetools.pencil.ui.TeleportDrawing.bounds(it))}.forEach{com.vibetools.pencil.ui.TeleportDrawing.draw(canvas,it)}
        foldedNotes.filter{visible(worldViewport,com.vibetools.pencil.model.FoldedNoteLabel.bounds(it))}.forEach{com.vibetools.pencil.model.FoldedNoteLabel.draw(canvas,it)}
        val hue=(android.os.SystemClock.uptimeMillis()%6000L)/6000f*360f
        emphasisStars.forEachIndexed{i,star->if(visible(worldViewport,RectF(star.x,star.y,star.x+star.size,star.y+star.size)))com.vibetools.pencil.model.EmphasisStarDrawing.draw(canvas,star,(hue+i*120f)%360f)}
        canvas.restore()
        if(emphasisStars.isNotEmpty()&&isShown)postInvalidateDelayed(50L)
        if(selectionPath.size>1){paint.color=Color.rgb(30,120,230);paint.alpha=230;paint.style=Paint.Style.STROKE;paint.strokeWidth=2f;paint.pathEffect=android.graphics.DashPathEffect(floatArrayOf(10f,7f),0f);val selectionPaintPath=Path();val first=selectionPath.first();selectionPaintPath.moveTo((first.x+panX)*zoom,(first.y+panY)*zoom);selectionPath.drop(1).forEach{point->selectionPaintPath.lineTo((point.x+panX)*zoom,(point.y+panY)*zoom)};canvas.drawPath(selectionPaintPath,paint);paint.pathEffect=null}
    }
}
