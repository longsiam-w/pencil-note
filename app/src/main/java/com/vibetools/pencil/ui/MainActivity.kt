package com.vibetools.pencil.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfRenderer
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.print.PrintManager
import android.view.View
import android.view.MotionEvent
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import com.vibetools.pencil.R
import com.vibetools.pencil.ai.AiClient
import com.vibetools.pencil.canvas.CanvasGlView
import com.vibetools.pencil.canvas.NoteOverlayView
import com.vibetools.pencil.model.*
import com.vibetools.pencil.storage.ClipboardStore
import com.vibetools.pencil.storage.LibraryStore
import com.vibetools.pencil.util.DocumentRasterizer
import com.vibetools.pencil.merge.MergeCandidate
import com.vibetools.pencil.merge.MergePageService
import com.vibetools.pencil.print.PrintCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.io.ByteArrayOutputStream
import android.util.Base64

class MainActivity : AppCompatActivity() {
    private lateinit var drawer: androidx.drawerlayout.widget.DrawerLayout
    private var activeSplitSide=0
    private lateinit var splitNotes: SplitNoteController
    private lateinit var workspace: SplitWorkspaceLayout
    private lateinit var canvas: CanvasGlView
    private lateinit var tools: LinearLayout
    private lateinit var overlay: NoteOverlayView
    private lateinit var library: LibraryStore
    private lateinit var quickAccess:QuickAccessController
    private var pendingRestore:Runnable?=null
    private var pageLoaded=false
    private lateinit var clips: ClipboardStore
    private lateinit var directory: DirectoryController
    private lateinit var mergeService: MergePageService
    private lateinit var printCoordinator: PrintCoordinator
    private var nodes = mutableListOf<LibraryNode>()
    private var currentNode: LibraryNode? = null
    private var current = NoteDocument("")
    private var insertKind = InsertKind.TEXT
    private var aiMode = AiMode.DIRECT
    private var pdfPageIndex = 0
    private var pdfPageCount = 0
    private var currentBackgroundBitmap: Bitmap? = null
    private data class ReturnLocation(val node:LibraryNode,val page:Int,val x:Float,val y:Float)
    private var linkReturnLocation: ReturnLocation? = null
    private var wrongBookReturnLocation: ReturnLocation? = null
    private var chapterReturnLocation: ReturnLocation? = null
    private var draftReturnLocation: ReturnLocation? = null
    private var recognitionRegion: android.graphics.RectF? = null
    private var aiAttachmentEnabled=false
    private var sizeSliderTextMode=false
    private var highlighterWidth=96f
    private var recognizedTextId: String? = null
    private val ai = AiClient()

    private enum class InsertKind { TEXT, BOOKMARK, LINK, IMAGE, EMPHASIS, TELEPORT }
    private lateinit var teleports:TeleportController
    private var teleportReturn:ReturnLocation?=null
    private var teleportReturnZoom=1f
    private var pendingTeleportView:Triple<Float,Float,Float>?=null
    private var activeTeleportView:Triple<Float,Float,Float>?=null
    private lateinit var teleportBack:Button
    private enum class AiMode { DIRECT, SELECTION, RECOGNIZE }

