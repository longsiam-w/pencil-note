package com.vibetools.pencil.ui

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject

object ChapterTemplates {
    data class Template(val name:String,val files:List<String>)
    private val default=Template("默认",listOf("主文件"))
    fun load(context:Context):List<Template> = listOf(default)+runCatching{
        val array=JSONArray(context.getSharedPreferences("pencil_settings",Context.MODE_PRIVATE).getString("chapter_templates","[]"))
        (0 until array.length()).map{val o=array.getJSONObject(it);val f=o.getJSONArray("files");Template(o.getString("name"),(0 until f.length()).map{f.getString(it)})}
    }.getOrDefault(emptyList())
    private fun save(context:Context,templates:List<Template>){
        val array=JSONArray();templates.forEach{array.put(JSONObject().put("name",it.name).put("files",JSONArray(it.files)))}
        context.getSharedPreferences("pencil_settings",Context.MODE_PRIVATE).edit().putString("chapter_templates",array.toString()).apply()
    }
    fun choose(context:Context,done:(List<String>)->Unit){val templates=load(context);AlertDialog.Builder(context).setTitle("选择章节结构模板").setItems(templates.map{"${it.name}：${it.files.joinToString("、")}"}.toTypedArray()){_,i->done(templates[i].files)}.setNegativeButton("取消",null).show()}
    fun manage(context:Context){
        val templates=load(context)
        AlertDialog.Builder(context).setTitle("章节结构模板").setItems(templates.map{"${it.name}：${it.files.joinToString("、")}"}.toTypedArray()){_,index->
            if(index==0){AlertDialog.Builder(context).setMessage("默认模板固定包含一个主文件。").setPositiveButton("确定",null).show()}
            else AlertDialog.Builder(context).setTitle(templates[index].name).setItems(arrayOf("编辑","删除")){_,action->
                if(action==0)edit(context,templates,index) else {save(context,templates.drop(1).filterIndexed{i,_->i!=index-1});manage(context)}
            }.show()
        }.setPositiveButton("添加模板"){_,_->edit(context,templates,null)}.setNegativeButton("关闭",null).show()
    }
    private fun edit(context:Context,templates:List<Template>,index:Int?){
        val name=EditText(context).apply{hint="模板名称";setText(index?.let{templates[it].name}.orEmpty())}
        val files=EditText(context).apply{hint="每行一个默认空文件名称，例如：主文件、练习、复习";minLines=3;setText(index?.let{templates[it].files.joinToString("\n")}.orEmpty())}
        val form=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(32,8,32,8);addView(name);addView(files)}
        val dialog=AlertDialog.Builder(context).setTitle(if(index==null)"添加章节结构模板" else "编辑章节结构模板").setView(form).setNegativeButton("取消",null).setPositiveButton("保存",null).create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            val title=name.text.toString().trim()
            val names=files.text.toString().lines().map{it.trim()}.filter{it.isNotEmpty()}
            when{
                title.isEmpty()->name.error="请输入模板名称"
                templates.withIndex().any{it.index!=index&&it.value.name==title}->name.error="模板名称已存在"
                names.isEmpty()->files.error="至少填写一个空文件名称"
                names.distinct().size!=names.size->files.error="文件名称不能重复"
                else->{val updated=templates.drop(1).toMutableList();val item=Template(title,names);if(index==null)updated.add(item)else updated[index-1]=item;save(context,updated);dialog.dismiss();manage(context)}
            }
        }}
        dialog.show()
    }
}
