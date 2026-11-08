package com.vibetools.pencil.ui

object SplitGeometry {
    fun ratio(value: Float): Float = if(value.isFinite()) value.coerceIn(.3f,.7f) else .5f
    fun divider(width: Int, ratio: Float) = (width * ratio(ratio)).toInt()
    fun fullyInside(left: Float, top: Float, right: Float, bottom: Float,
                    paneLeft: Float, paneTop: Float, paneRight: Float, paneBottom: Float): Boolean =
        listOf(left,top,right,bottom,paneLeft,paneTop,paneRight,paneBottom).all{it.isFinite()} &&
            right>left && bottom>top && left>=paneLeft && right<=paneRight && top>=paneTop && bottom<=paneBottom
}
