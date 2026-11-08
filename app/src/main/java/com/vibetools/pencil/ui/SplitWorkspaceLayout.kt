package com.vibetools.pencil.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout

/** Routes a stream to one document, except the explicit selection-transfer gesture. */
class SplitWorkspaceLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    var splitEnabled = false
        set(value) { cancelGesture(); field=value;applyRatio() }
    var splitRatio = .5f
        private set
    val dividerX get() = SplitGeometry.divider(width,splitRatio)
    var onGestureStart: ((Int) -> Unit)? = null
    var onGestureFinished: ((Int) -> Unit)? = null
    var onGestureCancelled: ((Int) -> Unit)? = null
    var onDividerChanged: (() -> Unit)? = null
    var onTransferStart: ((Int,Float,Float) -> Boolean)? = null
    var onTransferMove: ((Float,Float) -> Unit)? = null
    var onTransferEnd: ((Boolean) -> Unit)? = null
    var drawTransfer: ((Canvas) -> Unit)? = null
    private val guard=SplitGestureGuard()
    private var lastEvent:MotionEvent?=null
    private var dividerPending=false
    private var dividerDragging=false
    private var dividerCancelled=false
    private var downX=0f
    private var downY=0f
    private var originalRatio=.5f
    private var transferring=false
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val activateDivider=Runnable {
        if(dividerPending&&!dividerCancelled){dividerDragging=true;performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);invalidate()}
    }
    init { orientation=HORIZONTAL;isMotionEventSplittingEnabled=false }
    private fun applyRatio(){
        if(childCount>=2){
            (getChildAt(0).layoutParams as LayoutParams).apply{weight=if(splitEnabled)splitRatio else 1f;getChildAt(0).layoutParams=this}
            (getChildAt(1).layoutParams as LayoutParams).apply{weight=1f-splitRatio;getChildAt(1).layoutParams=this}
        }
        requestLayout();invalidate();onDividerChanged?.invoke()
    }
    fun cancelGesture(){
        removeCallbacks(activateDivider)
        if(transferring){transferring=false;onTransferEnd?.invoke(false)}
        if(dividerDragging){splitRatio=originalRatio;applyRatio()}
        dividerPending=false;dividerDragging=false;dividerCancelled=false
        val owner=guard.owner
        lastEvent?.let{val cancel=MotionEvent.obtain(it).apply{action=MotionEvent.ACTION_CANCEL};super.dispatchTouchEvent(cancel);cancel.recycle()}
        if(owner!=null)onGestureCancelled?.invoke(owner)
        guard.reset();lastEvent?.recycle();lastEvent=null
        parent?.requestDisallowInterceptTouchEvent(false)
    }
    override fun dispatchTouchEvent(event:MotionEvent):Boolean {
        if(!splitEnabled)return super.dispatchTouchEvent(event)
        val action=event.actionMasked
        if(action==MotionEvent.ACTION_DOWN){
            cancelGesture();downX=event.x;downY=event.y
            parent?.requestDisallowInterceptTouchEvent(true)
            // Existing selection drags take precedence when started on the divider hit strip.
            val side=if(event.x<dividerX)0 else 1
            if(onTransferStart?.invoke(side,event.x,event.y)==true){transferring=true;onTransferMove?.invoke(event.x,event.y);return true}
            if(kotlin.math.abs(event.x-dividerX)<=12f*resources.displayMetrics.density){
                dividerPending=true;originalRatio=splitRatio;postDelayed(activateDivider,ViewConfiguration.getLongPressTimeout().toLong());return true
            }
            guard.begin(event.x,width,dividerX);guard.owner?.let{onGestureStart?.invoke(it)}
        }
        if(transferring){
            if(event.pointerCount>1||action==MotionEvent.ACTION_CANCEL){transferring=false;onTransferEnd?.invoke(false);invalidate()}
            else if(action==MotionEvent.ACTION_UP){onTransferMove?.invoke(event.x,event.y);transferring=false;onTransferEnd?.invoke(true);parent?.requestDisallowInterceptTouchEvent(false);invalidate()}
            else if(action==MotionEvent.ACTION_MOVE){onTransferMove?.invoke(event.x,event.y);invalidate()}
            return true
        }
        if(dividerPending){
            if(event.pointerCount>1){dividerCancelled=true;removeCallbacks(activateDivider);if(dividerDragging){splitRatio=originalRatio;applyRatio()};dividerDragging=false}
            if(action==MotionEvent.ACTION_MOVE&&!dividerCancelled){
                if(dividerDragging){splitRatio=SplitGeometry.ratio(event.x/width.coerceAtLeast(1));applyRatio()}
                else if(kotlin.math.hypot(event.x-downX,event.y-downY)>ViewConfiguration.get(context).scaledTouchSlop){dividerCancelled=true;removeCallbacks(activateDivider)}
            }
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){
                removeCallbacks(activateDivider)
                if(action==MotionEvent.ACTION_CANCEL&&dividerDragging){splitRatio=originalRatio;applyRatio()}
                dividerPending=false;dividerDragging=false;parent?.requestDisallowInterceptTouchEvent(false);invalidate()
            }
            return true
        }
        val xs=mutableListOf<Float>()
        for(pointer in 0 until event.pointerCount){xs+=event.getX(pointer);for(sample in 0 until event.historySize)xs+=event.getHistoricalX(pointer,sample)}
        if(!guard.accept(xs,width,dividerX)){
            if(lastEvent!=null){val cancel=MotionEvent.obtain(event).apply{this.action=MotionEvent.ACTION_CANCEL};super.dispatchTouchEvent(cancel);cancel.recycle();guard.owner?.let{onGestureCancelled?.invoke(it)};lastEvent?.recycle();lastEvent=null}
        }else{lastEvent?.recycle();lastEvent=MotionEvent.obtain(event);super.dispatchTouchEvent(event)}
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){
            if(action==MotionEvent.ACTION_CANCEL&&!guard.invalid)guard.owner?.let{onGestureCancelled?.invoke(it)}
            if(action==MotionEvent.ACTION_UP&&!guard.invalid)guard.owner?.let{onGestureFinished?.invoke(it)}
            guard.reset();lastEvent?.recycle();lastEvent=null;parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
    override fun dispatchDraw(canvas:Canvas){
        super.dispatchDraw(canvas)
        if(splitEnabled){
            paint.color=if(dividerDragging)Color.rgb(30,120,230)else Color.rgb(100,115,125);paint.strokeWidth=(if(dividerDragging)4f else 2f)*resources.displayMetrics.density
            canvas.drawLine(dividerX.toFloat(),0f,dividerX.toFloat(),height.toFloat(),paint)
            drawTransfer?.invoke(canvas)
        }
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){if(w!=oldw||h!=oldh)cancelGesture();super.onSizeChanged(w,h,oldw,oldh)}
}
