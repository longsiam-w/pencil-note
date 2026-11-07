package com.vibetools.pencil.canvas

import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Path
import android.graphics.RectF
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.graphics.PixelFormat
import android.graphics.Bitmap
import android.opengl.GLUtils
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.vibetools.pencil.model.EraserTool
import com.vibetools.pencil.model.CanvasClip
import com.vibetools.pencil.model.SelectionRegion
import com.vibetools.pencil.model.Stroke
import com.vibetools.pencil.model.TextBox
import com.vibetools.pencil.model.Tool
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.abs

class CanvasGlView(context: Context) : GLSurfaceView(context) {
    enum class Mode { DRAG, WRITE, INSERT, SELECT, ERASE }
    enum class SelectionStyle { RECTANGLE, FREEFORM }
    private data class HistoryState(val strokes: List<Stroke>, val texts: List<TextBox>, val backgroundHidden: Boolean, val extras: com.vibetools.pencil.model.DocumentExtras?, val centerX:Float, val centerY:Float, val zoom:Float)

    private val paperColor = context.getColor(com.vibetools.pencil.R.color.paper_beige)
    private val renderer = PencilRenderer(paperColor)
    private val undoStack = ArrayDeque<HistoryState>()
    private val redoStack = ArrayDeque<HistoryState>()
    private val historyLimit = 100
    private var mode = Mode.DRAG
    private var writeTool = Tool.PEN
    private var eraserTool = EraserTool.ERASER
    private var color = Color.BLACK
    private var strokeWidth = 3f
    private var textFontSize = 18f
    private var highlighterSnap = false
    private var lastX = 0f
    private var lastY = 0f
    private var activeStroke: Stroke? = null
    private var pendingHistory: HistoryState? = null
    private var selectionStart: android.graphics.PointF? = null
    private var selection: SelectionRegion.Rectangle? = null
    private var selectionStyle = SelectionStyle.RECTANGLE
    private val freeformPoints = mutableListOf<PointF>()
    private var selectionPolygon: List<PointF> = emptyList()
    private var movingSelection = false
    private var moveStartState: HistoryState? = null
    private var moved = false
    private var textRequestedListener: ((Float, Float) -> Unit)? = null
    private var insertRequestedListener: ((Float, Float) -> Unit)? = null
    private var transformListener: ((Float, Float, Float) -> Unit)? = null
    private var tapListener: ((Float, Float) -> Unit)? = null
    private var objectTouchListener: ((MotionEvent) -> Boolean)? = null
    fun setObjectTouchListener(listener:(MotionEvent)->Boolean){objectTouchListener=listener}
    private var historyListener: ((Boolean, Boolean) -> Unit)? = null
    private var correctionFluidListener: (() -> Unit)? = null
    private var contentChangedListener: ((List<Stroke>, List<TextBox>) -> Unit)? = null
    private var backgroundStateProvider: (() -> Boolean)? = null
    private var backgroundStateRestorer: ((Boolean) -> Unit)? = null
    private var extrasProvider:(()->com.vibetools.pencil.model.DocumentExtras)?=null
    private var extrasRestorer:((com.vibetools.pencil.model.DocumentExtras)->Unit)?=null
    private var selectionMoveListener:((Float,Float)->Unit)?=null
    private var pasteOffsetListener:((CanvasClip,Float,Float)->Unit)?=null
    private var selectionChangedListener:((List<PointF>)->Unit)?=null
    private var selectionFinishedListener:(()->Unit)?=null
    private var cutSelectionStrokes=true
    private var twoFingerLastX=0f
    private var twoFingerLastY=0f
    private var gestureTransforming=false
    private var twoFingerSpan=0f
    private var suppressSingleTouch=false
    private val brushPrefs=context.getSharedPreferences("pencil_brush",Context.MODE_PRIVATE)
    private var correctionActive=false
    private var eraserSize=56f
    fun currentEraserTool()=eraserTool
    fun currentEraserSize()=eraserSize
    fun setEraserSize(value:Float){eraserSize=value.coerceIn(.5f,80f);brushPrefs.edit().putFloat("eraser:${eraserTool.name}:size",eraserSize).apply();if(correctionActive)strokeWidth=eraserSize}
    fun restoreLastEraserTool(){setEraserTool(runCatching{EraserTool.valueOf(brushPrefs.getString("last_eraser","SAND_ERASER")!!)}.getOrDefault(EraserTool.SAND_ERASER))}
    fun currentZoom()=renderer.zoomValue()
    fun currentStrokeWidth()=strokeWidth
    fun restoreLastWriteTool(){setWriteTool(runCatching{Tool.valueOf(brushPrefs.getString("last_tool","PEN")!!)}.getOrDefault(Tool.PEN))}
    private fun saveBrush(){if(!correctionActive)brushPrefs.edit().putFloat("${writeTool.name}:width",strokeWidth).putInt("${writeTool.name}:color",color).apply()}

    init {
        if(!brushPrefs.getBoolean("pencil_gray_default_v1",false)){brushPrefs.edit().putInt("PENCIL:color",Color.rgb(153,153,153)).putBoolean("pencil_gray_default_v1",true).apply()}
        if(!brushPrefs.getBoolean("highlighter_width_v3",false)){
            val previous=brushPrefs.getFloat("HIGHLIGHTER:width",32f)
            brushPrefs.edit().putFloat("HIGHLIGHTER:width",(previous*3f).coerceIn(96f,480f)).putBoolean("highlighter_width_v3",true).apply()
        }
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(PixelFormat.OPAQUE)
        setZOrderOnTop(false)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        preserveEGLContextOnPause = true
        setBackgroundColor(paperColor)
    }

