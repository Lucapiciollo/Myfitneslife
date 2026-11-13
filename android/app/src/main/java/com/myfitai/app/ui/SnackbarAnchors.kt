package com.myfitai.app.ui

import android.app.Activity
import android.view.View
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import com.google.android.material.snackbar.Snackbar
import com.myfitai.app.R

/**
 * Regola unica per ancorare gli snackbar alla barra inferiore di una schermata.
 *
 * Le schermate radice (Home, Cibo) sono ospitate da [TabHostActivity], che nasconde (GONE) la `bottomNav`
 * del figlio e disegna la propria sotto il contenuto del tab. Ancorare uno snackbar a una vista GONE lo
 * posiziona in alto (le sue coordinate sono 0,0) e fa cadere il tocco su "Annulla" sulla vista sottostante.
 *
 * Regola: si ancora SOLO se la vista di ancoraggio e' davvero visibile e misurata. Altrimenti nessuna ancora:
 * lo snackbar resta in fondo al `android.R.id.content` della schermata, che nell'host coincide con il bordo
 * superiore della barra di navigazione dell'host (il contenuto del tab e' il FrameLayout sopra la barra),
 * quindi non finisce mai sotto di essa.
 */
object SnackbarAnchors {

    /** Pura (testabile in JVM): una vista e' un'ancora valida solo se agganciata, visibile e con altezza > 0. */
    fun isUsableAnchor(isAttachedToWindow: Boolean, isShown: Boolean, height: Int): Boolean =
        isAttachedToWindow && isShown && height > 0

    /** La vista di ancoraggio se utilizzabile, altrimenti `null` (nessun ancoraggio). */
    fun resolve(activity: Activity, @IdRes anchorId: Int): View? =
        activity.findViewById<View?>(anchorId)
            ?.takeIf { isUsableAnchor(it.isAttachedToWindow, it.isShown, it.height) }

    /** Crea lo snackbar sul contenuto della schermata, ancorato solo se [resolve] trova una vista valida. */
    fun make(
        activity: Activity,
        @StringRes message: Int,
        @IdRes anchorId: Int = R.id.bottomNav,
    ): Snackbar {
        val snackbar = Snackbar.make(activity.findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG)
        resolve(activity, anchorId)?.let { snackbar.setAnchorView(it) }
        return snackbar
    }
}
