package com.vibetools.pencil.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.view.MotionEvent
import android.view.ViewConfiguration
import org.json.JSONArray

@SuppressLint("SetJavaScriptEnabled")
class AiAnswerView @JvmOverloads constructor(context:Context,attrs:AttributeSet?=null):WebView(context,attrs){
    private var regenerateListener:((String)->Unit)?=null
    fun setRegenerateListener(listener:(String)->Unit){regenerateListener=listener}
    fun updateTurn(turn:org.json.JSONObject){
        conversation?.let{list->for(i in 0 until list.length())if(list.optJSONObject(i)?.optString("id")==turn.optString("id")){list.put(i,org.json.JSONObject(turn.toString()));break}}
        if(ready)evaluateJavascript("window.updateTurn(${turn});",null)
    }
    private var ready=false
    private var answers:List<String> = emptyList()
    private var conversation:JSONArray? = null
    private var longPressTask:Runnable?=null
    private var longPressX=0f
    private var longPressY=0f
    private var answerLongPressListener:(()->Unit)?=null
    fun setAnswerLongPressListener(listener:()->Unit){answerLongPressListener=listener}
    init {
        // Root document must never pan sideways; nested DOM scrollers still handle formulas.
        isHorizontalScrollBarEnabled=false
        settings.javaScriptEnabled=true
        addJavascriptInterface(object {
            @android.webkit.JavascriptInterface fun regenerate(id:String){post{regenerateListener?.invoke(id)}}
        },"PencilAi")
        settings.allowContentAccess=false
        settings.allowFileAccess=true
        settings.blockNetworkLoads=true
        webViewClient=object:WebViewClient(){
            override fun onPageFinished(view:WebView,url:String){ready=true;refresh();conversation?.let{evaluateJavascript("window.showConversation(${it});",null)};postDelayed({scrollToLatest()},80)}
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)=true
            override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse? {
                return if(request.url.toString().startsWith("file:///android_asset/ai/"))null else WebResourceResponse("text/plain","UTF-8",java.io.ByteArrayInputStream(byteArrayOf()))
            }
        }
        loadUrl("file:///android_asset/ai/index.html")
    }
    fun showAnswers(value:List<String>){answers=value.toList();refresh()}
    fun showConversation(value:org.json.JSONArray){conversation=JSONArray(value.toString());if(ready)evaluateJavascript("window.showConversation(${value});",null)}
    fun showSingleConversation(value:org.json.JSONArray){conversation=JSONArray(value.toString());if(ready)evaluateJavascript("window.showConversation(${value});",null)}
    fun conversationSnapshot():org.json.JSONArray=JSONArray(conversation?.toString()?:("[]"))
    fun scrollToLatest(){if(ready)evaluateJavascript("window.scrollToLatest();",null)}
    fun selectedText(callback:(String)->Unit){
        if(!ready){callback("");return}
        evaluateJavascript("window.getSelectedText ? window.getSelectedText() : '';" ){value->
            callback(runCatching{org.json.JSONTokener(value).nextValue().toString()}.getOrDefault(""))
        }
    }
    private fun refresh(){if(ready)evaluateJavascript("window.showAnswers(${JSONArray(answers)});",null)}
    override fun onScrollChanged(left:Int,top:Int,oldLeft:Int,oldTop:Int){
        super.onScrollChanged(left,top,oldLeft,oldTop)
        // WebView scrolling is the document viewport; CSS overflow scrollers are independent.
        if(left!=0)scrollTo(0,top)
    }
    private var lastTouchY=0f
    override fun onTouchEvent(event:MotionEvent):Boolean {
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{lastTouchY=event.y;longPressX=event.x;longPressY=event.y;parent?.requestDisallowInterceptTouchEvent(true);longPressTask=Runnable{answerLongPressListener?.invoke()}.also{postDelayed(it,ViewConfiguration.getLongPressTimeout().toLong())}}
            MotionEvent.ACTION_MOVE->{if(kotlin.math.hypot(event.x-longPressX,event.y-longPressY)>ViewConfiguration.get(context).scaledTouchSlop)longPressTask?.let{removeCallbacks(it)};val direction=if(event.y<lastTouchY)1 else -1;parent?.requestDisallowInterceptTouchEvent(kotlin.math.abs(event.x-longPressX)>kotlin.math.abs(event.y-longPressY)||canScrollVertically(direction));lastTouchY=event.y}
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{longPressTask?.let{removeCallbacks(it)};longPressTask=null;parent?.requestDisallowInterceptTouchEvent(false)}
        }
        return super.onTouchEvent(event)
    }
}
