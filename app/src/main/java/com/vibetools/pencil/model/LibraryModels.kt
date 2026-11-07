package com.vibetools.pencil.model

import java.util.UUID

enum class LibraryNodeType { FOLDER, NOTE }

data class LibraryNode(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val type: LibraryNodeType,
    var parentId: String? = null,
    var noteType: FileType = FileType.BLANK,
    var sourceUri: String? = null,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var chapterSourceId: String? = null,
    var chapterStartPage: Int? = null,
    var chapterEndPage: Int? = null,
    var chapterMerge: Boolean = false
)

data class StoredLink(
    val id: String = UUID.randomUUID().toString(),
    val noteId: String,
    val pageId: String = "page-1",
    val x: Float,
    val y: Float,
    val kind: String,
    val value: String,
    val label: String = value
)

data class StoredSummary(
    val id: String = UUID.randomUUID().toString(),
    val noteId: String,
    val chapterTitle: String,
    val concepts: String,
    val memory: String = "",
    val methods: String = "",
    val mistakes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    var x: Float = 0f,
    var y: Float = 0f
)

data class CanvasImage(
    val id: String = UUID.randomUUID().toString(),
    val path: String,
    var x: Float,
    var y: Float,
    var width: Float = 420f,
    var height: Float = 300f
)

data class NoteDocument(
    val noteId: String,
    val strokes: MutableList<Stroke> = mutableListOf(),
    val texts: MutableList<TextBox> = mutableListOf(),
    val bookmarks: MutableList<Bookmark> = mutableListOf(),
    val links: MutableList<StoredLink> = mutableListOf(),
    val summaries: MutableList<StoredSummary> = mutableListOf(),
    val images: MutableList<CanvasImage> = mutableListOf(),
    var backgroundHidden: Boolean = false,
    val foldedNotes: MutableList<FoldedNote> = mutableListOf(),
    val emphasisStars: MutableList<EmphasisStar> = mutableListOf()
)

data class DocumentExtras(
    val images: MutableList<CanvasImage>,
    val links: MutableList<StoredLink>,
    val summaries: MutableList<StoredSummary>,
    val bookmarks: MutableList<Bookmark>,
    val foldedNotes: MutableList<FoldedNote> = mutableListOf(),
    val emphasisStars: MutableList<EmphasisStar> = mutableListOf()
)

data class CanvasClip(
    val id: String = UUID.randomUUID().toString(),
    val strokes: MutableList<Stroke>,
    val texts: MutableList<TextBox>,
    val images: MutableList<CanvasImage> = mutableListOf(),
    val links: MutableList<StoredLink> = mutableListOf(),
    val summaries: MutableList<StoredSummary> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis()
)
