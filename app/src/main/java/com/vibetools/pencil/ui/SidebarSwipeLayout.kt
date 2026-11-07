package com.vibetools.pencil.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

class SidebarSwipeLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    var onSwipe: ((Boolean) -> Unit)? = null
    private var startX = 0f
    private var startY = 0f
    private var swiping = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    // Reserve the gesture before DrawerLayout can start dragging the open drawer.
    override fun dispatchTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN)parent?.requestDisallowInterceptTouchEvent(true)
        val handled=super.dispatchTouchEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_UP||event.actionMasked==MotionEvent.ACTION_CANCEL)parent?.requestDisallowInterceptTouchEvent(false)
        return handled
    }
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startX=event.x; startY=event.y; swiping=false;parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> {
                val dx=event.x-startX
                val dy=event.y-startY
                if(abs(dx)>slop*2 && abs(dx)>abs(dy)*1.5f) {
                    swiping=true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(event)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(!swiping)return super.onTouchEvent(event)
        when(event.actionMasked) {
            MotionEvent.ACTION_UP -> { if(abs(event.x-startX)>48*resources.displayMetrics.density)onSwipe?.invoke(event.x>startX);swiping=false;parent?.requestDisallowInterceptTouchEvent(false) }
            MotionEvent.ACTION_CANCEL -> { swiping=false;parent?.requestDisallowInterceptTouchEvent(false) }
        }
        return true
    }
}