    fun setMode(value: Mode) { mode = value; activeStroke = null; selectionStart = null; freeformPoints.clear() }
    fun setSelectionStyle(value: SelectionStyle) { selectionStyle = value; clearSelection(); mode=Mode.SELECT }
    fun currentSelectionStyle()=selectionStyle
    fun isMovingSelection()=movingSelection
    fun setWriteTool(tool: Tool) { correctionActive=false;writeTool=tool;mode=Mode.WRITE;strokeWidth=brushPrefs.getFloat("${tool.name}:width",when(tool){Tool.PEN->3f;Tool.PENCIL->1.5f;Tool.HIGHLIGHTER->96f});color=brushPrefs.getInt("${tool.name}:color",when(tool){Tool.HIGHLIGHTER->Color.YELLOW;Tool.PENCIL->Color.rgb(153,153,153);else->Color.BLACK});brushPrefs.edit().putString("last_tool",tool.name).apply() }
    fun setEraserTool(tool: EraserTool) { eraserTool = tool;correctionActive=false;eraserSize=brushPrefs.getFloat("eraser:${tool.name}:size",56f);brushPrefs.edit().putString("last_eraser",tool.name).apply(); if(tool==EraserTool.CORRECTION_FLUID){ correctionActive=true;writeTool=Tool.PEN;color=paperColor;strokeWidth=eraserSize;mode=Mode.WRITE }else mode = Mode.ERASE }
    fun setColor(value: Int) { color = value;saveBrush() }
    fun setStrokeWidth(value:Float){strokeWidth=value.coerceIn(.5f,if(writeTool==Tool.HIGHLIGHTER&&!correctionActive)480f else 80f);saveBrush()}
    fun setTextFontSize(value:Float){textFontSize=value.coerceIn(8f,72f)}
    fun setHighlighterSnap(enabled:Boolean){highlighterSnap=enabled}
    fun currentTextFontSize()=textFontSize
    fun currentTool()=writeTool
    fun currentColor()=color
    fun setTextRequestedListener(listener: (Float, Float) -> Unit) { textRequestedListener = listener }
    fun setInsertRequestedListener(listener: (Float, Float) -> Unit) { insertRequestedListener = listener }
    fun setTransformListener(listener: (Float, Float, Float) -> Unit) { transformListener = listener }
    fun setTapListener(listener: (Float, Float) -> Unit) { tapListener = listener }
    fun setHistoryListener(listener: (Boolean, Boolean) -> Unit) { historyListener = listener; notifyHistory() }
    fun setCorrectionFluidListener(listener: () -> Unit) { correctionFluidListener = listener }
    fun setContentChangedListener(listener: (List<Stroke>, List<TextBox>) -> Unit) { contentChangedListener = listener }
    fun setBackgroundHistory(provider:()->Boolean,restorer:(Boolean)->Unit){backgroundStateProvider=provider;backgroundStateRestorer=restorer}
    fun setExtrasHistory(provider:()->com.vibetools.pencil.model.DocumentExtras,restorer:(com.vibetools.pencil.model.DocumentExtras)->Unit){extrasProvider=provider;extrasRestorer=restorer}
    fun setSelectionMoveListener(listener:(Float,Float)->Unit){selectionMoveListener=listener}
    fun setPasteOffsetListener(listener:(CanvasClip,Float,Float)->Unit){pasteOffsetListener=listener}
    fun setSelectionChangedListener(listener:(List<PointF>)->Unit){selectionChangedListener=listener}
    fun setSelectionFinishedListener(listener:()->Unit){selectionFinishedListener=listener}
    fun setCutSelectionStrokes(enabled:Boolean){cutSelectionStrokes=enabled}
    fun setDocument(strokes: List<Stroke>, texts: List<TextBox>) { queueEvent { renderer.setStrokes(strokes); renderer.setTextBoxes(texts); undoStack.clear(); redoStack.clear(); post { notifyHistory(); requestRender() } } }
    fun setPageBackground(bitmap:Bitmap?,forceFit:Boolean=false){
        queueEvent {
            val hadBackground=renderer.hasBackground()
            renderer.setBackground(bitmap)
            if(bitmap!=null&&(!hadBackground||forceFit)) {
                renderer.fitOn(bitmap.width.toFloat(),bitmap.height.toFloat(),width.toFloat(),height.toFloat())
                post { transformListener?.invoke(renderer.panXValue(),renderer.panYValue(),renderer.zoomValue()) }
            }
            post { requestRender() }
        }
    }
    fun setStrokes(strokes: List<Stroke>) { queue { renderer.setStrokes(strokes) } }
    fun setTextBoxes(texts: List<TextBox>) { queue { renderer.setTextBoxes(texts) } }
    fun getStrokes(): List<Stroke> = renderer.snapshotStrokes()
    fun getTextBoxes(): List<TextBox> = renderer.snapshotTexts()
    fun addText(text: TextBox) { recordHistory(); queue { renderer.addText(text) } }
    fun updateText(id:String,value:String,fontSize:Float){recordHistory();queue{renderer.updateText(id,value,fontSize)}}
    fun addStroke(stroke: Stroke) { recordHistory(); queue { renderer.commitStroke(stroke) } }
    fun selectedRegion(): SelectionRegion.Rectangle? = selection
    fun restoreSelection(region:SelectionRegion.Rectangle){selection=region;selectionPolygon=listOf(PointF(region.left,region.top),PointF(region.right,region.top),PointF(region.right,region.bottom),PointF(region.left,region.bottom),PointF(region.left,region.top));renderer.setSelection(selectionPolygon);selectionChangedListener?.invoke(selectionPolygon);requestRender()}
    fun clearSelection() { selection = null; selectionStart = null; freeformPoints.clear(); selectionPolygon=emptyList(); movingSelection=false;moveStartState=null;renderer.unlockSelection();selectionChangedListener?.invoke(emptyList());queue { renderer.setSelection(emptyList()) }; requestRender() }
    fun selectionIntersects(bounds:RectF):Boolean {
        val r=selection?:return false
        if(!RectF.intersects(RectF(r.left,r.top,r.right,r.bottom),bounds))return false
        if(selectionPolygon.size<3)return true
        val selected=Path().apply{moveTo(selectionPolygon.first().x,selectionPolygon.first().y);selectionPolygon.drop(1).forEach{lineTo(it.x,it.y)};close()}
        val image=Path().apply{addRect(bounds,Path.Direction.CW)}
        return selected.op(image,Path.Op.INTERSECT)&&!selected.isEmpty
    }
    fun selectionContains(x:Float,y:Float):Boolean {
        val r=selection?:return false
        if(x !in r.left..r.right||y !in r.top..r.bottom)return false
        if(selectionPolygon.size<3)return true
        var contained=false;var j=selectionPolygon.lastIndex
        for(i in selectionPolygon.indices){val a=selectionPolygon[i];val b=selectionPolygon[j];if((a.y>y)!=(b.y>y)&&x<(b.x-a.x)*(y-a.y)/(b.y-a.y)+a.x)contained=!contained;j=i}
        return contained
    }
    fun restoreFoldedContent(strokes:List<Stroke>,texts:List<TextBox>,onRestored:()->Unit){
        clearSelection()
        queueEvent{
            renderer.setStrokes(renderer.snapshotStrokes()+strokes)
            renderer.setTextBoxes(renderer.snapshotTexts()+texts)
            val s=renderer.snapshotStrokes();val t=renderer.snapshotTexts()
            post{contentChangedListener?.invoke(s,t);onRestored();requestRender()}
        }
    }
    fun checkpoint(){recordHistory()}
    fun deleteSelection(record:Boolean=true) { val polygon=selectionPolygon.toList();selection?.let { if(record)recordHistory(); queue { renderer.deleteIn(it,polygon) } }; clearSelection() }
    fun removeSelectedContent(keepHighlighter:Boolean=false,onRemoved:()->Unit) {
        val region=selection?:return;val polygon=selectionPolygon.toList()
        // The callback runs after the renderer mutation, so saving cannot race the removal.
        clearSelection()
        queueEvent { renderer.deleteIn(region,polygon,keepHighlighter);val s=renderer.snapshotStrokes();val t=renderer.snapshotTexts();post{contentChangedListener?.invoke(s,t);onRemoved();requestRender()} }
    }
    fun replaceSelectionWithText(text:TextBox){val region=selection;val polygon=selectionPolygon.toList();if(region==null)return;recordHistory();queueEvent{renderer.deleteIn(region,polygon);renderer.addText(text);selection=null;selectionStart=null;selectionPolygon=emptyList();renderer.setSelection(emptyList());val s=renderer.snapshotStrokes();val t=renderer.snapshotTexts();post{selectionChangedListener?.invoke(emptyList());requestRender();contentChangedListener?.invoke(s,t)}}}
    fun duplicateSelection() { selection?.let { recordHistory(); queue { renderer.duplicateIn(it,selectionPolygon) } }; clearSelection() }
    fun copySelection(): CanvasClip? = selection?.let { renderer.copyIn(it,selectionPolygon) }
    fun cutSelection(): CanvasClip? = copySelection()?.also { deleteSelection() }
    fun paste(clip: CanvasClip, offsetX: Float = 48f, offsetY: Float = 48f) { recordHistory(); queue { renderer.paste(clip, offsetX, offsetY) } }
    fun pasteAtWorldTopLeft(clip:CanvasClip,targetX:Float,targetY:Float){
        val xs=clip.strokes.flatMap{it.points}.map{it.x}+clip.texts.map{it.x}+clip.images.map{it.x}+clip.links.map{it.x}+clip.summaries.map{it.x}
        val ys=clip.strokes.flatMap{it.points}.map{it.y}+clip.texts.map{it.y}+clip.images.map{it.y}+clip.links.map{it.y}+clip.summaries.map{it.y}
        val dx=targetX-(xs.minOrNull()?:0f);val dy=targetY-(ys.minOrNull()?:0f)
        recordHistory();queue{renderer.paste(clip,dx,dy)}
    }
    fun pasteAtTopLeft(clip:CanvasClip){val xs=clip.strokes.flatMap{it.points}.map{it.x}+clip.texts.map{it.x}+clip.images.flatMap{listOf(it.x,it.x+it.width)}+clip.links.map{it.x}+clip.summaries.map{it.x};val ys=clip.strokes.flatMap{it.points}.map{it.y}+clip.texts.map{it.y}+clip.images.flatMap{listOf(it.y,it.y+it.height)}+clip.links.map{it.y}+clip.summaries.map{it.y};val dx=40f-(xs.minOrNull()?:0f);val dy=40f-(ys.minOrNull()?:0f);recordHistory();queueEvent{renderer.paste(clip,dx,dy);val s=renderer.snapshotStrokes();val t=renderer.snapshotTexts();post{pasteOffsetListener?.invoke(clip,dx,dy);contentChangedListener?.invoke(s,t);requestRender()}}}
    fun pasteCentered(clip:CanvasClip){val xs=clip.strokes.flatMap{it.points}.map{it.x}+clip.texts.map{it.x}+clip.images.flatMap{listOf(it.x,it.x+it.width)}+clip.links.map{it.x}+clip.summaries.map{it.x};val ys=clip.strokes.flatMap{it.points}.map{it.y}+clip.texts.map{it.y}+clip.images.flatMap{listOf(it.y,it.y+it.height)}+clip.links.map{it.y}+clip.summaries.map{it.y};val minX=xs.minOrNull()?:0f;val maxX=xs.maxOrNull()?:minX;val minY=ys.minOrNull()?:0f;val maxY=ys.maxOrNull()?:minY;val center=viewportCenterWorld();val dx=center.x-(minX+maxX)/2f;val dy=center.y-(minY+maxY)/2f;val before=snapshot();recordHistory();queueEvent{val pastedStrokeIds=clip.strokes.map{UUID.randomUUID().toString()};val pastedTextIds=clip.texts.map{UUID.randomUUID().toString()};renderer.pasteWithIds(clip,dx,dy,pastedStrokeIds,pastedTextIds);renderer.lockIds(pastedStrokeIds.toSet(),pastedTextIds.toSet());selection=SelectionRegion.Rectangle(minX+dx,minY+dy,maxX+dx,maxY+dy);selectionPolygon=emptyList();renderer.setSelection(listOf(PointF(minX+dx,minY+dy),PointF(maxX+dx,minY+dy),PointF(maxX+dx,maxY+dy),PointF(minX+dx,maxY+dy),PointF(minX+dx,minY+dy)));val s=renderer.snapshotStrokes();val t=renderer.snapshotTexts();post{pasteOffsetListener?.invoke(clip,dx,dy);moveStartState=before;movingSelection=true;mode=Mode.SELECT;selectionChangedListener?.invoke(listOf(PointF(minX+dx,minY+dy),PointF(maxX+dx,minY+dy),PointF(maxX+dx,maxY+dy),PointF(minX+dx,maxY+dy),PointF(minX+dx,minY+dy)));requestRender();contentChangedListener?.invoke(s,t)}}}
    fun scaleSelection(factor:Float,onScaled:()->Unit){val region=selection?:return;val polygon=selectionPolygon.toList();val cx=(region.left+region.right)/2f;val cy=(region.top+region.bottom)/2f;recordHistory();clearSelection();queueEvent{renderer.scaleIn(region,polygon,cx,cy,factor);val s=renderer.snapshotStrokes();val t=renderer.snapshotTexts();post{contentChangedListener?.invoke(s,t);onScaled();requestRender()}}}
    fun moveSelection(dx: Float, dy: Float) { selection?.let { recordHistory(); queue { renderer.moveIn(it,selectionPolygon,dx,dy) } }; requestRender() }
    fun beginSelectionMove(){if(selection!=null){mode=Mode.SELECT;selectionStart=null;suppressSingleTouch=false;moveStartState=snapshot();recordHistory();renderer.lockSelection(selection!!,selectionPolygon);movingSelection=true}}
    fun confirmSelectionMove(){movingSelection=false;moveStartState=null;renderer.unlockSelection();clearSelection()}
    fun cancelSelectionMove(){moveStartState?.let(::restore);if(undoStack.isNotEmpty())undoStack.removeLast();redoStack.clear();movingSelection=false;moveStartState=null;renderer.unlockSelection();clearSelection();notifyHistory()}
    fun canUndo() = undoStack.isNotEmpty()
    fun canRedo() = redoStack.isNotEmpty()
    fun undo() { if (undoStack.isEmpty()) return; val current=snapshot(); val previous=undoStack.removeLast(); redoStack.addLast(current); restore(previous); notifyHistory() }
    fun redo() { if (redoStack.isEmpty()) return; val current=snapshot(); val next=redoStack.removeLast(); undoStack.addLast(current); restore(next); notifyHistory() }
    fun viewportCenterWorld(): PointF = renderer.toWorld(width / 2f, height / 2f)
    fun centerOn(worldX:Float,worldY:Float){queueEvent{renderer.centerOn(worldX,worldY,width.toFloat(),height.toFloat());post{transformListener?.invoke(renderer.panXValue(),renderer.panYValue(),renderer.zoomValue());requestRender()}}}
    fun restoreView(centerX:Float,centerY:Float,zoom:Float){queueEvent{renderer.setView(centerX,centerY,zoom,width.toFloat(),height.toFloat());post{transformListener?.invoke(renderer.panXValue(),renderer.panYValue(),renderer.zoomValue());requestRender()}}}
    fun screenToWorld(x: Float, y: Float): PointF = renderer.toWorld(x, y)

