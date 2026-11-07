package com.vibetools.pencil.ui

object PdfPageNavigation {
    fun groupStart(pageIndex:Int,twoPage:Boolean):Int {
        val page=pageIndex.coerceAtLeast(0)
        return if(twoPage)page-page%2 else page
    }
    fun jumpTarget(inputPage:Int,offset:Int,pageCount:Int,twoPage:Boolean):Int? {
        val actual=inputPage.toLong()+offset.toLong()
        if(actual !in 1L..pageCount.toLong())return null
        return groupStart((actual-1L).toInt(),twoPage)
    }
}
