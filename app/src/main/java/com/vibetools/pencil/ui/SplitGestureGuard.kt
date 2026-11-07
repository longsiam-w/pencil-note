package com.vibetools.pencil.ui

/** One owner for the entire pointer stream, including historical/coalesced samples. */
class SplitGestureGuard {
    var owner: Int? = null
        private set
    var invalid = false
        private set

    fun begin(x: Float, width: Int) {
        owner = side(x, width)
        invalid = owner == null
    }

    fun accept(xs: List<Float>, width: Int): Boolean {
        if (invalid || owner == null) return false
        if (xs.any { side(it, width) != owner }) invalid = true
        return !invalid
    }

    fun reset() { owner = null; invalid = false }

    private fun side(x: Float, width: Int): Int? = when {
        !x.isFinite() || width <= 0 || x < 0 || x >= width -> null
        x < width / 2 -> 0
        else -> 1
    }
}

object SplitNoteIdentity {
    fun secondaryAfterMainChange(mainId: String?, secondaryId: String?): String? =
        secondaryId?.takeUnless { it == mainId }
}
