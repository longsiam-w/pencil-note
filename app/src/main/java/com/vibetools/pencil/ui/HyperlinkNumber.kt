package com.vibetools.pencil.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Generates a creation-time label and advances by one second if that label is already taken. */
object HyperlinkNumber {
    fun create(now:Long,existing:Set<String>,locale:Locale=Locale.US):String {
        val formatter=SimpleDateFormat("yyMMddHHmmss",locale)
        var candidate=now
        while(true){
            val value=formatter.format(Date(candidate))
            if(value !in existing)return value
            candidate+=1000L
        }
    }
}