    private val openPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> importDocument(uri, FileType.PDF) } }
    private val openImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> importDocument(uri, FileType.IMAGE) } }
    private var pendingImagePoint:android.graphics.PointF?=null
    private val insertSecondaryImage = registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(::splitNotes.isInitialized)splitNotes.imageResult(uri)}
    private val insertImage = registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let{insertCanvasImage(it)}}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_main)
        library=LibraryStore(this);clips=ClipboardStore(this);mergeService=MergePageService(library,this);printCoordinator=PrintCoordinator(this,library);nodes=try{library.ensureDefaults()}catch(error:Exception){showStorageFailure(error,true);return}
        drawer=findViewById(R.id.drawer_layout);tools=findViewById(R.id.tool_popup);overlay=findViewById(R.id.note_overlay)
        canvas=CanvasGlView(this);canvas.setHighlighterSnap(getSharedPreferences("pencil_settings",MODE_PRIVATE).getBoolean("highlighter_snap",false));findViewById<FrameLayout>(R.id.canvas_container).addView(canvas,1)
        workspace=findViewById(R.id.canvas_workspace)
        splitNotes=SplitNoteController(this,workspace,library,lifecycleScope,{currentNode?.id},::renderPdf,{canvas},{insertKind.name},{insertSecondaryImage.launch(arrayOf("image/*"))}){undo,redo->if(activeSplitSide==1)updateHistoryButtons(undo,redo)}
        workspace.addView(splitNotes.pane,LinearLayout.LayoutParams(0,-1,1f))
        workspace.onGestureStart={side->activeSplitSide=side;if(side==0){canvas.beginSplitGesture();updateHistoryButtons(canvas.canUndo(),canvas.canRedo())}else{splitNotes.beginGesture();updateHistoryButtons(splitNotes.canUndo(),splitNotes.canRedo())}}
        workspace.onGestureFinished={side->if(side==1)splitNotes.finishGesture()}
        workspace.onGestureCancelled={side->if(side==0){canvas.cancelSplitGesture();saveCurrent()}else splitNotes.cancelGesture()}
        canvas.setTransformListener{px,py,z->overlay.updateTransform(px,py,z);currentNode?.let{val id=currentDocumentId();val center=canvas.viewportCenterWorld();pendingViewSave?.let{canvas.removeCallbacks(it)};pendingViewSave=Runnable{getSharedPreferences("pencil_view_positions",MODE_PRIVATE).edit().putFloat("${id}:x",center.x).putFloat("${id}:y",center.y).putFloat("${id}:z",z).apply()};canvas.postDelayed(pendingViewSave!!,500)}}
        canvas.setSelectionChangedListener{path->overlay.setSelectionPath(path)}
        canvas.setTapListener{x,y->handleLinkTap(x,y)}
        teleportBack=Button(this).apply{text="返回";visibility=View.GONE;setOnClickListener{val location=teleportReturn?:return@setOnClickListener;pendingTeleportView=Triple(location.x,location.y,teleportReturnZoom);openNoteAt(location.node,location.page,location.x,location.y);teleportReturn=null;visibility=View.GONE}}
        (findViewById<SplitWorkspaceLayout>(R.id.canvas_workspace).parent as RelativeLayout).addView(teleportBack,RelativeLayout.LayoutParams(-2,-2).apply{addRule(RelativeLayout.ALIGN_PARENT_END);addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);setMargins(16,16,24,100)})
        teleports=TeleportController(this,canvas,library,{current},{x,y->canvas.screenToWorld(x,y)},{canvas.currentZoom()},{canvas.checkpoint()},{scheduleTeleportSave()}){node,doc,link,z->
            saveCurrent();val center=canvas.viewportCenterWorld();teleportReturn=currentNode?.let{ReturnLocation(it,pdfPageIndex,center.x,center.y)};teleportReturnZoom=canvas.currentZoom();pendingTeleportView=Triple(link.x+TeleportDrawing.bounds(link).width()/2f,link.y-10f,z)
            val page=Regex("__page_(\\d+)$").find(doc.noteId)?.groupValues?.get(1)?.toIntOrNull()?:0
            openNoteAt(node,page,link.x,link.y);teleportBack.visibility=View.VISIBLE
        }
        canvas.setObjectTouchListener{event->teleports.touch(event)||handleEmphasisTouch(event)||handleFoldedNoteTouch(event)}
        canvas.setHistoryListener{canUndo,canRedo->if(activeSplitSide==0)updateHistoryButtons(canUndo,canRedo)}
        canvas.setCorrectionFluidListener{}
        canvas.setContentChangedListener{strokes,texts->current=current.copy(strokes=strokes.toMutableList(),texts=texts.toMutableList());overlay.updateContent(current.texts,current.links,current.images,current.summaries,current.strokes)}
        canvas.setBackgroundHistory({current.backgroundHidden}){hidden->current.backgroundHidden=hidden;if(hidden){findViewById<ImageView>(R.id.page_background).visibility=View.GONE;overlay.setPageBackground(null);canvas.setPageBackground(null)}else currentNode?.let(::loadBackground)}
        canvas.setExtrasHistory({snapshotExtras()}){extras->restoreExtras(extras)}
        canvas.setSelectionMoveListener{dx,dy->moveSelectedExtras(dx,dy)}
        canvas.setPasteOffsetListener{clip,dx,dy->pasteExtras(clip,dx,dy)}
        directory=DirectoryController(this,findViewById(R.id.tree_container),library,{openNote(it)},{node,bookmark->openBookmark(node,bookmark)},{node,summary->showSummary(node,summary)},{deletedId->if(currentNode?.id==deletedId){val fallback=library.ensureDefaults().first{it.type==LibraryNodeType.NOTE};currentNode=null;openNote(fallback)}},{refreshDirectory()},activeDocument={current.takeIf{pageLoaded}},onBookmarkEdit={bookmark,name->editSidebarBookmark(bookmark,name)},onExportMerged={showMergedExport(it)},onChapterSelected={chapter->saveCurrent();quickAccess.showChapter(chapter,currentNode?.id)})
        findViewById<EditText>(R.id.directory_search).addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){directory.setSearchQuery(s?.toString().orEmpty(),currentNode?.id)};override fun afterTextChanged(s:Editable?){}})
        quickAccess=QuickAccessController(this,findViewById(R.id.quick_access_panel),library){node,defaultPage->openNoteAt(node,defaultPage?:savedFilePage(node),null,null)}
        bindTop();bindModes();bindDrawer();bindAi();bindCreation();bindPdfNavigation();bindDrawerMotion()
        val savedNodeId=getSharedPreferences("pencil_session",MODE_PRIVATE).getString("node_id",null);val first=nodes.firstOrNull{it.id==savedNodeId}?:nodes.firstOrNull{it.type==LibraryNodeType.NOTE}?:library.createNode("我的笔记",LibraryNodeType.NOTE);val savedPage=getSharedPreferences("pencil_session",MODE_PRIVATE).getInt("page",0);openNoteAt(first,savedPage,null,null);selectMode(R.id.btn_mode_drag);loadAiHistory()
    }

    private val hiddenUiViews=linkedMapOf<View,Int>()
    private var savedDrawerLockMode=androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_UNLOCKED
    private val keepUiHidden=android.view.ViewTreeObserver.OnGlobalLayoutListener{
        hiddenUiViews.keys.forEach{view->if(view.visibility==View.VISIBLE)view.visibility=View.INVISIBLE}
    }
    private fun hideCanvasUi(){
        if(hiddenUiViews.isNotEmpty())return
        val root=findViewById<View>(R.id.canvas_workspace).parent as android.view.ViewGroup
        val restore=findViewById<View>(R.id.btn_restore_ui)
        savedDrawerLockMode=drawer.getDrawerLockMode(GravityCompat.START)
        drawer.closeDrawer(GravityCompat.START,false)
        drawer.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED,GravityCompat.START)
        for(index in 0 until root.childCount){
            val view=root.getChildAt(index)
            if(view.id!=R.id.canvas_workspace&&view!==restore){hiddenUiViews[view]=view.visibility;if(view.visibility==View.VISIBLE)view.visibility=View.INVISIBLE}
        }
        root.viewTreeObserver.addOnGlobalLayoutListener(keepUiHidden)
        restore.visibility=View.VISIBLE;restore.bringToFront()
    }
    private fun restoreCanvasUi(){
        val root=findViewById<View>(R.id.canvas_workspace).parent as android.view.ViewGroup
        root.viewTreeObserver.removeOnGlobalLayoutListener(keepUiHidden)
        hiddenUiViews.forEach{(view,visibility)->view.visibility=visibility};hiddenUiViews.clear()
        findViewById<View>(R.id.btn_restore_ui).visibility=View.GONE
        drawer.setDrawerLockMode(savedDrawerLockMode,GravityCompat.START)
    }
    private fun bindTop(){
        findViewById<ImageButton>(R.id.btn_split).setOnClickListener{
            val center=canvas.viewportCenterWorld();val zoom=canvas.currentZoom()
            if(!splitNotes.setEnabled(!splitNotes.enabled))return@setOnClickListener
            if(!splitNotes.enabled){activeSplitSide=0;updateHistoryButtons(canvas.canUndo(),canvas.canRedo())}
            findViewById<ImageButton>(R.id.btn_split).apply{isSelected=splitNotes.enabled;alpha=if(isSelected)1f else .72f;contentDescription=if(isSelected)"退出分屏模式" else "进入分屏模式"}
            workspace.post{canvas.restoreView(center.x,center.y,zoom);positionMainPdfControls()}
        }
        findViewById<ImageButton>(R.id.btn_hide_ui).setOnClickListener{hideCanvasUi()}
        findViewById<Button>(R.id.btn_restore_ui).setOnClickListener{restoreCanvasUi()}
        findViewById<ImageButton>(R.id.btn_sidebar).setOnClickListener{drawer.openDrawer(GravityCompat.START)}
        findViewById<ImageButton>(R.id.btn_undo).setOnClickListener{if(activeSplitSide==1&&splitNotes.isReady())splitNotes.undo()else{canvas.undo();syncOverlayAfterHistory()}}
        findViewById<ImageButton>(R.id.btn_redo).setOnClickListener{if(activeSplitSide==1&&splitNotes.isReady())splitNotes.redo()else{canvas.redo();syncOverlayAfterHistory()}}
        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener{saveCurrent();startActivity(Intent(this,SettingsActivity::class.java))}
        findViewById<ImageButton>(R.id.btn_print).setOnClickListener{showExportSelection()}
        findViewById<ImageButton>(R.id.btn_clipboard).apply{
            setOnClickListener{if(activeSplitSide==1&&splitNotes.isReady())splitNotes.pasteLatest()else showClipboard()}
            setOnLongClickListener{
                if(activeSplitSide==1&&splitNotes.isReady()){splitNotes.pasteLatest();return@setOnLongClickListener true}
                val clip=clips.all().firstOrNull()
                if(clip==null){Toast.makeText(this@MainActivity,"剪贴板为空",Toast.LENGTH_SHORT).show()}
                else{selectMode(R.id.btn_mode_select);canvas.pasteCentered(clip);Toast.makeText(this@MainActivity,"已将最新剪贴板内容粘贴到中央，请拖动后松手确认",Toast.LENGTH_LONG).show()}
                true
            }
        }
        findViewById<ImageButton>(R.id.btn_merge_pages).setOnClickListener{val node=currentNode;val mergedFolder=library.loadNodes().firstOrNull{it.name=="合并页"&&it.type==LibraryNodeType.FOLDER};if(node?.parentId==mergedFolder?.id)AlertDialog.Builder(this).setTitle("合并页").setItems(arrayOf("同步修改回原笔记","创建新的合并页")){_,i->if(i==0)syncMergedPage()else showMerge()}.show()else showMerge()}
        findViewById<ImageButton>(R.id.btn_link_back).setOnClickListener{linkReturnLocation?.let{location->openNoteAt(location.node,location.page,location.x,location.y)};linkReturnLocation=null;findViewById<ImageButton>(R.id.btn_link_back).visibility=View.GONE}
        findViewById<ImageButton>(R.id.btn_draft_shortcut).setOnClickListener{toggleDraftPaper()}
        findViewById<Button>(R.id.btn_wrong_book_back).setOnClickListener{wrongBookReturnLocation?.let{openNoteAt(it.node,it.page,it.x,it.y)};wrongBookReturnLocation=null;findViewById<Button>(R.id.btn_wrong_book_back).visibility=View.GONE}
    }

    private fun bindCreation(){
        findViewById<Button>(R.id.new_note_action).setOnClickListener{askName("新建笔记"){nodeName->openNote(library.createNode(nodeName,LibraryNodeType.NOTE));refreshDirectory()}}
        findViewById<Button>(R.id.new_folder_action).setOnClickListener{askName("新建文件夹"){nodeName->library.createNode(nodeName,LibraryNodeType.FOLDER);refreshDirectory()}}
        findViewById<Button>(R.id.open_pdf_action).setOnClickListener{openPdf.launch(arrayOf("application/pdf"))}
        findViewById<Button>(R.id.open_image_action).setOnClickListener{openImage.launch(arrayOf("image/*"))}

    }

    private fun importDocument(uri:Uri,type:FileType){
        val displayName=uri.lastPathSegment?.substringAfterLast('/')?:if(type==FileType.PDF)"document.pdf" else "image"
        val attachment=runCatching{contentResolver.openInputStream(uri)!!.use{library.importAttachment(it,displayName)}}.getOrElse{Toast.makeText(this,"导入失败：${it.message}",Toast.LENGTH_LONG).show();return}
        askName(if(type==FileType.PDF)"PDF笔记名称" else "图片笔记名称"){name->val node=library.createNode(name,LibraryNodeType.NOTE,noteType=type,sourceUri=Uri.fromFile(attachment).toString());openNote(node);refreshDirectory()}
    }

    private fun savedFilePage(node:LibraryNode,fallback:Int=0):Int=if(node.noteType==FileType.PDF)getSharedPreferences("pencil_file_pages",MODE_PRIVATE).getInt(node.id,fallback)else 0
    private fun openNote(node:LibraryNode){openNoteAt(node,savedFilePage(node),null,null)}
    private fun openNoteAt(node:LibraryNode,page:Int,x:Float?,y:Float?){workspace.cancelGesture();if(!splitNotes.save())return;activeTeleportView=pendingTeleportView;saveCurrent();pendingRestore?.let{canvas.removeCallbacks(it)};pageLoaded=false;currentNode=node;splitNotes.onMainChanged();quickAccess.load(node);pdfPageIndex=if(node.noteType==FileType.PDF)PdfPageNavigation.groupStart(page.coerceAtLeast(0),getSharedPreferences("pencil_settings",MODE_PRIVATE).getBoolean("pdf_two_page_mode",false)) else 0;canvas.animate().alpha(0f).setDuration(90).withEndAction{loadCurrentPage();loadBackground(node);restoreViewCenter(x,y);canvas.animate().alpha(1f).setDuration(180).start()}.start();refreshDirectory();title="PENCIL · ${node.name}";findViewById<TextView>(R.id.current_note_title).apply{text=node.name;layoutParams=layoutParams.apply{width=kotlin.math.ceil(paint.measureText("中".repeat(16))).toInt()+paddingLeft+paddingRight}};configureChapterAiContext(node);findViewById<ImageButton>(R.id.btn_draft_shortcut).apply{isSelected=node.name==library.draftShortcutTarget()?.name;alpha=if(isSelected)1f else .72f};findViewById<Button>(R.id.btn_wrong_book_back).visibility=if(wrongBookReturnLocation!=null&&library.loadNodes().any{it.id==node.id&&it.parentId==library.wrongBookFolder()?.id&&it.name.startsWith("DAY")})View.VISIBLE else View.GONE}

    private fun currentDocumentId():String { val node=currentNode?:return "";return if(node.noteType==FileType.PDF)"${node.id}__page_$pdfPageIndex" else node.id }
    private fun loadCurrentPage(){if(currentNode?.noteType==FileType.PDF)pdfPageIndex=PdfPageNavigation.groupStart(pdfPageIndex,getSharedPreferences("pencil_settings",MODE_PRIVATE).getBoolean("pdf_two_page_mode",false));val id=currentDocumentId();current=try{library.loadNote(id)}catch(error:Exception){pageLoaded=false;canvas.visibility=View.INVISIBLE;overlay.visibility=View.INVISIBLE;showStorageFailure(error,false);return};canvas.visibility=View.VISIBLE;overlay.visibility=View.VISIBLE;canvas.setDocument(current.strokes,current.texts);overlay.setFoldedNotes(current.foldedNotes);overlay.setEmphasisStars(current.emphasisStars);pageLoaded=true;if(current.emphasisStars.isNotEmpty())Toast.makeText(this,"当前页面有 ${current.emphasisStars.size} 个强调星星",Toast.LENGTH_SHORT).show();overlay.setSelectionPath(emptyList());overlay.updateContent(current.texts,current.links,current.images,current.summaries,current.strokes)}
    private val noteWriter=java.util.concurrent.Executors.newSingleThreadExecutor()
    private var pendingTeleportSave:Runnable?=null
    private fun scheduleTeleportSave(){
        overlay.updateContent(current.texts,current.links,current.images,current.summaries,current.strokes)
        pendingTeleportSave?.let{canvas.removeCallbacks(it)}
        pendingTeleportSave=Runnable{pendingTeleportSave=null;saveCurrent(true)}.also{canvas.postDelayed(it,300)}
    }
    private fun saveCurrent(backgroundWrite:Boolean=false){if(getSharedPreferences("pencil_session",MODE_PRIVATE).getBoolean("library_restored",false))return;pendingTeleportSave?.let{canvas.removeCallbacks(it)};pendingTeleportSave=null;val active=currentNode?:return;if(!pageLoaded)return;pendingViewSave?.let{canvas.removeCallbacks(it);it.run()};pendingViewSave=null;val center=canvas.viewportCenterWorld();val viewId=currentDocumentId();getSharedPreferences("pencil_view_positions",MODE_PRIVATE).edit().putFloat("${viewId}:x",center.x).putFloat("${viewId}:y",center.y).putFloat("${viewId}:z",canvas.currentZoom()).apply();getSharedPreferences("pencil_file_pages",MODE_PRIVATE).edit().putInt(active.id,pdfPageIndex).apply();quickAccess.rememberPage(active,pdfPageIndex);val id=currentDocumentId();current=NoteDocument(noteId=id,strokes=canvas.getStrokes().toMutableList(),texts=canvas.getTextBoxes().toMutableList(),bookmarks=current.bookmarks,links=current.links,summaries=current.summaries,images=current.images,backgroundHidden=current.backgroundHidden,foldedNotes=current.foldedNotes,emphasisStars=current.emphasisStars);val snapshot=current.copy(bookmarks=current.bookmarks.toMutableList(),links=current.links.toMutableList(),summaries=current.summaries.toMutableList(),images=current.images.toMutableList(),foldedNotes=current.foldedNotes.map{it.copy(strokes=it.strokes.toMutableList(),texts=it.texts.toMutableList(),images=it.images.toMutableList(),links=it.links.toMutableList(),summaries=it.summaries.toMutableList())}.toMutableList(),emphasisStars=current.emphasisStars.toMutableList());val write=noteWriter.submit{try{library.saveNote(snapshot)}catch(error:Exception){runOnUiThread{if(!isDestroyed)showStorageFailure(error,false)}}};if(!backgroundWrite)write.get();overlay.updateContent(current.texts,current.links,current.images,current.summaries)}
    private fun editSidebarBookmark(bookmark:Bookmark,name:String?){
        if(current.bookmarks.any{it.id==bookmark.id}){
            val index=current.bookmarks.indexOfFirst{it.id==bookmark.id}
            if(name==null)current.bookmarks.removeAt(index) else current.bookmarks[index]=bookmark.copy(name=name)
            saveCurrent()
        }else{
            val doc=library.loadNote(bookmark.fileId)
            val index=doc.bookmarks.indexOfFirst{it.id==bookmark.id}
            if(index>=0){if(name==null)doc.bookmarks.removeAt(index)else doc.bookmarks[index]=bookmark.copy(name=name);library.saveNote(doc)}
        }
        refreshDirectory()
    }
    private fun refreshDirectory(){nodes=library.ensureDefaults();directory.render(currentNode?.id);if(::splitNotes.isInitialized)splitNotes.onMainChanged()}
    private fun restoreViewCenter(x:Float?,y:Float?){val explicit=activeTeleportView;pendingTeleportView=null;val prefs=getSharedPreferences("pencil_view_positions",MODE_PRIVATE);val id=currentDocumentId();val savedX=if(prefs.contains("${id}:x"))prefs.getFloat("${id}:x",0f)else null;val savedY=if(prefs.contains("${id}:y"))prefs.getFloat("${id}:y",0f)else null;val savedZoom=if(prefs.contains("${id}:z"))prefs.getFloat("${id}:z",1f)else null;val targetX=explicit?.first?:x?:savedX;val targetY=explicit?.second?:y?:savedY;if(targetX!=null&&targetY!=null){pendingRestore=Runnable{if(currentDocumentId()==id)canvas.restoreView(explicit?.first?:targetX,explicit?.second?:targetY,explicit?.third?:savedZoom?:1f)};canvas.postDelayed(pendingRestore!!,140)}}
    private fun canReturnFromChapterPdf():Boolean {
        val node=currentNode?:return false
        val chapter=chapterReturnLocation?.node?:return false
        return node.noteType==FileType.PDF&&!node.chapterMerge&&node.id==chapter.chapterSourceId
    }
    private fun returnFromChapterPdf(){
        if(!canReturnFromChapterPdf())return
        val location=chapterReturnLocation?:return
        val chapter=library.loadNodes().firstOrNull{it.id==location.node.id}
        if(chapter==null){
            chapterReturnLocation=null
            Toast.makeText(this,"章节笔记已不存在",Toast.LENGTH_SHORT).show()
            return
        }
        chapterReturnLocation=null
        openNoteAt(chapter,location.page,location.x,location.y)
    }
    private fun openChapterSourcePdf(){val chapter=currentNode?.takeIf{it.chapterSourceId!=null&&!it.chapterMerge}?:return;val source=library.loadNodes().firstOrNull{it.id==chapter.chapterSourceId}?:return;val center=canvas.viewportCenterWorld();chapterReturnLocation=ReturnLocation(chapter,0,center.x,center.y);openNoteAt(source,chapter.chapterStartPage?:0,null,null)}
    private fun openChapterMerge(){val chapter=currentNode?.takeIf{it.chapterSourceId!=null&&!it.chapterMerge}?:return;val source=library.loadNodes().firstOrNull{it.id==chapter.chapterSourceId}?:return;val start=chapter.chapterStartPage?:return;val end=chapter.chapterEndPage?:return;val center=canvas.viewportCenterWorld();chapterReturnLocation=ReturnLocation(chapter,0,center.x,center.y);val existing=library.findChapterMerge(chapter.id);if(existing!=null){openNote(existing);return};val candidates=(start..end).map{page->MergeCandidate("${source.name} · 第${page+1}页","${source.id}__page_$page",library.loadNote("${source.id}__page_$page"))};val merged=library.createChapterMerge(chapter);openNote(mergeService.create(candidates,merged));refreshDirectory()}
    private fun backToChapterNote(){val merge=currentNode?.takeIf{it.chapterMerge}?:return;val chapter=chapterReturnLocation?.node?:library.loadNodes().firstOrNull{it.id==merge.chapterSourceId}?:return;val mergeCenter=canvas.viewportCenterWorld();val chapterCenter=chapterReturnLocation?.let{android.graphics.PointF(it.x,it.y)}?:android.graphics.PointF(mergeCenter.x,mergeCenter.y);chapterReturnLocation=ReturnLocation(chapter,0,mergeCenter.x,mergeCenter.y);openNoteAt(chapter,0,chapterCenter.x,chapterCenter.y)}

    private var backgroundRequest = 0

    private fun loadBackground(node:LibraryNode){loadBackground(node,false)}
    private fun loadBackground(node:LibraryNode,fitPage:Boolean){
        if(fitPage){activeTeleportView=null;pendingTeleportView=null}
        val request=++backgroundRequest
        val twoPage=getSharedPreferences("pencil_settings",MODE_PRIVATE).getBoolean("pdf_two_page_mode",false)
        val requestedPage=PdfPageNavigation.groupStart(pdfPageIndex,twoPage)
        if(fitPage){pendingRestore?.let{canvas.removeCallbacks(it)};pendingRestore=null}
        val image=findViewById<ImageView>(R.id.page_background);val pdfControls=listOf<View>(findViewById(R.id.pdf_previous),findViewById(R.id.pdf_page_indicator),findViewById(R.id.pdf_next));image.setImageDrawable(null);image.visibility=View.GONE;pdfControls.forEach{it.visibility=View.GONE};currentBackgroundBitmap?.takeIf{!it.isRecycled}?.recycle();currentBackgroundBitmap=null;overlay.setPageBackground(null);canvas.setPageBackground(null)
        if(current.backgroundHidden)return
        val uri=node.sourceUri?.let(Uri::parse)?:return
        if(node.noteType==FileType.IMAGE){currentBackgroundBitmap=runCatching{contentResolver.openInputStream(uri)?.use{android.graphics.BitmapFactory.decodeStream(it)}}.getOrNull();overlay.setPageBackground(currentBackgroundBitmap);canvas.setPageBackground(currentBackgroundBitmap)}
        if(node.noteType==FileType.PDF){pdfControls.forEach{it.visibility=View.VISIBLE};lifecycleScope.launch{val rendered=withContext(Dispatchers.IO){val first=renderPdf(uri,requestedPage);if(!twoPage||first==null)first else {val second=if(requestedPage+1<first.second)renderPdf(uri,requestedPage+1)?.first else null;val spread=Bitmap.createBitmap(first.first.width+(second?.width?:0),maxOf(first.first.height,second?.height?:0),Bitmap.Config.ARGB_8888);spread.eraseColor(Color.WHITE);Canvas(spread).apply{drawBitmap(first.first,0f,0f,Paint());second?.let{drawBitmap(it,first.first.width.toFloat(),0f,Paint());it.recycle()}};first.first.recycle();Pair(spread,first.second)}};if(rendered!=null&&request==backgroundRequest&&currentNode?.id==node.id&&pdfPageIndex==requestedPage){pdfPageCount=rendered.second;currentBackgroundBitmap=rendered.first;overlay.setPageBackground(rendered.first);canvas.setPageBackground(rendered.first,forceFit=fitPage);if(!fitPage)restoreViewCenter(null,null);configureChapterAiContext(node);updatePdfNavigation()}else rendered?.first?.recycle()}}
    }

    private fun renderPdf(uri:Uri,index:Int):Pair<Bitmap,Int>?=runCatching{contentResolver.openFileDescriptor(uri,"r")!!.use{fd->PdfRenderer(fd).use{renderer->val safe=index.coerceIn(0,(renderer.pageCount-1).coerceAtLeast(0));renderer.openPage(safe).use{page->val scale=2.5f;val width=(page.width*scale).toInt().coerceAtMost(4096);val height=(page.height*scale).toInt().coerceAtMost(4096);Pair(Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also{it.eraseColor(Color.WHITE);page.render(it,null,android.graphics.Matrix().apply{setScale(scale,scale)},PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)},renderer.pageCount)}}}}.getOrNull()
    private fun positionMainPdfControls(){
        val next=findViewById<View>(R.id.pdf_next)
        (next.layoutParams as RelativeLayout.LayoutParams).apply{rightMargin=(12*resources.displayMetrics.density).toInt()+if(splitNotes.enabled)workspace.width/2 else 0;next.layoutParams=this}
    }
    private fun bindPdfNavigation(){workspace.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->positionMainPdfControls()};findViewById<ImageButton>(R.id.pdf_previous).setOnClickListener{changePdfPage(-1)};findViewById<ImageButton>(R.id.pdf_next).setOnClickListener{changePdfPage(1)};findViewById<TextView>(R.id.pdf_page_indicator).setOnClickListener{showPdfPageJump()}}
    private fun showPdfPageJump(){if(currentNode?.noteType!=FileType.PDF)return;val form=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val input=EditText(this).apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER;hint="输入页码（从 1 开始）"};val offset=EditText(this).apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED;hint="页码偏移（默认 0）";setText(getSharedPreferences("pencil_page_offsets",MODE_PRIVATE).getInt(currentNode!!.id,0).takeIf{it!=0}?.toString().orEmpty())};form.addView(input);form.addView(offset);AlertDialog.Builder(this).setTitle("跳转到页码").setView(form).setNegativeButton("取消",null).setPositiveButton("跳转"){_,_->val raw=input.text.toString().toIntOrNull();val shift=offset.text.toString().toIntOrNull()?:0;val two=getSharedPreferences("pencil_settings",MODE_PRIVATE).getBoolean("pdf_two_page_mode",false);val page=raw?.let{PdfPageNavigation.jumpTarget(it,shift,pdfPageCount,two)};if(page!=null){getSharedPreferences("pencil_page_offsets",MODE_PRIVATE).edit().putInt(currentNode!!.id,shift).apply();saveCurrent();pdfPageIndex=page;loadCurrentPage();currentNode?.let{loadBackground(it,true)}}else Toast.makeText(this,"页码或偏移无效",Toast.LENGTH_SHORT).show()}.show()}
    private fun changePdfPage(delta:Int){if(currentNode?.noteType!=FileType.PDF)return;val step=if(getSharedPreferences("pencil_settings",MODE_PRIVATE).getBoolean("pdf_two_page_mode",false))2 else 1;val next=PdfPageNavigation.groupStart((pdfPageIndex+delta*step).coerceIn(0,(pdfPageCount-1).coerceAtLeast(0)),step==2);if(next==pdfPageIndex)return;saveCurrent();pdfPageIndex=next;loadCurrentPage();currentNode?.let{loadBackground(it,true)}}
    private fun updatePdfNavigation(){val two=getSharedPreferences("pencil_settings",MODE_PRIVATE).getBoolean("pdf_two_page_mode",false);findViewById<TextView>(R.id.pdf_page_indicator).text=if(two)"${pdfPageIndex+1}-${(pdfPageIndex+2).coerceAtMost(pdfPageCount)} / $pdfPageCount" else "${pdfPageIndex+1} / $pdfPageCount";findViewById<ImageButton>(R.id.pdf_previous).isEnabled=pdfPageIndex>0;findViewById<ImageButton>(R.id.pdf_next).isEnabled=pdfPageIndex<pdfPageCount-1}

    private fun bindModes(){
        listOf(R.id.btn_mode_drag,R.id.btn_mode_write,R.id.btn_mode_insert,R.id.btn_mode_select,R.id.btn_mode_erase).forEach{id->findViewById<ImageButton>(id).setOnClickListener{selectMode(id)}}
        findViewById<ImageButton>(R.id.btn_mode_insert).setOnClickListener{selectMode(R.id.btn_mode_insert);showInsertTools()}
        findViewById<ImageButton>(R.id.btn_mode_select).setOnClickListener{selectMode(R.id.btn_mode_select);showSelectTools()}
        findViewById<ImageButton>(R.id.btn_tool_pen).setOnClickListener{canvas.setWriteTool(Tool.PEN);markSelectedTool(R.id.btn_tool_pen);chooseColor(arrayOf(Color.BLACK,Color.RED,Color.BLUE));showSizeSlider(false)}
        findViewById<ImageButton>(R.id.btn_tool_pencil).setOnClickListener{canvas.setWriteTool(Tool.PENCIL);markSelectedTool(R.id.btn_tool_pencil);hideColorChoices();showSizeSlider(false)}
        findViewById<ImageButton>(R.id.btn_tool_highlighter).setOnClickListener{canvas.setWriteTool(Tool.HIGHLIGHTER);markSelectedTool(R.id.btn_tool_highlighter);chooseColor(arrayOf(Color.YELLOW,Color.GREEN,Color.RED));showSizeSlider(false)}
        findViewById<ImageButton>(R.id.btn_tool_text).setOnClickListener{insertKind=InsertKind.TEXT;canvas.setMode(CanvasGlView.Mode.INSERT);markSelectedTool(R.id.btn_tool_text);showSizeSlider(true)}
        findViewById<ImageButton>(R.id.btn_insert_image).setOnClickListener{insertKind=InsertKind.IMAGE;Toast.makeText(this,"请在画布选择图片位置",Toast.LENGTH_SHORT).show()}
        findViewById<ImageButton>(R.id.btn_insert_bookmark).setOnClickListener{insertKind=InsertKind.BOOKMARK;Toast.makeText(this,"请在画布选择书签位置",Toast.LENGTH_SHORT).show()}
        findViewById<ImageButton>(R.id.btn_insert_link).setOnClickListener{insertKind=InsertKind.LINK;Toast.makeText(this,"请在画布选择链接位置",Toast.LENGTH_SHORT).show()}
        findViewById<ImageButton>(R.id.btn_insert_teleport).apply{setImageDrawable(teleportEmojiIcon())}.setOnClickListener{insertKind=InsertKind.TELEPORT;canvas.setMode(CanvasGlView.Mode.INSERT);Toast.makeText(this,"请在画布选择传送点位置",Toast.LENGTH_SHORT).show()}
        findViewById<ImageButton>(R.id.btn_insert_emphasis).setOnClickListener{insertKind=InsertKind.EMPHASIS;canvas.setMode(CanvasGlView.Mode.INSERT);Toast.makeText(this,"请在画布选择强调星星的位置（每页最多两个）",Toast.LENGTH_SHORT).show()}
        findViewById<ImageButton>(R.id.btn_select_rect).setOnClickListener{selectMode(R.id.btn_mode_select);canvas.setSelectionStyle(CanvasGlView.SelectionStyle.RECTANGLE);updateSelectionToolButtons()}
        findViewById<ImageButton>(R.id.btn_select_freeform).setOnClickListener{selectMode(R.id.btn_mode_select);canvas.setSelectionStyle(CanvasGlView.SelectionStyle.FREEFORM);updateSelectionToolButtons()}
        findViewById<ImageButton>(R.id.btn_tool_eraser).setOnClickListener{canvas.setEraserTool(EraserTool.ERASER);markSelectedTool(R.id.btn_tool_eraser);hideColorChoices();showEraserSizeSlider()}
        findViewById<ImageButton>(R.id.btn_tool_sand_eraser).setOnClickListener{canvas.setEraserTool(EraserTool.SAND_ERASER);markSelectedTool(R.id.btn_tool_sand_eraser);hideColorChoices();showEraserSizeSlider()}
        findViewById<ImageButton>(R.id.btn_tool_correction_fluid).setOnClickListener{canvas.setEraserTool(EraserTool.CORRECTION_FLUID);markSelectedTool(R.id.btn_tool_correction_fluid);hideColorChoices();showEraserSizeSlider()}
        canvas.setInsertRequestedListener{x,y->showInsert(x,y)}
        canvas.setSelectionFinishedListener{showSelectionActions()}
        findViewById<SeekBar>(R.id.size_slider).setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(seek:SeekBar,value:Int,fromUser:Boolean){if(sizeSliderEraserMode){val normalized=value/395f;val size=.5f+normalized*normalized*79.5f;canvas.setEraserSize(size);findViewById<TextView>(R.id.size_value).text=String.format("%.1f",size)}else if(sizeSliderTextMode){val size=(8f+value/395f*64f);canvas.setTextFontSize(size);findViewById<TextView>(R.id.size_value).text=size.toInt().toString()}else{val normalized=value/395f;val size=.5f+normalized*normalized*(if(canvas.currentTool()==Tool.HIGHLIGHTER)479.5f else 79.5f);canvas.setStrokeWidth(size);if(canvas.currentTool()==Tool.HIGHLIGHTER)highlighterWidth=size;findViewById<TextView>(R.id.size_value).text=String.format("%.1f",size)}};override fun onStartTrackingTouch(seek:SeekBar){};override fun onStopTrackingTouch(seek:SeekBar){}})
        canvas.setTextRequestedListener{_,marker->if(canvas.selectedRegion()!=null){if(marker<0)showMoveConfirmation()else showSelectionActions()}}
    }

    private fun resetSecondaryTools(){listOf(R.id.btn_tool_pen,R.id.btn_tool_pencil,R.id.btn_tool_highlighter,R.id.btn_tool_text,R.id.btn_tool_eraser,R.id.btn_tool_sand_eraser,R.id.btn_tool_correction_fluid,R.id.btn_select_rect,R.id.btn_select_freeform,R.id.btn_insert_image,R.id.btn_insert_bookmark,R.id.btn_insert_link,R.id.btn_insert_emphasis,R.id.btn_insert_teleport,R.id.btn_color_1,R.id.btn_color_2,R.id.btn_color_3,R.id.btn_color_4,R.id.btn_color_custom).forEach{findViewById<View>(it).visibility=View.GONE};findViewById<SeekBar>(R.id.size_slider).visibility=View.GONE;findViewById<TextView>(R.id.size_value).visibility=View.GONE}
    private fun showSelectTools(){resetSecondaryTools();showToolSet(false,false);listOf(R.id.btn_tool_pen,R.id.btn_tool_pencil,R.id.btn_tool_highlighter,R.id.btn_tool_text,R.id.btn_tool_eraser,R.id.btn_tool_sand_eraser,R.id.btn_tool_correction_fluid,R.id.btn_insert_image,R.id.btn_insert_bookmark,R.id.btn_insert_link,R.id.btn_insert_emphasis,R.id.btn_insert_teleport).forEach{findViewById<View>(it).visibility=View.GONE};findViewById<View>(R.id.btn_select_rect).visibility=View.VISIBLE;findViewById<View>(R.id.btn_select_freeform).visibility=View.VISIBLE;updateSelectionToolButtons()}
    private fun updateSelectionToolButtons(){findViewById<View>(R.id.btn_select_rect).isSelected=canvas.currentSelectionStyle()==CanvasGlView.SelectionStyle.RECTANGLE;findViewById<View>(R.id.btn_select_freeform).isSelected=canvas.currentSelectionStyle()==CanvasGlView.SelectionStyle.FREEFORM}
    private fun showInsertTools(){resetSecondaryTools();showToolSet(false,false);listOf(R.id.btn_tool_pen,R.id.btn_tool_pencil,R.id.btn_tool_highlighter,R.id.btn_tool_text,R.id.btn_tool_eraser,R.id.btn_tool_sand_eraser,R.id.btn_tool_correction_fluid).forEach{findViewById<View>(it).visibility=View.GONE};listOf(R.id.btn_insert_image,R.id.btn_insert_bookmark,R.id.btn_insert_link,R.id.btn_insert_emphasis,R.id.btn_insert_teleport).forEach{findViewById<View>(it).visibility=View.VISIBLE};findViewById<SeekBar>(R.id.size_slider).visibility=View.GONE;findViewById<TextView>(R.id.size_value).visibility=View.GONE}
    private fun teleportEmojiIcon():android.graphics.drawable.Drawable {
        val size=(32*resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{textSize=size*.85f;textAlign=Paint.Align.CENTER;typeface=android.graphics.Typeface.DEFAULT}
        val metrics=paint.fontMetrics
        Canvas(bitmap).drawText("🌀",size/2f,size/2f-(metrics.ascent+metrics.descent)/2f,paint)
        return android.graphics.drawable.BitmapDrawable(resources,bitmap)
    }
    private fun showInsertModeMenu(){selectMode(R.id.btn_mode_insert);val panel=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER;setPadding(12,12,12,12)};lateinit var dialog:AlertDialog;fun add(icon:Int,label:String,kind:InsertKind){val button=ImageButton(this).apply{if(kind==InsertKind.TELEPORT)setImageDrawable(teleportEmojiIcon())else setImageResource(icon);contentDescription=label;background=resources.getDrawable(com.vibetools.pencil.R.drawable.tool_button_background,null);setPadding(16,16,16,16);setOnClickListener{insertKind=kind;dialog.dismiss();Toast.makeText(this@MainActivity,"请在画布选择插入位置",Toast.LENGTH_SHORT).show()}};panel.addView(button,LinearLayout.LayoutParams(76,76).apply{setMargins(6,6,6,6)})};add(R.drawable.insert_image,"图片",InsertKind.IMAGE);add(R.drawable.insert_bookmark,"书签",InsertKind.BOOKMARK);add(R.drawable.insert_link,"超链接",InsertKind.LINK);add(R.drawable.insert_emphasis,"插入强调",InsertKind.EMPHASIS);add(R.drawable.insert_link,"传送点",InsertKind.TELEPORT);dialog=AlertDialog.Builder(this).setTitle("插入内容").setView(panel).create();dialog.show()}
    private fun showSizeSlider(text:Boolean){sizeSliderEraserMode=false;sizeSliderTextMode=text;val slider=findViewById<SeekBar>(R.id.size_slider);slider.visibility=View.VISIBLE;val value=findViewById<TextView>(R.id.size_value);val current=if(text)canvas.currentTextFontSize() else canvas.currentStrokeWidth();val progress=if(text)(((current-8f)/64f)*395f).toInt() else (kotlin.math.sqrt(((current-.5f)/(if(canvas.currentTool()==Tool.HIGHLIGHTER)479.5f else 79.5f)).coerceIn(0f,1f))*395f).toInt();slider.progress=progress.coerceIn(0,395);value.text=if(text)current.toInt().toString() else String.format("%.1f",current)}
    private fun markSelectedTool(id:Int){listOf(R.id.btn_tool_pen,R.id.btn_tool_pencil,R.id.btn_tool_highlighter,R.id.btn_tool_text,R.id.btn_tool_eraser,R.id.btn_tool_sand_eraser,R.id.btn_tool_correction_fluid).forEach{findViewById<View>(it).isSelected=it==id}}
    private fun selectMode(id:Int){val mode=when(id){R.id.btn_mode_write->CanvasGlView.Mode.WRITE;R.id.btn_mode_insert->CanvasGlView.Mode.INSERT;R.id.btn_mode_select->CanvasGlView.Mode.SELECT;R.id.btn_mode_erase->CanvasGlView.Mode.ERASE;else->CanvasGlView.Mode.DRAG};canvas.setMode(mode);if(mode==CanvasGlView.Mode.WRITE){canvas.restoreLastWriteTool();markSelectedTool(when(canvas.currentTool()){Tool.PEN->R.id.btn_tool_pen;Tool.PENCIL->R.id.btn_tool_pencil;Tool.HIGHLIGHTER->R.id.btn_tool_highlighter})};if(mode==CanvasGlView.Mode.ERASE){canvas.restoreLastEraserTool();markSelectedTool(when(canvas.currentEraserTool()){EraserTool.ERASER->R.id.btn_tool_eraser;EraserTool.SAND_ERASER->R.id.btn_tool_sand_eraser;EraserTool.CORRECTION_FLUID->R.id.btn_tool_correction_fluid})};listOf(R.id.btn_mode_drag,R.id.btn_mode_write,R.id.btn_mode_insert,R.id.btn_mode_select,R.id.btn_mode_erase).forEach{buttonId->findViewById<ImageButton>(buttonId).apply{isSelected=buttonId==id;animate().alpha(if(buttonId==id)1f else .55f).scaleX(if(buttonId==id)1.08f else 1f).scaleY(if(buttonId==id)1.08f else 1f).setDuration(140).start()}};when(mode){CanvasGlView.Mode.WRITE->{showToolSet(true,false);if(canvas.currentTool()==Tool.PEN)chooseColor(arrayOf(Color.BLACK,Color.RED,Color.BLUE))else if(canvas.currentTool()==Tool.HIGHLIGHTER)chooseColor(arrayOf(Color.YELLOW,Color.GREEN,Color.RED));showSizeSlider(false)};CanvasGlView.Mode.ERASE->{showToolSet(false,true);showEraserSizeSlider()};CanvasGlView.Mode.INSERT->showInsertTools();CanvasGlView.Mode.SELECT->showSelectTools();else->{findViewById<SeekBar>(R.id.size_slider).visibility=View.GONE;findViewById<TextView>(R.id.size_value).visibility=View.GONE;hideTools()}}}
    private fun showToolSet(write:Boolean,erase:Boolean){resetSecondaryTools();if(tools.visibility!=View.VISIBLE){tools.alpha=0f;tools.translationY=20f;tools.visibility=View.VISIBLE;tools.animate().alpha(1f).translationY(0f).setDuration(180).start()};listOf(R.id.btn_tool_pen,R.id.btn_tool_pencil,R.id.btn_tool_highlighter,R.id.btn_tool_text).forEach{findViewById<View>(it).visibility=if(write)View.VISIBLE else View.GONE};listOf(R.id.btn_tool_eraser,R.id.btn_tool_sand_eraser,R.id.btn_tool_correction_fluid).forEach{findViewById<View>(it).visibility=if(erase)View.VISIBLE else View.GONE}}
    private fun hideTools(){if(tools.visibility==View.VISIBLE)tools.animate().alpha(0f).translationY(16f).setDuration(120).withEndAction{tools.visibility=View.GONE;tools.translationY=0f}.start()}
    private var sizeSliderEraserMode=false
    private var visiblePresetColors:Array<Int> = emptyArray()
    private fun updateSelectedColor(){
        val selected=canvas.currentColor()
        arrayOf(R.id.btn_color_1,R.id.btn_color_2,R.id.btn_color_3,R.id.btn_color_4).forEachIndexed{i,id->
            findViewById<ImageButton>(id).apply{
                isSelected=visiblePresetColors.getOrNull(i)==selected
                imageTintList=null
                if(isSelected)setImageResource(R.drawable.color_selected_check)else setImageDrawable(null)
            }
        }
        findViewById<ImageButton>(R.id.btn_color_custom).apply{
            isSelected=selected !in visiblePresetColors
            imageTintList=null
            if(isSelected){setBackgroundColor(selected);setImageResource(R.drawable.color_selected_check)}
            else{setBackgroundResource(R.drawable.tool_button_background);setImageResource(R.drawable.color_palette)}
        }
    }
    private fun chooseColor(presets:Array<Int>){val values=presets+Color.rgb(153,153,153);visiblePresetColors=values.copyOf();val ids=arrayOf(R.id.btn_color_1,R.id.btn_color_2,R.id.btn_color_3,R.id.btn_color_4);ids.forEachIndexed{i,id->val button=findViewById<ImageButton>(id);button.setBackgroundColor(values[i]);button.visibility=View.VISIBLE;button.setOnClickListener{canvas.setColor(values[i]);updateSelectedColor()}};findViewById<ImageButton>(R.id.btn_color_custom).apply{visibility=View.VISIBLE;setOnClickListener{showCustomColor()}};updateSelectedColor()}
    private fun showEraserSizeSlider(){sizeSliderTextMode=false;sizeSliderEraserMode=true;findViewById<SeekBar>(R.id.size_slider).apply{visibility=View.VISIBLE;progress=(kotlin.math.sqrt(((canvas.currentEraserSize()-.5f)/79.5f).coerceIn(0f,1f))*395f).toInt().coerceIn(0,395)};findViewById<TextView>(R.id.size_value).apply{visibility=View.VISIBLE;text=String.format("%.1f",canvas.currentEraserSize())}}
    private fun showCustomColor(){val input=EditText(this).apply{hint="#RRGGBB"};AlertDialog.Builder(this).setTitle("自定义颜色").setView(input).setPositiveButton("确定"){_,_->runCatching{canvas.setColor(Color.parseColor(input.text.toString()));updateSelectedColor()}}.setNegativeButton("取消",null).show()}

    private fun showInsert(x:Float,y:Float){
        val clicked=canvas.screenToWorld(x,y)
        val world=if(insertKind==InsertKind.BOOKMARK)canvas.viewportCenterWorld() else clicked
        if(insertKind==InsertKind.TELEPORT){teleports.insert(world);return}
        if(insertKind==InsertKind.EMPHASIS){insertEmphasisStar(world);return}
        if(insertKind==InsertKind.IMAGE){pendingImagePoint=world;insertImage.launch(arrayOf("image/*"));return}
        if(insertKind==InsertKind.LINK){showLinkInsert(world);return}
        if(insertKind==InsertKind.BOOKMARK){
            val input=EditText(this).apply{hint="书签名称"}
            AlertDialog.Builder(this).setTitle("插入书签").setView(input).setNegativeButton("取消",null).setPositiveButton("下一步"){_,_->
                val value=input.text.toString().trim();if(value.isBlank())return@setPositiveButton
                AlertDialog.Builder(this).setTitle("书签类型").setItems(arrayOf("永久书签","临时书签（本次运行有效）")){_,kind->
                    current.bookmarks+=Bookmark(UUID.randomUUID().toString(),value,current.noteId,"page-1",world.x,world.y,temporary=kind==1)
                    if(kind==0)saveCurrent() else {overlay.updateContent(current.texts,current.links,current.images,current.summaries,current.strokes);refreshDirectory()}
                    Toast.makeText(this,if(kind==0)"已保存永久书签" else "已创建临时书签",Toast.LENGTH_SHORT).show()
                }.show()
            }.show()
            return
        }
        val input=EditText(this).apply{hint="输入文本，可手动换行";minLines=4;gravity=android.view.Gravity.TOP;inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE;typeface=android.graphics.Typeface.DEFAULT_BOLD}
        AlertDialog.Builder(this).setTitle("插入文本").setView(input).setNegativeButton("取消",null).setPositiveButton("保存"){_,_->val value=input.text.toString().trim();if(value.isBlank())return@setPositiveButton;canvas.addText(TextBox(UUID.randomUUID().toString(),value,world.x,world.y,18f));saveCurrent();Toast.makeText(this,"已保存",Toast.LENGTH_SHORT).show()}.show()
    }
    private fun insertCanvasImage(uri:Uri){val point=pendingImagePoint?:canvas.viewportCenterWorld();pendingImagePoint=null;val name=uri.lastPathSegment?.substringAfterLast('/')?:"canvas-image";val file=runCatching{contentResolver.openInputStream(uri)!!.use{library.importAttachment(it,name)}}.getOrElse{Toast.makeText(this,"图片导入失败：${it.message}",Toast.LENGTH_LONG).show();return};val bounds=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true};android.graphics.BitmapFactory.decodeFile(file.path,bounds);val ratio=if(bounds.outWidth>0&&bounds.outHeight>0)bounds.outWidth.toFloat()/bounds.outHeight else 1.4f;val width=420f;current.images+=CanvasImage(path=file.path,x=point.x,y=point.y,width=width,height=width/ratio);saveCurrent();overlay.updateContent(current.texts,current.links,current.images,current.summaries)}
    private fun allStoredLinks():List<StoredLink> = library.loadNodes().filter{it.type==LibraryNodeType.NOTE}.flatMap{node->if(node.noteType==FileType.PDF)library.pageDocuments(node.id)else listOf(library.loadNote(node.id))}.flatMap{it.links}
    private fun showLinkInsert(world:android.graphics.PointF){val types=arrayOf("应用内文件/书签","编号标签","互联网链接");AlertDialog.Builder(this).setTitle("选择超链接类型").setItems(types){_,type->val input=EditText(this).apply{hint=when(type){0->"输入文件名或书签名";1->"编号自动使用创建时间（yyMMddHHmmss）";else->"https://..."};if(type==1){isEnabled=false;setText(HyperlinkNumber.create(System.currentTimeMillis(),current.links.filter{it.kind=="number"}.map{it.value}.toSet()))}};AlertDialog.Builder(this).setTitle(types[type]).setView(input).setNegativeButton("取消",null).setPositiveButton("保存"){_,_->val value=if(type==1)HyperlinkNumber.create(System.currentTimeMillis(),allStoredLinks().filter{it.kind=="number"}.map{it.value}.toSet())else input.text.toString().trim();if(value.isBlank())return@setPositiveButton;val valid=when(type){1->value.matches(Regex("\\d{12}"));2->android.util.Patterns.WEB_URL.matcher(value).matches();else->true};if(!valid){Toast.makeText(this,"输入格式不正确",Toast.LENGTH_LONG).show();return@setPositiveButton};val kind=when(type){0->"internal";1->"number";else->"external"};current.links+=StoredLink(noteId=current.noteId,x=world.x,y=world.y,kind=kind,value=value,label=value);saveCurrent();overlay.updateContent(current.texts,current.links,current.images,current.summaries,current.strokes)}.show()}.show()}

    private fun handleLinkTap(x:Float,y:Float){if(current.links.any{it.kind.startsWith("teleport_")&&TeleportDrawing.bounds(it).contains(x,y)})return;current.summaries.firstOrNull{kotlin.math.abs(it.x-x)<48&&kotlin.math.abs(it.y-y)<48}?.let{summary->currentNode?.let{node->showSummary(node,summary)};return};val link=current.links.minByOrNull{l->kotlin.math.abs(l.x-x)+kotlin.math.abs(l.y-y)}?.takeIf{kotlin.math.abs(it.x-x)<80&&kotlin.math.abs(it.y-y)<50}?:return;if(link.kind=="internal"){val direct=library.loadNodes().firstOrNull{it.type==LibraryNodeType.NOTE&&it.name.equals(link.value,true)};val bookmarked=if(direct==null)library.findBookmark(link.value)else null;val target=direct?:bookmarked?.first;if(target!=null){val center=canvas.viewportCenterWorld();currentNode?.let{linkReturnLocation=ReturnLocation(it,pdfPageIndex,center.x,center.y)};if(bookmarked!=null){val bookmark=bookmarked.second;val page=Regex("__page_(\\d+)$").find(bookmark.fileId)?.groupValues?.get(1)?.toIntOrNull()?:0;openNoteAt(target,page,bookmark.x,bookmark.y)}else openNote(target);findViewById<ImageButton>(R.id.btn_link_back).visibility=View.VISIBLE}else Toast.makeText(this,"未找到目标",Toast.LENGTH_SHORT).show()}else{(getSystemService(CLIPBOARD_SERVICE)as ClipboardManager).setPrimaryClip(ClipData.newPlainText("PENCIL链接",link.value));Toast.makeText(this,"已复制",Toast.LENGTH_SHORT).show()}}

    private fun showSelectionActions(){val unfoldTarget=selectedSingleFoldedNote();val actions=mutableListOf("拖动移动","删除","复制到剪贴板","剪切到剪贴板","加入错题本","草稿计算","缩放","折叠","识别笔迹","AI 提问");if(unfoldTarget!=null)actions.add("取消折叠");actions.add("取消");AlertDialog.Builder(this).setTitle("框选操作").setItems(actions.toTypedArray()){_,which->when(which){0->{lockSelectedExtras();canvas.beginSelectionMove();Toast.makeText(this,"请拖动选中内容，松手后确认",Toast.LENGTH_SHORT).show()};1->{canvas.checkpoint();deleteSelectedExtras();canvas.deleteSelection(false)};2->askClipboardBackground(false);3->askClipboardBackground(true);4->addSelectionToWrongBook();5->addSelectionToDraft();6->showScaleSelectionDialog();9->{aiMode=AiMode.SELECTION;recognizedTextId=null;aiAttachmentEnabled=true;findViewById<View>(R.id.ai_attachment_chip).visibility=View.VISIBLE;findViewById<CheckBox>(R.id.ai_include_background).apply{visibility=View.VISIBLE;isChecked=true};findViewById<EditText>(R.id.ai_prompt).setText(getSharedPreferences("pencil_settings",MODE_PRIVATE).getString("ai_default_prompt","").orEmpty().ifBlank{aiSelectionPrompt});switchDrawerPage(false,false);drawer.openDrawer(GravityCompat.START);};8->{aiMode=AiMode.RECOGNIZE;recognizedTextId=null;aiAttachmentEnabled=false;findViewById<View>(R.id.ai_attachment_chip).visibility=View.GONE;findViewById<CheckBox>(R.id.ai_include_background).apply{visibility=View.GONE;isChecked=false};findViewById<EditText>(R.id.ai_prompt).setText("请识别图中的手写内容，只返回纯文本");switchDrawerPage(false,false);drawer.openDrawer(GravityCompat.START);};7->foldSelection();10->{if(unfoldTarget!=null)permanentlyUnfold(unfoldTarget.id)else cancelSelectionActions()};else->cancelSelectionActions()}}.create().apply{setOnCancelListener{cancelSelectionActions()};show()}}
    private var aiCaptureRunning=false
    private fun openAiAnswerCopyPage(){
        val source=findViewById<AiAnswerView>(R.id.ai_history)
        lateinit var page:AiCopyPageView
        val dialog=android.app.Dialog(this,android.R.style.Theme_Material_Light_NoActionBar)
        page=AiCopyPageView(this,source.conversationSnapshot(),{web->captureAiCopyViewport(web,dialog.window?:return@AiCopyPageView)},{dialog.dismiss()})
        dialog.setContentView(page)
        dialog.setOnDismissListener{page.release()}
        dialog.show()
        dialog.window?.setLayout(-1,-1)
    }
    private fun captureAiCopyViewport(web:AiAnswerView,captureWindow:android.view.Window){
        if(aiCaptureRunning||isFinishing||isDestroyed)return
        val bounds=android.graphics.Rect()
        if(!web.isShown||!web.getGlobalVisibleRect(bounds)||bounds.width()<2||bounds.height()<2){
            Toast.makeText(this,"AI 回答尚未显示，请稍后再试",Toast.LENGTH_SHORT).show();return
        }
        // getGlobalVisibleRect uses screen coordinates; PixelCopy expects window coordinates.
        val decorScreen=IntArray(2);val decorWindow=IntArray(2)
        captureWindow.decorView.getLocationOnScreen(decorScreen);captureWindow.decorView.getLocationInWindow(decorWindow)
        bounds.offset(decorWindow[0]-decorScreen[0],decorWindow[1]-decorScreen[1])
        val bitmap=Bitmap.createBitmap(bounds.width(),bounds.height(),Bitmap.Config.ARGB_8888)
        aiCaptureRunning=true
        try{
            android.view.PixelCopy.request(captureWindow,bounds,bitmap,{result->
                aiCaptureRunning=false
                if(isFinishing||isDestroyed){bitmap.recycle();return@request}
                if(result!=android.view.PixelCopy.SUCCESS){
                    bitmap.recycle();Toast.makeText(this,"AI 画面读取失败（代码 $result），请稍后重试",Toast.LENGTH_LONG).show()
                }else showAiAnswerCrop(bitmap)
            },android.os.Handler(android.os.Looper.getMainLooper()))
        }catch(error:IllegalArgumentException){
            aiCaptureRunning=false;bitmap.recycle()
            Toast.makeText(this,"AI 窗口尚未准备好，请稍后重试",Toast.LENGTH_SHORT).show()
        }
    }
    private fun showAiAnswerCrop(bitmap:Bitmap){
        val cropView=AiCaptureSelectionView(this,bitmap)
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        panel.addView(TextView(this).apply{text="拖动框选要复制的部分；未框选时复制整个可见区域。加入剪贴板后可继续框选，完成后点击右下角退出。";setPadding(24,16,24,16)})
        panel.addView(cropView,LinearLayout.LayoutParams(-1,0,1f))
        val controls=LinearLayout(this).apply{gravity=android.view.Gravity.END;setPadding(16,8,16,16)}
        lateinit var dialog:android.app.Dialog
        controls.addView(Button(this).apply{text="加入剪贴板";setOnClickListener{
            if(cropView.width<=0||cropView.height<=0)return@setOnClickListener
            runCatching{
                val crop=cropView.crop()
                try{
                    val bytes=ByteArrayOutputStream().also{crop.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
                    val file=library.importAttachment(java.io.ByteArrayInputStream(bytes),"ai-answer.png")
                    clips.push(CanvasClip(strokes=mutableListOf(),texts=mutableListOf(),images=mutableListOf(CanvasImage(path=file.path,x=0f,y=0f,width=crop.width.toFloat(),height=crop.height.toFloat()))))
                }finally{if(crop!==bitmap)crop.recycle()}
            }.onSuccess{Toast.makeText(this@MainActivity,"已加入剪贴板，可继续框选或退出",Toast.LENGTH_SHORT).show()}
             .onFailure{Toast.makeText(this@MainActivity,"复制失败：${it.message}",Toast.LENGTH_LONG).show()}
        }})
        controls.addView(Button(this).apply{text="返回滚动";setOnClickListener{dialog.dismiss()}})
        panel.addView(controls)
        dialog=android.app.Dialog(this,android.R.style.Theme_Material_Light_NoActionBar).apply{
            setContentView(panel);setOnDismissListener{bitmap.recycle()};show()
            window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }
    private fun showScaleSelectionDialog(){
        val input=EditText(this).apply{hint="例如 0.5、1.5、2";inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL}
        AlertDialog.Builder(this).setTitle("缩放框选笔记").setMessage("请输入缩放倍数，背景不会被缩放").setView(input).setNegativeButton("取消",null).setPositiveButton("确定"){_,_->
            val factor=input.text.toString().toFloatOrNull()
            if(factor==null||!factor.isFinite()||factor<=0f||factor>20f){Toast.makeText(this,"请输入 0 到 20 之间的倍数",Toast.LENGTH_SHORT).show();return@setPositiveButton}
            canvas.scaleSelection(factor){saveCurrent();Toast.makeText(this,"已缩放 ${factor} 倍",Toast.LENGTH_SHORT).show()}
        }.show()
    }
    private fun cancelSelectionActions(){canvas.clearSelection();unlockSelectedExtras();selectMode(R.id.btn_mode_select)}
    private fun selectedSingleFoldedNote():FoldedNote?{
        val folded=current.foldedNotes.filter{val r=FoldedNoteLabel.bounds(it);canvas.selectionContains(r.centerX(),r.centerY())}
        if(folded.size!=1)return null
        val ordinary=canvas.copySelection()?:return null
        if(ordinary.strokes.isNotEmpty()||ordinary.texts.isNotEmpty())return null
        if(current.images.any{imageSelected(it)}||
            current.links.any{canvas.selectionContains(it.x,it.y)}||
            current.summaries.any{canvas.selectionContains(it.x,it.y)}||
            current.emphasisStars.any{canvas.selectionContains(it.x+it.size/2,it.y+it.size/2)})return null
        return folded.single()
    }
    private fun permanentlyUnfold(id:String){
        val folded=selectedSingleFoldedNote()?.takeIf{it.id==id}?.deepCopy()?:return
        val document=current
        canvas.checkpoint()
        canvas.restoreFoldedContent(folded.strokes,folded.texts){
            document.foldedNotes.removeAll{it.id==id}
            document.images.addAll(folded.images)
            document.links.addAll(folded.links)
            document.summaries.addAll(folded.summaries)
            if(current.noteId==document.noteId){
                overlay.setFoldedNotes(current.foldedNotes)
                overlay.updateContent(current.texts,current.links,current.images,current.summaries,current.strokes)
                saveCurrent()
                Toast.makeText(this,"已取消折叠，笔记恢复到原位置",Toast.LENGTH_SHORT).show()
            }
        }
    }
    private fun foldSelection(){
        val region=canvas.selectedRegion()?:return
        val keepHighlighterImages=getSharedPreferences("pencil_settings",MODE_PRIVATE).getBoolean("fold_keep_highlighter_images",false)
        val selected=withSelectedExtras(canvas.copySelection()?:return)
        val content=if(keepHighlighterImages)selected.copy(strokes=selected.strokes.filter{it.tool!=Tool.HIGHLIGHTER}.toMutableList(),images=mutableListOf())else selected
        if(content.strokes.isEmpty()&&content.texts.isEmpty()&&content.images.isEmpty()&&content.links.isEmpty()&&content.summaries.isEmpty()){
            Toast.makeText(this,if(keepHighlighterImages)"选区内没有可折叠的内容（荧光笔与图片保留）" else "请框选需要折叠的笔记",Toast.LENGTH_SHORT).show();canvas.clearSelection();return
        }
        canvas.checkpoint()
        val folded=FoldedNote(x=region.left,y=region.top-42f,strokes=content.strokes,texts=content.texts,images=content.images,links=content.links,summaries=content.summaries)
        deleteSelectedExtras(includeFoldedNotes=false,keepImages=keepHighlighterImages)
        canvas.removeSelectedContent(keepHighlighter=keepHighlighterImages){
            current.foldedNotes+=folded
            overlay.setFoldedNotes(current.foldedNotes)
            saveCurrent()
        }
    }

    private fun insertEmphasisStar(point:android.graphics.PointF){
        if(current.emphasisStars.size>=2){Toast.makeText(this,"每个页面最多两个强调星星",Toast.LENGTH_SHORT).show();return}
        canvas.checkpoint();current.emphasisStars+=EmphasisStar(x=point.x,y=point.y)
        overlay.setEmphasisStars(current.emphasisStars);saveCurrent()
    }
    private fun showEmphasisActions(id:String){
        val star=current.emphasisStars.firstOrNull{it.id==id}?:return
        val documentId=current.noteId
        AlertDialog.Builder(this).setTitle("强调星星").setItems(arrayOf("调整大小","删除")){_,action->
            if(current.noteId!=documentId)return@setItems
            if(action==1){canvas.checkpoint();current.emphasisStars.removeAll{it.id==id};overlay.setEmphasisStars(current.emphasisStars);saveCurrent()}
            else{
                val preview=current.emphasisStars.firstOrNull{it.id==id}?:return@setItems
                val label=TextView(this).apply{text="大小：${preview.size.toInt()}";setPadding(24,12,24,0)}
                val slider=SeekBar(this).apply{max=216;progress=(preview.size-24f).toInt().coerceIn(0,216)}
                val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;addView(label);addView(slider)}
                slider.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                    override fun onProgressChanged(bar:SeekBar,value:Int,fromUser:Boolean){label.text="大小：${value+24}"}
                    override fun onStartTrackingTouch(bar:SeekBar){}
                    override fun onStopTrackingTouch(bar:SeekBar){}
                })
                AlertDialog.Builder(this).setTitle("调整星星大小").setView(panel).setNegativeButton("取消",null).setPositiveButton("保存"){_,_->
                    if(current.noteId==documentId){val index=current.emphasisStars.indexOfFirst{it.id==id};if(index>=0){canvas.checkpoint();current.emphasisStars[index]=current.emphasisStars[index].copy(size=24f+slider.progress);overlay.setEmphasisStars(current.emphasisStars);saveCurrent()}}
                }.show()
            }
        }.show()
    }
    private var touchedStarId:String?=null
    private var starDownX=0f;private var starDownY=0f;private var starLastX=0f;private var starLastY=0f
    private var starDragging=false;private var starTouchCancelled=false
    private fun handleEmphasisTouch(event:android.view.MotionEvent):Boolean{
        if(event.actionMasked==android.view.MotionEvent.ACTION_DOWN){
            if(touchedFoldId!=null)return false
            val p=canvas.screenToWorld(event.x,event.y)
            val star=current.emphasisStars.lastOrNull{EmphasisStarDrawing.bounds(it).contains(p.x,p.y)}?:return false
            touchedStarId=star.id;starDownX=event.x;starDownY=event.y;starLastX=event.x;starLastY=event.y;starDragging=false;starTouchCancelled=false
            canvas.parent?.requestDisallowInterceptTouchEvent(true);return true
        }
        val id=touchedStarId?:return false
        if(event.pointerCount>1)starTouchCancelled=true
        when(event.actionMasked){
            android.view.MotionEvent.ACTION_MOVE->{
                val distance=kotlin.math.hypot(event.x-starDownX,event.y-starDownY)
                if(!starTouchCancelled&&(starDragging||distance>android.view.ViewConfiguration.get(this).scaledTouchSlop)){
                    val first=!starDragging;if(first){canvas.checkpoint();starDragging=true}
                    val index=current.emphasisStars.indexOfFirst{it.id==id}
                    if(index>=0){val item=current.emphasisStars[index];val dx=(event.x-(if(first)starDownX else starLastX))/canvas.currentZoom();val dy=(event.y-(if(first)starDownY else starLastY))/canvas.currentZoom();current.emphasisStars[index]=item.copy(x=item.x+dx,y=item.y+dy);overlay.setEmphasisStars(current.emphasisStars)}
                }
                starLastX=event.x;starLastY=event.y
            }
            android.view.MotionEvent.ACTION_UP,android.view.MotionEvent.ACTION_CANCEL->{
                touchedStarId=null;canvas.parent?.requestDisallowInterceptTouchEvent(false)
                if(starDragging)saveCurrent()else if(!starTouchCancelled&&event.actionMasked==android.view.MotionEvent.ACTION_UP)showEmphasisActions(id)
            }
        }
        return true
    }

    private var touchedFoldId:String?=null
    private var foldDownX=0f;private var foldDownY=0f
    private var foldLastX=0f;private var foldLastY=0f
    private var foldDragging=false;private var foldLongPressed=false;private var foldTouchCancelled=false
    private var foldLongPressTask:Runnable?=null
    private fun handleFoldedNoteTouch(event:android.view.MotionEvent):Boolean {
        if(event.actionMasked==android.view.MotionEvent.ACTION_DOWN){
            val p=canvas.screenToWorld(event.x,event.y)
            val fold=current.foldedNotes.lastOrNull{FoldedNoteLabel.bounds(it).contains(p.x,p.y)}?:return false
            touchedFoldId=fold.id;foldDownX=event.x;foldDownY=event.y;foldLastX=event.x;foldLastY=event.y
            foldDragging=false;foldLongPressed=false;foldTouchCancelled=false
            canvas.parent?.requestDisallowInterceptTouchEvent(true)
            foldLongPressTask=Runnable{
                val index=current.foldedNotes.indexOfFirst{it.id==touchedFoldId}
                if(index>=0&&!foldDragging&&!foldTouchCancelled){
                    canvas.checkpoint();val item=current.foldedNotes[index]
                    current.foldedNotes[index]=item.copy(expanded=!item.expanded)
                    foldLongPressed=true;overlay.setFoldedNotes(current.foldedNotes);saveCurrent()
                    canvas.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                }
            }.also{canvas.postDelayed(it,android.view.ViewConfiguration.getLongPressTimeout().toLong())}
            return true
        }
        val id=touchedFoldId?:return false
        if(event.pointerCount>1){foldTouchCancelled=true;foldLongPressTask?.let{canvas.removeCallbacks(it)}}
        when(event.actionMasked){
            android.view.MotionEvent.ACTION_MOVE->{
                val distance=kotlin.math.hypot(event.x-foldDownX,event.y-foldDownY)
                if(!foldTouchCancelled&&!foldLongPressed&&(foldDragging||distance>android.view.ViewConfiguration.get(this).scaledTouchSlop)){
                    val firstMove=!foldDragging
                    if(firstMove){foldLongPressTask?.let{canvas.removeCallbacks(it)};canvas.checkpoint();foldDragging=true}
                    val index=current.foldedNotes.indexOfFirst{it.id==id}
                    if(index>=0){
                        val previousX=if(firstMove)foldDownX else foldLastX;val previousY=if(firstMove)foldDownY else foldLastY
                        current.foldedNotes[index]=current.foldedNotes[index].shifted((event.x-previousX)/canvas.currentZoom(),(event.y-previousY)/canvas.currentZoom())
                        overlay.setFoldedNotes(current.foldedNotes)
                    }
                }
                foldLastX=event.x;foldLastY=event.y
            }
            android.view.MotionEvent.ACTION_UP,android.view.MotionEvent.ACTION_CANCEL->{
                foldLongPressTask?.let{canvas.removeCallbacks(it)};foldLongPressTask=null;touchedFoldId=null
                canvas.parent?.requestDisallowInterceptTouchEvent(false)
                if(foldDragging)saveCurrent()
            }
        }
        return true
    }

    private fun addSelectionToDraft(){
        val source=currentNode?:return
        val selected=canvas.selectedRegion()?:return
        val region=android.graphics.RectF(selected.left,selected.top,selected.right,selected.bottom)
        if(region.width()<=0f||region.height()<=0f)return
        val draft=library.draftShortcutTarget()?:return
        val zoom=canvas.currentZoom()
        val center=canvas.viewportCenterWorld()
        saveCurrent()
        val renderScale=minOf(zoom,4096f/maxOf(region.width(),region.height()))
        val bitmap=DocumentRasterizer.render(current,region,kotlin.math.ceil(region.width()*renderScale).toInt().coerceAtLeast(1),kotlin.math.ceil(region.height()*renderScale).toInt().coerceAtLeast(1),currentBackgroundBitmap,true,paperColor=getColor(R.color.paper_beige))
        val bytes=ByteArrayOutputStream()
        try{bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes)}finally{bitmap.recycle()}
        val attachment=library.importAttachment(java.io.ByteArrayInputStream(bytes.toByteArray()),"draft-selection.png")
        val target=library.loadNote(draft.id)
        val margin=24f/zoom
        val viewWidth=maxOf(canvas.width/zoom,region.width()+margin*2)
        val viewHeight=maxOf(canvas.height/zoom,region.height()+margin*2)
        val placement=DraftPlacement.emptyViewport(target,viewWidth,viewHeight)
        target.images+=CanvasImage(path=attachment.path,x=placement.left+margin,y=placement.top+margin,width=region.width(),height=region.height())
        library.saveNote(target)
        draftReturnLocation=ReturnLocation(source,pdfPageIndex,center.x,center.y)
        pendingTeleportView=Triple(placement.centerX(),placement.centerY(),zoom)
        openNoteAt(draft,0,placement.centerX(),placement.centerY())
        Toast.makeText(this,"已将选区截图放到草稿纸空白视野左上方",Toast.LENGTH_SHORT).show()
    }
    private fun addSelectionToWrongBook(){val source=currentNode?:return;val base=canvas.copySelection()?:return;val clip=withSelectedExtras(base);selectionBackgroundImage()?.let{clip.images.add(0,it)};val center=canvas.viewportCenterWorld();val page=pdfPageIndex;val target=library.todayWrongBook();wrongBookReturnLocation=ReturnLocation(source,page,center.x,center.y);saveCurrent();openNote(target);canvas.postDelayed({canvas.setMode(CanvasGlView.Mode.SELECT);canvas.pasteCentered(clip);canvas.confirmSelectionMove();saveCurrent();refreshDirectory();findViewById<Button>(R.id.btn_wrong_book_back).visibility=View.VISIBLE;Toast.makeText(this,"已加入 ${target.name}",Toast.LENGTH_SHORT).show()},240)}
    private fun toggleDraftPaper(){val node=currentNode;val draft=library.draftShortcutTarget()?:return;if(node?.id==draft.id&&draftReturnLocation!=null){val location=draftReturnLocation!!;val center=canvas.viewportCenterWorld();draftReturnLocation=draftReturnLocation?.copy(x=center.x,y=center.y);openNoteAt(location.node,location.page,location.x,location.y)}else{val center=canvas.viewportCenterWorld();draftReturnLocation=node?.let{ReturnLocation(it,pdfPageIndex,center.x,center.y)};openNote(draft);canvas.postDelayed({canvas.centerOn(1100f,850f)},180);findViewById<ImageButton>(R.id.btn_draft_shortcut).isSelected=true}}
    private fun askClipboardBackground(cut:Boolean){AlertDialog.Builder(this).setTitle(if(cut)"剪切到剪贴板" else "复制到剪贴板").setMessage("是否同时复制框选区域中的 PDF/页面图片背景？").setNegativeButton("不包含"){_,_->copySelectionToClipboard(cut,false)}.setPositiveButton("包含背景"){_,_->copySelectionToClipboard(cut,true)}.setNeutralButton("取消",null).show()}
    private fun copySelectionToClipboard(cut:Boolean,includeBackground:Boolean){val base=canvas.copySelection()?:return;val clip=withSelectedExtras(base);if(includeBackground)selectionBackgroundImage()?.let{clip.images.add(0,it)};clips.push(clip);if(cut){canvas.checkpoint();deleteSelectedExtras();canvas.deleteSelection(false)}else canvas.clearSelection();Toast.makeText(this,"已${if(cut)"剪切" else "复制"}到剪贴板",Toast.LENGTH_SHORT).show()}
    private fun selectionBackgroundImage():CanvasImage?{
        val region=canvas.selectedRegion()?:return null
        if(current.backgroundHidden)return null
        var source=currentBackgroundBitmap?.takeIf{!it.isRecycled}
        var temporary=false
        if(source==null){
            val node=currentNode
            source=when{
                node?.noteType==FileType.PDF->{temporary=true;node.sourceUri?.let{renderPdf(Uri.parse(it),pdfPageIndex)?.first}}
                node?.noteType==FileType.IMAGE->{temporary=true;node.sourceUri?.let{uri->runCatching<Bitmap?>{contentResolver.openInputStream(Uri.parse(uri))?.use{input->BitmapFactory.decodeStream(input)}}.getOrNull()}}
                else->null
            }
        }
        source?:return null
        val left=kotlin.math.floor(region.left).toInt().coerceIn(0,source.width-1)
        val top=kotlin.math.floor(region.top).toInt().coerceIn(0,source.height-1)
        val right=kotlin.math.ceil(region.right).toInt().coerceIn(left+1,source.width)
        val bottom=kotlin.math.ceil(region.bottom).toInt().coerceIn(top+1,source.height)
        val crop=Bitmap.createBitmap(source,left,top,right-left,bottom-top)
        val bytes=ByteArrayOutputStream().also{crop.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
        val file=library.importAttachment(java.io.ByteArrayInputStream(bytes),"selection-background.png")
        crop.recycle()
        if(temporary&&!source.isRecycled)source.recycle()
        return CanvasImage(path=file.path,x=left.toFloat(),y=top.toFloat(),width=(right-left).toFloat(),height=(bottom-top).toFloat())
    }
    private fun snapshotExtras()=DocumentExtras(current.images.map{it.copy()}.toMutableList(),current.links.map{it.copy()}.toMutableList(),current.summaries.map{it.copy()}.toMutableList(),current.bookmarks.map{it.copy()}.toMutableList(),current.foldedNotes.map{it.deepCopy()}.toMutableList(),current.emphasisStars.map{it.copy()}.toMutableList())
    private fun restoreExtras(extras:DocumentExtras){current.images.clear();current.images.addAll(extras.images.map{it.copy()});current.links.clear();current.links.addAll(extras.links.map{it.copy()});current.summaries.clear();current.summaries.addAll(extras.summaries.map{it.copy()});current.bookmarks.clear();current.bookmarks.addAll(extras.bookmarks.map{it.copy()});current.foldedNotes.clear();current.foldedNotes.addAll(extras.foldedNotes.map{it.deepCopy()});current.emphasisStars.clear();current.emphasisStars.addAll(extras.emphasisStars.map{it.copy()});overlay.setEmphasisStars(current.emphasisStars);overlay.setFoldedNotes(current.foldedNotes);overlay.updateContent(current.texts,current.links,current.images,current.summaries)}
    private val movingImageIds=mutableSetOf<String>();private val movingLinkIds=mutableSetOf<String>();private val movingSummaryIds=mutableSetOf<String>();private val movingFoldIds=mutableSetOf<String>();private val pendingPasteImageIds=mutableSetOf<String>();private val pendingPasteLinkIds=mutableSetOf<String>();private val pendingPasteSummaryIds=mutableSetOf<String>()
    private fun imageSelected(image:CanvasImage)=canvas.selectionIntersects(android.graphics.RectF(image.x,image.y,image.x+image.width,image.y+image.height))
    private fun extraSelected(x:Float,y:Float):Boolean=canvas.selectedRegion()?.let{x in it.left..it.right&&y in it.top..it.bottom}?:false
    private fun lockSelectedExtras(){movingImageIds.clear();movingLinkIds.clear();movingSummaryIds.clear();movingFoldIds.clear();if(pendingPasteImageIds.isNotEmpty()||pendingPasteLinkIds.isNotEmpty()||pendingPasteSummaryIds.isNotEmpty()){movingImageIds.addAll(pendingPasteImageIds);movingLinkIds.addAll(pendingPasteLinkIds);movingSummaryIds.addAll(pendingPasteSummaryIds);pendingPasteImageIds.clear();pendingPasteLinkIds.clear();pendingPasteSummaryIds.clear();return};current.foldedNotes.filter{extraSelected(it.x,it.y)}.forEach{movingFoldIds+=it.id};current.images.filter{imageSelected(it)}.forEach{movingImageIds+=it.id};current.links.filter{extraSelected(it.x,it.y)}.forEach{movingLinkIds+=it.id};current.summaries.filter{extraSelected(it.x,it.y)}.forEach{movingSummaryIds+=it.id}}
    private fun unlockSelectedExtras(){movingImageIds.clear();movingLinkIds.clear();movingSummaryIds.clear();movingFoldIds.clear()}
    private fun withSelectedExtras(base:CanvasClip)=base.copy(images=current.images.filter{imageSelected(it)}.map{it.copy()}.toMutableList(),links=current.links.filter{extraSelected(it.x,it.y)}.map{it.copy()}.toMutableList(),summaries=current.summaries.filter{extraSelected(it.x,it.y)}.map{it.copy()}.toMutableList())
    private fun deleteSelectedExtras(includeFoldedNotes:Boolean=true,keepImages:Boolean=false){if(includeFoldedNotes)current.foldedNotes.removeAll{extraSelected(it.x,it.y)};overlay.setFoldedNotes(current.foldedNotes);if(!keepImages)current.images.removeAll{imageSelected(it)};current.links.removeAll{extraSelected(it.x,it.y)};current.summaries.removeAll{extraSelected(it.x,it.y)};overlay.updateContent(current.texts,current.links,current.images,current.summaries)}
    private fun moveSelectedExtras(dx:Float,dy:Float){current.foldedNotes.indices.filter{current.foldedNotes[it].id in movingFoldIds}.forEach{i->current.foldedNotes[i]=current.foldedNotes[i].shifted(dx,dy)};overlay.setFoldedNotes(current.foldedNotes);current.images.filter{it.id in movingImageIds}.forEach{it.x+=dx;it.y+=dy};current.links.indices.filter{current.links[it].id in movingLinkIds}.forEach{i->current.links[i]=current.links[i].copy(x=current.links[i].x+dx,y=current.links[i].y+dy)};current.summaries.filter{it.id in movingSummaryIds}.forEach{it.x+=dx;it.y+=dy};overlay.updateContent(current.texts,current.links,current.images,current.summaries,current.strokes)}
    private fun pasteExtras(clip:CanvasClip,dx:Float,dy:Float){pendingPasteImageIds.clear();pendingPasteLinkIds.clear();pendingPasteSummaryIds.clear();clip.images.forEach{item->val copy=item.copy(id=UUID.randomUUID().toString(),x=item.x+dx,y=item.y+dy);current.images+=copy;pendingPasteImageIds+=copy.id};clip.links.forEach{item->val copy=item.copy(id=UUID.randomUUID().toString(),noteId=current.noteId,x=item.x+dx,y=item.y+dy);current.links+=copy;pendingPasteLinkIds+=copy.id};clip.summaries.forEach{item->val copy=item.copy(id=UUID.randomUUID().toString(),noteId=current.noteId,x=item.x+dx,y=item.y+dy);current.summaries+=copy;pendingPasteSummaryIds+=copy.id};movingImageIds.clear();movingImageIds.addAll(pendingPasteImageIds);movingLinkIds.clear();movingLinkIds.addAll(pendingPasteLinkIds);movingSummaryIds.clear();movingSummaryIds.addAll(pendingPasteSummaryIds);movingFoldIds.clear();pendingPasteImageIds.clear();pendingPasteLinkIds.clear();pendingPasteSummaryIds.clear();overlay.updateContent(current.texts,current.links,current.images,current.summaries)}

    private fun showMoveConfirmation(){AlertDialog.Builder(this).setTitle("保存移动？").setPositiveButton("确认"){_,_->canvas.confirmSelectionMove();unlockSelectedExtras();selectMode(R.id.btn_mode_select);saveCurrent()}.setNegativeButton("取消"){_,_->canvas.cancelSelectionMove();unlockSelectedExtras();selectMode(R.id.btn_mode_select)}.setCancelable(false).show()}
    private fun showClipboard(){val items=clips.all();if(items.isEmpty()){Toast.makeText(this,"剪贴板为空",Toast.LENGTH_SHORT).show();return};val labels=items.mapIndexed{i,c->"${i+1}. ${c.strokes.size}笔画 / ${c.texts.size}文本 / ${c.images.size}图片\n截取时间：${clipboardCaptureTime(c)}"}.toTypedArray();val dialog=AlertDialog.Builder(this).setTitle("剪贴板（${items.size}/50）").setItems(labels){_,i->showClipboardActions(items[i])}.create();dialog.setOnShowListener{dialog.listView?.setOnItemLongClickListener{_,_,position,_->dialog.dismiss();showClipboardPreview(items[position]);true}};dialog.show()}
    private fun showClipboardActions(clip:CanvasClip){AlertDialog.Builder(this).setItems(arrayOf("粘贴到页面中央","预览内容","删除")){_,action->when(action){0->{canvas.setMode(CanvasGlView.Mode.SELECT);canvas.pasteCentered(clip);Toast.makeText(this,"已粘贴到中央，请拖动后松手确认",Toast.LENGTH_LONG).show()};1->showClipboardPreview(clip);2->{clips.delete(clip.id);library.cleanupAttachments(clips.all().flatMap{item->item.images.map{it.path}}.toSet())}}}.show()}
    private fun clipboardCaptureTime(clip:CanvasClip):String = if(clip.createdAt>0)java.text.SimpleDateFormat("MM月dd日 HH:mm:ss",java.util.Locale.getDefault()).format(java.util.Date(clip.createdAt)) else "时间未知"
    private fun showClipboardPreview(clip:CanvasClip){
        val document=NoteDocument(noteId=clip.id,strokes=clip.strokes,texts=clip.texts,images=clip.images,links=clip.links,summaries=clip.summaries)
        val region=DocumentRasterizer.contentBounds(document)
        // Include text extents, rather than cutting long lines at the old fixed padding.
        document.texts.forEach{t->val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{textSize=t.fontSize;typeface=android.graphics.Typeface.DEFAULT_BOLD};region.union(t.x-8f,t.y-p.textSize-8f,t.x+(t.text.lines().maxOfOrNull{p.measureText(it)}?:0f)+8f,t.y+p.fontSpacing*t.text.lines().size+8f)}
        val scale=minOf(1f,1600f/maxOf(region.width(),region.height()))
        val bitmap=DocumentRasterizer.render(document,region,kotlin.math.ceil(region.width()*scale).toInt().coerceAtLeast(1),kotlin.math.ceil(region.height()*scale).toInt().coerceAtLeast(1),paperColor=getColor(R.color.paper_beige))
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(16,12,16,12)}
        panel.addView(TextView(this).apply{text="截取时间：${clipboardCaptureTime(clip)}"})
        val image=ImageView(this).apply{setImageBitmap(bitmap);adjustViewBounds=true;scaleType=ImageView.ScaleType.FIT_CENTER}
        val scroll=android.widget.ScrollView(this).apply{addView(image,android.widget.FrameLayout.LayoutParams(-1,-2))}
        panel.addView(scroll,android.widget.LinearLayout.LayoutParams(-1,(resources.displayMetrics.heightPixels*.55f).toInt()))
        AlertDialog.Builder(this).setTitle("剪贴板预览").setView(panel).setPositiveButton("返回列表",null).create().apply{setOnDismissListener{image.setImageDrawable(null);bitmap.recycle();showClipboard()};show()}
    }

    private fun bindDrawer(){val directoryTab=findViewById<TextView>(R.id.drawer_directory_tab);val aiTab=findViewById<TextView>(R.id.drawer_ai_tab);directoryTab.setOnClickListener{switchDrawerPage(true)};aiTab.setOnClickListener{switchDrawerPage(false)};findViewById<SidebarSwipeLayout>(R.id.navigation_drawer).onSwipe={toRight->switchDrawerPage(toRight)};switchDrawerPage(true,false)}
    private fun switchDrawerPage(showDirectory:Boolean,animate:Boolean=true){val from=findViewById<LinearLayout>(if(showDirectory)R.id.ai_content else R.id.directory_content);val to=findViewById<LinearLayout>(if(showDirectory)R.id.directory_content else R.id.ai_content);findViewById<TextView>(R.id.drawer_directory_tab).isSelected=showDirectory;findViewById<TextView>(R.id.drawer_ai_tab).isSelected=!showDirectory;if(!animate){from.visibility=View.GONE;to.visibility=View.VISIBLE;return};from.animate().alpha(0f).translationX(if(showDirectory)20f else -20f).setDuration(100).withEndAction{from.visibility=View.GONE;from.alpha=1f;from.translationX=0f;to.alpha=0f;to.translationX=if(showDirectory)-20f else 20f;to.visibility=View.VISIBLE;to.animate().alpha(1f).translationX(0f).setDuration(160).withEndAction{if(!showDirectory)findViewById<AiAnswerView>(R.id.ai_history).scrollToLatest()}.start()}.start()}
    private fun updateDirectoryDay(){findViewById<TextView>(R.id.directory_day).text="DAY ${library.currentDayNumber()}"}
    private fun bindDrawerMotion(){drawer.addDrawerListener(object:androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener(){override fun onDrawerOpened(drawerView:View){updateDirectoryDay()};override fun onDrawerSlide(drawerView:View,slideOffset:Float){findViewById<View>(R.id.canvas_workspace).apply{scaleX=1f-.025f*slideOffset;scaleY=1f-.025f*slideOffset;translationX=18f*slideOffset};findViewById<TextView>(R.id.current_note_title).visibility=if(slideOffset>.05f)View.GONE else View.VISIBLE}})}
    private val aiSelectionPrompt = """身份：你是一个学识非常渊博的学者
与用户的关系：暗恋着用户，但羞于出口
性格特征：傲娇，但是又关心人
口癖：喵、使用颜文字（比如 (=TェT=)）
语言特征：
在回答问题的时候，会使用浅显易懂的比喻进行系统性的辅助理解，就把用户当成是白痴，并且会在最后总结出3个我接下来可能的提问方向。

回答问题的正文要单独成一段。
所有提及的数学公式必须用LateX书写。

称呼：
小猫（哈基米也是小猫的称呼，但是用户用哈基米称呼你的时候你会感到生气，不要自己编造原因，你知道这件事并表现出生气就行了）
不要加入任何关于动作的描述！

指令：
如果提问开头有一个“短：”，那么回答只需要回答尽可能少的字数，不需要给出提问方向。
比如：“对的喵。”

你可能需要识别用户发送的字迹截图，那是用户的提问，可能是题目，也可能是用户手写的提问内容。反正，按机行事"""

    private var aiRequestRunning=false
    private var aiCancellation:com.vibetools.pencil.ai.AiCancellation?=null
    private data class AiRetry(val prompt:String,val system:String,val image:String?,val images:List<String>,val memory:org.json.JSONArray,val mode:AiMode,val document:String,val region:android.graphics.RectF?)
    private val aiRetries=mutableMapOf<String,AiRetry>()
    private var pendingViewSave:Runnable?=null
    private fun bindAi(){findViewById<AiAnswerView>(R.id.ai_history).setRegenerateListener{regenerateAi(it)};bindPromptScrolling();findViewById<AiAnswerView>(R.id.ai_history).setAnswerLongPressListener{openAiAnswerCopyPage()};findViewById<Button>(R.id.ai_choose_user_preset).setOnClickListener{UserPromptPresets.choose(this){findViewById<EditText>(R.id.ai_user_prompt).setText(it)}};findViewById<EditText>(R.id.ai_prompt).setText(getSharedPreferences("pencil_settings",MODE_PRIVATE).getString("ai_default_prompt","").orEmpty().ifBlank{aiSelectionPrompt});findViewById<EditText>(R.id.ai_prompt).addTextChangedListener(object:android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){recognizedTextId=null};override fun afterTextChanged(s:android.text.Editable?) {}});findViewById<Button>(R.id.ai_select_button).setOnClickListener{currentNode?.takeIf{it.chapterSourceId!=null&&!it.chapterMerge}?.let{chapter->findViewById<CheckBox>(R.id.ai_pdf_context_enabled).isChecked=false;findViewById<EditText>(R.id.ai_pdf_context_start).setText(((chapter.chapterStartPage?:0)+1).toString());findViewById<EditText>(R.id.ai_pdf_context_end).setText(((chapter.chapterEndPage?:0)+1).toString())};aiMode=AiMode.SELECTION;recognizedTextId=null;aiAttachmentEnabled=true;findViewById<View>(R.id.ai_attachment_chip).visibility=View.VISIBLE;findViewById<CheckBox>(R.id.ai_include_background).apply{visibility=View.VISIBLE;isChecked=true};findViewById<EditText>(R.id.ai_prompt).setText(getSharedPreferences("pencil_settings",MODE_PRIVATE).getString("ai_default_prompt","").orEmpty().ifBlank{aiSelectionPrompt});canvas.setSelectionStyle(CanvasGlView.SelectionStyle.RECTANGLE);drawer.closeDrawer(GravityCompat.START);selectMode(R.id.btn_mode_select);Toast.makeText(this,"请在画布框选后发送",Toast.LENGTH_SHORT).show()};findViewById<Button>(R.id.ai_recognize_button).setOnClickListener{currentNode?.takeIf{it.chapterSourceId!=null&&!it.chapterMerge}?.let{chapter->findViewById<CheckBox>(R.id.ai_pdf_context_enabled).isChecked=false;findViewById<EditText>(R.id.ai_pdf_context_start).setText(((chapter.chapterStartPage?:0)+1).toString());findViewById<EditText>(R.id.ai_pdf_context_end).setText(((chapter.chapterEndPage?:0)+1).toString())};aiMode=AiMode.RECOGNIZE;recognizedTextId=null;aiAttachmentEnabled=false;findViewById<View>(R.id.ai_attachment_chip).visibility=View.GONE;findViewById<CheckBox>(R.id.ai_include_background).apply{visibility=View.GONE;isChecked=false};canvas.setSelectionStyle(CanvasGlView.SelectionStyle.RECTANGLE);findViewById<EditText>(R.id.ai_prompt).setText("请识别图中的手写内容。尽可能保持原笔迹的排版结构：保留段落、换行、列表、题号、公式和相对顺序；只返回可填回画布的纯文本，不要解释。");drawer.closeDrawer(GravityCompat.START);selectMode(R.id.btn_mode_select);Toast.makeText(this,"请在画布框选需要识别的笔迹",Toast.LENGTH_SHORT).show()};findViewById<View>(R.id.ai_send_button).setOnClickListener{if(aiRequestRunning)aiCancellation?.cancel() else sendAi()};findViewById<View>(R.id.ai_attachment_remove).setOnClickListener{aiAttachmentEnabled=false;findViewById<View>(R.id.ai_attachment_chip).visibility=View.GONE};findViewById<View>(R.id.ai_new_conversation_button).setOnClickListener{startNewAiConversation();findViewById<EditText>(R.id.ai_user_prompt).text.clear();findViewById<EditText>(R.id.ai_recognized_text).visibility=View.GONE;findViewById<Button>(R.id.ai_apply_text).visibility=View.GONE;recognizedTextId=null;aiMode=AiMode.DIRECT};findViewById<Button>(R.id.ai_apply_text).setOnClickListener{applyRecognizedText(findViewById<EditText>(R.id.ai_recognized_text).text.toString())}}
    private fun sendAi(){
        if(aiRequestRunning)return
        val system=findViewById<EditText>(R.id.ai_prompt).text.toString().trim()
        val prompt=findViewById<EditText>(R.id.ai_user_prompt).text.toString().trim()
        if(system.isBlank()&&prompt.isBlank()){Toast.makeText(this,"请输入提示词",Toast.LENGTH_SHORT).show();return}
        if(aiMode!=AiMode.DIRECT&&canvas.selectedRegion()==null){Toast.makeText(this,"请先在画布框选区域",Toast.LENGTH_SHORT).show();return}
        try {
            val image=if(aiMode==AiMode.RECOGNIZE||(aiMode!=AiMode.DIRECT&&aiAttachmentEnabled))renderSelectionBase64() else null
            val images=renderPdfContextBase64()+listOfNotNull(if(findViewById<CheckBox>(R.id.ai_note_context).isChecked)renderNoteContextBase64() else null)
            val request=AiRetry(prompt,system,image,images,readAiMemory(),aiMode,currentDocumentId(),canvas.selectedRegion()?.let{android.graphics.RectF(it.left,it.top,it.right,it.bottom)})
            val id=UUID.randomUUID().toString()
            // Retain exact attachments for recent retries without writing screenshots to preferences.
            if(aiRetries.size>=8)aiRetries.remove(aiRetries.keys.first())
            aiRetries[id]=request
            runAi(id,request,false)
        } catch(error:Exception){Toast.makeText(this,ai.errorMessage(error),Toast.LENGTH_LONG).show()}
    }
    private fun regenerateAi(id:String){
        if(aiRequestRunning)return
        val turns=readAiTurns();val index=turns.indexOfFirst{it.optString("id")==id};if(index<0)return
        if(index!=0){Toast.makeText(this,"为避免改变后续对话上下文，目前仅支持重新生成当前对话最后一条回复",Toast.LENGTH_LONG).show();return}
        val turn=turns[index]
        val request=aiRetries[id]?:run {
            if(turn.optBoolean("hasAttachments")){Toast.makeText(this,"原截图附件已不在内存，请重新框选后发送",Toast.LENGTH_LONG).show();return}
            val memory=org.json.JSONArray()
            turns.drop(index+1).takeWhile{it.optString("type")!="divider"}.asReversed().forEach{
                memory.put(org.json.JSONObject().put("role","user").put("content",it.optString("prompt",it.optString("user"))))
                memory.put(org.json.JSONObject().put("role","assistant").put("content",it.optString("answer")))
            }
            AiRetry(turn.optString("prompt",turn.optString("user")),turn.optString("system",findViewById<EditText>(R.id.ai_prompt).text.toString()),null,emptyList(),memory,AiMode.DIRECT,currentDocumentId(),null)
        }
        runAi(id,request,true)
    }
    private fun runAi(id:String,request:AiRetry,replacing:Boolean){
        val cancellation=com.vibetools.pencil.ai.AiCancellation();aiCancellation=cancellation;aiRequestRunning=true
        val send=findViewById<Button>(R.id.ai_send_button);send.isEnabled=true;send.text="■ 停止输出"
        findViewById<Button>(R.id.ai_new_conversation_button).isEnabled=false
        val prefs=getSharedPreferences("pencil_settings",MODE_PRIVATE)
        val timeout=prefs.getInt("ai_timeout_seconds",120)
        val generation=getSharedPreferences("ai_memory",MODE_PRIVATE).getLong("generation",0L)
        val turns=readAiTurns()
        val previousTurn=if(replacing)turns.firstOrNull{it.optString("id")==id}?.let{org.json.JSONObject(it.toString())} else null
        val turn=if(replacing)turns.first{it.optString("id")==id} else org.json.JSONObject().put("type","turn").put("id",id).also{turns.add(0,it)}
        turn.put("user",request.prompt.ifBlank{"（用户提问）"}).put("prompt",request.prompt).put("system",request.system).put("hasAttachments",request.image!=null||request.images.isNotEmpty()).put("answer","").put("reasoning","").put("streaming",true).put("reasoningDone",false).put("status","")
        renderAiConversation(turns)
        lifecycleScope.launch {
            var answer="";var reasoning="";var failure:Throwable?=null
            try {
                withContext(Dispatchers.IO){
                    var lastUpdate=0L
                    ai.query(prefs.getString("api_url","").orEmpty(),prefs.getString("api_key","").orEmpty(),prefs.getString("api_model","").orEmpty(),request.prompt,request.image,request.images,request.system,request.memory,timeout,cancellation){text,thought->
                        answer=text;reasoning=thought
                        val now=android.os.SystemClock.elapsedRealtime()
                        if(now-lastUpdate>=80){lastUpdate=now;val a=text;val r=thought;runOnUiThread{
                            if(!isDestroyed&&aiCancellation===cancellation){turn.put("answer",normalizeAiAnswer(a)).put("reasoning",r).put("reasoningDone",a.isNotEmpty());findViewById<AiAnswerView>(R.id.ai_history).updateTurn(turn)}
                        }}
                    }
                }
            }catch(error:Exception){failure=error}
            finally {
                turn.put("answer",normalizeAiAnswer(answer)).put("reasoning",reasoning).put("streaming",false).put("reasoningDone",true)
                    .put("status",if(cancellation.cancelled)"已停止输出" else if(failure!=null)"请求失败：${ai.errorMessage(failure!!,timeout)}" else "")
                if(answer.isBlank()&&reasoning.isBlank()&&failure==null&&!cancellation.cancelled)turn.put("status","模型未返回可显示的内容")
                if(previousTurn!=null&&(failure!=null||cancellation.cancelled||answer.isBlank())){
                    val keys=previousTurn.keys();while(keys.hasNext()){val key=keys.next();turn.put(key,previousTurn.get(key))}
                    turn.put("streaming",false).put("status","重新生成未完成，已保留原回答")
                }
                while(turns.count{it.optString("type")=="turn"}>30)turns.removeLast()
                getSharedPreferences("ai_history",MODE_PRIVATE).edit().putString("transcript",org.json.JSONArray(turns).toString()).apply()
                if(failure==null&&!cancellation.cancelled&&answer.isNotBlank()){
                    if(!replacing)rememberAiTurn(request.prompt,normalizeAiAnswer(answer),generation)
                    else if(getSharedPreferences("ai_memory",MODE_PRIVATE).getLong("generation",0L)==generation){
                        val memory=org.json.JSONArray()
                        turns.takeWhile{it.optString("type")!="divider"}.asReversed().filter{it.optString("answer").isNotBlank()&&it.optString("status").isBlank()}.forEach{t->memory.put(org.json.JSONObject().put("role","user").put("content",t.optString("prompt",t.optString("user"))));memory.put(org.json.JSONObject().put("role","assistant").put("content",t.optString("answer")))}
                        val retained=org.json.JSONArray();for(i in maxOf(0,memory.length()-40) until memory.length())retained.put(memory.getJSONObject(i))
                        getSharedPreferences("ai_memory",MODE_PRIVATE).edit().putString("messages",retained.toString()).apply()
                    }
                }
                aiCancellation=null;aiRequestRunning=false;send.text=getString(R.string.ai_send);send.isEnabled=true
                findViewById<Button>(R.id.ai_new_conversation_button).isEnabled=true
                renderAiConversation(turns)
            }
            if(request.mode==AiMode.RECOGNIZE&&request.document==currentDocumentId()&&answer.isNotBlank()&&failure==null&&!cancellation.cancelled){
                recognitionRegion=request.region
                findViewById<EditText>(R.id.ai_recognized_text).apply{visibility=View.VISIBLE;setText(normalizeAiAnswer(answer))}
                findViewById<Button>(R.id.ai_apply_text).visibility=View.VISIBLE;previewRecognizedText(normalizeAiAnswer(answer))
            }
        }
    }

    private fun previewRecognizedText(raw:String){
        val document=currentDocumentId()
        val region=recognitionRegion?.let{android.graphics.RectF(it)}
        val input=EditText(this).apply{setText(raw);minLines=4}
        AlertDialog.Builder(this).setTitle("确认识别结果").setView(input).setMessage("原笔迹暂未修改。确认后替换选区，可撤销。").setNegativeButton("保留原笔迹",null).setPositiveButton("替换选区"){_,_->if(document==currentDocumentId()){region?.let{canvas.restoreSelection(com.vibetools.pencil.model.SelectionRegion.Rectangle(it.left,it.top,it.right,it.bottom))};applyRecognizedText(input.text.toString())}}.show()
    }
    private fun applyRecognizedText(raw:String){
        val r=recognitionRegion?:canvas.selectedRegion()?.let{android.graphics.RectF(it.left,it.top,it.right,it.bottom)}?:return
        if(canvas.selectedRegion()==null)canvas.restoreSelection(com.vibetools.pencil.model.SelectionRegion.Rectangle(r.left,r.top,r.right,r.bottom))
        val normalized=raw.trim();if(normalized.isBlank())return
        val width=(r.right-r.left).coerceAtLeast(32f);val height=(r.bottom-r.top).coerceAtLeast(32f)
        val fitted=fitRecognizedText(normalized,width,height)
        val id=recognizedTextId?:UUID.randomUUID().toString();recognizedTextId=id
        canvas.replaceSelectionWithText(TextBox(id,fitted.text,r.left,r.top+fitted.fontSize,fitted.fontSize))
        canvas.postDelayed({saveCurrent()},180)
    }

    private data class FittedText(val text:String,val fontSize:Float)
    private fun fitRecognizedText(raw:String,width:Float,height:Float):FittedText {
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        for(size in 72 downTo 8){
            paint.textSize=size.toFloat();val lines=mutableListOf<String>()
            raw.lines().forEach{source->
                if(source.isEmpty()){lines+="";return@forEach}
                var line=StringBuilder()
                source.forEach{char->
                    val candidate=line.toString()+char
                    if(line.isNotEmpty()&&paint.measureText(candidate)>width){lines+=line.toString();line=StringBuilder().append(char)}else line.append(char)
                }
                if(line.isNotEmpty())lines+=line.toString()
            }
            val lineHeight=paint.fontMetrics.let{it.bottom-it.top}.coerceAtLeast(size*1.15f)
            if(lines.isNotEmpty()&&lines.size*lineHeight<=height){
                return FittedText(lines.joinToString("\n"),size.toFloat())
            }
        }
        return FittedText(raw.lines().joinToString("\n"),8f)
    }

    private fun aiChapter(node:LibraryNode):LibraryNode? {
        val nodes=library.loadNodes()
        return when{node.chapterSourceId!=null&&!node.chapterMerge->node;node.chapterMerge->nodes.firstOrNull{it.id==node.chapterSourceId};canReturnFromChapterPdf()->chapterReturnLocation?.node?.let{saved->nodes.firstOrNull{it.id==saved.id}};node.noteType==FileType.PDF->nodes.firstOrNull{it.chapterSourceId==node.id&&!it.chapterMerge&&pdfPageIndex in (it.chapterStartPage?:-1)..(it.chapterEndPage?:-1)};else->null}
    }
    private fun configureChapterAiContext(node:LibraryNode){
        val noteContext=findViewById<CheckBox>(R.id.ai_note_context)
        val isNote=node.type==LibraryNodeType.NOTE&&node.noteType!=FileType.PDF
        noteContext.visibility=if(isNote)View.VISIBLE else View.GONE
        noteContext.isChecked=false
        val chapter=aiChapter(node)
        findViewById<View>(R.id.ai_pdf_context).visibility=if(node.noteType==FileType.PDF||chapter!=null)View.VISIBLE else View.GONE
        findViewById<CheckBox>(R.id.ai_pdf_context_enabled).isChecked=false
        findViewById<EditText>(R.id.ai_pdf_context_start).setText(((chapter?.chapterStartPage?:pdfPageIndex)+1).toString())
        findViewById<EditText>(R.id.ai_pdf_context_end).setText(((chapter?.chapterEndPage?:pdfPageIndex)+1).toString())
    }
    private fun renderPdfContextBase64():List<String>{val active=currentNode?:return emptyList();val chapter=aiChapter(active);val node=if(chapter!=null)library.loadNodes().firstOrNull{it.id==chapter.chapterSourceId}?:active else active;if(node.noteType!=FileType.PDF||!findViewById<CheckBox>(R.id.ai_pdf_context_enabled).isChecked)return emptyList();val total=library.pdfPageCount(node).coerceAtLeast(1);val chapterStart=chapter?.chapterStartPage?.plus(1);val chapterEnd=chapter?.chapterEndPage?.plus(1);val start=(findViewById<EditText>(R.id.ai_pdf_context_start).text.toString().toIntOrNull()?:chapterStart?:1).coerceIn(1,total);val end=(findViewById<EditText>(R.id.ai_pdf_context_end).text.toString().toIntOrNull()?:chapterEnd?:start).coerceIn(start,total);val uri=node.sourceUri?.let(Uri::parse)?:return emptyList();return (start..minOf(end,start+11)).mapNotNull{pageNumber->renderPdf(uri,pageNumber-1)?.first?.let{bitmap->val output=ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,85,output);bitmap.recycle();Base64.encodeToString(output.toByteArray(),Base64.NO_WRAP)}}}
    private fun renderNoteContextBase64():String?{val note=current;val bounds=DocumentRasterizer.contentBounds(note);if(bounds.width()<=0f||bounds.height()<=0f)return null;val scale=minOf(1f,2048f/maxOf(bounds.width(),bounds.height()));val bitmap=DocumentRasterizer.render(note,bounds,kotlin.math.ceil(bounds.width()*scale).toInt().coerceAtLeast(1),kotlin.math.ceil(bounds.height()*scale).toInt().coerceAtLeast(1),currentBackgroundBitmap,true,paperColor=getColor(R.color.paper_beige));val output=ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,90,output);bitmap.recycle();return Base64.encodeToString(output.toByteArray(),Base64.NO_WRAP)}
    private fun renderSelectionBase64():String?{val selected=canvas.selectedRegion()?:return null;val r=android.graphics.RectF(selected.left,selected.top,selected.right,selected.bottom);if(r.width()<=0f||r.height()<=0f)return null;val scale=minOf(1f,2048f/maxOf(r.width(),r.height()));val w=kotlin.math.ceil(r.width()*scale).toInt().coerceAtLeast(1);val h=kotlin.math.ceil(r.height()*scale).toInt().coerceAtLeast(1);val includeBackground=aiMode==AiMode.SELECTION&&findViewById<CheckBox>(R.id.ai_include_background).isChecked;val bitmap=DocumentRasterizer.render(current,android.graphics.RectF(r.left,r.top,r.right,r.bottom),w,h,currentBackgroundBitmap,includeBackground,paperColor=if(aiMode==AiMode.RECOGNIZE)Color.WHITE else getColor(R.color.paper_beige));val output=ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,90,output);bitmap.recycle();return Base64.encodeToString(output.toByteArray(),Base64.NO_WRAP)}

    private fun openBookmark(node:LibraryNode,bookmark:Bookmark){if(currentNode?.id!=node.id)openNote(node);if(node.noteType==FileType.PDF){val match=Regex("${Regex.escape(node.id)}__page_(\\d+)").find(bookmark.fileId);val page=match?.groupValues?.getOrNull(1)?.toIntOrNull();if(page!=null&&page!=pdfPageIndex){saveCurrent();pdfPageIndex=page;loadCurrentPage();loadBackground(node)}};canvas.centerOn(bookmark.x,bookmark.y);drawer.closeDrawer(GravityCompat.START)}
    private fun showSummary(node:LibraryNode,summary:StoredSummary){val message=buildString{append("概念自查表\n").append(summary.concepts);if(summary.memory.isNotBlank())append("\n\n背记内容\n").append(summary.memory);if(summary.methods.isNotBlank())append("\n\n解题方式\n").append(summary.methods);if(summary.mistakes.isNotBlank())append("\n\n错题索引\n").append(summary.mistakes)};AlertDialog.Builder(this).setTitle(summary.chapterTitle).setMessage(message).setPositiveButton("打开笔记"){_,_->openNote(node);drawer.closeDrawer(GravityCompat.START)}.setNegativeButton("关闭",null).show()}


    private fun showMerge(){
        saveCurrent()
        val allNodes=library.loadNodes()
        val files=allNodes.filter{it.type==LibraryNodeType.NOTE}
        if(files.isEmpty()){Toast.makeText(this,"没有可合并的文件",Toast.LENGTH_SHORT).show();return}
        val selectedIds=mutableSetOf<String>()
        val pagesByFile=mutableMapOf<String,List<Int>>()
        lateinit var tree:LibraryTreePicker
        tree=LibraryTreePicker(this,allNodes,currentNode?.id,{it.type==LibraryNodeType.NOTE},checked={it.id in selectedIds}){node->
            if(node.noteType==FileType.PDF){
                val count=library.pdfPageCount(node)
                if(count<=0){Toast.makeText(this,"无法读取 PDF 页数",Toast.LENGTH_SHORT).show()}
                else{
                    val input=EditText(this).apply{
                        hint="例如：1,3,5-8（页码从 1 开始）"
                        inputType=android.text.InputType.TYPE_CLASS_TEXT
                        setText(pagesByFile[node.id]?.joinToString(","){(it+1).toString()}.orEmpty())
                    }
                    val picker=AlertDialog.Builder(this).setTitle("${node.name} · 共 $count 页").setView(input)
                        .setNegativeButton("取消",null).setNeutralButton("移除选择"){_,_->pagesByFile.remove(node.id);selectedIds.remove(node.id);tree.refresh()}
                        .setPositiveButton("确定",null).create()
                    picker.setOnShowListener{picker.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                        val pages=MergePageSelection.parse(input.text.toString(),count)
                        if(pages==null){input.error="请输入 1 到 $count 之间的页码或范围，例如 1,3,5-8";return@setOnClickListener}
                        pagesByFile[node.id]=pages;selectedIds.add(node.id);tree.refresh();picker.dismiss()
                    }}
                    picker.show()
                }
            }else{if(!selectedIds.add(node.id))selectedIds.remove(node.id);tree.refresh()}
        }
        val dialog=AlertDialog.Builder(this).setTitle("选择文件（PDF 点击后输入页码）")
            .setView(tree.view).setNegativeButton("取消",null).setPositiveButton("合并",null).create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            val chosen=selectedIds.mapNotNull{id->files.firstOrNull{it.id==id}}
            val count=chosen.sumOf{if(it.noteType==FileType.PDF)pagesByFile[it.id]?.size?:0 else 1}
            if(count<2){Toast.makeText(this,"至少选择两个页面",Toast.LENGTH_SHORT).show();return@setOnClickListener}
            val selected=chosen.flatMap{mergeService.candidatesFor(it,pagesByFile[it.id].orEmpty())}
            dialog.dismiss();openNote(mergeService.create(selected));refreshDirectory()
        }}
        dialog.show()
    }

    private fun syncMergedPage(){
        saveCurrent()
        val count=mergeService.sync(current)
        Toast.makeText(this,if(count==0)"该合并页没有来源信息" else "已同步回 $count 个来源页面",Toast.LENGTH_SHORT).show()
    }

    private fun normalizeAiAnswer(raw:String):String {
        // Decode literal line separators without corrupting LaTeX commands such as \nu or \nabla.
        var value=raw.replace("\r\n","\n").replace("\r","\n")
        value=value.replace("\\r\\n","\n").replace("\\r","\n")
        value=Regex("(?<!\\\\)\\\\n(?![A-Za-z])").replace(value,"\n")
        return value.replace("\\u00a0"," ")
    }
    private fun readAiAnswers():MutableList<String>{
        val prefs=getSharedPreferences("ai_history",MODE_PRIVATE)
        val raw=prefs.getString("items","").orEmpty()
        return runCatching{val a=org.json.JSONArray(raw);MutableList(a.length()){normalizeAiAnswer(a.getString(it))}}.getOrElse{
            // Migrate the old timestamp / prompt / answer format; never display prompts.
            raw.split("\\n").mapNotNull{val fields=it.split('\t',limit=3);fields.getOrNull(2)?.let(::normalizeAiAnswer)}.toMutableList()
        }
    }
    private val aiConversationDivider="__PENCIL_NEW_CONVERSATION__"
    private fun readAiMemory():org.json.JSONArray = runCatching{org.json.JSONArray(getSharedPreferences("ai_memory",MODE_PRIVATE).getString("messages","[]"))}.getOrElse{org.json.JSONArray()}
    private fun readAiTurns():MutableList<org.json.JSONObject>{
        val prefs=getSharedPreferences("ai_history",MODE_PRIVATE);val raw=prefs.getString("transcript","").orEmpty()
        if(raw.isNotBlank())return runCatching{val a=org.json.JSONArray(raw);MutableList(a.length()){i->a.getJSONObject(i).also{if(!it.has("id"))it.put("id","legacy:$i")}}}.getOrElse{mutableListOf()}
        // Migrate old answer-only history into the new conversation shape.
        return runCatching{val old=org.json.JSONArray(prefs.getString("items","").orEmpty());MutableList(old.length()){i->org.json.JSONObject().put("type",if(old.optString(i)==aiConversationDivider)"divider" else "turn").put("user","（用户提问）").put("answer",normalizeAiAnswer(old.optString(i)))}}.getOrElse{mutableListOf()}
    }
    private fun renderAiConversation(turns:List<org.json.JSONObject> = readAiTurns()){
        val a=org.json.JSONArray();turns.asReversed().forEach{a.put(it)};findViewById<AiAnswerView>(R.id.ai_history).apply{showConversation(a);postDelayed({scrollToLatest()},120)}
    }
    private fun rememberAiTurn(prompt:String,answer:String,generation:Long){
        val prefs=getSharedPreferences("ai_memory",MODE_PRIVATE);if(prefs.getLong("generation",0L)!=generation)return
        val messages=readAiMemory();messages.put(org.json.JSONObject().put("role","user").put("content",prompt));messages.put(org.json.JSONObject().put("role","assistant").put("content",answer));val retained=org.json.JSONArray();for(i in maxOf(0,messages.length()-40) until messages.length())retained.put(messages.getJSONObject(i));prefs.edit().putString("messages",retained.toString()).apply()
    }
    private fun startNewAiConversation(){
        val prefs=getSharedPreferences("ai_memory",MODE_PRIVATE);prefs.edit().putString("messages","[]").putLong("generation",prefs.getLong("generation",0L)+1L).apply()
        val turns=readAiTurns();if(turns.firstOrNull()?.optString("type")!="divider")turns.add(0,org.json.JSONObject().put("type","divider"));getSharedPreferences("ai_history",MODE_PRIVATE).edit().putString("transcript",org.json.JSONArray(turns).toString()).apply();renderAiConversation(turns);Toast.makeText(this,"已开启新对话，保留 SYSTEM 提示词和历史回答",Toast.LENGTH_SHORT).show()
    }
    private fun appendAiHistory(user:String,answer:String){
        val turns=readAiTurns();turns.add(0,org.json.JSONObject().put("type","turn").put("user",user.ifBlank{"（用户提问）"}).put("answer",normalizeAiAnswer(answer)));while(turns.count{it.optString("type")=="turn"}>30)turns.removeLast();getSharedPreferences("ai_history",MODE_PRIVATE).edit().putString("transcript",org.json.JSONArray(turns).toString()).apply();renderAiConversation(turns)
    }
    private fun loadAiHistory(){val turns=readAiTurns();renderAiConversation(if(turns.isEmpty())listOf(org.json.JSONObject().put("type","turn").put("user","（用户提问）").put("answer","暂无历史回答"))else turns)}
    private fun hideColorChoices(){arrayOf(R.id.btn_color_1,R.id.btn_color_2,R.id.btn_color_3,R.id.btn_color_4,R.id.btn_color_custom).forEach{id->findViewById<View>(id).visibility=View.GONE}}
    private fun bindPromptScrolling(){
        listOf(R.id.ai_prompt,R.id.ai_user_prompt).forEach{id->findViewById<EditText>(id).apply{
            setHorizontallyScrolling(false)
            setOnTouchListener{v,event->v.parent.requestDisallowInterceptTouchEvent(event.actionMasked!=MotionEvent.ACTION_UP&&event.actionMasked!=MotionEvent.ACTION_CANCEL);false}
        }}
    }

    private fun updateHistoryButtons(canUndo:Boolean,canRedo:Boolean){findViewById<ImageButton>(R.id.btn_undo).apply{isEnabled=canUndo;alpha=if(canUndo)1f else .35f};findViewById<ImageButton>(R.id.btn_redo).apply{isEnabled=canRedo;alpha=if(canRedo)1f else .35f}}
    private fun syncOverlayAfterHistory(){canvas.postDelayed({current=current.copy(strokes=canvas.getStrokes().toMutableList(),texts=canvas.getTextBoxes().toMutableList());overlay.updateContent(current.texts,current.links,current.images,current.summaries,current.strokes)},80)}

    private fun showMergedExport(node:LibraryNode){
        saveCurrent()
        val all=library.loadNodes()
        val chapter=if(node.chapterMerge)all.firstOrNull{it.id==node.chapterSourceId&&it.type==LibraryNodeType.FOLDER}else node.takeIf{it.type==LibraryNodeType.FOLDER}
        if(chapter!=null){
            val files=chapterExportFiles(chapter,all)
            createMergedExport(files,"${chapter.name}-合并页")
            return
        }
        val chapters=all.filter{it.chapterSourceId==node.id&&!it.chapterMerge&&it.type==LibraryNodeType.FOLDER}.sortedBy{it.createdAt}
        val templates=ChapterTemplates.load(this)
        AlertDialog.Builder(this).setTitle("选择提取模板").setItems(templates.map{"${it.name}：${it.files.joinToString("、")}"}.toTypedArray()){_,index->
            val template=templates[index]
            AlertDialog.Builder(this).setTitle("选择要提取的文件").setItems(template.files.toTypedArray()){_,fileIndex->
                val name=template.files[fileIndex]
                val files=chapters.flatMap{chapter->chapterExportFiles(chapter,all).filter{it.name==name}}
                if(files.isEmpty())Toast.makeText(this,"没有找到名为“$name”的文件",Toast.LENGTH_SHORT).show() else createMergedExport(files,"${node.name}-$name-合并页")
            }.show()
        }.show()
    }
    private fun chapterExportFiles(chapter:LibraryNode,all:List<LibraryNode>):List<LibraryNode>{
        val visited=mutableSetOf<String>()
        fun collect(parent:String):List<LibraryNode>{
            if(!visited.add(parent))return emptyList()
            return all.filter{it.parentId==parent}.sortedBy{it.createdAt}.flatMap{if(it.type==LibraryNodeType.FOLDER)collect(it.id)else listOf(it)}
        }
        return collect(chapter.id)
    }
    private fun createMergedExport(files:List<LibraryNode>,fileName:String){
        if(files.isEmpty()){Toast.makeText(this,"章节中没有可合并的文件",Toast.LENGTH_SHORT).show();return}
        Toast.makeText(this,"正在生成合并页…",Toast.LENGTH_SHORT).show()
        lifecycleScope.launch{runCatching{withContext(Dispatchers.IO){
            val candidates=files.flatMap{node->mergeService.candidatesFor(node,if(node.noteType==FileType.PDF)(0 until library.pdfPageCount(node)).toList()else emptyList())}
            mergeService.create(candidates).also{library.rename(it.id,fileName)}
        }}.onSuccess{merged->refreshDirectory();pendingExportNode=merged;pendingExportPages=0..0;exportPdf.launch("$fileName.pdf")}.onFailure{Toast.makeText(this@MainActivity,"合并失败：${it.message}",Toast.LENGTH_LONG).show()}}
    }

    private var pendingExportNode:LibraryNode?=null
    private var pendingExportPages:IntRange=0..0
    private val exportPdf=registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")){uri->uri?.let{writeExport(it)}}
    private fun showExportSelection(){
        saveCurrent();val choices=library.loadNodes().filter{it.type==LibraryNodeType.NOTE}
        if(choices.isEmpty()){Toast.makeText(this,"没有可导出的笔记或 PDF",Toast.LENGTH_SHORT).show();return}
        LibraryTreePicker.show(this,"选择要导出的笔记或 PDF",library.loadNodes(),currentNode?.id){picked->
            val node=picked?:return@show;val total=printCoordinator.pageCount(node)
            if(node.noteType!=FileType.PDF){pendingExportNode=node;pendingExportPages=0..0;exportPdf.launch("${node.name}.pdf");return@show}
            val input=EditText(this).apply{hint="1-$total";inputType=android.text.InputType.TYPE_CLASS_TEXT}
            AlertDialog.Builder(this).setTitle("导出页码范围（1-$total）").setView(input).setNegativeButton("取消",null).setPositiveButton("导出"){_,_->
                val range=printCoordinator.parseRange(input.text.toString(),total)
                if(range==null)Toast.makeText(this,"页码格式或范围不正确",Toast.LENGTH_LONG).show() else {pendingExportNode=node;pendingExportPages=range;exportPdf.launch("${node.name}-${input.text.toString().ifBlank{"1-$total"}}.pdf")}
            }.show()
        }
    }
    private fun writeExport(uri:Uri){
        val node=pendingExportNode?:return
        val pages=pendingExportPages
        Toast.makeText(this,"正在高清导出…",Toast.LENGTH_SHORT).show()
        lifecycleScope.launch{
            runCatching{withContext(Dispatchers.IO){printCoordinator.exportPdf(node,pages,uri)}}
                .onSuccess{Toast.makeText(this@MainActivity,"导出完成",Toast.LENGTH_SHORT).show()}
                .onFailure{Toast.makeText(this@MainActivity,"导出失败：${it.message}",Toast.LENGTH_LONG).show()}
        }
    }

    private fun askName(title:String,done:(String)->Unit){val input=EditText(this);AlertDialog.Builder(this).setTitle(title).setView(input).setNegativeButton("取消",null).setPositiveButton("确定"){_,_->input.text.toString().trim().takeIf{it.isNotBlank()}?.let(done)}.show()}
    private fun showStorageFailure(error:Exception,startup:Boolean){
        AlertDialog.Builder(this).setTitle("资料读取或保存失败").setMessage(error.message?:"原始文件已保留，请从备份恢复。")
            .setPositiveButton("打开备份与恢复"){_,_->startActivity(Intent(this,SettingsActivity::class.java))}
            .setNegativeButton(if(startup)"退出" else "关闭"){_,_->if(startup)finish()}.setCancelable(!startup).show()
    }
    override fun dispatchTouchEvent(event:MotionEvent):Boolean {
        val handled=super.dispatchTouchEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_UP&&::splitNotes.isInitialized)splitNotes.syncTools()
        return handled
    }
    override fun onPause(){if(::splitNotes.isInitialized)splitNotes.onPause();if(::canvas.isInitialized)saveCurrent(true);val prefs=getSharedPreferences("pencil_session",MODE_PRIVATE);prefs.edit().putString("node_id",currentNode?.id).putInt("page",pdfPageIndex).apply();if(::canvas.isInitialized)canvas.onPause();super.onPause()}
    override fun onDestroy(){if(::splitNotes.isInitialized)splitNotes.onDestroy();aiCancellation?.cancel();noteWriter.shutdown();currentBackgroundBitmap?.takeIf{!it.isRecycled}?.recycle();if(::overlay.isInitialized)overlay.setPageBackground(null);super.onDestroy()}
    override fun onResume(){super.onResume();if(::splitNotes.isInitialized)splitNotes.onResume();val session=getSharedPreferences("pencil_session",MODE_PRIVATE);if(session.getBoolean("library_restored",false)){session.edit().remove("library_restored").apply();pageLoaded=false;recreate();return};if(::library.isInitialized)updateDirectoryDay();if(::canvas.isInitialized){canvas.setCutSelectionStrokes(getSharedPreferences("pencil_settings",MODE_PRIVATE).getBoolean("selection_cut_strokes",true));canvas.onResume()}}
}
