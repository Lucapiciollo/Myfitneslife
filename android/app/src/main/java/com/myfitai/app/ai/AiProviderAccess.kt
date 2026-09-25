package com.myfitai.app.ai

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myfitai.app.ui.SettingsActivity

/** Single navigation gate for features that require the selected AI provider. */
object AiProviderAccess {
    const val UNCONFIGURED_TITLE = "Nessun provider IA configurato"
    const val UNCONFIGURED_MESSAGE = "Per usare Alimentazione devi scegliere Gemini o OpenAI e configurare la relativa API key personale nelle Impostazioni."

    fun isConfigured(context: Context): Boolean =
        AiRuntimeService(context.applicationContext).selectedProviderType() != AiProviderType.NOT_CONFIGURED

    fun requireConfigured(activity: Activity): Boolean {
        if (isConfigured(activity)) return true
        MaterialAlertDialogBuilder(activity)
            .setTitle(UNCONFIGURED_TITLE)
            .setMessage(UNCONFIGURED_MESSAGE)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Apri impostazioni") { _, _ ->
                activity.startActivity(Intent(activity, SettingsActivity::class.java))
            }
            .show()
        return false
    }
}
