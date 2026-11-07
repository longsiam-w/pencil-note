package com.vibetools.pencil.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout

/** Prevents Android's split-pointer dispatch from sending one gesture to both documents. */
class SplitWorkspaceLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    var splitEnabled = false
        set(value) { cancelGesture(); field = value }
    var onGestureStart: ((Int) -> Unit)? = null
    var onGestureFinished: ((Int) -> Unit)? = null
    var onGestureCancelled: ((Int) -> Unit)? = null
    private val guard = SplitGestureGuard()
    private var lastEvent: MotionEvent? = null

    init { orientation = HORIZONTAL; isMotionEventSplittingEnabled = false }

    fun cancelGesture() {
        val owner = guard.owner
        lastEvent?.let { previous ->
            val cancel = MotionEvent.obtain(previous).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
        }
        if (owner != null) onGestureCancelled?.invoke(owner)
        guard.reset()
        lastEvent?.recycle(); lastEvent = null
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!splitEnabled) return super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancelGesture()
            guard.begin(event.x, width)
            guard.owner?.let { onGestureStart?.invoke(it) }
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        val xs = mutableListOf<Float>()
        for (pointer in 0 until event.pointerCount) {
            xs += event.getX(pointer)
            for (sample in 0 until event.historySize) xs += event.getHistoricalX(pointer, sample)
        }
        if (!guard.accept(xs, width)) {
            if (lastEvent != null) {
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel); cancel.recycle()
                guard.owner?.let { onGestureCancelled?.invoke(it) }
                lastEvent?.recycle(); lastEvent = null
            }
        } else {
            lastEvent?.recycle(); lastEvent = MotionEvent.obtain(event)
            super.dispatchTouchEvent(event)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (event.actionMasked == MotionEvent.ACTION_CANCEL && !guard.invalid) guard.owner?.let { onGestureCancelled?.invoke(it) }
            if(event.actionMasked==MotionEvent.ACTION_UP&&!guard.invalid)guard.owner?.let{onGestureFinished?.invoke(it)}
            guard.reset(); lastEvent?.recycle(); lastEvent = null
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        if (splitEnabled) {
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.rgb(100, 115, 125)
                strokeWidth = 2f * resources.displayMetrics.density
            }
            val middle = (width / 2).toFloat()
            canvas.drawLine(middle, 0f, middle, height.toFloat(), paint)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w != oldw || h != oldh) cancelGesture()
        super.onSizeChanged(w, h, oldw, oldh)
    }
}
