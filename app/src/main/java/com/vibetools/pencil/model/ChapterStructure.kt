package com.vibetools.pencil.model

/** Idempotent migration: retain the note ID so external references still open its content. */
object ChapterStructure {
    fun migrate(nodes:MutableList<LibraryNode>):Boolean {
        val books=nodes.filter{it.type==LibraryNodeType.NOTE&&it.noteType==FileType.PDF}.map{it.id}.toSet()
        val legacy=nodes.filter{it.type==LibraryNodeType.NOTE&&!it.chapterMerge&&it.chapterSourceId in books&&nodes.none{parent->parent.id==it.parentId&&parent.type==LibraryNodeType.FOLDER&&parent.chapterSourceId==it.chapterSourceId}}
        legacy.forEach{note->
            val folder=LibraryNode(name=note.name,type=LibraryNodeType.FOLDER,parentId=note.parentId?:note.chapterSourceId,createdAt=note.createdAt,updatedAt=note.updatedAt,chapterSourceId=note.chapterSourceId,chapterStartPage=note.chapterStartPage,chapterEndPage=note.chapterEndPage)
            nodes.filter{it.parentId==note.id}.forEach{it.parentId=folder.id}
            note.name="主文件";note.parentId=folder.id;nodes.add(folder)
        }
        return legacy.isNotEmpty()
    }
}
