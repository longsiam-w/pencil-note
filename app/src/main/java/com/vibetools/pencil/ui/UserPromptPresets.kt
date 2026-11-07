package com.vibetools.pencil.ui

import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject

object UserPromptPresets {
    private fun prefs(context:Context)=context.getSharedPreferences("pencil_settings",Context.MODE_PRIVATE)
    private fun load(context:Context)=runCatching{JSONArray(prefs(context).getString("ai_user_presets","[]"))}.getOrDefault(JSONArray())
    private fun save(context:Context,list:JSONArray){prefs(context).edit().putString("ai_user_presets",list.toString()).apply()}
    fun choose(context:Context,onChoose:(String)->Unit){
        val list=load(context)
        if(list.length()==0){Toast.makeText(context,"请先在设置中添加 USER 提示词预设",Toast.LENGTH_SHORT).show();return}
        AlertDialog.Builder(context).setTitle("USER 提示词预设").setItems(Array(list.length()){list.getJSONObject(it).optString("name")}){_,i->onChoose(list.getJSONObject(i).optString("text"))}.setNegativeButton("取消",null).show()
    }
    fun manage(context:Context){
        val list=load(context)
        AlertDialog.Builder(context).setTitle("管理 USER 提示词预设").setItems(arrayOf("＋ 新增预设")+Array(list.length()){list.getJSONObject(it).optString("name")}){_,i->
            if(i==0)edit(context,list,-1)else AlertDialog.Builder(context).setTitle(list.getJSONObject(i-1).optString("name")).setItems(arrayOf("编辑","删除")){_,action->if(action==0)edit(context,list,i-1)else{val next=JSONArray();for(j in 0 until list.length())if(j!=i-1)next.put(list.getJSONObject(j));save(context,next);manage(context)}}.show()
        }.setNegativeButton("关闭",null).show()
    }
    private fun edit(context:Context,list:JSONArray,index:Int){
        val old=if(index>=0)list.getJSONObject(index)else JSONObject()
        val name=EditText(context).apply{hint="预设名称";setText(old.optString("name"))}
        val text=EditText(context).apply{hint="USER 提示词";minLines=5;inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE;setText(old.optString("text"))}
        val panel=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(32,8,32,8);addView(name);addView(text)}
        val dialog=AlertDialog.Builder(context).setTitle(if(index<0)"新增预设" else "编辑预设").setView(panel).setPositiveButton("保存",null).setNegativeButton("取消",null).create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            if(name.text.isBlank()||text.text.isBlank()){Toast.makeText(context,"请输入名称和提示词",Toast.LENGTH_SHORT).show();return@setOnClickListener}
            val item=JSONObject().put("name",name.text.toString().trim()).put("text",text.text.toString())
            if(index<0)list.put(item)else list.put(index,item)
            save(context,list);dialog.dismiss();manage(context)
        }};dialog.show()
    }
}
