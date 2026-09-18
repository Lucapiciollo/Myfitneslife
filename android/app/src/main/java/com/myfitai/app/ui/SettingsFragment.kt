package com.myfitai.app.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.myfitai.app.R
import com.myfitai.app.ai.AiModelConfig
import com.myfitai.app.ai.AiRuntimeConfig
import com.myfitai.app.ai.AiSettingsStore
import com.myfitai.app.ai.GeminiByokProvider
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.progress.ProgressAnalysisPreferences
import com.myfitai.app.domain.ai.AiJobType
import com.myfitai.app.data.profile.NutritionAutoGenerationPreferences
import com.myfitai.app.data.profile.NutritionMealSchedulePreferences
import com.myfitai.app.security.AiCredentialProvider
import com.myfitai.app.security.SecureAiCredentialStore
import com.myfitai.app.ui.widgets.SettingRowView
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

class SettingsFragment : Fragment(R.layout.activity_settings) {
    private val data by lazy { AppDataContainer.get(requireContext()) }
    private lateinit var settings: AiSettingsStore
    private lateinit var credentialStore: SecureAiCredentialStore
    private lateinit var geminiInput: TextInputEditText
    private lateinit var openAiInput: TextInputEditText
    private lateinit var useGeminiSwitch: MaterialSwitch

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = AiSettingsStore(requireContext())
        credentialStore = SecureAiCredentialStore(requireContext())
        useGeminiSwitch = view.findViewById(R.id.useGeminiSwitch)
        geminiInput = view.findViewById(R.id.geminiApiKeyInput)
        openAiInput = view.findViewById(R.id.openAiApiKeyInput)
        geminiInput.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        openAiInput.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        geminiInput.isLongClickable = false
        openAiInput.isLongClickable = false
        useGeminiSwitch.isChecked = settings.useGemini

