package com.vibetools.pencil.print

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.vibetools.pencil.model.*
import com.vibetools.pencil.storage.LibraryStore
import com.vibetools.pencil.ui.PrintablePage
import com.vibetools.pencil.util.DocumentRasterizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PrintCoordinator(private val context:Context,private val library:LibraryStore){
    fun parseRange(value:String,total:Int):IntRange?{if(total<=0)return null;val m=Regex("^(\\d+)(?:-(\\d+))?$").matchEntire(value.trim().ifBlank{"1-$total"})?:return null;val a=m.groupValues[1].toIntOrNull()?.minus(1)?:return null;val b=m.groupValues[2].toIntOrNull()?.minus(1)?:a;return if(a in 0 until total&&b in a until total)a..b else null}
    fun pageCount(node:LibraryNode)=if(node.noteType==FileType.PDF)library.pdfPageCount(node) else 1
    suspend fun print(node:LibraryNode,pages:IntRange){exportPdf(node,pages,context.contentResolver.insert(android.provider.MediaStore.Files.getContentUri("external"),android.content.ContentValues().apply{put(android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME,"print-${node.name}.pdf");put(android.provider.MediaStore.Files.FileColumns.MIME_TYPE,"application/pdf")})?:return)}
    suspend fun render(node:LibraryNode,pages:IntRange):List<PrintablePage> = withContext(Dispatchers.IO){pages.map{page->val note=library.loadNote(if(node.noteType==FileType.PDF)"${node.id}__page_$page" else node.id);val bg=loadBackground(node,page);val r=if(bg!=null)RectF(0f,0f,bg.width.toFloat(),bg.height.toFloat())else DocumentRasterizer.contentBounds(note);val scale=if(bg!=null)1f else 2f;PrintablePage(DocumentRasterizer.render(note,r,(r.width()*scale).toInt().coerceAtLeast(1),(r.height()*scale).toInt().coerceAtLeast(1),bg,true)).also{bg?.recycle()}}}
    suspend fun exportPdf(node:LibraryNode,pages:IntRange,uri:Uri)=withContext(Dispatchers.IO){
        context.contentResolver.openOutputStream(uri)!!.use{out->val document=PdfDocument();pages.forEachIndexed{idx,pageNo->
            val note=library.loadNote(if(node.noteType==FileType.PDF)"${node.id}__page_$pageNo" else node.id);val bg=loadBackground(node,pageNo);val region=if(bg!=null)RectF(0f,0f,bg.width.toFloat(),bg.height.toFloat())else DocumentRasterizer.contentBounds(note);val width=region.width().toInt().coerceIn(1,12000);val height=region.height().toInt().coerceIn(1,16000);val info=PdfDocument.PageInfo.Builder(width,height,idx+1).create();val page=document.startPage(info);val c=page.canvas;c.drawColor(Color.WHITE);if(bg!=null)c.drawBitmap(bg,null,RectF(0f,0f,width.toFloat(),height.toFloat()),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG));val sx=width/region.width().coerceAtLeast(.001f);val sy=height/region.height().coerceAtLeast(.001f);c.save();c.scale(sx,sy);c.translate(-region.left,-region.top);DocumentRasterizer.drawContent(c,note,region);c.restore();document.finishPage(page);bg?.recycle()};document.writeTo(out);document.close()}
    }
    private fun loadBackground(node:LibraryNode,page:Int):Bitmap?{val uri=node.sourceUri?.let(Uri::parse)?:return null;return if(node.noteType==FileType.PDF)runCatching{context.contentResolver.openFileDescriptor(uri,"r")!!.use{fd->PdfRenderer(fd).use{renderer->renderer.openPage(page.coerceIn(0,renderer.pageCount-1)).use{p->val scale=4f;Bitmap.createBitmap((p.width*scale).toInt(),(p.height*scale).toInt(),Bitmap.Config.ARGB_8888).also{it.eraseColor(Color.WHITE);p.render(it,null,Matrix().apply{setScale(scale,scale)},PdfRenderer.Page.RENDER_MODE_FOR_PRINT)}}}}}.getOrNull() else null}
}
