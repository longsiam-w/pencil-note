package com.vibetools.pencil.ui

/** Routing state is independent of the shortcut group being displayed. */
class QuickAccessTarget {
    var secondary = false
        private set
    var splitEnabled = false
        private set
    fun setSplitEnabled(enabled: Boolean) { splitEnabled=enabled;if(!enabled)secondary=false }
    fun toggle() { if(splitEnabled)secondary=!secondary }
}