        view.findViewById<View>(R.id.backButton).setOnClickListener { (activity as? TabHostActivity)?.showExitConfirmation() }
        bindProviderControls(view)
        bindNavigation(view)
        bindDataDeletion(view)
        bindProgressFrequency(view)
        bindNutritionAutomation(view)
        view.findViewById<View>(R.id.analyzeNutritionPathButton).setOnClickListener { enqueueNutritionPath() }
        GeminiCostSettingsBinder.bind(requireActivity() as AppCompatActivity, view.findViewById(R.id.aiSectionCard), settings, viewLifecycleOwner)
        render(view)
    }

    private fun bindProviderControls(view: View) {
        view.findViewById<View>(R.id.saveOpenAiKeyButton).setOnClickListener { saveCredential(AiCredentialProvider.OPENAI, openAiInput) { candidate -> com.myfitai.app.ai.OpenAiProvider(credentialStore).verifyApiKey(candidate) } }
        view.findViewById<View>(R.id.pasteOpenAiKeyButton).setOnClickListener { pasteInto(openAiInput) }
        view.findViewById<View>(R.id.saveGeminiKeyButton).setOnClickListener { saveCredential(AiCredentialProvider.GEMINI, geminiInput) { candidate -> GeminiByokProvider(credentialStore).verifyApiKey(candidate) } }
        view.findViewById<View>(R.id.pasteGeminiKeyButton).setOnClickListener { pasteInto(geminiInput) }
        view.findViewById<View>(R.id.replaceGeminiKeyButton).setOnClickListener { reveal(view, R.id.geminiInputLayout, R.id.saveGeminiKeyButton, R.id.replaceGeminiKeyButton, geminiInput) }
        view.findViewById<View>(R.id.replaceOpenAiKeyButton).setOnClickListener { reveal(view, R.id.openAiInputLayout, R.id.saveOpenAiKeyButton, R.id.replaceOpenAiKeyButton, openAiInput) }
        view.findViewById<View>(R.id.deleteGeminiKeyButton).setOnClickListener { credentialStore.delete(AiCredentialProvider.GEMINI); settings.geminiVerifiedModel = null; render(view) }
        view.findViewById<View>(R.id.deleteOpenAiKeyButton).setOnClickListener { credentialStore.delete(AiCredentialProvider.OPENAI); render(view) }
        useGeminiSwitch.setOnCheckedChangeListener { _, checked -> settings.useGemini = checked; render(view) }
    }

    private fun render(view: View) {
        val geminiConfigured = credentialStore.exists(AiCredentialProvider.GEMINI)
        val openAiConfigured = credentialStore.exists(AiCredentialProvider.OPENAI)
        view.findViewById<View>(R.id.geminiInputLayout).visibility = if (geminiConfigured) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.pasteGeminiKeyButton).visibility = if (geminiConfigured) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.saveGeminiKeyButton).visibility = if (geminiConfigured) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.replaceGeminiKeyButton).visibility = if (geminiConfigured) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.deleteGeminiKeyButton).visibility = if (geminiConfigured) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.openAiInputLayout).visibility = if (openAiConfigured) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.pasteOpenAiKeyButton).visibility = if (openAiConfigured) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.saveOpenAiKeyButton).visibility = if (openAiConfigured) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.replaceOpenAiKeyButton).visibility = if (openAiConfigured) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.deleteOpenAiKeyButton).visibility = if (openAiConfigured) View.VISIBLE else View.GONE
        val config = AiRuntimeConfig(useGemini = useGeminiSwitch.isChecked, geminiConfigured = geminiConfigured, openAiConfigured = openAiConfigured)
        view.findViewById<TextView>(R.id.activeProviderText).text = when (config.selectedProvider()) {
            com.myfitai.app.ai.AiProviderType.GEMINI -> "Provider attivo: Gemini BYOK"
            com.myfitai.app.ai.AiProviderType.OPENAI -> "Provider attivo: OpenAI BYOK"
            com.myfitai.app.ai.AiProviderType.NOT_CONFIGURED -> "Provider selezionato ma non configurato"
        }
        view.findViewById<TextView>(R.id.geminiKeyStatusText).text = if (geminiConfigured) "Gemini configurato ✓\nModello: ${AiModelConfig.displayName(settings.geminiVerifiedModel ?: AiModelConfig.GEMINI_PRIMARY)}" else "Chiave Gemini non configurata"
        view.findViewById<TextView>(R.id.openAiKeyStatusText).text = if (openAiConfigured) "OpenAI configurato ✓ · chiave nascosta" else "Chiave OpenAI non configurata"
    }

    private fun saveCredential(provider: AiCredentialProvider, input: TextInputEditText, verify: suspend (String) -> com.myfitai.app.ai.AiRawResponse) {
        val candidate = input.text?.toString()?.trim().orEmpty()
        if (candidate.isBlank()) { Toast.makeText(requireContext(), "Inserisci una API key valida", Toast.LENGTH_SHORT).show(); return }
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { val response = verify(candidate); credentialStore.save(provider, candidate); if (provider == AiCredentialProvider.GEMINI) settings.geminiVerifiedModel = response.model }
                .onSuccess { input.text?.clear(); clearClipboard(); Toast.makeText(requireContext(), "Chiave verificata e salvata in modo sicuro", Toast.LENGTH_SHORT).show(); view?.let(::render) }
                .onFailure { input.text?.clear(); clearClipboard(); Toast.makeText(requireContext(), "Verifica ${if (provider == AiCredentialProvider.GEMINI) "Gemini" else "OpenAI"} non riuscita", Toast.LENGTH_LONG).show() }
        }
    }

    private fun bindNavigation(view: View) {
        view.findViewById<View>(R.id.rowProfile).setOnClickListener { startActivity(android.content.Intent(requireContext(), ProfileActivity::class.java)) }
        view.findViewById<View>(R.id.rowMeasurements).setOnClickListener { startActivity(android.content.Intent(requireContext(), MeasurementsActivity::class.java)) }
        view.findViewById<View>(R.id.rowFoodPreferences).setOnClickListener { startActivity(android.content.Intent(requireContext(), ProfileEditActivity::class.java)) }
        view.findViewById<View>(R.id.rowExport).setOnClickListener { startActivity(android.content.Intent(requireContext(), ExportActivity::class.java)) }
        view.findViewById<View>(R.id.rowMealCount).setOnClickListener { showMealCountDialog(view) }
        view.findViewById<SettingRowView>(R.id.rowUnits).apply { setTrailingBadge("Metrico", R.color.text_secondary); isClickable = false; isFocusable = false }
        view.findViewById<View>(R.id.rowPrivacy).setOnClickListener { MaterialAlertDialogBuilder(requireContext()).setTitle("Privacy e dati").setMessage("I dati del profilo restano nello storage locale. I backup Android sono disabilitati e le chiavi IA sono protette tramite Android Keystore.").setPositiveButton("OK", null).show() }
    }

    private fun bindProgressFrequency(view: View) {
        val card = view.findViewById<LinearLayout>(R.id.aiSectionCard)
        card.findViewWithTag<View>("progress-frequency-row")?.let { card.removeView(it) }
        val row = TextView(requireContext()).apply { tag = "progress-frequency-row"; text = "Analisi progressi automatica\nOgni ${data.progressAnalysisPreferences.intervalWeeks} settimane"; setPadding(0, dp(4), 0, dp(12)); setOnClickListener { showFrequencyDialog(this) } }
        card.addView(row, 0)
    }

    private fun bindNutritionAutomation(view: View) {
        val profileId = data.activeProfileStore.currentIdOrNull() ?: return
        val autoSwitch = view.findViewById<MaterialSwitch>(R.id.nutritionAutoGenerationSwitch)
        val cadenceText = view.findViewById<TextView>(R.id.nutritionCadenceValue)
        val timesText = view.findViewById<TextView>(R.id.nutritionMealTimesValue)
        fun render() {
            val enabled = data.nutritionAutoGenerationPreferences.enabled(profileId)
            autoSwitch.isChecked = enabled
            cadenceText.text = if (enabled) data.nutritionAutoGenerationPreferences.cadence(profileId).label else "Disattivata · generazione manuale"
            timesText.text = data.nutritionMealSchedulePreferences.times(profileId, data.mealCountPreferences.get(profileId))
                .joinToString(" · ", transform = NutritionMealSchedulePreferences::format)
        }
        autoSwitch.setOnCheckedChangeListener { _, checked ->
            data.nutritionAutoGenerationPreferences.setEnabled(profileId, checked)
            data.nutritionAutoGenerationScheduler.refresh(profileId)
            render()
        }
        view.findViewById<View>(R.id.rowNutritionCadence).setOnClickListener {
            val values = NutritionAutoGenerationPreferences.Cadence.entries
            MaterialAlertDialogBuilder(requireContext()).setTitle("Frequenza generazione piano")
                .setSingleChoiceItems(values.map { it.label }.toTypedArray(), values.indexOf(data.nutritionAutoGenerationPreferences.cadence(profileId))) { dialog, which ->
                    data.nutritionAutoGenerationPreferences.setCadence(profileId, values[which])
                    data.nutritionAutoGenerationScheduler.refresh(profileId)
                    render(); dialog.dismiss()
                }.setNegativeButton("Annulla", null).show()
        }
        view.findViewById<View>(R.id.rowNutritionMealTimes).setOnClickListener { showNutritionMealTimesDialog(profileId) { render() } }
        render()
    }

    private fun enqueueNutritionPath() {
        val profileId = data.activeProfileStore.currentIdOrNull()
        if (profileId == null) {
            Toast.makeText(requireContext(), "Completa prima il profilo", Toast.LENGTH_LONG).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val profile = data.userProfileRepository.get(profileId)
            val bia = data.biaRepository.all(profileId).first()
            val body = data.bodyMeasurementRepository.all(profileId).first()
            if (profile == null || bia.isEmpty() || body.isEmpty()) {
                Toast.makeText(requireContext(), "Inserisci prima profilo, una BIA e una misura corporea", Toast.LENGTH_LONG).show()
                return@launch
            }
            val jobKey = "${System.currentTimeMillis()}"
            data.aiJobScheduler.enqueue(AiJobType.NUTRITION_PATH, profileId, jobKey)
            Toast.makeText(requireContext(), "Suggerimento avviato. Riceverai una notifica quando sarà pronto.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showNutritionMealTimesDialog(profileId: Long, onChanged: () -> Unit) {
        val count = data.mealCountPreferences.get(profileId)
        val times = data.nutritionMealSchedulePreferences.times(profileId, count).toMutableList()
        fun open(index: Int) {
            if (index >= count) {
                data.nutritionMealSchedulePreferences.setTimes(profileId, times)
                viewLifecycleOwner.lifecycleScope.launch { data.notificationScheduler.refresh() }; onChanged(); return
            }
            val picker = com.google.android.material.timepicker.MaterialTimePicker.Builder()
                .setTimeFormat(com.google.android.material.timepicker.TimeFormat.CLOCK_24H)
                .setHour(times[index] / 60).setMinute(times[index] % 60)
                .setTitleText("Pasto ${index + 1}").build()
            picker.addOnPositiveButtonClickListener {
                times[index] = NutritionMealSchedulePreferences.parse(picker.hour, picker.minute); open(index + 1)
            }
            picker.show(childFragmentManager, "nutrition_meal_time_$index")
        }
        open(0)
    }

    private fun showFrequencyDialog(label: TextView) {
        val values = ProgressAnalysisPreferences.SUGGESTED_INTERVALS
        MaterialAlertDialogBuilder(requireContext()).setTitle("Frequenza analisi progressi").setItems(values.map { "Ogni $it ${if (it == 1) "settimana" else "settimane"}" }.toTypedArray()) { _, which ->
            data.progressAnalysisPreferences.intervalWeeks = values[which]
            data.activeProfileStore.currentIdOrNull()?.let { profileId ->
                if (data.progressAnalysisPreferences.lastSuccessEpochMillis(profileId) != null) data.progressAnalysisScheduler.reschedule(profileId)
            }
            label.text = "Analisi progressi automatica\nOgni ${values[which]} settimane"
        }.setNegativeButton("Annulla", null).show()
    }

    private fun showMealCountDialog(view: View) {
        val profileId = data.activeProfileStore.currentIdOrNull() ?: return
        val values = intArrayOf(4, 5, 6)
        MaterialAlertDialogBuilder(requireContext()).setTitle("Pasti al giorno").setSingleChoiceItems(values.map { "$it pasti" }.toTypedArray(), values.indexOf(data.mealCountPreferences.get(profileId))) { dialog, which -> data.mealCountPreferences.set(profileId, values[which]); view.findViewById<TextView>(R.id.mealCountValue).text = "${values[which]} pasti al giorno"; dialog.dismiss() }.setNegativeButton("Annulla", null).show()
    }

    private fun bindDataDeletion(view: View) {
        view.findViewById<View>(R.id.rowDeleteRecordedData).setOnClickListener { confirmDeletion("tutti i dati registrati", data.dataDeletionService::deleteRecordedData) }
        listOf(R.id.rowDeletePlans to ("alimentazioni" to data.dataDeletionService::deleteMealPlans), R.id.rowDeleteConsumptions to ("consumi registrati" to data.dataDeletionService::deleteFoodConsumptions), R.id.rowDeleteBia to ("misure BIA" to data.dataDeletionService::deleteBiaMeasurements), R.id.rowDeleteBody to ("misure corporee" to data.dataDeletionService::deleteBodyMeasurements), R.id.rowDeleteWorkouts to ("allenamenti" to data.dataDeletionService::deleteWorkouts), R.id.rowDeleteCheats to ("sgarri registrati" to data.dataDeletionService::deleteCheatEntries), R.id.rowDeleteReviews to ("review settimanali" to data.dataDeletionService::deleteWeeklyReviews)).forEach { (id, action) -> view.findViewById<View>(id).setOnClickListener { confirmDeletion(action.first, action.second) } }
    }

    private fun confirmDeletion(label: String, action: suspend () -> Unit) { MaterialAlertDialogBuilder(requireContext()).setTitle("Eliminare $label?").setNegativeButton("Annulla", null).setPositiveButton("Elimina") { _, _ -> viewLifecycleOwner.lifecycleScope.launch { runCatching { action() }.onSuccess { Toast.makeText(requireContext(), "$label eliminati", Toast.LENGTH_SHORT).show() } } }.show() }
    private fun reveal(view: View, inputId: Int, saveId: Int, replaceId: Int, input: TextInputEditText) { input.text?.clear(); view.findViewById<View>(inputId).visibility = View.VISIBLE; view.findViewById<View>(saveId).visibility = View.VISIBLE; view.findViewById<View>(replaceId).visibility = View.GONE }
    private fun pasteInto(input: TextInputEditText) { val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager; val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(requireContext())?.toString()?.trim().orEmpty(); if (text.isBlank()) return; input.setText(text); input.setSelection(text.length); clipboard.clearPrimaryClip() }
    private fun clearClipboard() { (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).clearPrimaryClip() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
