package com.vibetools.pencil.ui

/** One owner for the entire pointer stream, including historical/coalesced samples. */
class SplitGestureGuard {
    var owner: Int? = null
        private set
    var invalid = false
        private set

    fun begin(x: Float, width: Int, divider: Int = width / 2) {
        owner = side(x, width, divider)
        invalid = owner == null
    }

    fun accept(xs: List<Float>, width: Int, divider: Int = width / 2): Boolean {
        if (invalid || owner == null) return false
        if (xs.any { side(it, width, divider) != owner }) invalid = true
        return !invalid
    }

    fun reset() { owner = null; invalid = false }

    private fun side(x: Float, width: Int, divider: Int): Int? = when {
        !x.isFinite() || width <= 0 || x < 0 || x >= width -> null
        x < divider -> 0
        else -> 1
    }
}

object SplitNoteIdentity {
    fun secondaryAfterMainChange(mainId: String?, secondaryId: String?): String? =
        secondaryId?.takeUnless { it == mainId }
}
