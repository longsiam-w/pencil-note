package com.vibetools.pencil.ui

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import com.vibetools.pencil.model.Bookmark
import com.vibetools.pencil.model.NoteDocument
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.vibetools.pencil.model.LibraryNode
import com.vibetools.pencil.model.LibraryNodeType
import com.vibetools.pencil.storage.LibraryStore

class DirectoryController(
    private val context: Context,
    private val container: LinearLayout,
    private val store: LibraryStore,
    private val onOpen: (LibraryNode) -> Unit,
    private val onBookmark: (LibraryNode, com.vibetools.pencil.model.Bookmark) -> Unit,
    private val onSummary: (LibraryNode, com.vibetools.pencil.model.StoredSummary) -> Unit,
    private val onDeleted: (String) -> Unit,
    private val onChanged: () -> Unit,
    private val activeDocument:()->NoteDocument?={null},
    private val onBookmarkEdit:(Bookmark,String?)->Unit={_,_->},
    private val onExportMerged:(LibraryNode)->Unit={},
    private val onChapterSelected:(LibraryNode)->Unit={} 
) {
    private val expanded = mutableSetOf<String>()
    private val chapterPrefs=context.getSharedPreferences("pencil_chapter_settings",Context.MODE_PRIVATE)
    private val wrongBookPrefs=context.getSharedPreferences("pencil_wrong_book_filter",Context.MODE_PRIVATE)
    private val reviewDays=listOf(1,6,24,75)
    private var wrongBookDays=wrongBookPrefs.getStringSet("days",emptySet()).orEmpty().mapNotNull{it.toIntOrNull()}.filter{it in reviewDays}.toSet()
    private var searchQuery=""
    private var selectedNodeId:String?=null

    private var searchJob:Job?=null
    fun setSearchQuery(value:String,currentId:String?){
        searchQuery=value.trim();selectedNodeId=currentId;searchJob?.cancel()
        if(searchQuery.isEmpty()){render(currentId);return}
        renderSearch(searchQuery)
    }
    fun render(currentId: String?) {
        selectedNodeId=currentId
        searchJob?.cancel()
        if(searchQuery.isNotBlank()){renderSearch(searchQuery);return}
        container.removeAllViews()
        val nodes = store.ensureDefaults()
        val visible=visibleWrongBookNodes(nodes)
        val visited=mutableSetOf<String>()
        fun addLevel(parent: String?, depth: Int) {
            val parentIsBook=nodes.any{it.id==parent&&it.type==LibraryNodeType.NOTE&&it.noteType==com.vibetools.pencil.model.FileType.PDF}
            nodes.filter { it.parentId == parent && it.id in visible }.sortedWith(
                compareBy<LibraryNode> { specialFolderRank(it) }
                    .thenBy { it.type != LibraryNodeType.FOLDER }
                    .thenBy { if(parentIsBook&&it.chapterSourceId==parent&&!it.chapterMerge)it.createdAt else 0L }
                    .thenBy { it.name }
            ).forEach { node ->
                if(!visited.add(node.id))return@forEach
                val book=node.type==LibraryNodeType.NOTE&&node.noteType==com.vibetools.pencil.model.FileType.PDF
                val row = TextView(context).apply {
                    text = buildString { append("  ".repeat(depth)); append(folderEmoji(node)); append(node.name); if(isWrongBook(node)&&wrongBookDays.isNotEmpty())append("（${reviewDays.filter{it in wrongBookDays}.joinToString("、")}天前）") }
                    textSize = 15f; setPadding(12, 8, 8, 8); minHeight = 48; gravity = android.view.Gravity.CENTER_VERTICAL
                    if (node.type == LibraryNodeType.FOLDER && isBuiltinFolder(node)) { setTypeface(typeface, Typeface.BOLD); setTextColor(folderColor(node)) }
                    if (node.id == currentId) { setTypeface(typeface, Typeface.BOLD); setTextColor(android.graphics.Color.rgb(200,35,35)) }
                    setOnClickListener { if (node.type == LibraryNodeType.FOLDER) { if(node.chapterSourceId!=null&&!node.chapterMerge)onChapterSelected(node); if (!expanded.add(node.id)) expanded.remove(node.id); render(currentId) } else onOpen(node) }
                    setOnLongClickListener { showNodeMenu(node); true }
                }
                if(book){
                    // Keep exactly the same TextView geometry as folder rows.
                    row.text="  ".repeat(depth)+(if(node.id in expanded)"▾ " else "▸ ")+folderEmoji(node)+node.name
                    row.setOnTouchListener{_,event->
                        if(event.actionMasked==android.view.MotionEvent.ACTION_UP&&event.x<row.paddingLeft+row.paint.measureText("  ".repeat(depth)+"▸ ")){
                            if(!expanded.add(node.id))expanded.remove(node.id);render(currentId);true
                        }else false
                    }
                }
                container.addView(row,LinearLayout.LayoutParams(-1,-2))
                if (node.type == LibraryNodeType.FOLDER && node.id in expanded) addLevel(node.id, depth + 1)
                if(book&&node.id in expanded)addLevel(node.id,depth+1)
                if ((book&&node.id in expanded)||(!book&&node.type == LibraryNodeType.NOTE && node.id == currentId)) {
                    val documents = if (node.noteType == com.vibetools.pencil.model.FileType.PDF) store.pageDocuments(node.id).toMutableList() else mutableListOf(store.loadNote(node.id))
                    (documents.filter{it.noteId!=activeDocument()?.noteId}+listOfNotNull(activeDocument()?.takeIf{it.noteId==node.id||it.noteId.startsWith("${node.id}__page_")})).flatMap { it.bookmarks }.forEach { bookmark -> addBookmarkRow(node,bookmark,depth+1) }
                    documents.flatMap { it.summaries }.forEach { summary -> addSubItem("▣ ${summary.chapterTitle}", depth + 1) { onSummary(node, summary) } }
                }
            }
        }
        addLevel(null, 0)
    }

    private fun isWrongBook(node:LibraryNode)=node.type==LibraryNodeType.FOLDER&&node.parentId==null&&node.name=="错题本"

    private fun visibleWrongBookNodes(nodes:List<LibraryNode>):Set<String>{
        if(wrongBookDays.isEmpty())return nodes.map{it.id}.toSet()
        val root=nodes.firstOrNull{isWrongBook(it)}?:return nodes.map{it.id}.toSet()
        val children=nodes.groupBy{it.parentId}
        val hidden=mutableSetOf<String>()
        fun hide(node:LibraryNode){if(hidden.add(node.id))children[node.id].orEmpty().forEach{hide(it)}}
        val targetDays=wrongBookDays.map{store.currentDayNumber()-it}.toSet()
        children[root.id].orEmpty().forEach{node->
            val day=node.name.removePrefix("DAY").trim().toLongOrNull().takeIf{node.name.startsWith("DAY")}
            if(day !in targetDays)hide(node)
        }
        return nodes.map{it.id}.filter{it !in hidden}.toSet()
    }

    private fun showWrongBookFilter(node:LibraryNode){
        val selected=wrongBookDays.toMutableSet()
        AlertDialog.Builder(context).setTitle("显示指定日期的错题（可多选）")
            .setMultiChoiceItems(reviewDays.map{"${it}天前"}.toTypedArray(),reviewDays.map{it in selected}.toBooleanArray()){_,index,checked->
                if(checked)selected.add(reviewDays[index])else selected.remove(reviewDays[index])
            }
            .setNegativeButton("取消",null)
            .setNeutralButton("显示全部"){_,_->applyWrongBookFilter(node,emptySet())}
            .setPositiveButton("应用"){_,_->applyWrongBookFilter(node,selected)}.show()
    }

    private fun applyWrongBookFilter(node:LibraryNode,days:Set<Int>){
        wrongBookDays=days.toSet()
        wrongBookPrefs.edit().putStringSet("days",days.map{it.toString()}.toSet()).apply()
        expanded.add(node.id)
        render(selectedNodeId)
    }

    private fun isBuiltinFolder(node:LibraryNode)=node.type==LibraryNodeType.FOLDER&&node.parentId==null&&node.name in setOf("草稿纸","合并页","错题本")
    private fun specialFolderRank(node:LibraryNode)=when(node.name){"草稿纸"->0;"合并页"->1;"错题本"->2;else->10}
    private fun folderEmoji(node:LibraryNode):String {
        if(node.type!=LibraryNodeType.FOLDER)return if(node.noteType==com.vibetools.pencil.model.FileType.PDF)"📚 " else "📄 "
        if(node.chapterSourceId!=null&&!node.chapterMerge)return (if(node.id in expanded)"▾ " else "▸ ")+"▤ "
        return (if(node.id in expanded)"▾ " else "▸ ")+when(node.name){"草稿纸"->"🧻 ";"合并页"->"➕ ";"错题本"->"🪦 ";else->"📁 "}
    }
    private fun folderColor(node:LibraryNode)=when(node.name){"草稿纸"->android.graphics.Color.rgb(35,135,88);"合并页"->android.graphics.Color.rgb(220,125,35);else->android.graphics.Color.rgb(45,95,190)}

    private fun addBookmarkRow(node:LibraryNode,bookmark:Bookmark,depth:Int,search:Boolean=false){
        addSubItem("🔖 ${bookmark.name}${if(search)" · ${node.name}" else ""}",depth){onBookmark(node,bookmark)}
        container.getChildAt(container.childCount-1).setOnLongClickListener{
            AlertDialog.Builder(context).setTitle(bookmark.name).setItems(arrayOf("重命名","删除")){_,which->
                if(which==0){
                    val input=EditText(context).apply{setText(bookmark.name)}
                    val dialog=AlertDialog.Builder(context).setTitle("重命名书签").setView(input).setNegativeButton("取消",null).setPositiveButton("保存",null).create()
                    dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                        val name=input.text.toString().trim()
                        if(name.isBlank())input.error="请输入书签名称" else{onBookmarkEdit(bookmark,name);dialog.dismiss()}
                    }};dialog.show()
                }else AlertDialog.Builder(context).setTitle("删除书签").setMessage("确定删除“${bookmark.name}”？").setNegativeButton("取消",null).setPositiveButton("删除"){_,_->onBookmarkEdit(bookmark,null)}.show()
            }.show();true
        }
    }
    private fun renderSearch(query:String){
        searchJob?.cancel()
        val live=activeDocument()?.let{it.copy(bookmarks=it.bookmarks.toMutableList())}
        searchJob=(context as LifecycleOwner).lifecycleScope.launch{
            delay(200)
            val result=withContext(Dispatchers.IO){
                val allNodes=store.loadNodes()
                val visible=visibleWrongBookNodes(allNodes)
                val nodes=allNodes.filter{it.type==LibraryNodeType.NOTE&&it.id in visible}
                val docs=store.searchMetadata().filter{it.noteId!=live?.noteId}+listOfNotNull(live)
                nodes to docs.groupBy{it.noteId.substringBefore("__page_")}
            }
            ensureActive();if(searchQuery!=query)return@launch
            container.removeAllViews();val needle=query.lowercase();var count=0
            result.first.forEach{node->
                if(node.name.lowercase().contains(needle)){addSubItem("📄 ${node.name}",0,node){onOpen(node)};count++}
                result.second[node.id].orEmpty().forEach{doc->
                    doc.bookmarks.filter{it.name.lowercase().contains(needle)}.forEach{addBookmarkRow(node,it,0,true);count++}
                    doc.summaries.filter{it.chapterTitle.lowercase().contains(needle)||it.concepts.lowercase().contains(needle)}.forEach{summary->addSubItem("▣ ${summary.chapterTitle} · ${node.name}",0){onSummary(node,summary)};count++}
                }
            }
            if(count==0)addSubItem("未找到“$query”",0){}
        }
    }

    private fun addSubItem(label: String, depth: Int, node:LibraryNode?=null, action: () -> Unit) {
        container.addView(TextView(context).apply { text="  ".repeat(depth)+label; textSize=14f; setPadding(12,5,8,5); setOnClickListener { action() };if(node!=null){setOnLongClickListener{showNodeMenu(node);true};if(node.id==selectedNodeId){setTypeface(typeface,Typeface.BOLD);setTextColor(android.graphics.Color.rgb(200,35,35))}} }, LinearLayout.LayoutParams(-1,-2))
    }

    private fun moveNode(node:LibraryNode){
        val nodes=store.loadNodes()
        val excluded=mutableSetOf(node.id)
        var changed=true
        while(changed){changed=false;nodes.filter{it.parentId in excluded}.forEach{if(excluded.add(it.id))changed=true}}
        LibraryTreePicker.show(context,"移动到文件夹",nodes,node.id,foldersOnly=true,allowRoot=true,
            selectable={it.type==LibraryNodeType.FOLDER&&it.id !in excluded&&it.id!=node.parentId}){target->
            store.moveNode(node.id,target?.id);onChanged()
        }
    }

    private fun showNodeMenu(node: LibraryNode) {
        val special=store.isSpecial(node)
        val canExportMerged=node.noteType==com.vibetools.pencil.model.FileType.PDF||(node.type==LibraryNodeType.FOLDER&&node.chapterSourceId!=null&&!node.chapterMerge)
        val baseActions = if(isWrongBook(node))arrayOf("在此新建文件夹", "在此新建笔记", "按时间筛选错题") else when { node.noteType == com.vibetools.pencil.model.FileType.PDF -> arrayOf("新建章节", "移动到文件夹", "重命名", "删除"); node.type == LibraryNodeType.NOTE -> arrayOf("移动到文件夹", "重命名", "删除"); node.type == LibraryNodeType.FOLDER && special -> arrayOf("在此新建文件夹", "在此新建笔记"); node.type == LibraryNodeType.FOLDER -> arrayOf("在此新建文件夹", "在此新建笔记", "重命名", "删除"); node.noteType == com.vibetools.pencil.model.FileType.PDF -> arrayOf("新建章节", "重命名", "删除"); else -> arrayOf("重命名", "删除") }
        val actions=if(canExportMerged)baseActions+if(node.type==LibraryNodeType.FOLDER||node.chapterMerge)"导出章节合并页" else "导出指定类型合并页" else baseActions
        AlertDialog.Builder(context).setTitle(node.name).setItems(actions) { _, which ->
            if(canExportMerged&&which==baseActions.size){onExportMerged(node);return@setItems}
            if(isWrongBook(node)&&which==2){showWrongBookFilter(node);return@setItems}
            if (node.type == LibraryNodeType.FOLDER && which < 2) { askCreate(node, which == 0); return@setItems }
            if (node.noteType == com.vibetools.pencil.model.FileType.PDF && which == 0) { askChapter(node); return@setItems }
            if(special)return@setItems
            if(node.type==LibraryNodeType.NOTE&&which==if(node.noteType==com.vibetools.pencil.model.FileType.PDF)1 else 0){moveNode(node);return@setItems}
            val adjusted = when { node.type == LibraryNodeType.FOLDER -> which - 2; node.noteType == com.vibetools.pencil.model.FileType.PDF -> which - 2; else -> which - 1 }
            if (adjusted == 0) {
                val input=EditText(context).apply{setText(node.name)}
                AlertDialog.Builder(context).setTitle("重命名").setView(input).setNegativeButton("取消",null).setPositiveButton("保存"){_,_->store.rename(node.id,input.text.toString().trim().ifBlank{node.name});onChanged()}.show()
            } else AlertDialog.Builder(context).setTitle("确认删除").setMessage("将同时删除子目录和笔记内容。")
                .setNegativeButton("取消",null).setPositiveButton("删除"){_,_->store.delete(node.id);onDeleted(node.id);onChanged()}.show()
        }.show()
    }

    private fun askChapter(book:LibraryNode){
        val form=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(48,8,48,0)}
        val start=EditText(context).apply{hint="起始页码（从 1 开始）";inputType=android.text.InputType.TYPE_CLASS_NUMBER}
        val offset=EditText(context).apply{hint="页码偏移（可选，默认 0）";inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED;setText(chapterPrefs.getInt("page_offset:${book.id}",0).takeIf{it!=0}?.toString().orEmpty())}
        val end=EditText(context).apply{hint="结束页码（从 1 开始）";inputType=android.text.InputType.TYPE_CLASS_NUMBER}
        val title=EditText(context).apply{hint="章节标题"}
        form.addView(start);form.addView(end);form.addView(offset);form.addView(title)
        AlertDialog.Builder(context).setTitle("新建章节").setView(form).setNegativeButton("取消",null).setPositiveButton("创建"){_,_->
            val inputFirst=start.text.toString().toIntOrNull();val inputLast=end.text.toString().toIntOrNull();val shift=offset.text.toString().toIntOrNull()?:0;val name=title.text.toString().trim();val count=store.pdfPageCount(book)
            val first=inputFirst?.plus(shift);val last=inputLast?.plus(shift)
            if(first==null||last==null||name.isBlank()||first<1||last<first||last>count){android.widget.Toast.makeText(context,"请输入有效页码、偏移值和章节标题",android.widget.Toast.LENGTH_SHORT).show();return@setPositiveButton}
            chapterPrefs.edit().putInt("page_offset:${book.id}",shift).apply()
            ChapterTemplates.choose(context){files->
                val chapter=store.createChapter(book,name,first-1,last-1,files)
                expanded+=book.id;expanded+=chapter.id;onChanged()
            }
        }.show()
    }

    private fun askCreate(parent: LibraryNode, folder: Boolean) {
        val input = EditText(context)
        AlertDialog.Builder(context).setTitle(if (folder) "新建子文件夹" else "新建笔记").setView(input)
            .setNegativeButton("取消", null).setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) { store.createNode(name, if (folder) LibraryNodeType.FOLDER else LibraryNodeType.NOTE, parent.id).also{child->
                    if(parent.chapterSourceId!=null){child.chapterSourceId=parent.chapterSourceId;child.chapterStartPage=parent.chapterStartPage;child.chapterEndPage=parent.chapterEndPage;val all=store.loadNodes();val index=all.indexOfFirst{it.id==child.id};all[index]=child;store.saveNodes(all)}
                }; expanded += parent.id; onChanged() }
            }.show()
    }
}
