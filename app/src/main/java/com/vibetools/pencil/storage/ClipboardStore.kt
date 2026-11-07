package com.vibetools.pencil.storage

import android.content.Context
import android.graphics.PointF
import com.vibetools.pencil.model.*
import org.json.JSONArray
import org.json.JSONObject

class ClipboardStore(context: Context) {
    private val file=java.io.File(context.filesDir,"pencil/clipboard.json")
    private val openedGeneration=synchronized(LibraryIo.lock){LibraryIo.generation}
    fun all():MutableList<CanvasClip> = synchronized(LibraryIo.lock){
        if(!file.exists())return@synchronized mutableListOf<CanvasClip>()
        SafeFiles.read(file){raw->val a=JSONArray(raw);MutableList(a.length()){decode(a.getJSONObject(it))}}
    }
    fun push(clip:CanvasClip){val items=all();items.add(0,clip);while(items.size>50)items.removeAt(items.lastIndex);save(items)}
    fun delete(id:String){val items=all();items.removeAll{it.id==id};save(items)}
    private fun save(items:List<CanvasClip>)=synchronized(LibraryIo.lock){
        check(openedGeneration==LibraryIo.generation){"资料库已恢复，请重新打开页面后再修改"}
        if(file.exists())all()
        SafeFiles.write(file,JSONArray().also{a->items.forEach{a.put(encode(it))}}.toString())
    }
    private fun encode(c:CanvasClip)=JSONObject().put("id",c.id).put("createdAt",c.createdAt)
        .put("strokes",JSONArray().also{a->c.strokes.forEach{s->a.put(JSONObject().put("id",s.id).put("tool",s.tool.name).put("color",s.color).put("width",s.width).put("alpha",s.alpha).put("points",JSONArray().also{p->s.points.forEach{q->p.put(JSONObject().put("x",q.x).put("y",q.y))}}))}})
        .put("texts",JSONArray().also{a->c.texts.forEach{t->a.put(JSONObject().put("id",t.id).put("text",t.text).put("x",t.x).put("y",t.y).put("fontSize",t.fontSize).put("color",t.color))}})
        .put("images",JSONArray().also{a->c.images.forEach{i->a.put(JSONObject().put("id",i.id).put("path",i.path).put("x",i.x).put("y",i.y).put("width",i.width).put("height",i.height))}})
        .put("links",JSONArray().also{a->c.links.forEach{l->a.put(JSONObject().put("id",l.id).put("noteId",l.noteId).put("pageId",l.pageId).put("x",l.x).put("y",l.y).put("kind",l.kind).put("value",l.value).put("label",l.label))}})
        .put("summaries",JSONArray().also{a->c.summaries.forEach{s->a.put(JSONObject().put("id",s.id).put("noteId",s.noteId).put("chapterTitle",s.chapterTitle).put("concepts",s.concepts).put("memory",s.memory).put("methods",s.methods).put("mistakes",s.mistakes).put("createdAt",s.createdAt).put("x",s.x).put("y",s.y))}})
    private fun decode(o:JSONObject):CanvasClip{val ss=mutableListOf<Stroke>();o.optJSONArray("strokes")?.let{a->for(i in 0 until a.length()){val s=a.getJSONObject(i);val ps=mutableListOf<PointF>();s.optJSONArray("points")?.let{p->for(j in 0 until p.length()){val q=p.getJSONObject(j);ps+=PointF(q.optDouble("x").toFloat(),q.optDouble("y").toFloat())}};ss+=Stroke(s.getString("id"),Tool.valueOf(s.getString("tool")),s.optInt("color"),s.optDouble("width").toFloat(),s.optInt("alpha",255),ps)}};val ts=mutableListOf<TextBox>();o.optJSONArray("texts")?.let{a->for(i in 0 until a.length()){val t=a.getJSONObject(i);ts+=TextBox(t.getString("id"),t.getString("text"),t.optDouble("x").toFloat(),t.optDouble("y").toFloat(),t.optDouble("fontSize",18.0).toFloat(),t.optInt("color"))}};val images=mutableListOf<CanvasImage>();o.optJSONArray("images")?.let{a->for(i in 0 until a.length()){val v=a.getJSONObject(i);images+=CanvasImage(v.getString("id"),v.getString("path"),v.optDouble("x").toFloat(),v.optDouble("y").toFloat(),v.optDouble("width",420.0).toFloat(),v.optDouble("height",300.0).toFloat())}};val links=mutableListOf<StoredLink>();o.optJSONArray("links")?.let{a->for(i in 0 until a.length()){val l=a.getJSONObject(i);links+=StoredLink(l.getString("id"),l.optString("noteId"),l.optString("pageId","page-1"),l.optDouble("x").toFloat(),l.optDouble("y").toFloat(),l.optString("kind"),l.optString("value"),l.optString("label"))}};val summaries=mutableListOf<StoredSummary>();o.optJSONArray("summaries")?.let{a->for(i in 0 until a.length()){val s=a.getJSONObject(i);summaries+=StoredSummary(s.getString("id"),s.optString("noteId"),s.optString("chapterTitle"),s.optString("concepts"),s.optString("memory"),s.optString("methods"),s.optString("mistakes"),s.optLong("createdAt"),s.optDouble("x").toFloat(),s.optDouble("y").toFloat())}};return CanvasClip(id=o.optString("id"),strokes=ss,texts=ts,images=images,links=links,summaries=summaries,createdAt=o.optLong("createdAt"))}
}
