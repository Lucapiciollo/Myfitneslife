package com.myfitai.app.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Pads this view by the system bars so edge-to-edge content never sits under the status or navigation bar.
 * With [includeIme] the bottom padding also follows the on-screen keyboard.
 */
internal fun View.applySystemBarInsetsAsPadding(includeIme: Boolean = false) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val imeBottom = if (includeIme) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
        view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, imeBottom))
        insets
    }
    ViewCompat.requestApplyInsets(this)
}