    private fun queue(action: () -> Unit) { queueEvent { action(); val s=renderer.snapshotStrokes();val t=renderer.snapshotTexts();post{requestRender();contentChangedListener?.invoke(s,t)} } }
    private fun notifyContentChanged(){contentChangedListener?.invoke(renderer.snapshotStrokes(),renderer.snapshotTexts())}
    private fun notifySelectionChanged(){selectionChangedListener?.invoke(selectionPolygon.toList())}

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(mode!=Mode.SELECT&&!movingSelection&&objectTouchListener?.invoke(event)==true)return true
        if(event.actionMasked==MotionEvent.ACTION_DOWN)suppressSingleTouch=false
        if(event.pointerCount>1){
            parent?.requestDisallowInterceptTouchEvent(true)
            suppressSingleTouch=true
            if(activeStroke!=null){activeStroke?.let{renderer.removeStroke(it.id)};activeStroke=null;pendingHistory=null;notifyContentChanged()}
            val cx=(event.getX(0)+event.getX(1))/2f
            val cy=(event.getY(0)+event.getY(1))/2f
            val span=kotlin.math.hypot(event.getX(0)-event.getX(1),event.getY(0)-event.getY(1))
            when(event.actionMasked){
                MotionEvent.ACTION_POINTER_DOWN->{twoFingerLastX=cx;twoFingerLastY=cy;twoFingerSpan=span;gestureTransforming=true}
                MotionEvent.ACTION_MOVE->{
                    if(gestureTransforming){
                        val factor=if(twoFingerSpan>1f&&span>1f)span/twoFingerSpan else 1f
                        renderer.zoomBy(factor,twoFingerLastX,twoFingerLastY)
                        renderer.panBy(cx-twoFingerLastX,cy-twoFingerLastY)
                        transformListener?.invoke(renderer.panXValue(),renderer.panYValue(),renderer.zoomValue())
                        requestRender()
                    }
                    twoFingerLastX=cx;twoFingerLastY=cy;twoFingerSpan=span
                }
                MotionEvent.ACTION_POINTER_UP->{gestureTransforming=false}
            }
            return true
        }
        if(suppressSingleTouch){if(event.actionMasked==MotionEvent.ACTION_UP||event.actionMasked==MotionEvent.ACTION_CANCEL){gestureTransforming=false;parent?.requestDisallowInterceptTouchEvent(false)};return true}
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = x; lastY = y; moved = false
                if(mode==Mode.SELECT&&movingSelection){
                    val world=renderer.toWorld(x,y)
                    val currentSelection=selection
                    if(currentSelection==null||world.x !in currentSelection.left..currentSelection.right||world.y !in currentSelection.top..currentSelection.bottom){
                        textRequestedListener?.invoke(0f,-1f)
                        return true
                    }
                    parent?.requestDisallowInterceptTouchEvent(true)
                    lastX=x;lastY=y;return true
                }
                when (mode) {
                    Mode.WRITE -> { pendingHistory=snapshot();val pressure=event.pressure.coerceIn(.25f,1.5f);val width=if(writeTool==Tool.HIGHLIGHTER)strokeWidth else strokeWidth*pressure;activeStroke = Stroke(UUID.randomUUID().toString(), writeTool, color, width, if (writeTool == Tool.HIGHLIGHTER) 110 else 255).also { val world=renderer.toWorld(x,y); it.points.add(world); it.points.add(world); renderer.addOrUpdateStroke(it); requestRender(); notifyContentChanged() } }
                    Mode.ERASE -> { pendingHistory=snapshot();eraseAt(x, y) }
                    Mode.SELECT -> { selectionStart = renderer.toWorld(x, y); freeformPoints.clear(); selection = null; selectionPolygon=emptyList(); renderer.setSelection(emptyList()); if (selectionStyle == SelectionStyle.FREEFORM) freeformPoints += selectionStart!!; notifySelectionChanged() }
                    Mode.INSERT -> insertRequestedListener?.invoke(x, y)
                    else -> Unit
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastX; val dy = y - lastY
                if (abs(dx) + abs(dy) > 1f) moved = true
                when {
                    mode==Mode.SELECT&&movingSelection->{val wx=dx/renderer.zoomValue();val wy=dy/renderer.zoomValue();renderer.moveLocked(wx,wy);selectionMoveListener?.invoke(wx,wy);selection=selection?.let{SelectionRegion.Rectangle(it.left+wx,it.top+wy,it.right+wx,it.bottom+wy)};selectionPolygon=selectionPolygon.map{PointF(it.x+wx,it.y+wy)};renderer.setSelection(selectionPolygon);notifySelectionChanged();notifyContentChanged()}
                    mode==Mode.DRAG -> renderer.panBy(dx, dy)
                    mode==Mode.WRITE -> activeStroke?.let { stroke->val raw=renderer.toWorld(x,y);val last=stroke.points.last();val distance=kotlin.math.hypot(raw.x-last.x,raw.y-last.y);if(distance>=1.5f/renderer.zoomValue()){val smooth=if(stroke.points.size>2)PointF((last.x+raw.x)/2f,(last.y+raw.y)/2f)else raw;stroke.points.add(smooth);renderer.addOrUpdateStroke(stroke);requestRender();notifyContentChanged()} }
                    mode==Mode.ERASE -> if (moved) eraseAt(x, y)
                    mode==Mode.SELECT -> selectionStart?.let { start -> val now=renderer.toWorld(x,y); if(selectionStyle==SelectionStyle.FREEFORM){freeformPoints+=now;val loop=closedFreeformLoop(freeformPoints,40f/renderer.zoomValue());selectionPolygon=loop?:freeformPoints.toList();if(loop!=null){selection=SelectionRegion.Rectangle(loop.minOf{it.x},loop.minOf{it.y},loop.maxOf{it.x},loop.maxOf{it.y})};renderer.setSelection(selectionPolygon)}else{val r=SelectionRegion.Rectangle(minOf(start.x,now.x),minOf(start.y,now.y),maxOf(start.x,now.x),maxOf(start.y,now.y));selection=r;selectionPolygon=listOf(PointF(r.left,r.top),PointF(r.right,r.top),PointF(r.right,r.bottom),PointF(r.left,r.bottom),PointF(r.left,r.top));renderer.setSelection(selectionPolygon)};notifySelectionChanged()}
                    else -> Unit
                }
                if (mode == Mode.DRAG) transformListener?.invoke(renderer.panXValue(), renderer.panYValue(), renderer.zoomValue())
                lastX = x; lastY = y; requestRender(); return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.WRITE) { activeStroke?.let { stroke->if(stroke.points.size>1){if(highlighterSnap&&writeTool==Tool.HIGHLIGHTER)snapHighlighter(stroke);pendingHistory?.let(::pushHistoryState);renderer.commitStroke(stroke)}else renderer.removeStroke(stroke.id) }; activeStroke = null;pendingHistory=null }
                if(mode==Mode.ERASE){val before=pendingHistory;val changed=before!=null&&(before.strokes!=renderer.snapshotStrokes()||before.texts!=renderer.snapshotTexts()||before.backgroundHidden!=(backgroundStateProvider?.invoke()?:false));if(changed)before?.let(::pushHistoryState);pendingHistory=null}
                if(mode==Mode.SELECT&&movingSelection){
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if(event.actionMasked==MotionEvent.ACTION_UP&&moved)textRequestedListener?.invoke(0f,-1f)
                    requestRender();return true
                }
                if (mode == Mode.SELECT && selection != null && hasValidSelection() && !movingSelection && event.actionMasked==MotionEvent.ACTION_UP) { val before=snapshot();if(cutSelectionStrokes&&renderer.splitSelection(selection!!,selectionPolygon)){pushHistoryState(before);notifyContentChanged()};selectionFinishedListener?.invoke() }
                if (mode == Mode.DRAG && !moved) { performClick();val p=renderer.toWorld(x,y);tapListener?.invoke(p.x,p.y) }
                val s=renderer.snapshotStrokes();val t=renderer.snapshotTexts();post{contentChangedListener?.invoke(s,t)}
                requestRender(); return true
            }
        }
        return true
    }

    private fun closedFreeformLoop(points:List<PointF>,tolerance:Float):List<PointF>? { if(points.size<4)return null;val first=points.first();val last=points.last();if(kotlin.math.hypot(last.x-first.x,last.y-first.y)<=tolerance)return points+first;val a=points[points.lastIndex-1];for(index in 0 until points.size-3){val b=points[index];val c=points[index+1];segmentIntersection(a,last,b,c)?.let{cross->return points.subList(index+1,points.size).toList()+cross}};return null }
    private fun segmentIntersection(a:PointF,b:PointF,c:PointF,d:PointF):PointF? { val abx=b.x-a.x;val aby=b.y-a.y;val cdx=d.x-c.x;val cdy=d.y-c.y;val denominator=abx*cdy-aby*cdx;if(kotlin.math.abs(denominator)<.001f)return null;val acx=c.x-a.x;val acy=c.y-a.y;val t=(acx*cdy-acy*cdx)/denominator;val u=(acx*aby-acy*abx)/denominator;return if(t in 0f..1f&&u in 0f..1f)PointF(a.x+t*abx,a.y+t*aby)else null }

    private fun snapHighlighter(stroke:Stroke){val first=stroke.points.first();val last=stroke.points.last();if(kotlin.math.abs(last.x-first.x)>=kotlin.math.abs(last.y-first.y))stroke.points.forEach{it.y=first.y}else stroke.points.forEach{it.x=first.x}}
    private fun hasValidSelection():Boolean { val r=selection?:return false; return if(selectionStyle==SelectionStyle.RECTANGLE)r.right-r.left>=8f/renderer.zoomValue()&&r.bottom-r.top>=8f/renderer.zoomValue() else selectionPolygon.size>=3 }
    private fun eraseAt(x: Float, y: Float) { val p = renderer.toWorld(x, y); renderer.eraseAt(p.x, p.y, eraserSize/2f, eraserTool);notifyContentChanged();requestRender();if(eraserTool==EraserTool.CORRECTION_FLUID)correctionFluidListener?.invoke() }
    private fun snapshot():HistoryState { val center=renderer.toWorld(width/2f,height/2f);return HistoryState(renderer.snapshotStrokes(),renderer.snapshotTexts(),backgroundStateProvider?.invoke()?:false,extrasProvider?.invoke(),center.x,center.y,renderer.zoomValue()) }
    private fun recordHistory() { pushHistoryState(snapshot()) }
    private fun pushHistoryState(state:HistoryState){undoStack.addLast(state);while(undoStack.size>historyLimit)undoStack.removeFirst();redoStack.clear();notifyHistory()}
    private fun restore(state: HistoryState) { queueEvent { renderer.setStrokes(state.strokes); renderer.setTextBoxes(state.texts);renderer.setView(state.centerX,state.centerY,state.zoom,width.toFloat(),height.toFloat());val s=renderer.snapshotStrokes();val t=renderer.snapshotTexts();post { if((backgroundStateProvider?.invoke()?:false)!=state.backgroundHidden)backgroundStateRestorer?.invoke(state.backgroundHidden);state.extras?.let{extrasRestorer?.invoke(it)};transformListener?.invoke(renderer.panXValue(),renderer.panYValue(),renderer.zoomValue());requestRender();contentChangedListener?.invoke(s,t) } } }
    override fun performClick():Boolean{super.performClick();return true}
    private fun notifyHistory() { post { historyListener?.invoke(canUndo(), canRedo()) } }
}

