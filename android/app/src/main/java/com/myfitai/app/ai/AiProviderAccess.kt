package com.myfitai.app.ai

import android.app.Activity
import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myfitai.app.navigation.AiSettingsEntry

/** Single navigation gate for features that require the selected AI provider. */
object AiProviderAccess {
    const val UNCONFIGURED_TITLE = "Nessun provider IA configurato"
    const val UNCONFIGURED_MESSAGE = "Per usare questa funzione scegli Gemini o OpenAI e inserisci la tua chiave API. Vale per tutti i profili."
    const val OPEN_SETTINGS_ACTION = "Apri impostazioni IA"

    fun isConfigured(context: Context): Boolean =
        AiRuntimeService(context.applicationContext).selectedProviderType() != AiProviderType.NOT_CONFIGURED

    /** Stato del provider: usa solo la presenza delle credenziali e il provider scelto, mai una chiave. */
    fun status(context: Context): AiProviderStatus =
        AiProviderStatus.from(AiRuntimeService(context.applicationContext).config())

    fun requireConfigured(activity: Activity): Boolean {
        if (isConfigured(activity)) return true
        MaterialAlertDialogBuilder(activity)
            .setTitle(UNCONFIGURED_TITLE)
            .setMessage(UNCONFIGURED_MESSAGE)
            .setNegativeButton("Annulla", null)
            .setPositiveButton(OPEN_SETTINGS_ACTION) { _, _ -> AiSettingsEntry.open(activity) }
            .show()
        return false
    }
}
