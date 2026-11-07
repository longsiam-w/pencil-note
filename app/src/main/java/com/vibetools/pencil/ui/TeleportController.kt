package com.vibetools.pencil.ui

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Toast
import android.app.AlertDialog
import com.vibetools.pencil.model.*
import com.vibetools.pencil.storage.LibraryStore
import org.json.JSONObject

class TeleportController(
    private val context:Context,private val view:View,private val store:LibraryStore,
    private val document:()->NoteDocument,private val world:(Float,Float)->PointF,
    private val zoom:()->Float,private val checkpoint:()->Unit,private val changed:()->Unit,
    private val jump:(LibraryNode,NoteDocument,StoredLink,Float)->Unit
){
    private data class Mother(val node:LibraryNode,val doc:NoteDocument,val link:StoredLink)
    private suspend fun mothers():List<Mother> {
        val live=document().let{it.copy(links=it.links.toMutableList())}
        return withContext(Dispatchers.IO){
            val nodes=store.loadNodes().filter{it.type==LibraryNodeType.NOTE}.associateBy{it.id}
            (store.searchMetadata().filter{it.noteId!=live.noteId}+live).flatMap{doc->
                val node=nodes[doc.noteId.substringBefore("__page_")]
                if(node==null)emptyList()else doc.links.filter{it.kind=="teleport_mother"}.map{Mother(node,doc,it)}
            }
        }
    }
    fun insert(point:PointF){
        AlertDialog.Builder(context).setTitle("插入传送点").setItems(arrayOf("母传送点（红色，唯一编号）","子传送点（蓝色，选择目标）")){_,which->
            (context as LifecycleOwner).lifecycleScope.launch{
            if(which==0){
                val prefs=context.getSharedPreferences("pencil_teleports",Context.MODE_PRIVATE)
                val max=mothers().mapNotNull{runCatching{JSONObject(it.link.value).getLong("number")}.getOrNull()}.maxOrNull()?:0L
                val number=maxOf(prefs.getLong("sequence",0L),max)+1L
                prefs.edit().putLong("sequence",number).apply()
                checkpoint();document().links+=StoredLink(noteId=document().noteId,x=point.x,y=point.y,kind="teleport_mother",value=JSONObject().put("number",number).put("zoom",zoom()).toString(),label="🌀 $number");changed()
            }else{
                val targets=mothers().sortedBy{runCatching{JSONObject(it.link.value).optLong("number")}.getOrDefault(0L)}
                if(targets.isEmpty()){Toast.makeText(context,"请先放置一个母传送点",Toast.LENGTH_SHORT).show();return@launch}
                AlertDialog.Builder(context).setTitle("选择母传送点").setItems(targets.map{"${it.link.label} · ${it.node.name}${if(it.node.noteType==FileType.PDF) " · 第${page(it.doc.noteId)+1}页" else ""}"}.toTypedArray()){_,index->
                    checkpoint();document().links+=StoredLink(noteId=document().noteId,x=point.x,y=point.y,kind="teleport_child",value=targets[index].link.id,label="🌀");changed()
                }.show()
            }
            }
        }.show()
    }
    private fun page(id:String)=Regex("__page_(\\d+)$").find(id)?.groupValues?.get(1)?.toIntOrNull()?:0
    private var touched:String?=null;private var downX=0f;private var downY=0f;private var lastX=0f;private var lastY=0f
    private var dragging=false;private var longPressed=false;private var cancelled=false;private var task:Runnable?=null
    fun touch(event:MotionEvent):Boolean{
        if(event.actionMasked==MotionEvent.ACTION_DOWN){
            val p=world(event.x,event.y)
            val link=document().links.lastOrNull{it.kind.startsWith("teleport_")&&TeleportDrawing.bounds(it).contains(p.x,p.y)}?:return false
            touched=link.id;downX=event.x;downY=event.y;lastX=event.x;lastY=event.y;dragging=false;longPressed=false;cancelled=false
            view.parent?.requestDisallowInterceptTouchEvent(true)
            task=Runnable{
                val item=document().links.firstOrNull{it.id==touched}?:return@Runnable
                if(!dragging&&!cancelled){longPressed=true
                    if(item.kind=="teleport_child"){
                        (context as LifecycleOwner).lifecycleScope.launch{
                        val target=mothers().firstOrNull{it.link.id==item.value}
                        if(target==null)Toast.makeText(context,"对应母传送点已不存在",Toast.LENGTH_SHORT).show()
                        else jump(target.node,target.doc,target.link,runCatching{JSONObject(target.link.value).optDouble("zoom",1.0).toFloat()}.getOrDefault(1f))
                        }
                    }else AlertDialog.Builder(context).setTitle(item.label).setItems(arrayOf("删除母传送点")){_,_->checkpoint();document().links.removeAll{it.id==item.id};changed()}.show()
                }
            }.also{view.postDelayed(it,ViewConfiguration.getLongPressTimeout().toLong())};return true
        }
        val id=touched?:return false
        if(event.pointerCount>1){cancelled=true;task?.let{view.removeCallbacks(it)}}
        when(event.actionMasked){
            MotionEvent.ACTION_MOVE->{if(!cancelled&&!longPressed&&(dragging||kotlin.math.hypot(event.x-downX,event.y-downY)>ViewConfiguration.get(context).scaledTouchSlop)){
                val first=!dragging;if(first){task?.let{view.removeCallbacks(it)};checkpoint();dragging=true}
                val index=document().links.indexOfFirst{it.id==id};if(index>=0){val item=document().links[index];document().links[index]=item.copy(x=item.x+(event.x-(if(first)downX else lastX))/zoom(),y=item.y+(event.y-(if(first)downY else lastY))/zoom());changed()}
            };lastX=event.x;lastY=event.y}
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{task?.let{view.removeCallbacks(it)};task=null;touched=null;view.parent?.requestDisallowInterceptTouchEvent(false)
                if(!dragging&&!longPressed&&!cancelled&&event.actionMasked==MotionEvent.ACTION_UP){val item=document().links.firstOrNull{it.id==id};if(item!=null)AlertDialog.Builder(context).setTitle(if(item.kind=="teleport_child")"子传送点（长按跳转）" else item.label).setItems(arrayOf("删除")){_,_->checkpoint();document().links.removeAll{it.id==id};changed()}.show()}
            }
        };return true
    }
}

object TeleportDrawing {
    fun bounds(link:StoredLink):RectF {val paint=android.graphics.Paint().apply{textSize=20f};return RectF(link.x,link.y-30f,link.x+paint.measureText(link.label)+20f,link.y+10f)}
    fun draw(canvas:android.graphics.Canvas,link:StoredLink){val p=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);p.color=if(link.kind=="teleport_mother")android.graphics.Color.argb(145,220,45,45)else android.graphics.Color.argb(145,30,110,220);canvas.drawRoundRect(bounds(link),8f,8f,p);p.color=android.graphics.Color.WHITE;p.textSize=20f;canvas.drawText(link.label,link.x+10f,link.y,p)}
}
