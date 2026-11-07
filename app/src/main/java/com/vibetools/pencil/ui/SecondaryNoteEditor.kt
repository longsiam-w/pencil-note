package com.vibetools.pencil.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.widget.*
import com.vibetools.pencil.canvas.CanvasGlView
import com.vibetools.pencil.model.*
import com.vibetools.pencil.storage.ClipboardStore
import com.vibetools.pencil.storage.LibraryStore
import java.util.UUID

/** Editing callbacks are bound to the secondary document, never the Activity's main document. */
class SecondaryNoteEditor(
    private val context: Context,
    private val canvas: CanvasGlView,
    private val library: LibraryStore,
    private val document: () -> NoteDocument?,
    private val changed: () -> Unit,
    private val pickImage: () -> Unit
) {
    private val clips = ClipboardStore(context)
    private var pendingImage: Pair<String, PointF>? = null
    private val movingImages = mutableSetOf<String>()
    private val movingLinks = mutableSetOf<String>()
    private val movingSummaries = mutableSetOf<String>()
    private val movingFolds = mutableSetOf<String>()
    private val movingStars = mutableSetOf<String>()
    private fun active(id: String) = document()?.takeIf { it.noteId == id }
    private fun selected(x: Float, y: Float) = canvas.selectionContains(x, y)
    private fun selected(image: CanvasImage) = canvas.selectionIntersects(RectF(image.x,image.y,image.x+image.width,image.y+image.height))

    fun insert(kind: String, x: Float, y: Float) {
        val doc = document() ?: return
        val id = doc.noteId
        val world = if (kind == "BOOKMARK") canvas.viewportCenterWorld() else canvas.screenToWorld(x,y)
        when (kind) {
            "IMAGE" -> { pendingImage = id to world; pickImage() }
            "EMPHASIS" -> {
                if (doc.emphasisStars.size >= 2) { toast("每页最多两个强调星星"); return }
                canvas.checkpoint(); doc.emphasisStars += EmphasisStar(x=world.x,y=world.y); changed()
            }
            "BOOKMARK" -> {
                val input = EditText(context).apply { hint="书签名称" }
                AlertDialog.Builder(context).setTitle("副笔记 · 插入书签").setView(input).setNegativeButton("取消",null).setPositiveButton("下一步") { _,_ ->
                    val name=input.text.toString().trim(); if(name.isBlank()) return@setPositiveButton
                    AlertDialog.Builder(context).setTitle("书签类型").setItems(arrayOf("永久书签","临时书签（本次运行有效）")) { _,which ->
                        active(id)?.let { canvas.checkpoint(); it.bookmarks += Bookmark(UUID.randomUUID().toString(),name,id,"page-1",world.x,world.y,temporary=which==1); changed() }
                    }.show()
                }.show()
            }
            "LINK" -> {
                AlertDialog.Builder(context).setTitle("副笔记 · 超链接").setItems(arrayOf("应用内文件/书签","编号标签","互联网链接")) { _,type ->
                    val input=EditText(context).apply { hint=if(type==2)"https://..." else "文件名或书签名"; if(type==1){setText(HyperlinkNumber.create(System.currentTimeMillis(),emptySet()));isEnabled=false} }
                    AlertDialog.Builder(context).setView(input).setNegativeButton("取消",null).setPositiveButton("保存") { _,_ ->
                        val target=active(id)?:return@setPositiveButton
                        val value=input.text.toString().trim(); if(value.isBlank())return@setPositiveButton
                        if(type==2&&!android.util.Patterns.WEB_URL.matcher(value).matches()){toast("输入格式不正确");return@setPositiveButton}
                        canvas.checkpoint();target.links += StoredLink(noteId=id,x=world.x,y=world.y,kind=when(type){0->"internal";1->"number";else->"external"},value=value); changed()
                    }.show()
                }.show()
            }
            else -> {
                val input=EditText(context).apply { hint="输入文本，可手动换行";minLines=4;gravity=Gravity.TOP;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
                val fontSize=canvas.currentTextFontSize()
                AlertDialog.Builder(context).setTitle("副笔记 · 插入文本").setView(input).setNegativeButton("取消",null).setPositiveButton("保存") { _,_ ->
                    if(active(id)==null)return@setPositiveButton
                    val value=input.text.toString().trim();if(value.isNotBlank()){canvas.addText(TextBox(UUID.randomUUID().toString(),value,world.x,world.y,fontSize));canvas.afterPendingChanges(changed)}
                }.show()
            }
        }
    }

    fun imageResult(uri: Uri?) {
        val pending=pendingImage;pendingImage=null
        if(uri==null||pending==null)return
        val doc=active(pending.first)?:return
        runCatching {
            val file=context.contentResolver.openInputStream(uri)!!.use{library.importAttachment(it,"secondary-image")}
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,bounds)
            val ratio=if(bounds.outWidth>0&&bounds.outHeight>0)bounds.outWidth.toFloat()/bounds.outHeight else 1.4f
            canvas.checkpoint(); doc.images+=CanvasImage(path=file.path,x=pending.second.x,y=pending.second.y,width=420f,height=420f/ratio);changed()
        }.onFailure{toast("图片导入失败：${it.message}")}
    }

    private fun clip(): CanvasClip? {
        val doc=document()?:return null
        return canvas.copySelection()?.copy(images=doc.images.filter{selected(it)}.map{it.copy()}.toMutableList(),links=doc.links.filter{selected(it.x,it.y)}.map{it.copy()}.toMutableList(),summaries=doc.summaries.filter{selected(it.x,it.y)}.map{it.copy()}.toMutableList())
    }
    private fun deleteExtras() {
        val doc=document()?:return
        doc.images.removeAll{selected(it)};doc.links.removeAll{selected(it.x,it.y)};doc.summaries.removeAll{selected(it.x,it.y)}
        doc.foldedNotes.removeAll{selected(it.x,it.y)};doc.emphasisStars.removeAll{selected(it.x,it.y)}
    }
    private fun lock() {
        val doc=document()?:return
        movingImages.clear();movingLinks.clear();movingSummaries.clear();movingFolds.clear();movingStars.clear()
        movingImages.addAll(doc.images.filter{selected(it)}.map{it.id});movingLinks.addAll(doc.links.filter{selected(it.x,it.y)}.map{it.id})
        movingSummaries.addAll(doc.summaries.filter{selected(it.x,it.y)}.map{it.id});movingFolds.addAll(doc.foldedNotes.filter{selected(it.x,it.y)}.map{it.id});movingStars.addAll(doc.emphasisStars.filter{selected(it.x,it.y)}.map{it.id})
    }
    fun move(dx: Float, dy: Float) {
        val doc=document()?:return
        doc.images.filter{it.id in movingImages}.forEach{it.x+=dx;it.y+=dy}
        doc.links.indices.filter{doc.links[it].id in movingLinks}.forEach{i->doc.links[i]=doc.links[i].copy(x=doc.links[i].x+dx,y=doc.links[i].y+dy)}
        doc.summaries.filter{it.id in movingSummaries}.forEach{it.x+=dx;it.y+=dy}
        doc.foldedNotes.indices.filter{doc.foldedNotes[it].id in movingFolds}.forEach{i->doc.foldedNotes[i]=doc.foldedNotes[i].shifted(dx,dy)}
        doc.emphasisStars.indices.filter{doc.emphasisStars[it].id in movingStars}.forEach{i->doc.emphasisStars[i]=doc.emphasisStars[i].copy(x=doc.emphasisStars[i].x+dx,y=doc.emphasisStars[i].y+dy)}
    }
    fun pasted(clip: CanvasClip, dx: Float, dy: Float) {
        val doc=document()?:return
        movingImages.clear();movingLinks.clear();movingSummaries.clear();movingFolds.clear();movingStars.clear()
        clip.images.forEach{val item=it.copy(id=UUID.randomUUID().toString(),x=it.x+dx,y=it.y+dy);doc.images+=item;movingImages+=item.id}
        clip.links.forEach{val item=it.copy(id=UUID.randomUUID().toString(),noteId=doc.noteId,x=it.x+dx,y=it.y+dy);doc.links+=item;movingLinks+=item.id}
        clip.summaries.forEach{val item=it.copy(id=UUID.randomUUID().toString(),noteId=doc.noteId,x=it.x+dx,y=it.y+dy);doc.summaries+=item;movingSummaries+=item.id}
    }
    fun moveConfirmation() {
        val id=document()?.noteId?:return
        AlertDialog.Builder(context).setTitle("副笔记 · 保存移动？").setPositiveButton("确认"){_,_->if(active(id)!=null){canvas.confirmSelectionMove();changed()}}.setNegativeButton("取消"){_,_->if(active(id)!=null){canvas.cancelSelectionMove();canvas.afterPendingChanges(changed)}}.setCancelable(false).show()
    }
    fun selectionActions() {
        val doc=document()?:return
        val id=doc.noteId
        val actions=arrayOf("拖动移动","删除","复制到剪贴板","剪切到剪贴板","缩放","折叠","取消折叠","取消")
        AlertDialog.Builder(context).setTitle("副笔记 · 框选操作").setItems(actions){_,which->
            if(active(id)==null)return@setItems
            when(which){
                0->{lock();canvas.beginSelectionMove()}
                1->{canvas.checkpoint();deleteExtras();canvas.deleteSelection(false);canvas.afterPendingChanges(changed)}
                2->{clip()?.let{clips.push(it)};canvas.clearSelection()}
                3->{clip()?.let{clips.push(it)};canvas.checkpoint();deleteExtras();canvas.deleteSelection(false);canvas.afterPendingChanges(changed)}
                4->{
                    val input=EditText(context).apply{hint="例如 0.5、1.5、2";inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL}
                    AlertDialog.Builder(context).setTitle("缩放选区").setView(input).setNegativeButton("取消",null).setPositiveButton("确定"){_,_->
                        if(active(id)==null)return@setPositiveButton
                        val factor=input.text.toString().toFloatOrNull()?.takeIf{it.isFinite()&&it in .1f..10f}?:return@setPositiveButton
                        val region=canvas.selectedRegion()?:return@setPositiveButton;val cx=(region.left+region.right)/2f;val cy=(region.top+region.bottom)/2f
                        val images=doc.images.filter{selected(it)}
                        val links=doc.links.filter{selected(it.x,it.y)}.map{it.id}.toSet()
                        val summaries=doc.summaries.filter{selected(it.x,it.y)}
                        canvas.scaleSelection(factor){changed()}
                        images.forEach{it.x=cx+(it.x-cx)*factor;it.y=cy+(it.y-cy)*factor;it.width*=factor;it.height*=factor}
                        doc.links.indices.filter{doc.links[it].id in links}.forEach{i->val item=doc.links[i];doc.links[i]=item.copy(x=cx+(item.x-cx)*factor,y=cy+(item.y-cy)*factor)}
                        summaries.forEach{it.x=cx+(it.x-cx)*factor;it.y=cy+(it.y-cy)*factor}
                    }.show()
                }
                5->{val region=canvas.selectedRegion()?:return@setItems;val clip=clip()?:return@setItems;canvas.checkpoint();doc.foldedNotes+=FoldedNote(x=region.left,y=region.top,strokes=clip.strokes,texts=clip.texts,images=clip.images,links=clip.links,summaries=clip.summaries);deleteExtrasExceptFold(doc.foldedNotes.last().id);canvas.deleteSelection(false);canvas.afterPendingChanges(changed)}
                6->{val folds=doc.foldedNotes.filter{selected(it.x,it.y)};canvas.checkpoint();folds.forEach{fold->doc.foldedNotes.removeAll{it.id==fold.id};doc.images.addAll(fold.images);doc.links.addAll(fold.links);doc.summaries.addAll(fold.summaries);canvas.restoreFoldedContent(fold.strokes,fold.texts,changed)}}
                else->canvas.clearSelection()
            }
        }.show()
    }
    private fun deleteExtrasExceptFold(id: String) {
        val doc=document()?:return
        val keep=doc.foldedNotes.first{it.id==id};doc.foldedNotes.remove(keep);deleteExtras();doc.foldedNotes+=keep
    }
    fun pasteLatest() { clips.all().firstOrNull()?.let{canvas.setMode(CanvasGlView.Mode.SELECT);canvas.pasteCentered(it)}?:toast("剪贴板为空") }
    private fun toast(message:String) = Toast.makeText(context,message,Toast.LENGTH_SHORT).show()
}
