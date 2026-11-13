package com.myfitai.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX6/D1: la bottomNav del figlio e' GONE quando Home/Cibo sono ospitate da TabHostActivity. Una vista GONE
 * (o non agganciata, o a altezza 0) non deve mai essere usata come ancora dello snackbar.
 */
class SnackbarAnchorsTest {

    @Test
    fun visibleAttachedAnchorWithHeightIsUsed() {
        assertTrue(SnackbarAnchors.isUsableAnchor(isAttachedToWindow = true, isShown = true, height = 168))
    }

    @Test
    fun goneAnchorInsideTabHostIsNotUsed() {
        // bottomNav del figlio nascosta dall'host: isShown = false.
        assertFalse(SnackbarAnchors.isUsableAnchor(isAttachedToWindow = true, isShown = false, height = 0))
        assertFalse(SnackbarAnchors.isUsableAnchor(isAttachedToWindow = true, isShown = false, height = 168))
    }

    @Test
    fun detachedOrZeroHeightAnchorIsNotUsed() {
        assertFalse(SnackbarAnchors.isUsableAnchor(isAttachedToWindow = false, isShown = true, height = 168))
        assertFalse(SnackbarAnchors.isUsableAnchor(isAttachedToWindow = true, isShown = true, height = 0))
    }
}
