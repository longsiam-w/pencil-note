package com.vibetools.pencil.export

import android.content.ContentResolver
import android.net.Uri
import com.vibetools.pencil.model.FileType
import com.vibetools.pencil.model.LibraryNodeType
import com.vibetools.pencil.model.StoredSummary
import com.vibetools.pencil.storage.LibraryStore

class SummaryExporter(
    private val resolver: ContentResolver,
    private val library: LibraryStore,
) {
    fun export(destination: Uri,bookId:String?=null) {
        resolver.openOutputStream(destination)?.bufferedWriter()?.use { writer ->
            writer.write(buildMarkdown(bookId))
        } ?: error("Cannot open export destination")
    }

    fun buildMarkdown(bookId:String?=null): String = buildString {
        library.loadNodes()
            .filter { it.type == LibraryNodeType.NOTE && (bookId==null || it.id==bookId || it.chapterSourceId==bookId) }
            .forEach { node ->
                val documents = if (node.noteType == FileType.PDF) {
                    library.pageDocuments(node.id)
                } else {
                    listOf(library.loadNote(node.id))
                }
                val summaries = documents.flatMap { it.summaries }
                if (summaries.isEmpty()) return@forEach

                append("# ").append(node.name).append("\n\n")
                summaries.forEach { appendSummary(it) }
            }
    }

    private fun StringBuilder.appendSummary(summary: StoredSummary) {
        append("## ").append(summary.chapterTitle).append("\n\n")
        append("### 概念自查表\n").append(summary.concepts).append("\n\n")
        appendOptionalSection("背记内容", summary.memory)
        appendOptionalSection("解题方式", summary.methods)
        appendOptionalSection("错题索引", summary.mistakes)
    }

    private fun StringBuilder.appendOptionalSection(title: String, content: String) {
        if (content.isNotBlank()) append("### ").append(title).append('\n').append(content).append("\n\n")
    }
}
