package com.vibetools.pencil.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.widget.*
import com.vibetools.pencil.model.*
import com.vibetools.pencil.storage.LibraryStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class QuickAccessController(private val context:Context,private val panel:LinearLayout,private val library:LibraryStore,private val onOpen:(LibraryNode,Int?)->Unit){
    private val target=QuickAccessTarget()
    val controlsSecondary get()=target.secondary
    fun setSplitEnabled(enabled:Boolean){target.setSplitEnabled(enabled);render()}
    private val prefs=context.getSharedPreferences("pencil_quick_access",Context.MODE_PRIVATE)
    private var group=""
    private var recordPages=true
    private var currentId:String?=null
    private fun entries():JSONArray=runCatching{JSONArray(prefs.getString("group:$group","[]"))}.getOrElse{JSONArray()}
    private fun save(items:JSONArray){prefs.edit().putString("group:$group",items.toString()).apply();render()}
    private var openingGroup:String?=null
    private fun chapterOf(node:LibraryNode,nodes:List<LibraryNode>):LibraryNode?{
        val byId=nodes.associateBy{it.id}
        var current:LibraryNode?=if(node.chapterMerge)byId[node.chapterSourceId]else node
        val visited=mutableSetOf<String>()
        while(current!=null&&visited.add(current.id)){
            if(current.type==LibraryNodeType.FOLDER&&current.chapterSourceId!=null&&!current.chapterMerge)return current
            current=byId[current.parentId]
        }
        return null
    }
    fun load(node:LibraryNode){
        recordPages=true
        val nodes=library.loadNodes()
        val chapter=chapterOf(node,nodes)
        val requestedGroup=openingGroup;openingGroup=null
        if(chapter!=null){
            currentId=node.id
            group="chapter:${chapter.id}"
            if(!prefs.contains("group:$group")){
                val items=JSONArray();val ids=mutableSetOf<String>()
                fun add(item:JSONObject){if(ids.add(item.optString("id")))items.put(item)}
                nodes.firstOrNull{it.id==chapter.chapterSourceId}?.let{add(JSONObject().put("id",it.id).put("page",chapter.chapterStartPage?:0))}
                val members=nodes.filter{chapterOf(it,nodes)?.id==chapter.id&&it.type==LibraryNodeType.NOTE}.sortedBy{it.id!=node.id}
                val migratedPages=mutableSetOf<String>()
                val migration=prefs.edit()
                members.forEach{member->
                    val old=prefs.getString("file:${member.id}",null)
                    val entries=runCatching{JSONArray(prefs.getString("group:$old","[]"))}.getOrElse{JSONArray()}
                    for(i in 0 until entries.length()){
                        val item=entries.getJSONObject(i);add(item)
                        val id=item.optString("id");val oldKey="page:$old:$id"
                        if(prefs.contains(oldKey)&&migratedPages.add(id))migration.putInt("page:$group:$id",prefs.getInt(oldKey,0))
                    }
                    add(JSONObject().put("id",member.id))
                }
                migration.putString("group:$group",items.toString()).apply()
            }
            render();return
        }
        if(requestedGroup!=null){currentId=node.id;group=requestedGroup;render();return}
        currentId=node.id
        group=prefs.getString("file:${node.id}",null)?:UUID.randomUUID().toString().also{newGroup->
            val nodes=library.loadNodes()
            val chapter=if(node.chapterMerge)nodes.firstOrNull{it.id==node.chapterSourceId}else node.takeIf{it.chapterSourceId!=null}
            val items=JSONArray()
            chapter?.let{c->nodes.firstOrNull{it.id==c.chapterSourceId}?.let{book->items.put(JSONObject().put("id",book.id).put("page",c.chapterStartPage?:0))};items.put(JSONObject().put("id",c.id))}
            if(chapter==null)items.put(JSONObject().put("id",node.id))
            prefs.edit().putString("group:$newGroup",items.toString()).putString("file:${node.id}",newGroup).apply()
        }
        render()
    }
    /** Clicking a chapter explicitly refreshes its shared shortcuts from current descendants. */
    fun showChapter(chapter:LibraryNode,activeId:String?){
        if(chapter.type!=LibraryNodeType.FOLDER||chapter.chapterSourceId==null||chapter.chapterMerge)return
        val nodes=library.loadNodes()
        val children=nodes.groupBy{it.parentId}
        val visited=mutableSetOf<String>()
        val items=JSONArray()
        fun collect(parentId:String){
            if(!visited.add(parentId))return
            children[parentId].orEmpty().sortedWith(compareBy<LibraryNode>{it.type!=LibraryNodeType.FOLDER}.thenBy{it.name}.thenBy{it.createdAt}).forEach{node->
                if(node.type==LibraryNodeType.FOLDER)collect(node.id)
                else items.put(JSONObject().put("id",node.id))
            }
        }
        collect(chapter.id)
        openingGroup=null
        recordPages=false
        currentId=activeId
        group="chapter:${chapter.id}"
        save(items)
    }
    fun rememberPage(node:LibraryNode,page:Int){
        if(recordPages&&group.isNotBlank()&&node.noteType==FileType.PDF) prefs.edit().putInt("page:$group:${node.id}",page).apply()
    }
    private fun render(){
        panel.removeAllViews()
        val header=LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL}
        header.addView(TextView(context).apply{text="快捷访问";setTypeface(typeface,Typeface.BOLD);setPadding(8,8,8,8)},LinearLayout.LayoutParams(0,-2,1f))
        header.addView(Button(context).apply{
            text=if(target.secondary)"副 >" else "< 主"
            textSize=12f;minWidth=0;minimumWidth=0;setPadding(0,0,0,0)
            isEnabled=target.splitEnabled
            contentDescription=if(target.secondary)"快捷访问控制副笔记，点击切换为主笔记" else "快捷访问控制主笔记，点击切换为副笔记"
            tooltipText=if(target.splitEnabled)contentDescription else "开启分屏后可切换快捷访问的目标"
            setOnClickListener{target.toggle();render()}
        },LinearLayout.LayoutParams(48.dp(),40.dp()))
        header.addView(Button(context).apply{text="＋";contentDescription="添加文件快捷方式";setOnClickListener{add()}},LinearLayout.LayoutParams(48.dp(),40.dp()))
        panel.addView(header)
        val list=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
        val nodes=library.loadNodes().associateBy{it.id}
        val items=entries()
        for(i in 0 until items.length()){
            val item=items.getJSONObject(i)
            val node=nodes[item.optString("id")]?:continue
            val row=LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL}
            row.addView(TextView(context).apply{
                text=node.name;maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(8,10,4,10)
                if(node.id==currentId){setTypeface(typeface,Typeface.BOLD);setTextColor(Color.rgb(200,35,35))}
                setOnClickListener{
                    openingGroup=if(target.secondary)null else group
                    val pageKey="page:$group:${node.id}"
                    val page=if(prefs.contains(pageKey))prefs.getInt(pageKey,0)else if(item.has("page"))item.optInt("page")else null
                    onOpen(node,page)
                }
            },LinearLayout.LayoutParams(0,-2,1f))
            row.addView(TextView(context).apply{text="×";textSize=22f;gravity=android.view.Gravity.CENTER;contentDescription="移除 ${node.name} 的快捷方式";setOnClickListener{val next=JSONArray();for(j in 0 until items.length())if(j!=i)next.put(items.getJSONObject(j));save(next)}},LinearLayout.LayoutParams(36.dp(),44.dp()))
            list.addView(row)
        }
        if(list.childCount==0)list.addView(TextView(context).apply{text="点击 ＋ 添加文件";setPadding(8,12,8,12)})
        panel.addView(ScrollView(context).apply{addView(list)},LinearLayout.LayoutParams(-1,-2).apply{height=minOf(220.dp(),maxOf(44.dp(),list.childCount*48.dp()))})
    }
    private fun add(){
        val existing=entries();val ids=(0 until existing.length()).map{existing.getJSONObject(it).optString("id")}.toSet()
        val nodes=library.loadNodes().filter{it.type==LibraryNodeType.NOTE&&it.id !in ids}.sortedBy{library.displayPath(it)}
        if(nodes.isEmpty()){Toast.makeText(context,"没有可添加的文件",Toast.LENGTH_SHORT).show();return}
        LibraryTreePicker.show(context,"添加快捷访问",library.loadNodes(),currentId,
            selectable={it.type==LibraryNodeType.NOTE&&it.id !in ids}){node->
            node?:return@show
            existing.put(JSONObject().put("id",node.id));save(existing)
        }
    }
    private fun Int.dp()=(this*context.resources.displayMetrics.density).toInt()
}
