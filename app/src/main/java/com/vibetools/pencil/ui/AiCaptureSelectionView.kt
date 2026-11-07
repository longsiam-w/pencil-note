package com.vibetools.pencil.ui

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

/** Select a crop within the captured WebView viewport, including rendered formulas. */
class AiCaptureSelectionView(context:Context,private val bitmap:Bitmap):View(context){
    private val display=RectF();private var selected:RectF?=null;private var start=PointF()
    override fun onDraw(canvas:Canvas){
        val scale=minOf(width.toFloat()/bitmap.width,height.toFloat()/bitmap.height)
        val w=bitmap.width*scale;val h=bitmap.height*scale
        display.set((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2)
        canvas.drawBitmap(bitmap,null,display,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        selected?.let{canvas.drawRect(it,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLUE;style=Paint.Style.STROKE;strokeWidth=3f})}
    }
    override fun onTouchEvent(event:MotionEvent):Boolean{
        val x=event.x.coerceIn(display.left,display.right);val y=event.y.coerceIn(display.top,display.bottom)
        when(event.actionMasked){MotionEvent.ACTION_DOWN->{start=PointF(x,y);parent?.requestDisallowInterceptTouchEvent(true)}
            MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP->{selected=RectF(minOf(x,start.x),minOf(y,start.y),maxOf(x,start.x),maxOf(y,start.y));invalidate()}}
        return true
    }
    fun crop():Bitmap{
        val r=selected?.takeIf{it.width()>8f&&it.height()>8f}?:display
        val x=((r.left-display.left)/display.width()*bitmap.width).toInt().coerceIn(0,bitmap.width-1)
        val y=((r.top-display.top)/display.height()*bitmap.height).toInt().coerceIn(0,bitmap.height-1)
        val w=(r.width()/display.width()*bitmap.width).toInt().coerceIn(1,bitmap.width-x)
        val h=(r.height()/display.height()*bitmap.height).toInt().coerceIn(1,bitmap.height-y)
        return Bitmap.createBitmap(bitmap,x,y,w,h)
    }
}