private class PencilRenderer(private val paperColor:Int) : GLSurfaceView.Renderer {
    private data class CachedStroke(val pointCount:Int,val minX:Float,val minY:Float,val maxX:Float,val maxY:Float,val buffer:java.nio.FloatBuffer,var ribbonKey:String="",var ribbonBuffer:java.nio.FloatBuffer?=null)
    // All renderer state is guarded by this monitor. Never retain caller-owned point lists.
    private val strokes = mutableListOf<Stroke>()
    private var lockedStrokeIds:Set<String>?=null
    private var lockedTextIds:Set<String>?=null
    private val strokeCache = mutableMapOf<String,CachedStroke>()
    private val texts = mutableListOf<TextBox>()
    private var selectionPath: List<PointF> = emptyList()
    private var width = 1; private var height = 1; private var panX = 0f; private var panY = 0f; private var zoom = 1f
    private var program = 0; private var positionHandle = 0; private var colorHandle = 0; private var viewportHandle = 0; private var panHandle = 0; private var zoomHandle = 0
    private var textureProgram=0;private var textureId=0;private var backgroundBitmap:Bitmap?=null;private var backgroundWidth=0;private var backgroundHeight=0
    private var gridBuffer:java.nio.FloatBuffer?=null;private var gridCount=0;private var gridKey=""
    private val vertexShader = """
        attribute vec2 aPosition; uniform vec2 uViewport; uniform vec2 uPan; uniform float uZoom;
        void main() { vec2 screen=(aPosition+uPan)*uZoom; vec2 clip=vec2(screen.x/uViewport.x*2.0-1.0,1.0-screen.y/uViewport.y*2.0); gl_Position=vec4(clip,0.0,1.0); }
    """.trimIndent()
    private val fragmentShader = """precision mediump float; uniform vec4 uColor; void main(){gl_FragColor=uColor;}"""
    private val textureVertexShader="""attribute vec2 aPosition;attribute vec2 aTexCoord;varying vec2 vTexCoord;void main(){gl_Position=vec4(aPosition,0.0,1.0);vTexCoord=aTexCoord;}"""
    private val textureFragmentShader="""precision mediump float;uniform sampler2D uTexture;varying vec2 vTexCoord;void main(){gl_FragColor=texture2D(uTexture,vTexCoord);}"""

