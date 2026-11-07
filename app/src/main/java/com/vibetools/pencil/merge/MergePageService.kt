package com.vibetools.pencil.merge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.pdf.PdfRenderer
import com.vibetools.pencil.model.FileType
import com.vibetools.pencil.model.LibraryNode
import com.vibetools.pencil.model.CanvasImage
import com.vibetools.pencil.model.LibraryNodeType
import com.vibetools.pencil.model.NoteDocument
import com.vibetools.pencil.model.StoredLink
import com.vibetools.pencil.storage.LibraryStore
import com.vibetools.pencil.util.DocumentRasterizer
import java.io.FileOutputStream
import java.util.UUID

data class MergeCandidate(
    val label: String,
    val sourceId: String,
    val document: NoteDocument,
)

class MergePageService(private val library: LibraryStore, private val context: Context) {

    fun candidatesFor(node: LibraryNode, pages: List<Int> = emptyList()): List<MergeCandidate> {
        val path=library.displayPath(node)
        val selectedPages=if(node.noteType==FileType.PDF)pages else listOf(0)
        return selectedPages.map{page->
            val id=if(node.noteType==FileType.PDF)"${node.id}__page_$page" else node.id
            MergeCandidate(if(node.noteType==FileType.PDF)"$path · 第${page+1}页" else path,id,library.loadNote(id))
        }
    }

    fun create(selected: List<MergeCandidate>, target: LibraryNode? = null): LibraryNode {
        require(selected.isNotEmpty()) { "At least one page is required" }

        val mergedNode = target ?: run {
            val mergedFolder = library.loadNodes().first {
                it.name == MERGED_FOLDER_NAME && it.type == LibraryNodeType.FOLDER
            }
            library.createNode(
                name = "合并页 ${System.currentTimeMillis()}",
                type = LibraryNodeType.NOTE,
                parentId = mergedFolder.id,
            )
        }
        val target = NoteDocument(mergedNode.id)
        var horizontalOffset = 0f

        selected.forEach { candidate ->
            val source = candidate.document
            val pdfBackground = loadPdfBackground(candidate.sourceId)
            val bounds = if(pdfBackground!=null) android.graphics.RectF(0f,0f,pdfBackground.width.toFloat(),pdfBackground.height.toFloat()) else DocumentRasterizer.contentBounds(source)
            val sectionWidth = maxOf(bounds.width(),pdfBackground?.width?.toFloat()?:0f,MIN_SECTION_WIDTH)
            pdfBackground?.let{background->target.images+=CanvasImage(path=saveBackground(background),x=horizontalOffset,y=0f,width=background.width.toFloat(),height=background.height.toFloat());background.recycle()}

            target.strokes += source.strokes.map { stroke ->
                stroke.copy(
                    id = UUID.randomUUID().toString(),
                    points = stroke.points.map { point ->
                        PointF(
                            point.x - bounds.left + horizontalOffset,
                            point.y - bounds.top,
                        )
                    }.toMutableList(),
                )
            }
            target.texts += source.texts.map {
                it.copy(
                    id = UUID.randomUUID().toString(),
                    x = it.x - bounds.left + horizontalOffset,
                    y = it.y - bounds.top,
                )
            }
            target.images += source.images.map {
                it.copy(
                    id = UUID.randomUUID().toString(),
                    x = it.x - bounds.left + horizontalOffset,
                    y = it.y - bounds.top,
                )
            }
            target.links += source.links.map {
                it.copy(
                    id = UUID.randomUUID().toString(),
                    noteId = mergedNode.id,
                    x = it.x - bounds.left + horizontalOffset,
                    y = it.y - bounds.top,
                )
            }
            target.summaries += source.summaries.map {
                it.copy(
                    id = UUID.randomUUID().toString(),
                    noteId = mergedNode.id,
                    x = it.x - bounds.left + horizontalOffset,
                    y = it.y - bounds.top,
                )
            }
            target.links += StoredLink(
                noteId = mergedNode.id,
                x = horizontalOffset,
                y = 0f,
                kind = MERGE_SOURCE_KIND,
                value = candidate.sourceId,
                label = listOf(
                    bounds.left,
                    bounds.top,
                    horizontalOffset,
                    sectionWidth,
                    bounds.height(),
                ).joinToString(SEPARATOR),
            )
            horizontalOffset += sectionWidth + SECTION_GAP
        }

        library.saveNote(target)
        return mergedNode
    }

