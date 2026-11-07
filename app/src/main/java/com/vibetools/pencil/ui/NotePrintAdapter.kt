package com.vibetools.pencil.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import java.io.FileOutputStream

data class PrintablePage(val bitmap: Bitmap)

class NotePrintAdapter(private val pages: List<PrintablePage>,private val onFinished:()->Unit={}) : PrintDocumentAdapter() {
    override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes, signal: CancellationSignal?, callback: LayoutResultCallback, extras: Bundle?) {
        if(signal?.isCanceled==true)callback.onLayoutCancelled() else callback.onLayoutFinished(PrintDocumentInfo.Builder("pencil-note.pdf").setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(pages.size).build(),true)
    }

    override fun onWrite(ranges:Array<out PageRange>,destination:ParcelFileDescriptor,signal:CancellationSignal,callback:WriteResultCallback){
        runCatching{val document=PdfDocument();pages.forEachIndexed{index,item->if(signal.isCanceled)return@forEachIndexed;val info=PdfDocument.PageInfo.Builder(1600,1200,index+1).create();val page=document.startPage(info);page.canvas.drawColor(Color.WHITE);val src=Rect(0,0,item.bitmap.width,item.bitmap.height);val ratio=minOf(1600f/item.bitmap.width,1200f/item.bitmap.height);val w=(item.bitmap.width*ratio).toInt();val h=(item.bitmap.height*ratio).toInt();val left=(1600-w)/2;val top=(1200-h)/2;page.canvas.drawBitmap(item.bitmap,src,Rect(left,top,left+w,top+h),null);document.finishPage(page)};FileOutputStream(destination.fileDescriptor).use{document.writeTo(it)};document.close();callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))}.onFailure{callback.onWriteFailed(it.message)}.also{onFinished()}
    }
}
