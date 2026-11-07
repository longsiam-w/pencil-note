package com.vibetools.pencil.ui

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.vibetools.pencil.ai.AiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.appcompat.app.AppCompatActivity
import com.vibetools.pencil.R
import com.vibetools.pencil.storage.LibraryStore
import java.io.File

class SettingsActivity : AppCompatActivity() {
    private val preferences by lazy { getSharedPreferences("pencil_settings", MODE_PRIVATE) }
    private val store by lazy { LibraryStore(this) }
    private val exportBackup=registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri->uri?.let{exportTo(it)}}
    private val importBackup=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let{importFrom(it)}}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);setContentView(R.layout.activity_settings)
        val apiUrl=findViewById<EditText>(R.id.et_api_url);val aiTimeout=findViewById<EditText>(R.id.et_ai_timeout_seconds);val apiModel=findViewById<EditText>(R.id.et_api_model);val apiKey=findViewById<EditText>(R.id.et_api_key);val defaultPrompt=findViewById<EditText>(R.id.et_ai_default_prompt);val highlighterSnap=findViewById<CheckBox>(R.id.cb_highlighter_snap);val pdfTwoPage=findViewById<CheckBox>(R.id.cb_pdf_two_page);val selectionCutStrokes=findViewById<CheckBox>(R.id.cb_selection_cut_strokes);val foldKeepHighlighterImages=findViewById<CheckBox>(R.id.cb_fold_keep_highlighter_images)
        defaultPrompt.setOnTouchListener{v,event->v.parent.requestDisallowInterceptTouchEvent(event.actionMasked!=android.view.MotionEvent.ACTION_UP&&event.actionMasked!=android.view.MotionEvent.ACTION_CANCEL);false}
        apiUrl.setText(preferences.getString("api_url",""));aiTimeout.setText(preferences.getInt("ai_timeout_seconds",120).toString());apiModel.setText(preferences.getString("api_model",""));apiKey.setText(preferences.getString("api_key",""));defaultPrompt.setText(preferences.getString("ai_default_prompt",""));highlighterSnap.isChecked=preferences.getBoolean("highlighter_snap",false);pdfTwoPage.isChecked=preferences.getBoolean("pdf_two_page_mode",false);selectionCutStrokes.isChecked=preferences.getBoolean("selection_cut_strokes",true);foldKeepHighlighterImages.isChecked=preferences.getBoolean("fold_keep_highlighter_images",false)
        findViewById<Button>(R.id.btn_save_api).setOnClickListener{val timeout=aiTimeout.text.toString().toIntOrNull();if(timeout==null||timeout !in 5..1800){aiTimeout.error="请输入 5–1800 秒";return@setOnClickListener};preferences.edit().putString("api_url",apiUrl.text.toString().trim()).putString("api_model",apiModel.text.toString().trim()).putInt("ai_timeout_seconds",timeout).putString("api_key",apiKey.text.toString()).putString("ai_default_prompt",defaultPrompt.text.toString()).putBoolean("highlighter_snap",highlighterSnap.isChecked).putBoolean("pdf_two_page_mode",pdfTwoPage.isChecked).putBoolean("selection_cut_strokes",selectionCutStrokes.isChecked).putBoolean("fold_keep_highlighter_images",foldKeepHighlighterImages.isChecked).apply();Toast.makeText(this,R.string.settings_saved,Toast.LENGTH_SHORT).show()}
        findViewById<Button>(R.id.btn_manage_chapter_templates).setOnClickListener{ChapterTemplates.manage(this)}
        findViewById<Button>(R.id.btn_rollback_restore).apply{isEnabled=store.hasRestoreSnapshot();setOnClickListener{AlertDialog.Builder(this@SettingsActivity).setTitle("回退到恢复前资料库？").setMessage("将替换当前笔记、目录、附件与剪贴板。当前资料库将成为新的快照，可再次回退。此操作不回退偏好设置和 API Key。").setNegativeButton("取消",null).setPositiveButton("回退"){_,_->backupTask("正在回退资料库",{operation.beginCommit();store.rollbackRestore()}){finish()}}.show()}}
        updateDraftShortcutLabel()
        findViewById<Button>(R.id.btn_draft_shortcut_target).setOnClickListener{chooseDraftShortcutTarget()}
        findViewById<Button>(R.id.btn_manage_user_presets).setOnClickListener{UserPromptPresets.manage(this)}
        findViewById<Button>(R.id.btn_test_ai_connection).setOnClickListener{
            val button=findViewById<Button>(R.id.btn_test_ai_connection)
            val url=apiUrl.text.toString().trim();val model=apiModel.text.toString().trim();val key=apiKey.text.toString()
            if(url.isBlank()||model.isBlank()){AlertDialog.Builder(this).setTitle("测试连接失败").setMessage("请先填写 API URL 和模型名称。").setPositiveButton("确定",null).show();return@setOnClickListener}
            val timeout=aiTimeout.text.toString().toIntOrNull()
            if(timeout==null||timeout !in 5..1800){aiTimeout.error="请输入 5–1800 秒";return@setOnClickListener}
            button.isEnabled=false;button.text="测试中……"
            lifecycleScope.launch{
                val result=withContext(Dispatchers.IO){runCatching{AiClient().query(url,key,model,"hello",timeoutSeconds=timeout)}}
                button.isEnabled=true;button.text="测试 AI API 连接"
                result.onSuccess{Toast.makeText(this@SettingsActivity,"AI API 连接正常",Toast.LENGTH_SHORT).show()}.onFailure{error->AlertDialog.Builder(this@SettingsActivity).setTitle("AI API 连接失败").setMessage(error.message?:"未知错误").setPositiveButton("确定",null).show()}
            }
        }
        findViewById<Button>(R.id.btn_export).setOnClickListener{AlertDialog.Builder(this).setTitle("导出资料库备份").setMessage("包含笔记、目录、附件、剪贴板、模板、预设、笔刷、快捷访问及阅读设置。\n不包含 API Key、AI 对话历史和临时会话。请妥善保管备份中的笔记内容。").setNegativeButton("取消",null).setPositiveButton("选择保存位置"){_,_->exportBackup.launch("PENCIL-backup.zip")}.show()}
        findViewById<Button>(R.id.btn_import).setOnClickListener{confirmImport()}
    }

    private fun updateDraftShortcutLabel(){
        findViewById<Button>(R.id.btn_draft_shortcut_target).text="草稿纸快捷按钮目标：${runCatching{store.draftShortcutTarget()?.name}.getOrNull()?:"暂不可用"}"
    }
    private fun chooseDraftShortcutTarget(){
        val nodes=try{store.ensureDefaults()}catch(error:Exception){Toast.makeText(this,error.message,Toast.LENGTH_LONG).show();return}
        val folder=nodes.firstOrNull{it.type==com.vibetools.pencil.model.LibraryNodeType.FOLDER&&it.name=="草稿纸"&&it.parentId==null}?:return
        val descendants=mutableSetOf(folder.id)
        var changed=true
        while(changed){changed=false;nodes.filter{it.parentId in descendants}.forEach{if(descendants.add(it.id))changed=true}}
        val currentId=getSharedPreferences("pencil_session",MODE_PRIVATE).getString("node_id",null)
        LibraryTreePicker.show(this,"选择草稿纸快捷按钮目标",nodes,currentId,
            selectable={it.type==com.vibetools.pencil.model.LibraryNodeType.NOTE&&it.id in descendants},
            onReset={preferences.edit().remove("draft_shortcut_note_id").apply();updateDraftShortcutLabel()}){node->
            node?:return@show
            preferences.edit().putString("draft_shortcut_note_id",node.id).apply()
            updateDraftShortcutLabel()
            Toast.makeText(this,"草稿纸快捷目标已保存",Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmImport(){
        AlertDialog.Builder(this).setTitle("替换当前资料库？")
            .setMessage("将先校验备份，再替换当前笔记、目录、附件和剪贴板；不是合并导入。\n恢复前资料库将保留一份本地快照。当前设备 API Key 保持不变；旧版备份中的 Key 不会导入。\n校验与恢复期间请勿退出应用。")
            .setNegativeButton("取消",null).setPositiveButton("选择备份"){_,_->importBackup.launch(arrayOf("application/zip","application/octet-stream"))}.show()
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed(){if(backupRunning){Toast.makeText(this,"资料库操作中，请稍候",Toast.LENGTH_SHORT).show();return};super.onBackPressed()}
    private var backupRunning=false
    private var operation=com.vibetools.pencil.storage.BackupOperation()
    private fun backupTask(title:String,work:()->Unit,onSuccess:()->Unit){
        if(backupRunning)return
        backupRunning=true
        val dialog=AlertDialog.Builder(this).setTitle(title).setMessage("正在处理，请稍候。大资料库可能需要较长时间。")
            .setCancelable(false).setNegativeButton("取消",null).create()
        dialog.show()
        operation=com.vibetools.pencil.storage.BackupOperation{stage,percent->runOnUiThread{if(!isDestroyed){dialog.setMessage(stage+if(percent>=0)" · $percent%" else "");dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled=!stage.contains("不可取消")}}}
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener{operation.cancel();dialog.setMessage("正在安全取消…")}
        lifecycleScope.launch {
            try {
                val result=withContext(Dispatchers.IO+kotlinx.coroutines.NonCancellable){runCatching{work()}}
                if(!isDestroyed)result.onSuccess{onSuccess()}.onFailure{error->AlertDialog.Builder(this@SettingsActivity).setTitle("操作未完成")
                    .setMessage(error.message?:"请检查可用空间与备份文件；原资料库未被预先清空。")
                    .setPositiveButton("确定",null).show()}
            }finally{backupRunning=false;if(!isDestroyed)dialog.dismiss()}
        }
    }
    private fun exportTo(uri:Uri){backupTask("正在导出备份",{
        val temp=File.createTempFile("pencil-backup-",".zip",cacheDir)
        try{store.exportZip(temp,operation);val stream=contentResolver.openOutputStream(uri)?:error("无法打开目标文件")
            stream.use{out->temp.inputStream().use{input->val buffer=ByteArray(65536);var copied=0L;while(true){operation.check();val n=input.read(buffer);if(n<0)break;out.write(buffer,0,n);copied+=n;operation.report("正在写入备份文件",(copied*100/temp.length().coerceAtLeast(1)).toInt())}}}
        }finally{temp.delete()}
    }){Toast.makeText(this,"备份已导出（不含 API Key）",Toast.LENGTH_LONG).show()}}
    private fun importFrom(uri:Uri){backupTask("正在校验并恢复",{
        val temp=File.createTempFile("pencil-restore-",".zip",cacheDir)
        try{val stream=contentResolver.openInputStream(uri)?:error("无法读取备份文件")
            stream.use{input->temp.outputStream().use{out->val buffer=ByteArray(65536);var bytes=0L;while(true){operation.check();val n=input.read(buffer);if(n<0)break;bytes+=n;require(bytes<=4L*1024*1024*1024){"备份文件超过 4GB 安全上限"};out.write(buffer,0,n)}}}
            store.importZip(temp,operation)
        }finally{temp.delete()}
    }){Toast.makeText(this,"恢复成功，已保留恢复前快照",Toast.LENGTH_LONG).show();finish()}}
}
