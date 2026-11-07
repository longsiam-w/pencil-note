package com.vibetools.pencil.canvas

import kotlin.math.abs

/** Split a polyline at polygon edges; each fragment keeps its original direction. */
object StrokeSelectionClipper {
    data class Point(val x:Double,val y:Double)
    data class Fragment(val points:List<Point>,val inside:Boolean)
    private const val EPS=1e-8
    fun contains(p:Point,polygon:List<Point>):Boolean {
        var result=false
        for(i in polygon.indices){
            val a=polygon[i];val b=polygon[(i+1)%polygon.size]
            val cross=(p.x-a.x)*(b.y-a.y)-(p.y-a.y)*(b.x-a.x)
            if(abs(cross)<EPS&&p.x>=minOf(a.x,b.x)-EPS&&p.x<=maxOf(a.x,b.x)+EPS&&p.y>=minOf(a.y,b.y)-EPS&&p.y<=maxOf(a.y,b.y)+EPS)return true
            if((a.y>p.y)!=(b.y>p.y)&&p.x<(b.x-a.x)*(p.y-a.y)/(b.y-a.y)+a.x)result=!result
        }
        return result
    }
    fun split(points:List<Point>,polygon:List<Point>):List<Fragment> {
        if(points.size<2||polygon.size<3)return emptyList()
        val result=mutableListOf<Fragment>()
        var active=mutableListOf<Point>()
        var state:Boolean?=null
        fun append(a:Point,b:Point,inside:Boolean){
            if(state!=inside){if(active.size>=2)result+=Fragment(active.toList(),state!!);active=mutableListOf(a);state=inside}
            if(active.isEmpty())active.add(a)
            active.add(b)
        }
        for(index in 0 until points.lastIndex){
            val a=points[index];val b=points[index+1];val dx=b.x-a.x;val dy=b.y-a.y
            if(abs(dx)+abs(dy)<EPS){append(a,b,contains(a,polygon));continue}
            val cuts=mutableListOf(0.0,1.0)
            for(i in polygon.indices){
                val c=polygon[i];val d=polygon[(i+1)%polygon.size];val ex=d.x-c.x;val ey=d.y-c.y
                val den=dx*ey-dy*ex;val cx=c.x-a.x;val cy=c.y-a.y
                if(abs(den)>EPS){val t=(cx*ey-cy*ex)/den;val u=(cx*dy-cy*dx)/den;if(t>EPS&&t<1-EPS&&u>=-EPS&&u<=1+EPS)cuts+=t}
                else if(abs(cx*dy-cy*dx)<EPS){for(p in listOf(c,d)){val t=if(abs(dx)>abs(dy))(p.x-a.x)/dx else (p.y-a.y)/dy;if(t>EPS&&t<1-EPS)cuts+=t}}
            }
            val sorted=cuts.sorted().fold(mutableListOf<Double>()){acc,t->if(acc.isEmpty()||abs(t-acc.last())>EPS)acc.add(t);acc}
            fun at(t:Double)=Point(a.x+dx*t,a.y+dy*t)
            for(i in 0 until sorted.lastIndex){val start=sorted[i];val end=sorted[i+1];append(at(start),at(end),contains(at((start+end)/2),polygon))}
        }
        if(active.size>=2)result+=Fragment(active.toList(),state!!)
        return result
    }
}
