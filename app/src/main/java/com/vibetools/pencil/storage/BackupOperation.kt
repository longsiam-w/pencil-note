package com.vibetools.pencil.storage

import java.io.IOException

class BackupOperation(val progress:(String,Int)->Unit = {_,_->}) {
    @Volatile private var cancelled=false
    @Volatile private var committing=false
    @Synchronized fun cancel(){if(!committing)cancelled=true}
    @Synchronized fun beginCommit(){check();committing=true;progress("正在切换资料库，此阶段不可取消",-1)}
    fun check(){if(cancelled)throw IOException("操作已取消，原资料库未更改")}
    fun report(stage:String,percent:Int=-1){check();progress(stage,percent)}
}
