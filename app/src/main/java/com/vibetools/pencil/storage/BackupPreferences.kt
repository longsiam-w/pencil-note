package com.vibetools.pencil.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Explicit allowlist: no API key, AI messages, or transient session/task state. */
internal object BackupPreferences {
    private val names=listOf("pencil_settings","pencil_brush","pencil_chapter_settings","pencil_file_pages","pencil_page_offsets","pencil_quick_access","pencil_teleports","pencil_view_positions","pencil_wrong_book_filter")
    fun capture(context:Context):JSONObject {
        val root=JSONObject()
        names.forEach{name->val values=JSONObject()
            context.getSharedPreferences(name,Context.MODE_PRIVATE).all.forEach{(key,value)->
                if(key!="api_key"){
                    val type=when(value){is String->"string";is Boolean->"boolean";is Int->"int";is Long->"long";is Float->"float";is Set<*>->"set";else->null}
                    if(type!=null)values.put(key,JSONObject().put("type",type).put("value",if(value is Set<*>)JSONArray(value.toList()) else value))
                }
            };root.put(name,values)
        };return root
    }
    fun validate(root:JSONObject){
        val keys=root.keys();while(keys.hasNext()){
            val name=keys.next();require(name in names){"不支持的偏好分组"}
            val values=root.getJSONObject(name);val fields=values.keys()
            while(fields.hasNext()){
                val key=fields.next();require(key!="api_key"){"备份不能包含 API Key"}
                val item=values.getJSONObject(key)
                when(item.getString("type")){
                    "string"->item.getString("value");"boolean"->item.getBoolean("value");"int"->item.getInt("value");"long"->item.getLong("value");"float"->require(item.getDouble("value").toFloat().isFinite())
                    "set"->{val a=item.getJSONArray("value");for(i in 0 until a.length())a.getString(i)}
                    else->error("偏好类型无效")
                }
            }
        }
    }
    fun restore(context:Context,root:JSONObject){
        validate(root)
        val keys=root.keys();while(keys.hasNext()){
            val name=keys.next();val prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE)
            val key=prefs.getString("api_key",null).takeIf{name=="pencil_settings"}
            val edit=prefs.edit().clear();if(key!=null)edit.putString("api_key",key)
            val values=root.getJSONObject(name);val fields=values.keys()
            while(fields.hasNext()){
                val field=fields.next();val item=values.getJSONObject(field)
                when(item.getString("type")){
                    "string"->edit.putString(field,item.getString("value"));"boolean"->edit.putBoolean(field,item.getBoolean("value"));"int"->edit.putInt(field,item.getInt("value"));"long"->edit.putLong(field,item.getLong("value"));"float"->edit.putFloat(field,item.getDouble("value").toFloat())
                    "set"->{val a=item.getJSONArray("value");edit.putStringSet(field,(0 until a.length()).map{a.getString(it)}.toSet())}
                }
            };check(edit.commit()){"偏好设置写入失败，资料库已恢复，请重新检查设置"}
        }
    }
}
