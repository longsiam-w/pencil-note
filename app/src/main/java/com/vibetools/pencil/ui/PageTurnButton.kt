package com.vibetools.pencil.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.content.ContextCompat
import com.vibetools.pencil.R

/** Draw around the view centre, independent of font metrics and image padding. */
class PageTurnButton @JvmOverloads constructor(context:Context,attrs:AttributeSet?=null):AppCompatImageButton(context,attrs){
    private val arrowPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{
        color=ContextCompat.getColor(context,R.color.ink_primary)
        style=Paint.Style.STROKE
        strokeCap=Paint.Cap.ROUND
        strokeJoin=Paint.Join.ROUND
        strokeWidth=4f*resources.displayMetrics.density
    }
    private val arrow=Path()
    init {
        setImageDrawable(null)
        backgroundTintList=null
        supportBackgroundTintList=null
        setPadding(0,0,0,0)
    }
    override fun onDraw(canvas:Canvas){
        val density=resources.displayMetrics.density
        val cx=width/2f
        val cy=height/2f
        val halfWidth=9f*density
        val halfHeight=18f*density
        val direction=if(id==R.id.pdf_previous)-1f else 1f
        arrow.reset()
        arrow.moveTo(cx-direction*halfWidth,cy-halfHeight)
        arrow.lineTo(cx+direction*halfWidth,cy)
        arrow.lineTo(cx-direction*halfWidth,cy+halfHeight)
        arrowPaint.alpha=if(isEnabled)255 else 90
        canvas.drawPath(arrow,arrowPaint)
    }
}
