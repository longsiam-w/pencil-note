package com.vibetools.pencil.storage

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SafeFilesTest {
    @get:Rule val folder=TemporaryFolder()
    private fun archive(vararg entries:Pair<String,String>):File {
        val file=folder.newFile()
        ZipOutputStream(file.outputStream()).use{zip->entries.forEach{(name,text)->zip.putNextEntry(ZipEntry(name));zip.write(text.toByteArray());zip.closeEntry()}}
        return file
    }
    private fun rejects(action:()->Unit){try{action();fail("Expected validation failure")}catch(expected:Exception){}}
    @Test fun atomicWriteReplacesCompleteFile(){
        val file=folder.newFile();file.writeText("old")
        SafeFiles.write(file,"new")
        assertEquals("new",file.readText());assertFalse(File(file.parentFile,file.name+".writing").exists())
    }
    @Test fun corruptReadRecoversLastValidatedCopyAndPreservesDamage(){
        val file=folder.newFile();file.writeText("123")
        assertEquals(123,SafeFiles.read(file){it.toInt()})
        file.writeText("broken")
        assertEquals(123,SafeFiles.read(file){it.toInt()})
        assertEquals("broken",file.readText())
        assertEquals("broken",File(file.parentFile,file.name+".damaged").readText())
    }
    @Test fun noRecoveryDoesNotReturnEmptyData(){
        val file=folder.newFile();file.writeText("bad")
        rejects{SafeFiles.read(file){it.toInt()}}
        assertEquals("bad",file.readText())
    }
    @Test fun extractionAllowsLegacyBackup(){
        val zip=archive("library.json" to "[]","notes/n.json" to "{}","attachments/p.png" to "image")
        val stage=folder.newFolder();SafeFiles.extract(zip,stage)
        assertEquals("image",File(stage,"attachments/p.png").readText())
    }
    @Test fun traversalAndMissingIndexAreRejected(){
        rejects{SafeFiles.extract(archive("../escape" to "bad"),folder.newFolder())}
        rejects{SafeFiles.extract(archive("notes/n.json" to "{}"),folder.newFolder())}
    }
    @Test fun truncatedZipAndSizeLimitAreRejected(){
        val file=archive("library.json" to "[]");file.writeBytes(file.readBytes().dropLast(15).toByteArray())
        rejects{SafeFiles.extract(file,folder.newFolder())}
        rejects{SafeFiles.extract(archive("library.json" to "1234567890"),folder.newFolder(),maxBytes=5)}
    }
    @Test fun swapRetainsOldLibrary(){
        val parent=folder.newFolder();File(parent,"pencil").mkdir();File(parent,"pencil/library.json").writeText("old")
        val stage=File(parent,"stage").apply{mkdir()};File(stage,"library.json").writeText("new")
        LibrarySwap(parent).install(stage)
        assertEquals("new",File(parent,"pencil/library.json").readText())
        assertEquals("old",File(parent,"pencil-before-restore/library.json").readText())
    }
    @Test fun processDeathBetweenMovesRestoresOldLibrary(){
        val parent=folder.newFolder();File(parent,"pencil-before-restore").mkdir();File(parent,"pencil-before-restore/library.json").writeText("old")
        File(parent,"pencil-restore-journal").writeText("prepared")
        LibrarySwap(parent).recover()
        assertEquals("old",File(parent,"pencil/library.json").readText())
        assertFalse(File(parent,"pencil-restore-journal").exists())
    }
    @Test fun failedStageMoveRollsBack(){
        val parent=folder.newFolder();File(parent,"pencil").mkdir();File(parent,"pencil/library.json").writeText("old")
        rejects{LibrarySwap(parent).install(File(parent,"missing"))}
        assertEquals("old",File(parent,"pencil/library.json").readText())
    }
}
