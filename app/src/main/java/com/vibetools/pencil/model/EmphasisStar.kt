package com.vibetools.pencil.model

import android.graphics.*
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

data class EmphasisStar(val id:String=UUID.randomUUID().toString(),val x:Float,val y:Float,val size:Float=64f)

object EmphasisStarDrawing {
    fun bounds(star:EmphasisStar)=RectF(star.x,star.y,star.x+star.size,star.y+star.size)
    fun draw(canvas:Canvas,star:EmphasisStar,hue:Float=45f){
        val path=Path();val radius=star.size/2f
        for(i in 0 until 10){val angle=Math.PI*i/5-Math.PI/2;val r=if(i%2==0)radius else radius*.44f;val x=star.x+radius+(cos(angle)*r).toFloat();val y=star.y+radius+(sin(angle)*r).toFloat();if(i==0)path.moveTo(x,y)else path.lineTo(x,y)}
        path.close()
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.HSVToColor(floatArrayOf(hue,.8f,1f))}
        canvas.drawPath(path,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=star.size*.035f;paint.color=Color.HSVToColor(floatArrayOf(hue,.9f,.65f));canvas.drawPath(path,paint)
    }
}
