package com.vibetools.pencil.ui

/** User-facing page numbers are one-based; renderer page indices are zero-based. */
object MergePageSelection {
    fun parse(raw:String,pageCount:Int):List<Int>? {
        if(pageCount<=0||raw.isBlank())return null
        val selected=sortedSetOf<Int>()
        val normalized=raw.trim().replace('，',',').replace('、',',').replace('；',',').replace(';',',').replace('–','-').replace('—','-')
        for(part in normalized.split(',')){
            val match=Regex("^\\s*(\\d+)\\s*(?:-\\s*(\\d+)\\s*)?$").matchEntire(part)?:return null
            val first=match.groupValues[1].toIntOrNull()?:return null
            val last=match.groupValues[2].takeIf{it.isNotEmpty()}?.toIntOrNull()?:if(match.groupValues[2].isEmpty())first else return null
            if(first !in 1..pageCount||last !in first..pageCount)return null
            for(page in first..last)selected.add(page-1)
        }
        return selected.toList().takeIf{it.isNotEmpty()}
    }
}
