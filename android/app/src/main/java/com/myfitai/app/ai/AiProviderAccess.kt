package com.myfitai.app.ai

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.TabHostActivity

/** Single navigation gate for features that require the selected AI provider. */
object AiProviderAccess {
    fun isConfigured(context: Context): Boolean =
        AiRuntimeService(context.applicationContext).selectedProviderType() != AiProviderType.NOT_CONFIGURED

    fun requireConfigured(activity: Activity): Boolean {
        if (isConfigured(activity)) return true
        MaterialAlertDialogBuilder(activity)
            .setTitle("Nessun provider IA configurato")
            .setMessage("Per usare Alimentazione devi scegliere Gemini o OpenAI e configurare la relativa API key personale nelle Impostazioni.")
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Apri impostazioni") { _, _ ->
                activity.startActivity(Intent(activity, TabHostActivity::class.java)
                    .putExtra(BottomNavBinder.EXTRA_INITIAL_TAB, BottomNavBinder.Tab.MORE.name)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            .show()
        return false
    }
}