    fun sync(merged: NoteDocument): Int {
        val metadata = merged.links.filter { it.kind == MERGE_SOURCE_KIND }
        metadata.forEach { link -> syncSection(merged, link) }
        return metadata.size
    }

    private fun syncSection(merged: NoteDocument, metadata: StoredLink) {
        val values = metadata.label.split(SEPARATOR).mapNotNull(String::toFloatOrNull)
        if (values.size < METADATA_SIZE) return

        val sourceLeft = values[0]
        val sourceTop = values[1]
        val mergedLeft = values[2]
        val width = values[3]
        val height = values[4]
        val source = library.loadNote(metadata.value)

        fun insideSource(x: Float, y: Float): Boolean =
            x in sourceLeft..(sourceLeft + width) && y in sourceTop..(sourceTop + height)

        fun insideMerged(x: Float, y: Float): Boolean =
            x in mergedLeft..(mergedLeft + width) && y in 0f..height

        source.strokes.removeAll { stroke -> stroke.points.any { insideSource(it.x, it.y) } }
        source.texts.removeAll { insideSource(it.x, it.y) }
        source.images.removeAll { insideSource(it.x, it.y) }
        source.links.removeAll { it.kind != MERGE_SOURCE_KIND && insideSource(it.x, it.y) }
        source.summaries.removeAll { insideSource(it.x, it.y) }

        source.strokes += merged.strokes
            .filter { stroke -> stroke.points.any { insideMerged(it.x, it.y) } }
            .map { stroke ->
                stroke.copy(
                    id = UUID.randomUUID().toString(),
                    points = stroke.points.map { point ->
                        PointF(point.x - mergedLeft + sourceLeft, point.y + sourceTop)
                    }.toMutableList(),
                )
            }
        source.texts += merged.texts.filter { insideMerged(it.x, it.y) }.map {
            it.copy(
                id = UUID.randomUUID().toString(),
                x = it.x - mergedLeft + sourceLeft,
                y = it.y + sourceTop,
            )
        }
        source.images += merged.images.filter { insideMerged(it.x, it.y) }.map {
            it.copy(
                id = UUID.randomUUID().toString(),
                x = it.x - mergedLeft + sourceLeft,
                y = it.y + sourceTop,
            )
        }
        source.links += merged.links
            .filter { it.kind != MERGE_SOURCE_KIND && insideMerged(it.x, it.y) }
            .map {
                it.copy(
                    id = UUID.randomUUID().toString(),
                    noteId = source.noteId,
                    x = it.x - mergedLeft + sourceLeft,
                    y = it.y + sourceTop,
                )
            }
        source.summaries += merged.summaries.filter { insideMerged(it.x, it.y) }.map {
            it.copy(
                id = UUID.randomUUID().toString(),
                noteId = source.noteId,
                x = it.x - mergedLeft + sourceLeft,
                y = it.y + sourceTop,
            )
        }
        library.saveNote(source)
    }

    private fun loadPdfBackground(sourceId:String):Bitmap? { val match=Regex("^(.+)__page_(\\d+)$").find(sourceId)?:return null; val node=library.loadNodes().firstOrNull{it.id==match.groupValues[1]}?:return null; val uri=node.sourceUri?.let(android.net.Uri::parse)?:return null; val page=match.groupValues[2].toIntOrNull()?:return null; return runCatching{context.contentResolver.openFileDescriptor(uri,"r")!!.use{fd->PdfRenderer(fd).use{renderer->renderer.openPage(page.coerceIn(0,renderer.pageCount-1)).use{p->Bitmap.createBitmap(p.width,p.height,Bitmap.Config.ARGB_8888).also{it.eraseColor(android.graphics.Color.WHITE);p.render(it,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)}}}}}.getOrNull() }
    private fun saveBackground(bitmap:Bitmap):String { val file=java.io.File(context.filesDir,"pencil/attachments/merge_${UUID.randomUUID()}.png");file.parentFile?.mkdirs();FileOutputStream(file).use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};return file.path }

    private companion object {
        const val MERGED_FOLDER_NAME = "合并页"
        const val MERGE_SOURCE_KIND = "merge_source"
        const val SEPARATOR = "|"
        const val METADATA_SIZE = 5
        const val MIN_SECTION_WIDTH = 400f
        const val SECTION_GAP = 80f
    }
}
