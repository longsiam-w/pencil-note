package com.vibetools.pencil.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

/** Shared between store instances (the editor and settings are separate activities). */
internal object LibraryIo { val lock = Any(); var generation = 0L }

internal object SafeFiles {
    fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".writing")
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8)); stream.fd.sync()
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }

    /** Retain the last successfully parsed version, not merely the last bytes read. */
    fun <T> read(file: File, parse: (String) -> T): T {
        val previous = File(file.parentFile, file.name + ".previous")
        try {
            val text = file.readText(); val result = parse(text)
            // Failure to refresh a recovery copy must not turn a valid read into an empty document.
            runCatching { if (!previous.exists() || previous.readText() != text) write(previous, text) }
            return result
        } catch (error: Exception) {
            if (previous.exists()) {
                try {
                    val recovered = parse(previous.readText())
                    if(file.exists())runCatching { file.copyTo(File(file.parentFile,file.name+".damaged"),overwrite=true) }
                    return recovered
                } catch (_: Exception) { /* report original failure */ }
            }
            throw IOException("无法读取 ${file.name}，原文件已保留，请从备份恢复", error)
        }
    }

    /** ZIP central-directory validation detects truncated archives before touching the active library. */
    fun extract(source: File, destination: File, maxBytes: Long = 4L * 1024 * 1024 * 1024, maxFiles: Int = 100000, operation:BackupOperation = BackupOperation()) {
        destination.mkdirs()
        var bytes = 0L; var count = 0
        val seen = mutableSetOf<String>()
        ZipFile(source).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                operation.report("正在解压校验",if(zip.size()>0)count*100/zip.size() else -1)
                val entry = entries.nextElement()
                val name = entry.name.replace('\\', '/')
                require(!name.startsWith('/') && name.split('/').none { it == ".." || it == "." }) { "备份包含不安全路径" }
                if (entry.isDirectory) continue
                require(++count <= maxFiles && seen.add(name)) { "备份文件过多或包含重复文件" }
                val allowed = name in setOf("library.json", "settings.json", "clipboard.json", "manifest.json", "preferences.json") ||
                    name.matches(Regex("notes/[A-Za-z0-9_.-]+\\.json")) || name.matches(Regex("attachments/[A-Za-z0-9_.-]+"))
                require(allowed) { "备份包含不支持的文件：$name" }
                val target = File(destination, name)
                require(target.canonicalPath.startsWith(destination.canonicalPath + File.separator)) { "备份路径越界" }
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(target).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            operation.check(); val n = input.read(buffer); if (n < 0) break
                            bytes += n; require(bytes <= maxBytes) { "备份解压后超过 4GB 安全上限" }
                            out.write(buffer, 0, n)
                        }
                        out.fd.sync()
                    }
                }
                // ZipFile streams need an explicit CRC check; some implementations don't validate it.
                val crc = java.util.zip.CRC32()
                target.inputStream().use { input -> val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;crc.update(buffer,0,n)} }
                require(entry.crc < 0 || crc.value == entry.crc) { "备份校验失败：$name" }
                require(entry.size < 0 || target.length() == entry.size) { "备份文件不完整：$name" }
            }
        }
        require(File(destination, "library.json").isFile) { "不是有效的 PENCIL 备份：缺少 library.json" }
    }
}

/** Same-filesystem directory swap. The journal is persisted before moving either directory. */
internal class LibrarySwap(private val parent: File) {
    private val active = File(parent, "pencil")
    private val previous = File(parent, "pencil-before-restore")
    private val journal = File(parent, "pencil-restore-journal")
    fun recover() {
        if (!journal.exists()) return
        if (!active.exists() && previous.exists()) check(previous.renameTo(active)) { "恢复旧资料库失败，请保留应用数据" }
        // If active exists, either the old or the fully staged new library is present.
        check(active.exists()) { "资料库恢复尚未完成，请保留应用数据" }
        check(journal.delete()) { "无法清除恢复标记" }
    }
    fun install(staged: File) {
        recover()
        if (previous.exists()) check(previous.deleteRecursively()) { "无法清理上一次恢复快照" }
        SafeFiles.write(journal, "prepared")
        try {
            if (active.exists()) check(active.renameTo(previous)) { "无法保存恢复前快照" }
            check(staged.renameTo(active)) { "无法切换到新资料库" }
            journal.delete() // A leftover marker is safe: recover() recognizes the complete active directory.
        } catch (error: Exception) {
            if (!active.exists() && previous.exists()) previous.renameTo(active)
            throw error
        }
    }
}
