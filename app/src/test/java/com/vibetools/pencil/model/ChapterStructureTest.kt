package com.vibetools.pencil.model

import org.junit.Assert.*
import org.junit.Test

class ChapterStructureTest {
    @Test fun migrationPreservesNoteIdentityAndMovesChildren(){
        val book=LibraryNode(id="book",name="教材",type=LibraryNodeType.NOTE,noteType=FileType.PDF)
        val note=LibraryNode(id="chapter",name="第一章",type=LibraryNodeType.NOTE,parentId=book.id,createdAt=123L,chapterSourceId=book.id,chapterStartPage=2,chapterEndPage=7)
        val child=LibraryNode(id="child",name="练习",type=LibraryNodeType.NOTE,parentId=note.id)
        val merge=LibraryNode(id="merge",name="合并页",type=LibraryNodeType.NOTE,chapterSourceId=note.id,chapterMerge=true)
        val nodes=mutableListOf(book,note,child,merge)
        assertTrue(ChapterStructure.migrate(nodes))
        val folder=nodes.single{it.type==LibraryNodeType.FOLDER}
        assertEquals("第一章",folder.name);assertEquals(book.id,folder.parentId)
        assertEquals(123L,folder.createdAt);assertEquals(2,folder.chapterStartPage)
        assertEquals("chapter",note.id);assertEquals("主文件",note.name)
        assertEquals(folder.id,note.parentId);assertEquals(folder.id,child.parentId)
        assertEquals(note.id,merge.chapterSourceId)
        assertFalse(ChapterStructure.migrate(nodes));assertEquals(5,nodes.size)
    }
    @Test fun existingFolderNotesAndMergedPagesAreNotMigrated(){
        val book=LibraryNode(id="book",name="教材",type=LibraryNodeType.NOTE,noteType=FileType.PDF)
        val folder=LibraryNode(id="folder",name="章节",type=LibraryNodeType.FOLDER,parentId=book.id,chapterSourceId=book.id)
        val main=LibraryNode(name="主文件",type=LibraryNodeType.NOTE,parentId=folder.id,chapterSourceId=book.id)
        val merge=LibraryNode(name="合并页",type=LibraryNodeType.NOTE,chapterSourceId=book.id,chapterMerge=true)
        assertFalse(ChapterStructure.migrate(mutableListOf(book,folder,main,merge)))
    }
}
