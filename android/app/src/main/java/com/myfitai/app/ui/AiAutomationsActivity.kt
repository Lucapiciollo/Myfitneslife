package com.myfitai.app.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.data.profile.AiAutomationPreferences
import com.myfitai.app.navigation.AiSettingsEntry
import kotlinx.coroutines.launch

/**
 * Automazioni IA del profilo attivo (Progress Coach BIA, Analisi progressi, Proporzioni, Review settimanale).
 * Cambiano per ogni profilo; provider, chiavi e modelli sono condivisi e stanno in [AiSettingsActivity].
 */
class AiAutomationsActivity : BaseShellActivity() {
    private val data by lazy { AppDataContainer.get(this) }
    private val renderers = mutableListOf<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_automations)
        bindBack()
        SettingsSurfaces.normalize(this)

        findViewById<View>(R.id.openAiSettingsButton).setOnClickListener {
            // Aperta da Impostazioni IA: si torna indietro, evitando il ciclo A -> B -> A.
            if (intent.getBooleanExtra(EXTRA_FROM_AI_SETTINGS, false)) finish() else AiSettingsEntry.open(this)
        }

        val profileId = data.activeProfileStore.currentIdOrNull()
        if (profileId == null) {
            findViewById<View>(R.id.aiAutomationsNoProfile).visibility = View.VISIBLE
            findViewById<View>(R.id.aiAutomationsCard).visibility = View.GONE
            findViewById<View>(R.id.aiAutomationsScope).visibility = View.GONE
        } else {
            lifecycleScope.launch {
                val name = data.userProfileRepository.get(profileId)?.name?.trim().orEmpty()
                if (name.isNotBlank()) {
                    findViewById<TextView>(R.id.aiAutomationsScope).text = getString(R.string.ai_automations_scope, name)
                }
            }
            bindAutomationRows(profileId)
        }
        refreshProviderDependentState()
    }

    override fun onResume() {
        super.onResume()
        refreshProviderDependentState()
    }

    private fun refreshProviderDependentState() {
        findViewById<View>(R.id.aiAutomationsProviderBanner).visibility = if (aiProviderConfigured) View.GONE else View.VISIBLE
        renderers.forEach { it() }
    }

    private fun bindAutomationRows(profileId: Long) {
        val card = findViewById<LinearLayout>(R.id.aiAutomationsSection)
        val preferences = data.aiAutomationPreferences
        val rows = listOf(
            AiAutomationPreferences.Feature.BIA_PROGRESS_COACH to "Progress Coach BIA",
            AiAutomationPreferences.Feature.PROGRESS_ANALYSIS to "Analisi progressi",
            AiAutomationPreferences.Feature.BODY_PROPORTIONS to "Proporzioni corporee",
            AiAutomationPreferences.Feature.WEEKLY_REVIEW to "Review settimanale",
        )
        val rowMinHeight = resources.getDimensionPixelSize(R.dimen.control_min_height)
        rows.forEachIndexed { index, (feature, label) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, resources.getDimensionPixelSize(R.dimen.space_8), 0, resources.getDimensionPixelSize(R.dimen.space_8))
            }
            val title = TextView(this).apply {
                text = label
                setTextAppearance(R.style.Text_MyFitAI_SettingsLabel)
            }
            ViewCompat.setAccessibilityHeading(title, true)
            val automatic = MaterialSwitch(this).apply {
                applyMyFitAiSwitchTints()
                text = "Automatica"
                minHeight = rowMinHeight
                setTextAppearance(R.style.Text_MyFitAI_SettingsDescription)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val notifications = MaterialSwitch(this).apply {
                applyMyFitAiSwitchTints()
                text = "Notifiche"
                minHeight = rowMinHeight
                setTextAppearance(R.style.Text_MyFitAI_SettingsDescription)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val frequency = TextView(this).apply {
                setTextAppearance(R.style.Text_MyFitAI_SettingsDescription)
                isClickable = true
                isFocusable = true
                minHeight = rowMinHeight
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, resources.getDimensionPixelSize(R.dimen.space_2), 0, resources.getDimensionPixelSize(R.dimen.space_4))
            }
            var rendering = false
            fun render() {
                rendering = true
                val config = preferences.get(profileId, feature)
                automatic.isChecked = config.enabled
                notifications.isChecked = config.notificationsEnabled
                frequency.text = "Frequenza: ${if (config.frequency == AiAutomationPreferences.Frequency.WEEKLY) "settimanale" else "mensile"}. L'azione manuale resta sempre disponibile."
                automatic.isEnabled = aiProviderConfigured
                notifications.isEnabled = aiProviderConfigured
                frequency.isEnabled = aiProviderConfigured
                rendering = false
            }
            automatic.setOnCheckedChangeListener { _, checked ->
                if (rendering) return@setOnCheckedChangeListener
                preferences.setEnabled(profileId, feature, checked)
                if (feature == AiAutomationPreferences.Feature.BIA_PROGRESS_COACH) {
                    if (checked) data.biaProgressCoachScheduler.ensureScheduled(profileId) else data.biaProgressCoachScheduler.cancel(profileId)
                } else if (feature == AiAutomationPreferences.Feature.PROGRESS_ANALYSIS) {
                    if (checked) data.progressAnalysisScheduler.ensureScheduled(profileId) else data.progressAnalysisScheduler.cancel(profileId)
                }
                render()
            }
            notifications.setOnCheckedChangeListener { _, checked ->
                if (rendering) return@setOnCheckedChangeListener
                preferences.setNotificationsEnabled(profileId, feature, checked)
                render()
            }
            frequency.setOnClickListener {
                val labels = arrayOf("Ogni settimana", "Ogni mese")
                val current = preferences.get(profileId, feature).frequency.ordinal
                MaterialAlertDialogBuilder(this).setTitle("Frequenza $label")
                    .setSingleChoiceItems(labels, current) { dialog, which ->
                        preferences.setFrequency(profileId, feature, if (which == 0) AiAutomationPreferences.Frequency.WEEKLY else AiAutomationPreferences.Frequency.MONTHLY)
                        if (feature == AiAutomationPreferences.Feature.BIA_PROGRESS_COACH && preferences.get(profileId, feature).enabled) data.biaProgressCoachScheduler.ensureScheduled(profileId)
                        if (feature == AiAutomationPreferences.Feature.PROGRESS_ANALYSIS && preferences.get(profileId, feature).enabled) data.progressAnalysisScheduler.reschedule(profileId)
                        render()
                        dialog.dismiss()
                    }.setNegativeButton(R.string.action_cancel, null).show()
            }
            renderers += { render() }
            render()
            row.addView(title)
            row.addView(frequency)
            row.addView(automatic)
            row.addView(notifications)
            card.addView(row)
            if (index < rows.lastIndex) {
                card.addView(
                    View(this).apply { setBackgroundColor(getColor(R.color.divider)) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.divider_thickness)),
                )
            }
        }
    }

    companion object {
        /** Impostato da Impostazioni IA: "Apri Impostazioni IA" chiude questa schermata invece di impilarne un'altra. */
        const val EXTRA_FROM_AI_SETTINGS = "myfitai_from_ai_settings"
    }
}
