package com.vibetools.pencil.ui

import android.text.Editable
import android.text.TextWatcher
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.*
import com.vibetools.pencil.model.*

/** A fresh expansion state per picker; only the current document's ancestors open initially. */
class LibraryTreePicker(
    private val context:Context,
    private val nodes:List<LibraryNode>,
    currentId:String?,
    private val selectable:(LibraryNode)->Boolean,
    private val foldersOnly:Boolean=false,
    private val checked:((LibraryNode)->Boolean)?=null,
    private val onPick:(LibraryNode)->Unit
){
    private val byId=nodes.associateBy{it.id}
    private fun parent(node:LibraryNode):String? {
        var id=node.parentId
        val seen=mutableSetOf<String>()
        while(foldersOnly&&id!=null){val key=id;if(!seen.add(key))return null;val ancestor=byId[key]?:return null;if(ancestor.type==LibraryNodeType.FOLDER)return key;id=ancestor.parentId}
        return id
    }
    private val expanded=mutableSetOf<String>()
    private val visible=mutableSetOf<String>()
    private val children:Map<String?,List<LibraryNode>>
    private val current=currentId
    private val rows=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
    private var query=""
    private val scrollView=ScrollView(context).apply{isFillViewport=true;addView(rows);minimumHeight=dp(200)}
    private val search=EditText(context).apply{hint=if(foldersOnly)"搜索文件夹" else "搜索文件或文件夹";isSingleLine=true;setPadding(dp(16),dp(8),dp(16),dp(8))}
    val view=LinearLayout(context).apply{
        orientation=LinearLayout.VERTICAL
        addView(search,LinearLayout.LayoutParams(-1,-2))
        addView(scrollView,LinearLayout.LayoutParams(-1,0,1f))
        layoutParams=LinearLayout.LayoutParams(-1,(context.resources.displayMetrics.heightPixels*.52f).toInt())
    }
    init{
        nodes.filter{it.type==LibraryNodeType.FOLDER||(!foldersOnly&&selectable(it))}.forEach{node->
            var cursor:LibraryNode?=node
            val seen=mutableSetOf<String>()
            while(true){val item=cursor?:break;if(!seen.add(item.id))break;visible.add(item.id);cursor=byId[parent(item)]}
        }
        var ancestorId=byId[currentId]?.let{parent(it)}
        while(true){val id=ancestorId?:break;if(!expanded.add(id))break;ancestorId=byId[id]?.let{parent(it)}}
        children=nodes.filter{it.id in visible&&(!foldersOnly||it.type==LibraryNodeType.FOLDER)}.groupBy{parent(it)?.takeIf{p->p in visible}}
        search.addTextChangedListener(object:TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){query=s?.toString().orEmpty().trim();scrollView.scrollTo(0,0);refresh()}
            override fun afterTextChanged(s:Editable?){}
        })
        refresh()
    }
    private fun dp(value:Int)=(value*context.resources.displayMetrics.density).toInt()
    fun refresh(){
        val scroll=scrollView.scrollY
        val matches=if(query.isBlank())visible else nodes.filter{it.id in visible&&it.name.contains(query,ignoreCase=true)}.map{it.id}.toSet()
        val filtered=matches.toMutableSet()
        matches.forEach{id->var cursor=byId[id]?.let{parent(it)};val seen=mutableSetOf<String>();while(cursor!=null){val key=cursor;if(!seen.add(key))break;filtered.add(key);cursor=byId[key]?.let{parent(it)}}}
        rows.removeAllViews()
        fun level(parentId:String?,depth:Int,ancestors:Set<String>){
            children[parentId].orEmpty().sortedWith(compareBy<LibraryNode>{if(it.type==LibraryNodeType.FOLDER)when(it.name){"草稿纸"->0;"合并页"->1;"错题本"->2;else->3}else 4}.thenBy{if(it.chapterSourceId!=null&&!it.chapterMerge)it.createdAt else 0L}.thenBy{it.name}).forEach{node->
                if(node.id in ancestors||node.id !in filtered)return@forEach
                val branch=!children[node.id].isNullOrEmpty()
                val row=LinearLayout(context).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8+depth*20),0,dp(8),0);minimumHeight=dp(48)}
                fun toggle(){if(!expanded.add(node.id))expanded.remove(node.id);refresh()}
                row.addView(TextView(context).apply{text=if(branch)if(node.id in expanded||query.isNotBlank())"▾" else "▸" else "";textSize=20f;gravity=Gravity.CENTER;contentDescription="展开或收起 ${node.name}";if(branch)setOnClickListener{toggle()}},LinearLayout.LayoutParams(dp(40),dp(48)))
                val allowed=selectable(node)
                val label=TextView(context).apply{
                    val icon=if(node.type==LibraryNodeType.FOLDER){if(node.chapterSourceId!=null&&!node.chapterMerge)"▤" else "📁"} else if(node.noteType==FileType.PDF)"📚" else "📄"
                    text="${if(checked!=null&&allowed)if(checked.invoke(node))"☑ " else "☐ " else ""}$icon ${node.name}"
                    textSize=16f;setPadding(dp(4),dp(10),dp(4),dp(10))
                    if(node.id==current){setTypeface(typeface,Typeface.BOLD);setTextColor(Color.rgb(200,35,35))}
                    setOnClickListener{if(node.type==LibraryNodeType.FOLDER||!allowed){if(branch)toggle()}else onPick(node)}
                }
                row.addView(label,LinearLayout.LayoutParams(0,-2,1f))
                if(node.type==LibraryNodeType.FOLDER&&allowed)row.addView(Button(context).apply{text="选择";contentDescription="选择文件夹 ${node.name}";setOnClickListener{onPick(node)}},LinearLayout.LayoutParams(-2,dp(48)))
                rows.addView(row)
                if(node.id in expanded||query.isNotBlank())level(node.id,depth+1,ancestors+node.id)
            }
        }
        level(null,0,emptySet())
        if(rows.childCount==0)rows.addView(TextView(context).apply{text="未找到匹配项";setPadding(dp(24),dp(16),dp(24),dp(16))})
        scrollView.post{scrollView.scrollTo(0,scroll)}
    }
    companion object{
        fun show(context:Context,title:String,nodes:List<LibraryNode>,currentId:String?,foldersOnly:Boolean=false,allowRoot:Boolean=false,selectable:(LibraryNode)->Boolean={it.type==LibraryNodeType.NOTE},onReset:(()->Unit)?=null,onPick:(LibraryNode?)->Unit):AlertDialog{
            lateinit var dialog:AlertDialog
            val tree=LibraryTreePicker(context,nodes,currentId,selectable,foldersOnly){dialog.dismiss();onPick(it)}
            val builder=AlertDialog.Builder(context).setTitle(title).setView(tree.view).setNegativeButton("取消",null)
            if(allowRoot)builder.setNeutralButton("选择根目录"){_,_->onPick(null)}
            else if(onReset!=null)builder.setNeutralButton("恢复默认"){_,_->onReset()}
            dialog=builder.create();dialog.show();return dialog
        }
    }
}
