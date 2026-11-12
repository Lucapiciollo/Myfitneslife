package com.myfitai.app.navigation

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.myfitai.app.ui.AiSettingsActivity

/**
 * Unico ingresso verso le Impostazioni IA (provider, chiavi, modelli, costi), condivise da tutti i profili.
 * Rotellina, gate "provider non configurato", avvisi di Home/Cibo/BIA/Consiglio/Review e Altro passano da qui:
 * nessun chiamante costruisce a mano un Intent verso la configurazione IA.
 */
object AiSettingsEntry {
    /** Valore storico di [BottomNavBinder.EXTRA_SELECTED_TAB] del vecchio tab IA, ancora accettato dall'host. */
    const val LEGACY_AI_TAB_VALUE = "AI"

    val destination: Class<out Activity> get() = AiSettingsActivity::class.java

    /** True se l'host è stato avviato con il vecchio valore del tab IA (compatibilità difensiva, rimovibile). */
    fun isLegacyAiTabRequest(selectedTabExtra: String?): Boolean = selectedTabExtra == LEGACY_AI_TAB_VALUE

    fun createIntent(context: Context): Intent =
        Intent(context, destination).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun open(context: Context) {
        context.startActivity(createIntent(context))
    }
}
