package com.vibetools.pencil.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PdfPageNavigationTest {
    @Test fun `double page jump normalizes odd and even pages to one spread`() {
        assertEquals(0, PdfPageNavigation.jumpTarget(1, 0, 20, true))
        assertEquals(0, PdfPageNavigation.jumpTarget(2, 0, 20, true))
        assertEquals(2, PdfPageNavigation.jumpTarget(3, 0, 20, true))
        assertEquals(2, PdfPageNavigation.jumpTarget(4, 0, 20, true))
    }

    @Test fun `offset is applied before spread normalization`() {
        assertEquals(2, PdfPageNavigation.jumpTarget(1, 2, 20, true))
        assertEquals(2, PdfPageNavigation.jumpTarget(2, 1, 20, true))
        assertEquals(0, PdfPageNavigation.jumpTarget(3, -1, 20, true))
    }

    @Test fun `invalid shifted page is rejected`() {
        assertNull(PdfPageNavigation.jumpTarget(1, -1, 20, true))
        assertNull(PdfPageNavigation.jumpTarget(20, 1, 20, true))
    }
}
