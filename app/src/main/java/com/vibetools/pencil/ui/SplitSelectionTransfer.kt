package com.vibetools.pencil.ui

import android.graphics.*
import android.view.View
import android.widget.Toast
import com.vibetools.pencil.canvas.CanvasGlView
import com.vibetools.pencil.model.NoteDocument
import com.vibetools.pencil.storage.LibraryStore
import com.vibetools.pencil.util.DocumentRasterizer

/** Preview-only drag: neither document changes until a validated drop is durably saved. */
class SplitSelectionTransfer(
    private val workspace:SplitWorkspaceLayout,
    private val library:LibraryStore,
    private val ready:()->Boolean,
    private val canvas:(Int)->CanvasGlView,
    private val document:(Int)->NoteDocument?,
    private val capture:(Int)->SplitTransferContent?,
    private val flush:()->Unit,
    private val apply:(Int,NoteDocument)->Unit,
    private val failed:(Exception)->Unit
){
    private data class Drag(val side:Int,val content:SplitTransferContent,val sourceId:String,val targetId:String,val bounds:RectF,val sourceZoom:Float,val startX:Float,val startY:Float,val bitmap:Bitmap?,var dx:Float=0f,var dy:Float=0f)
    private var drag:Drag?=null
    init{
        workspace.onTransferStart=::start;workspace.onTransferMove={x,y->drag?.let{it.dx=x-it.startX;it.dy=y-it.startY};workspace.invalidate()}
        workspace.onTransferEnd=::finish;workspace.drawTransfer=::draw
    }
    private fun offset(view:View):PointF{val a=IntArray(2);val b=IntArray(2);view.getLocationOnScreen(a);workspace.getLocationOnScreen(b);return PointF((a[0]-b[0]).toFloat(),(a[1]-b[1]).toFloat())}
    private fun start(side:Int,x:Float,y:Float):Boolean{
        if(!ready())return false
        val view=canvas(side)
        if(!view.isMovingSelection())return false
        val origin=offset(view);val world=view.screenToWorld(x-origin.x,y-origin.y)
        val selection=view.selectedRegion()?:return false
        if(world.x !in selection.left..selection.right||world.y !in selection.top..selection.bottom)return false
        val content=capture(side)?.takeUnless{it.empty()}?:return false
        val bounds=content.bounds();if(bounds.isEmpty)return false
        val topLeft=view.screenToWorld(0f,0f);val z=view.currentZoom()
        val screen=RectF(origin.x+(bounds.left-topLeft.x)*z,origin.y+(bounds.top-topLeft.y)*z,origin.x+(bounds.right-topLeft.x)*z,origin.y+(bounds.bottom-topLeft.y)*z)
        val doc=document(side)?:return false;val target=document(1-side)?:return false
        val bitmap=runCatching{val scale=minOf(1f,1200f/maxOf(bounds.width(),bounds.height()));DocumentRasterizer.render(content.document("preview"),bounds,(bounds.width()*scale).toInt().coerceAtLeast(1),(bounds.height()*scale).toInt().coerceAtLeast(1),paperColor=Color.WHITE)}.getOrNull()
        drag=Drag(side,content,doc.noteId,target.noteId,screen,z,x,y,bitmap)
        return true
    }
    private fun moved(d:Drag)=RectF(d.bounds).apply{offset(d.dx,d.dy)}
    private fun targetBounds(side:Int):RectF{val view=canvas(side);val p=offset(view);return RectF(p.x,p.y,p.x+view.width,p.y+view.height)}
    private fun valid(d:Drag):Boolean{val b=moved(d);val t=targetBounds(1-d.side);return SplitGeometry.fullyInside(b.left,b.top,b.right,b.bottom,t.left,t.top,t.right,t.bottom)}
    private fun draw(output:Canvas){val d=drag?:return;val b=moved(d);val p=Paint(Paint.ANTI_ALIAS_FLAG);p.alpha=190;d.bitmap?.let{output.drawBitmap(it,null,b,p)};p.alpha=255;p.color=if(valid(d))Color.rgb(30,160,90)else Color.rgb(220,120,40);p.style=Paint.Style.STROKE;p.strokeWidth=2f*workspace.resources.displayMetrics.density;output.drawRect(b,p)}
    private fun finish(commit:Boolean){
        val d=drag?:return;drag=null
        try{
            if(!commit||!ready())return
            val source=document(d.side)?:return;val target=document(1-d.side)?:return
            if(source.noteId!=d.sourceId||target.noteId!=d.targetId)return
            val b=moved(d);val own=targetBounds(d.side)
            if(SplitGeometry.fullyInside(b.left,b.top,b.right,b.bottom,own.left,own.top,own.right,own.bottom)){
                val view=canvas(d.side);view.moveMovingSelectionBy(d.dx/d.sourceZoom,d.dy/d.sourceZoom);view.requestMoveConfirmation();return
            }
            if(!valid(d)){Toast.makeText(workspace.context,"内容未完全进入对侧，未移动",Toast.LENGTH_SHORT).show();return}
            flush()
            val view=canvas(1-d.side);val origin=offset(view);val targetWorld=view.screenToWorld(0f,0f)
            val sourceView=canvas(d.side);val sourceOrigin=offset(sourceView);val sourceWorld=sourceView.screenToWorld(0f,0f)
            val scale=d.sourceZoom/view.currentZoom()
            val dx=targetWorld.x+(sourceOrigin.x+d.dx-origin.x)/view.currentZoom()-sourceWorld.x*scale
            val dy=targetWorld.y+(sourceOrigin.y+d.dy-origin.y)/view.currentZoom()-sourceWorld.y*scale
            // Labels with fixed font sizes can be larger after changing zoom. Validate actual target rendering too.
            val transformed=d.content.appendTo(NoteDocument("bounds"),scale,dx,dy)
            val actual=SplitTransferContent(com.vibetools.pencil.model.CanvasClip(strokes=transformed.strokes,texts=transformed.texts,images=transformed.images,links=transformed.links,summaries=transformed.summaries),transformed.foldedNotes,transformed.emphasisStars).bounds()
            val z=view.currentZoom()
            if(!SplitGeometry.fullyInside(origin.x+(actual.left-targetWorld.x)*z,origin.y+(actual.top-targetWorld.y)*z,origin.x+(actual.right-targetWorld.x)*z,origin.y+(actual.bottom-targetWorld.y)*z,origin.x,origin.y,origin.x+view.width,origin.y+view.height)){
                Toast.makeText(workspace.context,"内容未完全进入对侧，未移动",Toast.LENGTH_SHORT).show();return
            }
            val sourceAfter=d.content.removeFrom(document(d.side)?:return)
            val targetAfter=d.content.appendTo(document(1-d.side)?:return,scale,dx,dy)
            library.saveSplitTransfer(sourceAfter,targetAfter)
            apply(d.side,sourceAfter);apply(1-d.side,targetAfter)
            Toast.makeText(workspace.context,"已移动到对侧笔记",Toast.LENGTH_SHORT).show()
        }catch(error:Exception){failed(error)}finally{d.bitmap?.recycle();workspace.invalidate()}
    }
}
