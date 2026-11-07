package com.vibetools.pencil.ui

import android.content.Context
import android.graphics.Color
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray

class AiCopyPageView(context:Context,conversation:JSONArray,onCapture:(AiAnswerView)->Unit,onExit:()->Unit):LinearLayout(context){
    val web=AiAnswerView(context)
    init{
        orientation=VERTICAL;setBackgroundColor(Color.WHITE)
        addView(TextView(context).apply{text="上下滚动查看完整回答；点击截取后框选复制。可返回滚动继续截取后文。";setPadding(24,16,24,16)})
        addView(web,LayoutParams(-1,0,1f))
        addView(LinearLayout(context).apply{
            gravity=android.view.Gravity.END
            addView(Button(context).apply{text="截取当前区域";setOnClickListener{onCapture(web)}})
            addView(Button(context).apply{text="退出";setOnClickListener{onExit()}})
        })
        val clean=JSONArray(conversation.toString())
        for(i in 0 until clean.length())clean.optJSONObject(i)?.apply{remove("reasoning");remove("status");remove("id");put("streaming",false)}
        web.showConversation(clean)
    }
    fun release(){web.stopLoading();web.destroy()}
}
