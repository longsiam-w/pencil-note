package com.vibetools.pencil.ui

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.lifecycle.LifecycleCoroutineScope
import com.vibetools.pencil.canvas.CanvasGlView
import com.vibetools.pencil.canvas.NoteOverlayView
import com.vibetools.pencil.model.*
import com.vibetools.pencil.storage.LibraryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Separate editable document and history, configured by the shared bottom toolbar. */
class SplitNoteController(
    private val activity: MainActivity,
    private val workspace: SplitWorkspaceLayout,
    private val library: LibraryStore,
    private val scope: LifecycleCoroutineScope,
    private val mainId: () -> String?,
    private val renderPdf: (Uri, Int) -> Pair<Bitmap, Int>?,
    private val mainCanvas: () -> CanvasGlView,
    private val insertKind: () -> String,
    private val pickImage: () -> Unit,
    private val historyChanged: (Boolean, Boolean) -> Unit
) {
    val pane = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(activity.getColor(com.vibetools.pencil.R.color.canvas_background))
        setPadding(1, (72 * resources.displayMetrics.density).toInt(), 0, 0)
    }
    private val header = Button(activity).apply { text = "选择副笔记"; setOnClickListener { choose() } }
    private val frame = FrameLayout(activity)
    private val canvas = CanvasGlView(activity)
    private val overlay = NoteOverlayView(activity)
    private val placeholder = TextView(activity).apply {
        text = "轻触选择副笔记"; textSize = 18f; gravity = Gravity.CENTER
        setOnClickListener { choose() }
    }
    private val previous = Button(activity).apply { text = "上一页"; setOnClickListener { load(page - 1) } }
    private val next = Button(activity).apply { text = "下一页"; setOnClickListener { load(page + 1) } }
    private val pageLabel = TextView(activity).apply { gravity = Gravity.CENTER }
    private val navigation = LinearLayout(activity).apply {
        addView(previous, LinearLayout.LayoutParams(0, -2, 1f))
        addView(pageLabel, LinearLayout.LayoutParams(0, -1, 1f))
        addView(next, LinearLayout.LayoutParams(0, -2, 1f))
    }
    private var document: NoteDocument? = null
    private var loaded = false
    private var dirty = false
    private var savingAllowed = true
    private var loadJob: kotlinx.coroutines.Job? = null
    private val editor = SecondaryNoteEditor(activity, canvas, library, {document.takeIf{loaded}}, {refreshOverlay(); dirty=true; save()}, pickImage)
    private val teleports = TeleportController(activity,canvas,library,{document ?: NoteDocument("")},{x,y->canvas.screenToWorld(x,y)},{canvas.currentZoom()},{canvas.checkpoint()},{refreshOverlay();dirty=true}){target,doc,link,zoom->
        if(target.id==mainId())return@TeleportController
        if(!save())return@TeleportController
        node=target;pageCount=0
        page=Regex("__page_(\\d+)$").find(doc.noteId)?.groupValues?.get(1)?.toIntOrNull()?:0
        load(page,Triple(link.x,link.y,zoom))
    }
    private var touchedFold: String? = null
    private var touchedStar: String? = null
    private var objectLast = android.graphics.PointF()
    private var objectDown = android.graphics.PointF()
    private var objectMoved = false
    private var objectCancelled = false
    private var node: LibraryNode? = null
    private var page = 0
    private var pageCount = 0
    private var request = 0
    private var background: Bitmap? = null
    var enabled = false
        private set

    init {
        pane.addView(header, LinearLayout.LayoutParams(-1, -2))
        pane.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        pane.addView(navigation, LinearLayout.LayoutParams(-1, -2))
        frame.addView(canvas, FrameLayout.LayoutParams(-1, -1))
        frame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        frame.addView(placeholder, FrameLayout.LayoutParams(-1, -1))
        canvas.setContentChangedListener { strokes,texts ->
            document?.takeIf{loaded}?.let { document=it.copy(strokes=strokes.toMutableList(),texts=texts.toMutableList());dirty=true;refreshOverlay() }
        }
        canvas.setHistoryListener(historyChanged)
        canvas.setSelectionChangedListener { overlay.setSelectionPath(it) }
        canvas.setSelectionFinishedListener { editor.selectionActions() }
        canvas.setTextRequestedListener { _, marker -> if(marker<0)editor.moveConfirmation()else editor.selectionActions() }
        canvas.setSelectionMoveListener { dx,dy -> editor.move(dx,dy);dirty=true;refreshOverlay() }
        canvas.setPasteOffsetListener { clip,dx,dy -> editor.pasted(clip,dx,dy);dirty=true;refreshOverlay() }
        canvas.setInsertRequestedListener { x,y -> if(insertKind()=="TELEPORT")teleports.insert(canvas.screenToWorld(x,y))else editor.insert(insertKind(),x,y) }
        canvas.setObjectTouchListener { event -> teleports.touch(event)||objectTouch(event) }
        canvas.setExtrasHistory({snapshotExtras()}) { extras ->
            document?.let { document=it.copy(images=extras.images,links=extras.links,summaries=extras.summaries,bookmarks=extras.bookmarks,foldedNotes=extras.foldedNotes,emphasisStars=extras.emphasisStars);dirty=true;refreshOverlay() }
        }
        canvas.setBackgroundHistory({document?.backgroundHidden?:false}) { hidden -> document?.backgroundHidden=hidden;dirty=true;overlay.setPageBackground(if(hidden)null else background);canvas.setPageBackground(if(hidden)null else background) }
        canvas.setMode(CanvasGlView.Mode.DRAG)
        canvas.setTransformListener { x, y, z -> overlay.updateTransform(x, y, z) }
        pane.visibility = View.GONE
        clear()
    }

    fun setEnabled(value: Boolean): Boolean {
        workspace.cancelGesture()
        if(!value && !save())return false
        enabled = value
        workspace.splitEnabled = value
        pane.visibility = if (value) View.VISIBLE else View.GONE
        if (!value) { save(); clear() }
        syncTools()
        workspace.invalidate()
        return true
    }

    fun onMainChanged() {
        if (SplitNoteIdentity.secondaryAfterMainChange(mainId(), node?.id) == null && node != null) { save(); clear() }
        else if (node != null && library.loadNodes().none { it.id == node?.id }) { dirty=false; clear() }
    }

    /** Shared by the secondary title picker and shortcut routing; never changes the main document. */
    fun openFromQuickAccess(selected:LibraryNode, requestedPage:Int?):Boolean {
        if(!enabled||selected.type!=LibraryNodeType.NOTE)return false
        if(selected.id==mainId()){
            Toast.makeText(activity,"该笔记已在主笔记中打开，不能同时作为副笔记",Toast.LENGTH_SHORT).show()
            return false
        }
        workspace.cancelGesture()
        if(!save())return false
        node=selected;pageCount=0
        page=if(selected.noteType==FileType.PDF)(requestedPage?:activity.getSharedPreferences("pencil_file_pages",0).getInt(selected.id,0)).coerceAtLeast(0) else 0
        load(page)
        return true
    }

    private fun choose() {
        LibraryTreePicker.show(activity, "选择副笔记（可读写）", library.loadNodes(), node?.id,
            selectable = { it.type == LibraryNodeType.NOTE && it.id != mainId() }) { selected ->
            if (!enabled || selected == null || selected.id == mainId()) return@show
            openFromQuickAccess(selected,null)
        }
    }

    private fun clearBackground() {
        overlay.setPageBackground(null); canvas.setPageBackground(null)
        // GL may still be consuming the old bitmap; drop ownership without recycling it here.
        background = null
    }

    private fun clear() {
        loadJob?.cancel();loadJob=null;loaded=false;dirty=false;document=null
        request++; node = null; page = 0; pageCount = 0
        clearBackground()
        canvas.setDocument(emptyList(), emptyList())
        overlay.updateContent(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        overlay.setFoldedNotes(emptyList()); overlay.setEmphasisStars(emptyList())
        placeholder.text = "轻触选择副笔记"; placeholder.visibility = View.VISIBLE
        header.text = "选择副笔记"; navigation.visibility = View.GONE
    }

    private fun load(targetPage: Int, targetView: Triple<Float,Float,Float>? = null) {
        val selected = node ?: return
        workspace.cancelGesture()
        if(!save())return
        loaded=false;dirty=false;document=null
        loadJob?.cancel()
        val token = ++request
        page = targetPage.coerceIn(0, (pageCount - 1).coerceAtLeast(page.coerceAtLeast(0)))
        val requestedPage = page
        clearBackground()
        canvas.setDocument(emptyList(), emptyList())
        overlay.updateContent(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        overlay.setFoldedNotes(emptyList()); overlay.setEmphasisStars(emptyList())
        placeholder.text = "正在加载副笔记…"; placeholder.visibility = View.VISIBLE
        header.text = "${selected.name} · 点击更换"
        loadJob=scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val uri = selected.sourceUri?.let(Uri::parse)
                    val rendered = if (selected.noteType == FileType.PDF && uri != null) renderPdf(uri, requestedPage) else null
                    val safePage = if (rendered != null) requestedPage.coerceIn(0, (rendered.second - 1).coerceAtLeast(0)) else requestedPage
                    val id = if (selected.noteType == FileType.PDF) "${selected.id}__page_$safePage" else selected.id
                    val document = library.loadNote(id)
                    val bitmap = if (document.backgroundHidden) { rendered?.first?.recycle(); null }
                        else if (selected.noteType == FileType.IMAGE && uri != null) activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                        else rendered?.first
                    Triple(document, bitmap, rendered?.second ?: 0)
                }
                if (!enabled || token != request || selected.id == mainId()) { result.second?.recycle(); return@launch }
                val document = result.first
                this@SplitNoteController.document=document
                loaded=true;dirty=false
                pageCount = result.third
                if (pageCount > 0) page = page.coerceIn(0, pageCount - 1)
                canvas.setDocument(document.strokes, document.texts)
                overlay.updateContent(document.texts, document.links, document.images, document.summaries, document.strokes)
                overlay.setFoldedNotes(document.foldedNotes); overlay.setEmphasisStars(document.emphasisStars)
                background = result.second
                overlay.setPageBackground(background); canvas.setPageBackground(background, forceFit = true)
                if (background == null) {
                    val bounds = com.vibetools.pencil.util.DocumentRasterizer.contentBounds(document)
                    canvas.post { canvas.restoreView(bounds.centerX(), bounds.centerY(), minOf(canvas.width / bounds.width(), canvas.height / bounds.height()).coerceIn(.1f, 2f)) }
                }
                navigation.visibility = if (selected.noteType == FileType.PDF) View.VISIBLE else View.GONE
                pageLabel.text = "${page + 1} / $pageCount"
                previous.isEnabled = page > 0; next.isEnabled = page + 1 < pageCount
                placeholder.visibility = View.GONE
                syncTools()
                targetView?.let{view->canvas.restoreView(view.first,view.second,view.third)}
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (token == request) { clear(); Toast.makeText(activity, "副笔记加载失败：${error.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun refreshOverlay() {
        val doc=document?:return
        overlay.updateContent(doc.texts,doc.links,doc.images,doc.summaries,doc.strokes)
        overlay.setFoldedNotes(doc.foldedNotes);overlay.setEmphasisStars(doc.emphasisStars)
    }
    private fun snapshotExtras(): DocumentExtras {
        val doc=document?:NoteDocument("")
        return DocumentExtras(doc.images.map{it.copy()}.toMutableList(),doc.links.map{it.copy()}.toMutableList(),doc.summaries.map{it.copy()}.toMutableList(),doc.bookmarks.map{it.copy()}.toMutableList(),doc.foldedNotes.map{it.deepCopy()}.toMutableList(),doc.emphasisStars.map{it.copy()}.toMutableList())
    }
    fun save(): Boolean {
        if(!loaded||!dirty||!savingAllowed)return true
        val doc=document?:return true
        return try {
            library.saveNote(doc.copy(strokes=canvas.getStrokes().toMutableList(),texts=canvas.getTextBoxes().toMutableList()))
            dirty=false;true
        } catch(error:Exception) { Toast.makeText(activity,"副笔记保存失败："+error.message,Toast.LENGTH_LONG).show();false }
    }
    fun syncTools() { canvas.copyToolsFrom(mainCanvas()) }
    fun transferCanvas() = canvas
    fun transferDocument():NoteDocument? = document?.takeIf{isReady()}?.copy(strokes=canvas.getStrokes().toMutableList(),texts=canvas.getTextBoxes().toMutableList())
    fun transferContent() = editor.transferContent()
    fun applyTransferredDocument(value:NoteDocument) { document=value;canvas.replaceTransferredContent(value.strokes,value.texts);dirty=false;refreshOverlay() }
    fun isReady() = enabled && loaded
    fun undo() { canvas.undo();canvas.afterPendingChanges{save()} }
    fun redo() { canvas.redo();canvas.afterPendingChanges{save()} }
    fun canUndo() = canvas.canUndo()
    fun canRedo() = canvas.canRedo()
    fun pasteLatest() = editor.pasteLatest()
    fun imageResult(uri:Uri?) = editor.imageResult(uri)
    private fun objectTouch(event:android.view.MotionEvent):Boolean {
        val doc=document?:return false
        if(event.actionMasked==android.view.MotionEvent.ACTION_DOWN){
            val point=canvas.screenToWorld(event.x,event.y)
            touchedFold=doc.foldedNotes.lastOrNull{FoldedNoteLabel.bounds(it).contains(point.x,point.y)}?.id
            touchedStar=if(touchedFold==null)doc.emphasisStars.lastOrNull{EmphasisStarDrawing.bounds(it).contains(point.x,point.y)}?.id else null
            if(touchedFold==null&&touchedStar==null)return false
            objectDown.set(event.x,event.y);objectLast.set(event.x,event.y);objectMoved=false;objectCancelled=false
            return true
        }
        if(touchedFold==null&&touchedStar==null)return false
        if(event.pointerCount>1)objectCancelled=true
        when(event.actionMasked){
            android.view.MotionEvent.ACTION_MOVE -> if(!objectCancelled){
                if(!objectMoved&&kotlin.math.hypot(event.x-objectDown.x,event.y-objectDown.y)>android.view.ViewConfiguration.get(activity).scaledTouchSlop){canvas.checkpoint();objectMoved=true}
                if(objectMoved){
                    val dx=(event.x-objectLast.x)/canvas.currentZoom();val dy=(event.y-objectLast.y)/canvas.currentZoom()
                    doc.foldedNotes.indexOfFirst{it.id==touchedFold}.takeIf{it>=0}?.let{i->doc.foldedNotes[i]=doc.foldedNotes[i].shifted(dx,dy)}
                    doc.emphasisStars.indexOfFirst{it.id==touchedStar}.takeIf{it>=0}?.let{i->doc.emphasisStars[i]=doc.emphasisStars[i].copy(x=doc.emphasisStars[i].x+dx,y=doc.emphasisStars[i].y+dy)}
                    dirty=true;refreshOverlay()
                }
                objectLast.set(event.x,event.y)
            }
            android.view.MotionEvent.ACTION_UP,android.view.MotionEvent.ACTION_CANCEL -> {
                val fold=touchedFold;val star=touchedStar;touchedFold=null;touchedStar=null
                if(event.actionMasked==android.view.MotionEvent.ACTION_UP&&!objectCancelled&&!objectMoved){
                    if(fold!=null){canvas.checkpoint();doc.foldedNotes.indexOfFirst{it.id==fold}.takeIf{it>=0}?.let{i->doc.foldedNotes[i]=doc.foldedNotes[i].copy(expanded=!doc.foldedNotes[i].expanded)};dirty=true;refreshOverlay()}
                    if(star!=null){val id=doc.noteId;AlertDialog.Builder(activity).setTitle("副笔记 · 强调星星").setItems(arrayOf("调整大小","删除")){_,which->
                        if(document?.noteId!=id)return@setItems
                        if(which==1){canvas.checkpoint();doc.emphasisStars.removeAll{it.id==star};dirty=true;refreshOverlay();save()}
                        else {val slider=SeekBar(activity).apply{max=216;progress=((doc.emphasisStars.firstOrNull{it.id==star}?.size?:48f)-24f).toInt().coerceIn(0,216)};AlertDialog.Builder(activity).setTitle("星星大小").setView(slider).setNegativeButton("取消",null).setPositiveButton("保存"){_,_->if(document?.noteId==id){canvas.checkpoint();doc.emphasisStars.indexOfFirst{it.id==star}.takeIf{it>=0}?.let{i->doc.emphasisStars[i]=doc.emphasisStars[i].copy(size=24f+slider.progress)};dirty=true;refreshOverlay();save()}}.show()}
                    }.show()}
                }
            }
        }
        return true
    }
    fun beginGesture() { syncTools();canvas.beginSplitGesture() }
    fun finishGesture() { canvas.afterPendingChanges { save() } }
    fun cancelGesture() { canvas.cancelSplitGesture();save() }
    fun onResume() { savingAllowed=!activity.getSharedPreferences("pencil_session",0).getBoolean("library_restored",false);canvas.onResume() }
    fun onPause() { workspace.cancelGesture();savingAllowed=!activity.getSharedPreferences("pencil_session",0).getBoolean("library_restored",false);if(savingAllowed)save();canvas.onPause() }
    fun onDestroy() { request++;loadJob?.cancel();overlay.setPageBackground(null); background = null }
}