    @Synchronized
    override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        GLES20.glClearColor(Color.red(paperColor)/255f,Color.green(paperColor)/255f,Color.blue(paperColor)/255f,1f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA,GLES20.GL_ONE_MINUS_SRC_ALPHA)
        val vertex=compileShader(GLES20.GL_VERTEX_SHADER,vertexShader); val fragment=compileShader(GLES20.GL_FRAGMENT_SHADER,fragmentShader)
        program=GLES20.glCreateProgram().also { GLES20.glAttachShader(it,vertex); GLES20.glAttachShader(it,fragment); GLES20.glLinkProgram(it) }
        positionHandle=GLES20.glGetAttribLocation(program,"aPosition"); colorHandle=GLES20.glGetUniformLocation(program,"uColor"); viewportHandle=GLES20.glGetUniformLocation(program,"uViewport"); panHandle=GLES20.glGetUniformLocation(program,"uPan"); zoomHandle=GLES20.glGetUniformLocation(program,"uZoom")
        val tv=compileShader(GLES20.GL_VERTEX_SHADER,textureVertexShader);val tf=compileShader(GLES20.GL_FRAGMENT_SHADER,textureFragmentShader);textureProgram=GLES20.glCreateProgram().also{GLES20.glAttachShader(it,tv);GLES20.glAttachShader(it,tf);GLES20.glLinkProgram(it)}
        backgroundBitmap?.let(::uploadBackground)
    }
    @Synchronized
    override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, w:Int,h:Int){width=w.coerceAtLeast(1);height=h.coerceAtLeast(1);GLES20.glViewport(0,0,width,height)}
    @Synchronized
    override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?){GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);drawBackground();GLES20.glUseProgram(program);GLES20.glUniform2f(viewportHandle,width.toFloat(),height.toFloat());GLES20.glUniform2f(panHandle,panX,panY);GLES20.glUniform1f(zoomHandle,zoom);if(textureId==0)drawGrid();strokes.forEach(::drawStroke);if(selectionPath.size>1)drawLines(selectionPath,Color.rgb(30,120,230),255,3f)}
    @Synchronized
    fun panBy(dx:Float,dy:Float){panX+=dx/zoom;panY+=dy/zoom}
    @Synchronized
    fun zoomBy(factor:Float,focusX:Float,focusY:Float){val old=zoom;val next=(zoom*factor).coerceIn(.10f,32f);val worldX=focusX/old-panX;val worldY=focusY/old-panY;zoom=next;panX=focusX/next-worldX;panY=focusY/next-worldY}
    @Synchronized
    fun centerOn(worldX:Float,worldY:Float,viewWidth:Float,viewHeight:Float){panX=viewWidth/(2f*zoom)-worldX;panY=viewHeight/(2f*zoom)-worldY}
    @Synchronized
    fun setView(worldX:Float,worldY:Float,valueZoom:Float,viewWidth:Float,viewHeight:Float){zoom=valueZoom.coerceIn(.10f,32f);centerOn(worldX,worldY,viewWidth,viewHeight)}
    @Synchronized
    fun fitOn(contentWidth:Float,contentHeight:Float,viewWidth:Float,viewHeight:Float){zoom=(minOf(viewWidth/contentWidth,viewHeight/contentHeight)*.92f).coerceIn(.10f,32f);centerOn(contentWidth/2f,contentHeight/2f,viewWidth,viewHeight)}
    @Synchronized
    fun panXValue()=panX
    @Synchronized
    fun panYValue()=panY
    @Synchronized
    fun zoomValue()=zoom
    @Synchronized
    fun toWorld(x:Float,y:Float)=android.graphics.PointF(x/zoom-panX,y/zoom-panY)
    @Synchronized
    fun hasBackground()=backgroundBitmap!=null&&!backgroundBitmap!!.isRecycled
    @Synchronized
    fun setBackground(bitmap:Bitmap?){backgroundBitmap=bitmap;backgroundWidth=bitmap?.width?:0;backgroundHeight=bitmap?.height?:0;if(textureProgram!=0){if(bitmap==null){if(textureId!=0)GLES20.glDeleteTextures(1,intArrayOf(textureId),0);textureId=0}else uploadBackground(bitmap)}}
    @Synchronized
    private fun uploadBackground(bitmap:Bitmap){if(textureId!=0)GLES20.glDeleteTextures(1,intArrayOf(textureId),0);val ids=IntArray(1);GLES20.glGenTextures(1,ids,0);textureId=ids[0];GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,textureId);GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT,1);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,bitmap,0)}
    @Synchronized
    private fun drawBackground(){if(textureId==0||backgroundWidth<=0||backgroundHeight<=0)return;val left=(panX*zoom/width*2f)-1f;val right=((backgroundWidth+panX)*zoom/width*2f)-1f;val top=1f-(panY*zoom/height*2f);val bottom=1f-((backgroundHeight+panY)*zoom/height*2f);val vertices=floatArrayOf(left,bottom,0f,1f,right,bottom,1f,1f,left,top,0f,0f,right,top,1f,0f);val buffer=ByteBuffer.allocateDirect(vertices.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer();buffer.put(vertices).position(0);GLES20.glUseProgram(textureProgram);val p=GLES20.glGetAttribLocation(textureProgram,"aPosition");val t=GLES20.glGetAttribLocation(textureProgram,"aTexCoord");buffer.position(0);GLES20.glEnableVertexAttribArray(p);GLES20.glVertexAttribPointer(p,2,GLES20.GL_FLOAT,false,16,buffer);buffer.position(2);GLES20.glEnableVertexAttribArray(t);GLES20.glVertexAttribPointer(t,2,GLES20.GL_FLOAT,false,16,buffer);GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,textureId);GLES20.glUniform1i(GLES20.glGetUniformLocation(textureProgram,"uTexture"),0);GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);GLES20.glDisableVertexAttribArray(p);GLES20.glDisableVertexAttribArray(t)}
    @Synchronized
    private fun copyStroke(stroke:Stroke)=stroke.copy(points=stroke.points.map{PointF(it.x,it.y)}.toMutableList())
    @Synchronized
    fun setStrokes(value:List<Stroke>){strokes.clear();strokes.addAll(value.map(::copyStroke));strokeCache.clear();lockedStrokeIds=null;lockedTextIds=null}
    private fun clipPolygon(r:SelectionRegion.Rectangle,polygon:List<PointF>):List<StrokeSelectionClipper.Point> = (if(polygon.size>=3)polygon else listOf(PointF(r.left,r.top),PointF(r.right,r.top),PointF(r.right,r.bottom),PointF(r.left,r.bottom))).map{StrokeSelectionClipper.Point(it.x.toDouble(),it.y.toDouble())}
    private fun strokeSelected(s:Stroke,r:SelectionRegion.Rectangle,polygon:List<PointF>):Boolean {
        val boundary=clipPolygon(r,polygon)
        return s.points.zipWithNext().any{(a,b)->StrokeSelectionClipper.contains(StrokeSelectionClipper.Point((a.x.toDouble()+b.x)/2,(a.y.toDouble()+b.y)/2),boundary)}
    }
    @Synchronized
    fun splitSelection(r:SelectionRegion.Rectangle,polygon:List<PointF>):Boolean {
        val boundary=clipPolygon(r,polygon)
        var changed=false
        val next=mutableListOf<Stroke>()
        for(stroke in strokes){
            val pieces=StrokeSelectionClipper.split(stroke.points.map{StrokeSelectionClipper.Point(it.x.toDouble(),it.y.toDouble())},boundary)
            if(pieces.any{it.inside}&&pieces.any{!it.inside}){
                changed=true
                next.addAll(pieces.map{piece->stroke.copy(id=UUID.randomUUID().toString(),points=piece.points.map{PointF(it.x.toFloat(),it.y.toFloat())}.toMutableList())})
            }else next.add(stroke)
        }
        if(changed){strokes.clear();strokes.addAll(next);strokeCache.clear();lockedStrokeIds=null}
        return changed
    }
    @Synchronized
    fun lockSelection(r:SelectionRegion.Rectangle,polygon:List<PointF>){lockedStrokeIds=strokes.filter{strokeSelected(it,r,polygon)}.map{it.id}.toSet();lockedTextIds=texts.filter{inside(PointF(it.x,it.y),r,polygon)}.map{it.id}.toSet()}
    @Synchronized
    fun lockIds(strokeIds:Set<String>,textIds:Set<String>){lockedStrokeIds=strokeIds;lockedTextIds=textIds}
    @Synchronized
    fun unlockSelection(){lockedStrokeIds=null;lockedTextIds=null}
    @Synchronized
    fun moveLocked(dx:Float,dy:Float){lockedStrokeIds?.let{ids->strokes.filter{s->s.id in ids}.forEach{s->s.points.forEach{p->p.x+=dx;p.y+=dy};strokeCache.remove(s.id)}};lockedTextIds?.let{ids->texts.indices.filter{i->texts[i].id in ids}.forEach{i->texts[i]=texts[i].copy(x=texts[i].x+dx,y=texts[i].y+dy)}}}
    @Synchronized
    fun setTextBoxes(value:List<TextBox>){texts.clear();texts.addAll(value)}
    @Synchronized
    fun setSelection(value:List<PointF>){selectionPath=value.map{PointF(it.x,it.y)}}
    @Synchronized
    fun snapshotStrokes()=strokes.map { it.copy(points=it.points.map { point -> PointF(point.x, point.y) }.toMutableList()) }
    @Synchronized
    fun snapshotTexts()=texts.toList()
    @Synchronized
    fun addText(text:TextBox){texts.add(text)}
    @Synchronized
    fun updateText(id:String,value:String,fontSize:Float){val index=texts.indexOfFirst{it.id==id};if(index>=0)texts[index]=texts[index].copy(text=value,fontSize=fontSize)}
    @Synchronized
    fun commitStroke(stroke:Stroke){if(stroke.points.size>1){strokes.removeAll{it.id==stroke.id};strokes.add(copyStroke(stroke));strokeCache.remove(stroke.id)}}
    @Synchronized
    fun removeStroke(id:String){strokes.removeAll{it.id==id};strokeCache.remove(id)}
    @Synchronized
    fun addOrUpdateStroke(stroke:Stroke){val i=strokes.indexOfFirst{it.id==stroke.id};val owned=copyStroke(stroke);if(i>=0)strokes[i]=owned else strokes.add(owned);strokeCache.remove(stroke.id)}
    @Synchronized
    fun eraseAt(x:Float,y:Float,radius:Float,eraser:EraserTool){val iterator=strokes.iterator();while(iterator.hasNext()){val s=iterator.next();val eligible=when(eraser){EraserTool.ERASER->s.tool==Tool.PENCIL||(s.tool==Tool.HIGHLIGHTER&&s.color==Color.rgb(153,153,153));EraserTool.SAND_ERASER,EraserTool.CORRECTION_FLUID->true};if(eligible&&s.points.any{abs(it.x-x)<=radius&&abs(it.y-y)<=radius}){iterator.remove();strokeCache.remove(s.id)}};if(eraser!=EraserTool.ERASER)texts.removeAll{abs(it.x-x)<=radius*2&&abs(it.y-y)<=radius*2}}
    @Synchronized
    private fun inside(p:PointF,r:SelectionRegion.Rectangle,polygon:List<PointF>):Boolean { if(p.x !in r.left..r.right||p.y !in r.top..r.bottom)return false;if(polygon.size<3)return true;var result=false;var j=polygon.lastIndex;for(i in polygon.indices){val a=polygon[i];val b=polygon[j];if((a.y>p.y)!=(b.y>p.y)&&p.x<(b.x-a.x)*(p.y-a.y)/((b.y-a.y).takeIf{abs(it)>.0001f}?:.0001f)+a.x)result=!result;j=i};return result }
    @Synchronized
    fun deleteIn(r:SelectionRegion.Rectangle,polygon:List<PointF>,keepHighlighter:Boolean=false){val removed=strokes.filter{strokeSelected(it,r,polygon)&&(!keepHighlighter||it.tool!=Tool.HIGHLIGHTER)};strokes.removeAll(removed.toSet());removed.forEach{strokeCache.remove(it.id)};texts.removeAll{inside(PointF(it.x,it.y),r,polygon)}}
    @Synchronized
    fun duplicateIn(r:SelectionRegion.Rectangle,polygon:List<PointF>){copyIn(r,polygon).let { paste(it,24f,24f) }}
    @Synchronized
    fun copyIn(r:SelectionRegion.Rectangle,polygon:List<PointF>):CanvasClip { val selectedStrokes=strokes.filter{strokeSelected(it,r,polygon)}.map{it.copy(points=it.points.map{p->PointF(p.x,p.y)}.toMutableList())}.toMutableList();val selectedTexts=texts.filter{inside(PointF(it.x,it.y),r,polygon)}.toMutableList();return CanvasClip(strokes=selectedStrokes,texts=selectedTexts) }
    @Synchronized
    fun paste(clip:CanvasClip,dx:Float,dy:Float){pasteWithIds(clip,dx,dy,clip.strokes.map{UUID.randomUUID().toString()},clip.texts.map{UUID.randomUUID().toString()})}
    @Synchronized
    fun pasteWithIds(clip:CanvasClip,dx:Float,dy:Float,strokeIds:List<String>,textIds:List<String>){strokes.addAll(clip.strokes.mapIndexed{i,s->s.copy(id=strokeIds[i],points=s.points.map{p->PointF(p.x+dx,p.y+dy)}.toMutableList())});texts.addAll(clip.texts.mapIndexed{i,t->t.copy(id=textIds[i],x=t.x+dx,y=t.y+dy)})}
    @Synchronized
    fun scaleIn(r:SelectionRegion.Rectangle,polygon:List<PointF>,cx:Float,cy:Float,factor:Float){
        strokes.indices.filter{strokeSelected(strokes[it],r,polygon)}.forEach{i->val s=strokes[i];strokes[i]=s.copy(width=s.width*factor,points=s.points.map{p->PointF(cx+(p.x-cx)*factor,cy+(p.y-cy)*factor)}.toMutableList());strokeCache.remove(s.id)}
        texts.indices.filter{inside(PointF(texts[it].x,texts[it].y),r,polygon)}.forEach{i->val t=texts[i];texts[i]=t.copy(x=cx+(t.x-cx)*factor,y=cy+(t.y-cy)*factor,fontSize=t.fontSize*factor)}
    }
    @Synchronized
    fun moveIn(r:SelectionRegion.Rectangle,polygon:List<PointF>,dx:Float,dy:Float){strokes.filter{strokeSelected(it,r,polygon)}.forEach{s->s.points.forEach{it.x+=dx;it.y+=dy};strokeCache.remove(s.id)};texts.indices.filter{inside(PointF(texts[it].x,texts[it].y),r,polygon)}.forEach{i->texts[i]=texts[i].copy(x=texts[i].x+dx,y=texts[i].y+dy)}}
    @Synchronized
    private fun drawStroke(s:Stroke){if(s.points.size<2)return;val cached=strokeCache[s.id]?.takeIf{it.pointCount==s.points.size}?:cacheStroke(s).also{strokeCache[s.id]=it};val left=-panX;val top=-panY;val right=left+width/zoom;val bottom=top+height/zoom;val pad=s.width.coerceAtLeast(0.01f)/2f;if(cached.maxX+pad<left||cached.minX-pad>right||cached.maxY+pad<top||cached.minY-pad>bottom)return;val key="${s.width}";if(cached.ribbonKey!=key||cached.ribbonBuffer==null){cached.ribbonBuffer=buildRibbon(s.points,s.width);cached.ribbonKey=key};drawBufferMode(cached.ribbonBuffer!!,s.points.size*2,s.color,s.alpha,1f,GLES20.GL_TRIANGLE_STRIP)}
    @Synchronized
    private fun cacheStroke(s:Stroke):CachedStroke{val data=FloatArray(s.points.size*2);var minX=Float.MAX_VALUE;var minY=Float.MAX_VALUE;var maxX=-Float.MAX_VALUE;var maxY=-Float.MAX_VALUE;s.points.forEachIndexed{i,p->data[i*2]=p.x;data[i*2+1]=p.y;minX=minOf(minX,p.x);minY=minOf(minY,p.y);maxX=maxOf(maxX,p.x);maxY=maxOf(maxY,p.y)};val buffer=ByteBuffer.allocateDirect(data.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer();buffer.put(data).position(0);return CachedStroke(s.points.size,minX,minY,maxX,maxY,buffer)}
    @Synchronized
    private fun buildRibbon(points:List<PointF>,lineWidth:Float):java.nio.FloatBuffer{
        // Geometry is in document coordinates; the vertex shader applies zoom once.
        val half=lineWidth.coerceAtLeast(0.01f)/2f
        val data=FloatArray(points.size*4)
        points.forEachIndexed{index,p->
            val previous=points[(index-1).coerceAtLeast(0)];val next=points[(index+1).coerceAtMost(points.lastIndex)]
            var dx=next.x-previous.x;var dy=next.y-previous.y;val length=kotlin.math.hypot(dx,dy).coerceAtLeast(0.001f);dx/=length;dy/=length
            val nx=-dy*half;val ny=dx*half;val offset=index*4
            data[offset]=p.x+nx;data[offset+1]=p.y+ny;data[offset+2]=p.x-nx;data[offset+3]=p.y-ny
        }
        return ByteBuffer.allocateDirect(data.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply{put(data);position(0)}
    }

    @Synchronized
    private fun drawBuffer(buffer:java.nio.FloatBuffer,count:Int,color:Int,alpha:Int,lineWidth:Float){drawBufferMode(buffer,count,color,alpha,lineWidth,GLES20.GL_LINE_STRIP)}
    @Synchronized
    private fun drawBufferMode(buffer:java.nio.FloatBuffer,count:Int,color:Int,alpha:Int,lineWidth:Float,mode:Int){buffer.position(0);GLES20.glEnableVertexAttribArray(positionHandle);GLES20.glVertexAttribPointer(positionHandle,2,GLES20.GL_FLOAT,false,0,buffer);GLES20.glUniform4f(colorHandle,Color.red(color)/255f,Color.green(color)/255f,Color.blue(color)/255f,alpha/255f);GLES20.glLineWidth(lineWidth.coerceIn(1f,32f));GLES20.glDrawArrays(mode,0,count);GLES20.glDisableVertexAttribArray(positionHandle)}
    @Synchronized
    private fun drawLines(points:List<android.graphics.PointF>,color:Int,alpha:Int,lineWidth:Float){val data=FloatArray(points.size*2);points.forEachIndexed{i,p->data[i*2]=p.x;data[i*2+1]=p.y};val b=ByteBuffer.allocateDirect(data.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer();b.put(data).position(0);GLES20.glEnableVertexAttribArray(positionHandle);GLES20.glVertexAttribPointer(positionHandle,2,GLES20.GL_FLOAT,false,0,b);GLES20.glUniform4f(colorHandle,Color.red(color)/255f,Color.green(color)/255f,Color.blue(color)/255f,alpha/255f);GLES20.glLineWidth(lineWidth.coerceIn(1f,32f));GLES20.glDrawArrays(GLES20.GL_LINE_STRIP,0,points.size);GLES20.glDisableVertexAttribArray(positionHandle)}
    @Synchronized
    private fun drawGrid(){val step=80f;val cellX=kotlin.math.floor((-panX)/step).toInt();val cellY=kotlin.math.floor((-panY)/step).toInt();val key="$cellX|$cellY|${(zoom*100).toInt()}|$width|$height";if(key!=gridKey){gridKey=key;val grid=mutableListOf<Float>();val left=cellX*step-80f;val top=cellY*step-80f;val right=left+width/zoom+160f;val bottom=top+height/zoom+160f;var x=kotlin.math.floor(left/step)*step;while(x<=right){grid+=x;grid+=top;grid+=x;grid+=bottom;x+=step};var y=kotlin.math.floor(top/step)*step;while(y<=bottom){grid+=left;grid+=y;grid+=right;grid+=y;y+=step};val data=FloatArray(grid.size){grid[it]};gridBuffer=ByteBuffer.allocateDirect(data.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply{put(data);position(0)};gridCount=data.size/2};gridBuffer?.let{drawBufferMode(it,gridCount,Color.rgb(188,194,199),180,1f,GLES20.GL_LINES)}}
    @Synchronized
    private fun compileShader(type:Int,source:String):Int{val shader=GLES20.glCreateShader(type);GLES20.glShaderSource(shader,source);GLES20.glCompileShader(shader);return shader}
}
