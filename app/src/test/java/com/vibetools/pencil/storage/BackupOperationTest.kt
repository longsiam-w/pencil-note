package com.vibetools.pencil.storage

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class BackupOperationTest {
    @Test fun cancelledPreparationCannotCommit(){
        val operation=BackupOperation();operation.cancel()
        try{operation.beginCommit();fail("Cancelled work must not commit")}catch(expected:IOException){}
    }
    @Test fun committingCannotBeCancelled(){
        val messages=mutableListOf<String>();val operation=BackupOperation{stage,_->messages+=stage}
        operation.beginCommit();operation.cancel();operation.check()
        assertTrue(messages.single().contains("不可取消"))
    }
    @Test fun reportsProgress(){
        var value=0;val operation=BackupOperation{_,percent->value=percent}
        operation.report("校验",42);assertEquals(42,value)
    }
}
