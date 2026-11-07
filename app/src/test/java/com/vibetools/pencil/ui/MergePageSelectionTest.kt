package com.vibetools.pencil.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MergePageSelectionTest {
    @Test fun parsesAndDeduplicatesInPageOrder(){
        assertEquals(listOf(0,2,4,5,6,7),MergePageSelection.parse("5-8,1,3,5",10))
        assertEquals(listOf(0,1,2,3),MergePageSelection.parse("1，2、3—4",4))
    }
    @Test fun rejectsInvalidRangesAndNumbers(){
        listOf("","0","11","4-2","1,","1,,2","abc","999999999999999999","1-999999999999999999").forEach{assertNull(it,MergePageSelection.parse(it,10))}
        assertNull(MergePageSelection.parse("1",0))
    }
}
