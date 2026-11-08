package com.vibetools.pencil.storage

import android.content.Context
import android.graphics.PointF
import com.vibetools.pencil.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LibraryStore(private val context: Context) {
    private val root = synchronized(LibraryIo.lock) { LibrarySwap(context.filesDir).recover(); File(context.filesDir, "pencil").apply { mkdirs() } }
    private val openedGeneration = synchronized(LibraryIo.lock){LibraryIo.generation}
    private fun requireCurrentGeneration(){check(openedGeneration==LibraryIo.generation){"资料库已恢复，请重新打开页面后再修改"}}
    private val libraryFile = File(root, "library.json")
    private val notesDir = File(root, "notes").apply { mkdirs() }
    private val attachmentsDir = File(root, "attachments").apply { mkdirs() }

    fun displayPath(node: LibraryNode, nodes: List<LibraryNode> = loadNodes()): String {
        val byId = nodes.associateBy { it.id }
        val parts = mutableListOf<String>()
        var current: LibraryNode? = node
        while (current != null) {
            parts += current.name
            current = current.parentId?.let(byId::get)
        }
        return parts.asReversed().joinToString("/")
    }

    fun ensureDefaults(): MutableList<LibraryNode> {
        val nodes = loadNodes()
        if (nodes.none { it.name == "草稿纸" && it.type == LibraryNodeType.FOLDER }) {
            nodes += LibraryNode(name = "草稿纸", type = LibraryNodeType.FOLDER)
        }
        if (nodes.none { it.name == "合并页" && it.type == LibraryNodeType.FOLDER }) {
            nodes += LibraryNode(name = "合并页", type = LibraryNodeType.FOLDER)
        }
        if (nodes.none { it.name == "错题本" && it.type == LibraryNodeType.FOLDER }) {
            nodes += LibraryNode(name = "错题本", type = LibraryNodeType.FOLDER)
        }
        // Cleanup is explicit maintenance, never a side effect of opening the directory.
        if (nodes.none { it.type == LibraryNodeType.NOTE }) {
            nodes += LibraryNode(name = "我的笔记", type = LibraryNodeType.NOTE)
        }
        val draftFolder=nodes.firstOrNull{it.name=="草稿纸"&&it.type==LibraryNodeType.FOLDER}
        if(draftFolder!=null&&nodes.none{it.type==LibraryNodeType.NOTE&&it.parentId==draftFolder.id})nodes+=LibraryNode(name="草稿纸 1",type=LibraryNodeType.NOTE,parentId=draftFolder.id)
        if(!libraryFile.exists() || libraryFile.readText()!=encodeNodes(nodes).toString())saveNodes(nodes)
        return nodes
    }

    fun loadNodes(): MutableList<LibraryNode> = synchronized(LibraryIo.lock) {
        if(!libraryFile.exists()&&!File(root,"library.json.previous").exists())return@synchronized mutableListOf<LibraryNode>()
        SafeFiles.read(libraryFile) { raw -> decodeNodes(JSONArray(raw)) }.also{nodes->
            if(ChapterStructure.migrate(nodes)){
                if(libraryFile.exists()&&!File(root,"library-before-chapter-folders.json").exists())libraryFile.copyTo(File(root,"library-before-chapter-folders.json"))
                saveNodes(nodes)
            }
        }
    }

    private fun decodeNodes(array:JSONArray):MutableList<LibraryNode> {
        return MutableList(array.length()) { i ->
            val o = array.getJSONObject(i)
            LibraryNode(o.getString("id"), o.getString("name"), LibraryNodeType.valueOf(o.getString("type")), o.optString("parentId").ifBlank { null }, FileType.valueOf(o.optString("noteType", "BLANK")), o.optString("sourceUri").ifBlank { null }, o.optLong("createdAt"), o.optLong("updatedAt"), o.optString("chapterSourceId").ifBlank { null }, o.optInt("chapterStartPage", -1).takeIf { it >= 0 }, o.optInt("chapterEndPage", -1).takeIf { it >= 0 }, o.optBoolean("chapterMerge", false))
        }
    }

    private fun encodeNodes(nodes: List<LibraryNode>):JSONArray {
        val array = JSONArray()
        nodes.forEach { n -> array.put(JSONObject().put("id", n.id).put("name", n.name).put("type", n.type.name).put("parentId", n.parentId ?: "").put("noteType", n.noteType.name).put("sourceUri", n.sourceUri ?: "").put("createdAt", n.createdAt).put("updatedAt", n.updatedAt).put("chapterSourceId",n.chapterSourceId?:"").put("chapterStartPage",n.chapterStartPage?:-1).put("chapterEndPage",n.chapterEndPage?:-1).put("chapterMerge",n.chapterMerge)) }
        return array
    }

    fun saveNodes(nodes:List<LibraryNode>) = synchronized(LibraryIo.lock) {
        requireCurrentGeneration()
        if(libraryFile.exists())SafeFiles.read(libraryFile){decodeNodes(JSONArray(it))}
        SafeFiles.write(libraryFile,encodeNodes(nodes).toString())
    }

    fun createNode(name: String, type: LibraryNodeType, parentId: String? = null, noteType: FileType = FileType.BLANK, sourceUri: String? = null): LibraryNode {
        val nodes = loadNodes(); val node = LibraryNode(name = name, type = type, parentId = parentId, noteType = noteType, sourceUri = sourceUri); nodes += node; saveNodes(nodes); return node
    }
    fun createChapter(source:LibraryNode,title:String,startPage:Int,endPage:Int,files:List<String> = listOf("主文件")):LibraryNode{
        require(source.noteType==FileType.PDF)
        require(files.isNotEmpty()&&files.all{it.isNotBlank()}&&files.distinct().size==files.size)
        val nodes=loadNodes()
        val chapter=LibraryNode(name=title,type=LibraryNodeType.FOLDER,parentId=source.id,chapterSourceId=source.id,chapterStartPage=startPage,chapterEndPage=endPage)
        nodes+=chapter
        files.forEach{name->nodes+=LibraryNode(name=name,type=LibraryNodeType.NOTE,parentId=chapter.id,chapterSourceId=source.id,chapterStartPage=startPage,chapterEndPage=endPage)}
        saveNodes(nodes);return chapter
    }
    fun findChapterMerge(chapterId:String):LibraryNode?=loadNodes().firstOrNull{it.chapterMerge&&it.chapterSourceId==chapterId}
    fun createChapterMerge(chapter:LibraryNode):LibraryNode{val node=LibraryNode(name="${chapter.name} · 合并页",type=LibraryNodeType.NOTE,chapterSourceId=chapter.id,chapterStartPage=chapter.chapterStartPage,chapterEndPage=chapter.chapterEndPage,chapterMerge=true);val nodes=loadNodes();nodes+=node;saveNodes(nodes);return node}

    fun moveNode(id:String,parentId:String?){val nodes=loadNodes();nodes.firstOrNull{it.id==id}?.let{it.parentId=parentId;it.updatedAt=System.currentTimeMillis();saveNodes(nodes)}}
    fun rename(id: String, name: String) { val nodes = loadNodes(); val node=nodes.find { it.id == id } ?: return;if(isSpecial(node))return;node.name = name;node.updatedAt=System.currentTimeMillis();saveNodes(nodes) }
    fun delete(id: String) { val nodes = loadNodes();val rootNode=nodes.find{it.id==id}?:return;if(isSpecial(rootNode))return;val ids = mutableSetOf(id); var changed: Boolean; do { changed = false; nodes.filter { it.parentId in ids && it.id !in ids }.forEach { ids += it.id; changed = true } } while (changed);val removed=nodes.filter{it.id in ids};nodes.removeAll { it.id in ids }; saveNodes(nodes); ids.forEach { noteId -> notesDir.listFiles()?.filter{it.name=="$noteId.json"||it.name.startsWith("${noteId}__page_")}?.forEach(File::delete) };removed.mapNotNull{it.sourceUri}.forEach{uri->runCatching{val file=File(android.net.Uri.parse(uri).path.orEmpty());if(file.parentFile==attachmentsDir)file.delete()}} }
    fun isSpecial(node: LibraryNode):Boolean = node.type==LibraryNodeType.FOLDER && node.parentId==null && (node.name=="草稿纸"||node.name=="合并页"||node.name=="错题本")
    fun wrongBookFolder():LibraryNode?=loadNodes().firstOrNull{it.name=="错题本"&&it.type==LibraryNodeType.FOLDER&&it.parentId==null}
    fun todayWrongBook():LibraryNode{val nodes=ensureDefaults();val folder=nodes.first{it.name=="错题本"&&it.type==LibraryNodeType.FOLDER};val name="DAY${wrongBookDayNumber()}";return nodes.firstOrNull{it.parentId==folder.id&&it.name==name}?:LibraryNode(name=name,type=LibraryNodeType.NOTE,parentId=folder.id).also{nodes+=it;saveNodes(nodes)}}
    fun currentDayNumber():Long=wrongBookDayNumber()
    private fun wrongBookDayNumber():Long{val base=java.util.Calendar.getInstance().apply{set(2025,java.util.Calendar.MAY,22,0,0,0);set(java.util.Calendar.MILLISECOND,0)};val today=java.util.Calendar.getInstance().apply{set(java.util.Calendar.HOUR_OF_DAY,0);set(java.util.Calendar.MINUTE,0);set(java.util.Calendar.SECOND,0);set(java.util.Calendar.MILLISECOND,0)};return ((today.timeInMillis-base.timeInMillis)/86400000L)+1L}
    private fun cleanupEmptyWrongBooks(nodes:MutableList<LibraryNode>){val folder=nodes.firstOrNull{it.name=="错题本"&&it.type==LibraryNodeType.FOLDER}?:return;val today=wrongBookDayNumber();val remove=nodes.filter{it.parentId==folder.id&&it.name.startsWith("DAY")&&it.name.removePrefix("DAY").toLongOrNull()?.let{n->n<today}==true&&loadNote(it.id).let{doc->doc.strokes.isEmpty()&&doc.texts.isEmpty()&&doc.images.isEmpty()&&doc.links.isEmpty()&&doc.summaries.isEmpty()&&doc.bookmarks.isEmpty()&&doc.foldedNotes.isEmpty()&&doc.emphasisStars.isEmpty()}};if(remove.isNotEmpty()){nodes.removeAll(remove.toSet());remove.forEach{File(notesDir,"${it.id}.json").delete()}}}

    fun draftShortcutTarget():LibraryNode? {
        val nodes=loadNodes()
        val folder=nodes.firstOrNull{it.type==LibraryNodeType.FOLDER&&it.name=="草稿纸"&&it.parentId==null}?:return null
        val candidates=nodes.filter{it.type==LibraryNodeType.NOTE&&it.parentId==folder.id}
        val targetId=context.getSharedPreferences("pencil_settings",Context.MODE_PRIVATE).getString("draft_shortcut_note_id",null)
        return candidates.firstOrNull{it.id==targetId}?:candidates.firstOrNull{it.name=="草稿纸 1"}?:candidates.firstOrNull()
    }

    fun loadNote(noteId:String):NoteDocument = synchronized(LibraryIo.lock) {
        val file=File(notesDir,"$noteId.json")
        if(!file.exists()&&!File(notesDir,"$noteId.json.previous").exists())NoteDocument(noteId) else SafeFiles.read(file){decodeNote(JSONObject(it))}
    }
    fun loadNoteIfExists(noteId: String): NoteDocument? { val file=File(notesDir,"$noteId.json");return if(file.exists()||File(notesDir,"$noteId.json.previous").exists())loadNote(noteId) else null }
    fun pageDocuments(nodeId:String):List<NoteDocument> = notesDir.listFiles()?.mapNotNull{file->Regex("^${Regex.escape(nodeId)}__page_(\\d+)\\.json$").matchEntire(file.name)?.let{match->match.groupValues[1].toInt() to runCatching{decodeNote(JSONObject(file.readText()))}.getOrNull()}}?.filter{it.second!=null}?.sortedBy{it.first}?.mapNotNull{it.second}?:emptyList()
    fun findBookmark(name:String):Pair<LibraryNode,Bookmark>? { loadNodes().filter{it.type==LibraryNodeType.NOTE}.forEach{node->val docs=if(node.noteType==FileType.PDF)pageDocuments(node.id) else listOf(loadNote(node.id));docs.forEach{doc->doc.bookmarks.firstOrNull{it.name.equals(name,true)}?.let{return node to it}}};return null }
    fun pdfPageCount(node:LibraryNode):Int = runCatching{context.contentResolver.openFileDescriptor(android.net.Uri.parse(node.sourceUri),"r")!!.use{fd->android.graphics.pdf.PdfRenderer(fd).use{it.pageCount}}}.getOrDefault(0)
    private data class MetadataCache(val modified:Long,val size:Long,val note:NoteDocument)
    private val metadataCache=mutableMapOf<String,MetadataCache>()
    /** Skip stroke/image payloads entirely, and reuse unchanged page metadata across searches. */
    @Synchronized fun searchMetadata():List<NoteDocument>{
        val files=notesDir.listFiles()?.filter{it.extension=="json"}.orEmpty()
        val paths=files.map{it.path}.toSet();metadataCache.keys.retainAll(paths)
        return files.mapNotNull{file->
            val old=metadataCache[file.path]
            if(old!=null&&old.modified==file.lastModified()&&old.size==file.length())old.note
            else runCatching{
                val root=JSONObject().put("noteId",file.nameWithoutExtension)
                android.util.JsonReader(file.reader()).use{reader->
                    reader.beginObject()
                    while(reader.hasNext()){
                        val key=reader.nextName()
                        if(key=="bookmarks"||key=="summaries"||key=="links"){
                            val array=JSONArray();reader.beginArray()
                            while(reader.hasNext()){
                                val item=JSONObject();reader.beginObject()
                                while(reader.hasNext()){
                                    val name=reader.nextName()
                                    when(reader.peek()){
                                        android.util.JsonToken.STRING,android.util.JsonToken.NUMBER->item.put(name,reader.nextString())
                                        android.util.JsonToken.BOOLEAN->item.put(name,reader.nextBoolean())
                                        else->reader.skipValue()
                                    }
                                };reader.endObject();array.put(item)
                            };reader.endArray();root.put(key,array)
                        }else reader.skipValue()
                    };reader.endObject()
                }
                decodeNote(root).also{metadataCache[file.path]=MetadataCache(file.lastModified(),file.length(),it)}
            }.getOrNull()
        }
    }
    // A durable redo intent keeps a two-document move recoverable after process death.
    private val transferJournal get() = File(root,"split-transfer.json")
    init { synchronized(LibraryIo.lock) { recoverSplitTransfer() } }
    private fun recoverSplitTransfer() {
        if(!transferJournal.exists())return
        val intent=SafeFiles.read(transferJournal){JSONObject(it)}
        val notes=intent.optJSONArray("notes")?:return
        for(i in 0 until notes.length()){
            val note=decodeNote(notes.getJSONObject(i))
            require(note.noteId.matches(Regex("[A-Za-z0-9_-]+"))){"跨笔记移动记录无效"}
            SafeFiles.write(File(notesDir,"${note.noteId}.json"),encodeNote(note).toString())
        }
        SafeFiles.write(transferJournal,"{}")
    }
    fun saveSplitTransfer(source:NoteDocument,target:NoteDocument)=synchronized(LibraryIo.lock){
        requireCurrentGeneration()
        require(source.noteId!=target.noteId)
        recoverSplitTransfer()
        SafeFiles.write(transferJournal,JSONObject().put("notes",JSONArray().put(encodeNote(target)).put(encodeNote(source))).toString())
        recoverSplitTransfer()
    }
    fun saveNote(note:NoteDocument) = synchronized(LibraryIo.lock) {
        requireCurrentGeneration()
        val file=File(notesDir,"${note.noteId}.json")
        if(file.exists())SafeFiles.read(file){decodeNote(JSONObject(it))}
        SafeFiles.write(file,encodeNote(note).toString())
    }
    fun cleanupAttachments(extraReferences:Set<String> = emptySet()){val referenced=mutableSetOf<String>();loadNodes().mapNotNull{it.sourceUri}.forEach{uri->android.net.Uri.parse(uri).path?.let(referenced::add)};notesDir.listFiles()?.forEach{file->runCatching{decodeNote(JSONObject(file.readText())).let { note -> (note.images+note.foldedNotes.flatMap{it.images}).mapTo(referenced){it.path} }}};referenced+=extraReferences;attachmentsDir.listFiles()?.filter{it.path !in referenced}?.forEach(File::delete)}

    fun importAttachment(input: java.io.InputStream, displayName: String): File { val safe=displayName.replace(Regex("[^A-Za-z0-9._-]"),"_");val target=File(attachmentsDir,"${UUID.randomUUID()}_$safe");target.outputStream().use{input.copyTo(it)};return target }

    /** Credentials and conversation history are deliberately excluded. */
    fun exportZip(destination:File,operation:BackupOperation=BackupOperation()) = synchronized(LibraryIo.lock) {
        val nodes=loadNodes()
        val settings=context.getSharedPreferences("pencil_settings",Context.MODE_PRIVATE)
        val config=JSONObject()
        listOf("api_url","api_model","ai_default_prompt").forEach{config.put(it,settings.getString(it,""))}
        listOf("highlighter_snap","fold_keep_highlighter_images","pdf_two_page_mode").forEach{config.put(it,settings.getBoolean(it,false))}
        config.put("selection_cut_strokes",settings.getBoolean("selection_cut_strokes",true)).put("ai_timeout_seconds",settings.getInt("ai_timeout_seconds",120))
        val hashes=JSONObject()
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            fun add(name:String,bytes:ByteArray){operation.report("正在打包：$name");zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry();hashes.put(name,sha256(bytes))}
            fun addFile(name:String,file:File){
                operation.report("正在打包：$name");zip.putNextEntry(ZipEntry(name));val digest=java.security.MessageDigest.getInstance("SHA-256")
                file.inputStream().use{input->val buffer=ByteArray(65536);while(true){operation.check();val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n);zip.write(buffer,0,n)}}
                zip.closeEntry();hashes.put(name,digest.digest().joinToString(""){"%02x".format(it)})
            }
            add("library.json",encodeNodes(nodes).toString().toByteArray(Charsets.UTF_8))
            notesDir.listFiles()?.filter{it.extension=="json"}?.forEach{file->val note=loadNote(file.nameWithoutExtension);add("notes/${file.name}",encodeNote(note).toString().toByteArray(Charsets.UTF_8))}
            attachmentsDir.listFiles()?.filter{it.isFile}?.forEach{addFile("attachments/${it.name}",it)}
            File(root,"clipboard.json").takeIf{it.exists()}?.let{addFile("clipboard.json",it)}
            add("settings.json",config.toString().toByteArray(Charsets.UTF_8))
            add("preferences.json",BackupPreferences.capture(context).toString().toByteArray(Charsets.UTF_8))
            val manifest=JSONObject().put("format","PENCIL").put("version",1).put("createdAt",System.currentTimeMillis()).put("credentialsIncluded",false)
                .put("scope","笔记、目录、附件、剪贴板、模板、预设、笔刷、快捷访问及阅读设置；不包含 API Key、AI 对话历史和临时会话").put("sha256",hashes)
            zip.putNextEntry(ZipEntry("manifest.json"));zip.write(manifest.toString().toByteArray(Charsets.UTF_8));zip.closeEntry()
        }
    }
    private fun sha256(bytes:ByteArray):String=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun fileHash(file:File):String{val digest=java.security.MessageDigest.getInstance("SHA-256");file.inputStream().use{input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}};return digest.digest().joinToString(""){"%02x".format(it)}}

    fun importZip(source:File,operation:BackupOperation=BackupOperation()) = synchronized(LibraryIo.lock) {
        val stage=File(context.filesDir,"pencil-stage-${UUID.randomUUID()}")
        try {
            SafeFiles.extract(source,stage,operation=operation)
            val manifestFile=File(stage,"manifest.json")
            if(manifestFile.exists()){
                val manifest=JSONObject(manifestFile.readText())
                require(manifest.getString("format")=="PENCIL"&&manifest.getInt("version")==1){"不支持的备份版本"}
                val hashes=manifest.getJSONObject("sha256")
                val files=stage.walkTopDown().filter{it.isFile&&it.name!="manifest.json"}.toList()
                require(hashes.length()==files.size){"备份清单与文件数量不一致"}
                files.forEach{file->val name=file.relativeTo(stage).invariantSeparatorsPath;require(hashes.has(name)&&hashes.getString(name)==fileHash(file)){"文件校验失败：$name"}}
            }
            val nodes=decodeNodes(JSONArray(File(stage,"library.json").readText()))
            val ids=nodes.map{it.id}.toSet()
            require(ids.size==nodes.size&&ids.all{it.matches(Regex("[A-Za-z0-9_-]+"))}){"目录 ID 无效或重复"}
            nodes.forEach{node->
                require(node.parentId==null||node.parentId in ids){"目录父节点缺失"}
                val visited=mutableSetOf<String>();var cursor:LibraryNode?=node
                val byId=nodes.associateBy{it.id}
                while(cursor!=null){require(visited.add(cursor.id)){"目录包含循环引用"};cursor=byId[cursor.parentId]}
            }
            fun attachment(path:String):String {
                val name=android.net.Uri.parse(path).lastPathSegment?:throw java.io.IOException("附件路径无效")
                require(File(stage,"attachments/$name").isFile){"备份缺少附件：$name"}
                return File(attachmentsDir,name).absolutePath
            }
            fun remap(value:Any?) {
                when(value){
                    is JSONObject->{
                        if(value.has("images")){val images=value.getJSONArray("images");for(i in 0 until images.length()){val image=images.getJSONObject(i);image.put("path",attachment(image.getString("path")))}}
                        val keys=value.keys();while(keys.hasNext()){val key=keys.next();if(key!="images")remap(value.opt(key))}
                    }
                    is JSONArray->for(i in 0 until value.length())remap(value.opt(i))
                }
            }
            File(stage,"notes").listFiles()?.forEach{file->
                val json=JSONObject(file.readText());require(json.getString("noteId")==file.nameWithoutExtension){"笔记 ID 与文件名不一致"}
                decodeNote(json);remap(json);SafeFiles.write(file,json.toString())
            }
            File(stage,"clipboard.json").takeIf{it.exists()}?.let{file->val json=JSONArray(file.readText());remap(json);SafeFiles.write(file,json.toString())}
            nodes.forEach{node->node.sourceUri?.let{node.sourceUri=android.net.Uri.fromFile(File(attachment(it))).toString()}}
            SafeFiles.write(File(stage,"library.json"),encodeNodes(nodes).toString())
            val config=File(stage,"settings.json").takeIf{it.exists()}?.let{JSONObject(it.readText())}
            // Validate all imported preference types before the directory swap.
            config?.let{o->
                listOf("api_url","api_model","ai_default_prompt").forEach{if(o.has(it))o.getString(it)}
                listOf("highlighter_snap","fold_keep_highlighter_images","pdf_two_page_mode","selection_cut_strokes").forEach{if(o.has(it))o.getBoolean(it)}
                if(o.has("ai_timeout_seconds"))o.getInt("ai_timeout_seconds")
            }
            val importedPreferences=File(stage,"preferences.json").takeIf{it.exists()}?.let{JSONObject(it.readText()).also{value->BackupPreferences.validate(value)}}
            // Keep the active device's credential, including when importing legacy backups containing a key.
            File(stage,"settings.json").delete();manifestFile.delete()
            operation.beginCommit()
            LibrarySwap(context.filesDir).install(stage)
            LibraryIo.generation++
            metadataCache.clear()
            importedPreferences?.let{BackupPreferences.restore(context,it)}
            config?.let{o->val edit=context.getSharedPreferences("pencil_settings",Context.MODE_PRIVATE).edit()
                listOf("api_url","api_model","ai_default_prompt").forEach{if(o.has(it))edit.putString(it,o.getString(it))}
                listOf("highlighter_snap","fold_keep_highlighter_images","pdf_two_page_mode","selection_cut_strokes").forEach{if(o.has(it))edit.putBoolean(it,o.getBoolean(it))}
                if(o.has("ai_timeout_seconds"))edit.putInt("ai_timeout_seconds",o.getInt("ai_timeout_seconds").coerceIn(5,1800))
                edit.apply()
            }
            context.getSharedPreferences("pencil_session",Context.MODE_PRIVATE).edit().putBoolean("library_restored",true).apply()
        } finally { if(stage.exists())stage.deleteRecursively() }
    }

    fun hasRestoreSnapshot():Boolean=File(context.filesDir,"pencil-before-restore/library.json").isFile
    fun rollbackRestore()=synchronized(LibraryIo.lock){
        val snapshot=File(context.filesDir,"pencil-before-restore")
        require(hasRestoreSnapshot()){"没有可回退的恢复前快照"}
        decodeNodes(JSONArray(File(snapshot,"library.json").readText()))
        val staged=File(context.filesDir,"pencil-rollback-${UUID.randomUUID()}")
        check(snapshot.renameTo(staged)){"无法准备回退快照"}
        try{LibrarySwap(context.filesDir).install(staged)}catch(error:Exception){if(staged.exists())staged.renameTo(snapshot);throw error}
        LibraryIo.generation++
        context.getSharedPreferences("pencil_session",Context.MODE_PRIVATE).edit().putBoolean("library_restored",true).apply()
    }

    private fun encodeNote(note: NoteDocument): JSONObject {
        val root = JSONObject().put("noteId", note.noteId).put("backgroundHidden",note.backgroundHidden)
        root.put("strokes", JSONArray().also { a -> note.strokes.forEach { s -> a.put(JSONObject().put("id", s.id).put("tool", s.tool.name).put("color", s.color).put("width", s.width).put("alpha", s.alpha).put("points", JSONArray().also { p -> s.points.forEach { point -> p.put(JSONObject().put("x", point.x).put("y", point.y)) } })) } })
        root.put("texts", JSONArray().also { a -> note.texts.forEach { t -> a.put(JSONObject().put("id", t.id).put("text", t.text).put("x", t.x).put("y", t.y).put("fontSize", t.fontSize).put("color", t.color)) } })
        root.put("bookmarks", JSONArray().also { a -> note.bookmarks.filterNot{it.temporary}.forEach { b -> a.put(JSONObject().put("id", b.id).put("name", b.name).put("fileId", b.fileId).put("pageId", b.pageId).put("x", b.x).put("y", b.y)) } })
        root.put("links", JSONArray().also { a -> note.links.forEach { l -> a.put(JSONObject().put("id", l.id).put("noteId", l.noteId).put("x", l.x).put("y", l.y).put("kind", l.kind).put("value", l.value).put("label", l.label)) } })
        root.put("summaries", JSONArray().also { a -> note.summaries.forEach { s -> a.put(JSONObject().put("id", s.id).put("noteId", s.noteId).put("chapterTitle", s.chapterTitle).put("concepts", s.concepts).put("memory", s.memory).put("methods", s.methods).put("mistakes", s.mistakes).put("createdAt", s.createdAt).put("x",s.x).put("y",s.y)) } })
        root.put("images",JSONArray().also{a->note.images.forEach{i->a.put(JSONObject().put("id",i.id).put("path",i.path).put("x",i.x).put("y",i.y).put("width",i.width).put("height",i.height))}})
        root.put("foldedNotes",JSONArray().also { a -> note.foldedNotes.forEach { f ->
            val content=NoteDocument(note.noteId,strokes=f.strokes.toMutableList(),texts=f.texts.toMutableList(),images=f.images.toMutableList(),links=f.links.toMutableList(),summaries=f.summaries.toMutableList())
            a.put(JSONObject().put("id",f.id).put("x",f.x).put("y",f.y).put("expanded",f.expanded).put("content",encodeNote(content)))
        } })
        root.put("emphasisStars",JSONArray().also{a->note.emphasisStars.forEach{s->a.put(JSONObject().put("id",s.id).put("x",s.x).put("y",s.y).put("size",s.size))}})
        return root
    }

    private fun decodeNote(root: JSONObject): NoteDocument {
        val note = NoteDocument(root.optString("noteId"),backgroundHidden=root.optBoolean("backgroundHidden",false))
        root.optJSONArray("strokes")?.let { a -> for (i in 0 until a.length()) { val o=a.getJSONObject(i); val points=mutableListOf<PointF>(); o.optJSONArray("points")?.let { p -> for(j in 0 until p.length()){ val q=p.getJSONObject(j); points+=PointF(q.optDouble("x").toFloat(),q.optDouble("y").toFloat()) } }; note.strokes+=Stroke(o.getString("id"),Tool.valueOf(o.getString("tool")),o.optInt("color"),o.optDouble("width").toFloat(),o.optInt("alpha",255),points) } }
        root.optJSONArray("texts")?.let { a -> for(i in 0 until a.length()){val o=a.getJSONObject(i);note.texts+=TextBox(o.getString("id"),o.getString("text"),o.optDouble("x").toFloat(),o.optDouble("y").toFloat(),o.optDouble("fontSize",18.0).toFloat(),o.optInt("color"))} }
        root.optJSONArray("bookmarks")?.let { a -> for(i in 0 until a.length()){val o=a.getJSONObject(i);note.bookmarks+=Bookmark(o.getString("id"),o.getString("name"),o.getString("fileId"),o.optString("pageId","page-1"),o.optDouble("x").toFloat(),o.optDouble("y").toFloat(),temporary=o.optBoolean("temporary",false))} }
        root.optJSONArray("links")?.let { a -> for(i in 0 until a.length()){val o=a.getJSONObject(i);note.links+=StoredLink(o.getString("id"),o.getString("noteId"),x=o.optDouble("x").toFloat(),y=o.optDouble("y").toFloat(),kind=o.getString("kind"),value=o.getString("value"),label=o.optString("label"))} }
        root.optJSONArray("summaries")?.let { a -> for(i in 0 until a.length()){val o=a.getJSONObject(i);note.summaries+=StoredSummary(o.getString("id"),o.getString("noteId"),o.getString("chapterTitle"),o.getString("concepts"),o.optString("memory"),o.optString("methods"),o.optString("mistakes"),o.optLong("createdAt"),o.optDouble("x").toFloat(),o.optDouble("y").toFloat())} }
        root.optJSONArray("images")?.let{a->for(i in 0 until a.length()){val o=a.getJSONObject(i);note.images+=CanvasImage(o.getString("id"),o.getString("path"),o.optDouble("x").toFloat(),o.optDouble("y").toFloat(),o.optDouble("width",420.0).toFloat(),o.optDouble("height",300.0).toFloat())}}
        root.optJSONArray("foldedNotes")?.let { a -> for(i in 0 until a.length()) {
            val o=a.getJSONObject(i);val content=decodeNote(o.getJSONObject("content"))
            note.foldedNotes+=FoldedNote(id=o.getString("id"),x=o.optDouble("x").toFloat(),y=o.optDouble("y").toFloat(),expanded=o.optBoolean("expanded",false),strokes=content.strokes,texts=content.texts,images=content.images,links=content.links,summaries=content.summaries)
        } }
        root.optJSONArray("emphasisStars")?.let{a->for(i in 0 until minOf(2,a.length())){val o=a.getJSONObject(i);note.emphasisStars+=EmphasisStar(id=o.getString("id"),x=o.optDouble("x").toFloat(),y=o.optDouble("y").toFloat(),size=o.optDouble("size",64.0).toFloat().coerceIn(24f,240f))}}
        return note
    }
}
