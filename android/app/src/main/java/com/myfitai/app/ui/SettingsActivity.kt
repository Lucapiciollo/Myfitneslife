package com.myfitai.app.ui

import android.os.Bundle
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.myfitai.app.R
import com.myfitai.app.ai.AiRuntimeConfig
import com.myfitai.app.ai.AiModelConfig
import com.myfitai.app.ai.AiSettingsStore
import com.myfitai.app.ai.GeminiByokProvider
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.data.profile.MealCountPreferences
import com.myfitai.app.data.profile.NutritionPlanSchedulePreferences
import com.myfitai.app.domain.progress.ProgressAnalysisPreferences
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.security.AiCredentialProvider
import com.myfitai.app.security.SecureAiCredentialStore
import com.myfitai.app.ui.widgets.SettingRowView
import com.myfitai.app.domain.food.NutritionPathTrigger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsActivity : BaseShellActivity() {
    private val data by lazy { AppDataContainer.get(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_settings)
        bindBottom(BottomNavBinder.Tab.MORE)
        bindBack()
        bindSectionHelp()

        val settings = AiSettingsStore(this)
        val credentialStore = SecureAiCredentialStore(this)

        val useGeminiSwitch = findViewById<MaterialSwitch>(R.id.useGeminiSwitch)
        val geminiContainer = findViewById<View>(R.id.geminiKeyContainer)
        val openAiContainer = findViewById<View>(R.id.openAiKeyContainer)
        val openAiInput = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.openAiApiKeyInput)
        val geminiInput = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.geminiApiKeyInput)
        val activeProviderText = findViewById<TextView>(R.id.activeProviderText)
        val geminiStatus = findViewById<TextView>(R.id.geminiKeyStatusText)
        val openAiStatus = findViewById<TextView>(R.id.openAiKeyStatusText)
        geminiInput.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        openAiInput.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        geminiInput.isLongClickable = false
        openAiInput.isLongClickable = false
        useGeminiSwitch.isChecked = settings.useGemini
        findViewById<View>(R.id.analyzeNutritionPathButton).setOnClickListener { enqueueNutritionPath() }

        fun render() {
            val useGemini = useGeminiSwitch.isChecked
            geminiContainer.visibility = View.VISIBLE
            openAiContainer.visibility = View.VISIBLE
            val geminiConfigured = credentialStore.exists(AiCredentialProvider.GEMINI)
            val openAiConfigured = credentialStore.exists(AiCredentialProvider.OPENAI)
            val geminiModel = settings.geminiVerifiedModel ?: AiModelConfig.GEMINI_PRIMARY
            findViewById<View>(R.id.geminiInputLayout).visibility = if (geminiConfigured) View.GONE else View.VISIBLE
            findViewById<View>(R.id.pasteGeminiKeyButton).visibility = if (geminiConfigured) View.GONE else View.VISIBLE
            findViewById<View>(R.id.saveGeminiKeyButton).visibility = if (geminiConfigured) View.GONE else View.VISIBLE
            findViewById<View>(R.id.replaceGeminiKeyButton).visibility = if (geminiConfigured) View.VISIBLE else View.GONE
            findViewById<View>(R.id.deleteGeminiKeyButton).visibility = if (geminiConfigured) View.VISIBLE else View.GONE
            findViewById<View>(R.id.openAiInputLayout).visibility = if (openAiConfigured) View.GONE else View.VISIBLE
            findViewById<View>(R.id.pasteOpenAiKeyButton).visibility = if (openAiConfigured) View.GONE else View.VISIBLE
            findViewById<View>(R.id.saveOpenAiKeyButton).visibility = if (openAiConfigured) View.GONE else View.VISIBLE
            findViewById<View>(R.id.replaceOpenAiKeyButton).visibility = if (openAiConfigured) View.VISIBLE else View.GONE
            findViewById<View>(R.id.deleteOpenAiKeyButton).visibility = if (openAiConfigured) View.VISIBLE else View.GONE
            val config = AiRuntimeConfig(
                useGemini = useGemini,
                geminiConfigured = geminiConfigured,
                openAiConfigured = openAiConfigured,
            )
            activeProviderText.text = when (val selected = config.selectedProvider()) {
                com.myfitai.app.ai.AiProviderType.GEMINI -> "Provider attivo: Gemini BYOK"
                com.myfitai.app.ai.AiProviderType.OPENAI -> "Provider attivo: OpenAI BYOK"
                com.myfitai.app.ai.AiProviderType.NOT_CONFIGURED -> if (useGemini) "Gemini selezionato ma non configurato" else "Provider OpenAI selezionato ma non configurato"
            }
            geminiStatus.text = if (geminiConfigured) {
                "Gemini configurato ✓\nModello: ${AiModelConfig.displayName(geminiModel)}"
            } else {
                "Chiave Gemini non configurata"
            }
            openAiStatus.text = if (openAiConfigured) "OpenAI configurato ✓ · chiave nascosta" else "Chiave OpenAI non configurata"
        }

        fun saveCredential(
            provider: AiCredentialProvider,
            input: com.google.android.material.textfield.TextInputEditText,
            verify: suspend (String) -> com.myfitai.app.ai.AiRawResponse,
        ) {
            val candidate = input.text?.toString()?.trim().orEmpty()
            if (candidate.isBlank()) { Toast.makeText(this, "Inserisci una API key valida", Toast.LENGTH_SHORT).show(); return }
            lifecycleScope.launch {
                runCatching {
                    val response = verify(candidate)
                    credentialStore.save(provider, candidate)
                    if (provider == AiCredentialProvider.GEMINI) settings.geminiVerifiedModel = response.model
                }
                    .onSuccess {
                        input.text?.clear()
                        clearClipboard()
                        Toast.makeText(this@SettingsActivity, "Chiave verificata e salvata in modo sicuro", Toast.LENGTH_SHORT).show()
                        render()
                    }
                    .onFailure {
                        input.text?.clear()
                        clearClipboard()
                        logVerificationOutcome(provider, it)
                        Toast.makeText(this@SettingsActivity, providerError(provider, it), Toast.LENGTH_LONG).show()
                    }
            }
        }

        findViewById<View>(R.id.saveOpenAiKeyButton).setOnClickListener {
            saveCredential(AiCredentialProvider.OPENAI, openAiInput) { candidate -> com.myfitai.app.ai.OpenAiProvider(credentialStore).verifyApiKey(candidate) }
        }
        findViewById<View>(R.id.pasteOpenAiKeyButton).setOnClickListener { pasteInto(openAiInput) }
        findViewById<View>(R.id.saveGeminiKeyButton).setOnClickListener {
            saveCredential(AiCredentialProvider.GEMINI, geminiInput) { candidate -> GeminiByokProvider(credentialStore).verifyApiKey(candidate) }
        }
        findViewById<View>(R.id.pasteGeminiKeyButton).setOnClickListener { pasteInto(geminiInput) }
        findViewById<View>(R.id.replaceGeminiKeyButton).setOnClickListener {
            geminiInput.text?.clear()
            findViewById<View>(R.id.geminiInputLayout).visibility = View.VISIBLE
            findViewById<View>(R.id.saveGeminiKeyButton).visibility = View.VISIBLE
            findViewById<View>(R.id.replaceGeminiKeyButton).visibility = View.GONE
        }
        findViewById<View>(R.id.deleteGeminiKeyButton).setOnClickListener {
            credentialStore.delete(AiCredentialProvider.GEMINI)
            settings.geminiVerifiedModel = null
            geminiInput.text?.clear()
            render()
        }
        findViewById<View>(R.id.replaceOpenAiKeyButton).setOnClickListener {
            openAiInput.text?.clear()
            findViewById<View>(R.id.openAiInputLayout).visibility = View.VISIBLE
            findViewById<View>(R.id.saveOpenAiKeyButton).visibility = View.VISIBLE
            findViewById<View>(R.id.replaceOpenAiKeyButton).visibility = View.GONE
        }
        findViewById<View>(R.id.deleteOpenAiKeyButton).setOnClickListener {
            credentialStore.delete(AiCredentialProvider.OPENAI); openAiInput.text?.clear(); render()
        }
        useGeminiSwitch.setOnCheckedChangeListener { _, checked ->
            settings.useGemini = checked
            render()
        }

        findViewById<View>(R.id.rowProfile).setOnClickListener { go(ProfileActivity::class.java) }
        findViewById<View>(R.id.rowMeasurements).setOnClickListener { go(MeasurementsActivity::class.java) }
        findViewById<View>(R.id.rowFoodPreferences).setOnClickListener { go(ProfileEditActivity::class.java) }
        findViewById<View>(R.id.rowMealCount).setOnClickListener { showMealCountDialog() }
        findViewById<SettingRowView>(R.id.rowUnits).apply {
            setTrailingBadge("Metrico", R.color.text_secondary)
            isClickable = false
            isFocusable = false
        }
        findViewById<View>(R.id.rowPrivacy).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Privacy e dati")
                .setMessage("I dati del profilo restano nello storage locale dell'app. I backup Android sono disabilitati e le chiavi IA sono protette tramite Android Keystore. L'export viene condiviso solo quando lo richiedi esplicitamente.")
                .setPositiveButton("OK", null)
                .show()
        }
        findViewById<View>(R.id.rowExport).setOnClickListener { go(ExportActivity::class.java) }

        bindDataDeletion()
        bindNutritionPlanSchedule()
        bindProgressAnalysisFrequency()
        GeminiCostSettingsBinder.bind(this, findViewById(R.id.aiSectionCard), settings)
        render()
    }

    private fun bindSectionHelp() {
        findViewById<View>(R.id.generalSectionHelpButton).setOnClickListener {
            showHelpCard(
                "Generale",
                "Qui trovi il profilo attivo, le rilevazioni, le preferenze alimentari, il numero di pasti, la privacy e l'esportazione dei dati.",
            )
        }
        findViewById<View>(R.id.dataSectionHelpButton).setOnClickListener {
            showHelpCard(
                "Gestione dati",
                "Le eliminazioni riguardano solo il profilo attivo. Usale per rimuovere categorie specifiche oppure tutti i dati registrati senza cancellare il profilo e le chiavi IA.",
            )
        }
        findViewById<View>(R.id.aiSectionHelpButton).setOnClickListener {
            showHelpCard(
                "Intelligenza artificiale",
                "Qui configuri il provider IA, verifichi e gestisci le chiavi API e attivi le funzioni automatiche dell'app. Le chiavi vengono protette localmente e non sono mostrate in chiaro dopo il salvataggio.",
            )
        }
    }

    private fun enqueueNutritionPath() {
        val profileId = data.activeProfileStore.currentIdOrNull() ?: return
        lifecycleScope.launch {
            val key = data.nutritionPathTrigger.maybeEnqueue(profileId)
            Toast.makeText(
                this@SettingsActivity,
                if (key != null) "Suggerimento avviato con i dati disponibili" else "Profilo non disponibile",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun bindNutritionPlanSchedule() {
        val profileId = data.activeProfileStore.currentIdOrNull() ?: return
        val card = findViewById<LinearLayout>(R.id.aiSectionCard)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(12))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Generazione automatica piano"
            textSize = 15f
            setTextColor(getColor(R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val enabledSwitch = MaterialSwitch(this)
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(enabledSwitch)
        val value = TextView(this).apply {
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
        }
        row.addView(header)
        row.addView(value, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(3)
        })

        fun dayLabel(day: java.time.DayOfWeek): String = when (day) {
            java.time.DayOfWeek.MONDAY -> "Lunedì"
            java.time.DayOfWeek.TUESDAY -> "Martedì"
            java.time.DayOfWeek.WEDNESDAY -> "Mercoledì"
            java.time.DayOfWeek.THURSDAY -> "Giovedì"
            java.time.DayOfWeek.FRIDAY -> "Venerdì"
            java.time.DayOfWeek.SATURDAY -> "Sabato"
            java.time.DayOfWeek.SUNDAY -> "Domenica"
        }

        fun renderSchedule() {
            val config = data.nutritionPlanSchedulePreferences.get(profileId)
            enabledSwitch.isChecked = config.enabled
            val frequencyLabel = when (config.frequency) {
                NutritionPlanSchedulePreferences.Frequency.DAILY -> "ogni giorno"
                NutritionPlanSchedulePreferences.Frequency.WEEKLY -> "ogni settimana il ${dayLabel(config.dayOfWeek)}"
                NutritionPlanSchedulePreferences.Frequency.BIWEEKLY -> "ogni 2 settimane"
                NutritionPlanSchedulePreferences.Frequency.MONTHLY -> "ogni mese"
            }
            value.text = if (config.enabled) {
                val hh = config.timeMinutes / 60
                val mm = config.timeMinutes % 60
                "$frequencyLabel alle %02d:%02d · prepara la settimana successiva".format(hh, mm)
            } else {
                "Disattivata · attiva per scegliere frequenza e ora"
            }
        }

        fun chooseTime(day: java.time.DayOfWeek) {
            val config = data.nutritionPlanSchedulePreferences.get(profileId)
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(config.timeMinutes / 60)
                .setMinute(config.timeMinutes % 60)
                .setTitleText("Ora di generazione")
                .build()
            picker.addOnPositiveButtonClickListener {
                data.nutritionPlanSchedulePreferences.setDayOfWeek(profileId, day)
                data.nutritionPlanSchedulePreferences.setTimeMinutes(profileId, picker.hour * 60 + picker.minute)
                data.nutritionPlanSchedulePreferences.setEnabled(profileId, true)
                data.nutritionPlanScheduler.reschedule(profileId)
                renderSchedule()
                Toast.makeText(this, "Generazione automatica aggiornata", Toast.LENGTH_SHORT).show()
            }
            picker.show(supportFragmentManager, "nutrition_plan_schedule_time")
        }

        fun chooseDay() {
            val days = java.time.DayOfWeek.entries
            val labels = days.map(::dayLabel).toTypedArray()
            val current = data.nutritionPlanSchedulePreferences.get(profileId).dayOfWeek
            var selectedIndex = days.indexOf(current)
            MaterialAlertDialogBuilder(this)
                .setTitle("Giorno di generazione")
                .setSingleChoiceItems(labels, selectedIndex) { _, which ->
                    selectedIndex = which
                }
                .setNegativeButton("Annulla", null)
                .setPositiveButton("OK") { _, _ -> chooseTime(days[selectedIndex]) }
                .show()
        }

        fun chooseFrequency() {
            val frequencies = NutritionPlanSchedulePreferences.Frequency.entries
            val labels = arrayOf("Ogni giorno", "Ogni settimana", "Ogni 2 settimane", "Ogni mese")
            val current = data.nutritionPlanSchedulePreferences.get(profileId).frequency
            var selectedIndex = frequencies.indexOf(current)
            MaterialAlertDialogBuilder(this)
                .setTitle("Frequenza generazione pasti")
                .setSingleChoiceItems(labels, selectedIndex) { _, which ->
                    selectedIndex = which
                }
                .setNegativeButton("Annulla", null)
                .setPositiveButton("OK") { _, _ ->
                    val selectedFrequency = frequencies[selectedIndex]
                    data.nutritionPlanSchedulePreferences.setFrequency(profileId, selectedFrequency)
                    if (selectedFrequency == NutritionPlanSchedulePreferences.Frequency.DAILY || selectedFrequency == NutritionPlanSchedulePreferences.Frequency.MONTHLY) {
                        chooseTime(data.nutritionPlanSchedulePreferences.get(profileId).dayOfWeek)
                    } else {
                        chooseDay()
                    }
                }
                .show()
        }

            row.setOnClickListener { chooseFrequency() }

        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            val current = data.nutritionPlanSchedulePreferences.get(profileId)
            if (current.enabled == checked) return@setOnCheckedChangeListener
            data.nutritionPlanSchedulePreferences.setEnabled(profileId, checked)
            if (checked) data.nutritionPlanScheduler.reschedule(profileId) else data.nutritionPlanScheduler.cancel(profileId)
            renderSchedule()
        }

        renderSchedule()
        card.addView(row, 0)
        card.addView(
            View(this).apply { setBackgroundColor(getColor(R.color.divider)) },
            1,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { bottomMargin = dp(12) },
        )
    }

    private fun bindProgressAnalysisFrequency() {
        val card = findViewById<LinearLayout>(R.id.aiSectionCard)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(0, dp(4), 0, dp(12))
        }
        val title = TextView(this).apply {
            text = "Analisi progressi automatica"
            textSize = 15f
            setTextColor(getColor(R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val value = TextView(this).apply {
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
        }
        fun renderValue() {
            val weeks = data.progressAnalysisPreferences.intervalWeeks
            value.text = "Ogni $weeks ${if (weeks == 1) "settimana" else "settimane"} · usa quota del provider IA"
        }
        renderValue()
        row.addView(title)
        row.addView(value, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
        row.setOnClickListener {
            val values = ProgressAnalysisPreferences.SUGGESTED_INTERVALS
            val labels = values.map { weeks ->
                "Ogni $weeks ${if (weeks == 1) "settimana" else "settimane"}${if (weeks == data.progressAnalysisPreferences.intervalWeeks) " ✓" else ""}"
            }.toTypedArray()
            MaterialAlertDialogBuilder(this)
                .setTitle("Frequenza analisi progressi")
                .setItems(labels) { _, which ->
                    val selected = values[which]
                    data.progressAnalysisPreferences.intervalWeeks = selected
                    data.activeProfileStore.currentIdOrNull()?.let { profileId ->
                        if (data.progressAnalysisPreferences.lastSuccessEpochMillis(profileId) != null) {
                            data.progressAnalysisScheduler.reschedule(profileId)
                        }
                    }
                    renderValue()
                    Toast.makeText(this, "Frequenza aggiornata. Le analisi automatiche consumano quota IA.", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("Annulla", null)
                .show()
        }
        card.addView(row, 0)
        card.addView(View(this).apply { setBackgroundColor(getColor(R.color.divider)) }, 1, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { bottomMargin = dp(12) })
    }

    private fun showMealCountDialog() {
        val profileId = data.activeProfileStore.currentIdOrNull() ?: return
        val current = data.mealCountPreferences.get(profileId)
        val labels = arrayOf("4 pasti", "5 pasti", "6 pasti")
        val values = intArrayOf(4, 5, 6)
        MaterialAlertDialogBuilder(this)
            .setTitle("Pasti al giorno")
            .setSingleChoiceItems(labels, values.indexOf(current)) { dialog, which ->
                data.mealCountPreferences.set(profileId, values[which])
                findViewById<TextView>(R.id.mealCountValue).text = "${values[which]} pasti al giorno"
                dialog.dismiss()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun bindDataDeletion() {
        val actions = listOf(
            R.id.rowDeletePlans to DeletionAction("alimentazioni", data.dataDeletionService::deleteMealPlans),
            R.id.rowDeleteConsumptions to DeletionAction("consumi registrati", data.dataDeletionService::deleteFoodConsumptions),
            R.id.rowDeleteBia to DeletionAction("misure BIA", data.dataDeletionService::deleteBiaMeasurements),
            R.id.rowDeleteBody to DeletionAction("misure corporee", data.dataDeletionService::deleteBodyMeasurements),
            R.id.rowDeleteWorkouts to DeletionAction("allenamenti", data.dataDeletionService::deleteWorkouts),
            R.id.rowDeleteCheats to DeletionAction("sgarri registrati", data.dataDeletionService::deleteCheatEntries),
            R.id.rowDeleteReviews to DeletionAction("review settimanali", data.dataDeletionService::deleteWeeklyReviews),
        )
        actions.forEach { (viewId, action) ->
            findViewById<View>(viewId).setOnClickListener { confirmDeletion(action.label, action.delete) }
        }
        findViewById<View>(R.id.rowDeleteRecordedData).setOnClickListener {
            confirmDeletion(
                "tutti i dati registrati (alimentazioni, misure, allenamenti, sgarri e review)",
                data.dataDeletionService::deleteRecordedData,
            )
        }
    }

    private fun confirmDeletion(label: String, delete: suspend () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Eliminare $label?")
            .setMessage("L'operazione è irreversibile per il profilo attivo. Profilo e chiavi IA resteranno disponibili.")
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Elimina") { _, _ ->
                lifecycleScope.launch {
                    runCatching { delete() }
                        .onSuccess { Toast.makeText(this@SettingsActivity, "$label eliminati", Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(this@SettingsActivity, "Eliminazione non riuscita", Toast.LENGTH_LONG).show() }
                }
            }
            .show()
    }

    private data class DeletionAction(val label: String, val delete: suspend () -> Unit)

    private fun providerError(provider: AiCredentialProvider, error: Throwable): String {
        val label = if (provider == AiCredentialProvider.GEMINI) "Gemini" else "OpenAI"
        val tail = "La chiave precedente non è stata modificata."
        return when (error) {
            is com.myfitai.app.ai.AiTransportException.Http -> when (error.failureKind) {
                com.myfitai.app.ai.AiTransportFailureKind.INVALID_API_KEY -> "API key $label invalida o senza permessi. $tail"
                com.myfitai.app.ai.AiTransportFailureKind.UNSUPPORTED_AUTH_METHOD ->
                    "La chiave $label è stata riconosciuta come Auth Key, ma la richiesta è stata rifiutata (HTTP ${error.statusCode}, ACCESS_TOKEN_TYPE_UNSUPPORTED). " +
                        "Di solito accade quando la Auth Key è legata a un progetto/organizzazione che non consente l'accesso via API key REST. $tail"
                com.myfitai.app.ai.AiTransportFailureKind.MODEL_UNAVAILABLE ->
                    if (provider == AiCredentialProvider.GEMINI) "Modello Gemini non disponibile (nemmeno il fallback). $tail"
                    else "Modello OpenAI non disponibile per questa chiave. $tail"
                com.myfitai.app.ai.AiTransportFailureKind.QUOTA_EXHAUSTED -> "Quota $label esaurita. $tail"
                com.myfitai.app.ai.AiTransportFailureKind.RATE_LIMITED -> "Limite temporaneo $label raggiunto. Riprova più tardi."
                com.myfitai.app.ai.AiTransportFailureKind.PROVIDER_UNAVAILABLE -> "$label non è temporaneamente disponibile (HTTP ${error.statusCode}). Riprova più tardi; $tail"
                else -> "$label ha rifiutato la richiesta (HTTP ${error.statusCode}). $tail"
            }
            is com.myfitai.app.ai.AiTransportException.Network -> "Problema di rete o timeout. $tail"
            is com.myfitai.app.ai.AiTransportException.InvalidResponse -> "Risposta $label non interpretabile. $tail"
            else -> "$label non raggiungibile. $tail"
        }
    }

    private fun pasteInto(input: com.google.android.material.textfield.TextInputEditText) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, "Clipboard vuota", Toast.LENGTH_SHORT).show()
            return
        }
        input.setText(text)
        input.setSelection(text.length)
        input.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        input.isLongClickable = false
        input.requestFocus()
        clipboard.clearPrimaryClip()
        Toast.makeText(this, "Chiave incollata. Verifica e salva per registrarla.", Toast.LENGTH_SHORT).show()
    }

    private fun clearClipboard() {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).clearPrimaryClip()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Sanitized verification telemetry: records ONLY provider + HTTP status + failure category.
     * Never logs the API key, request/response body, headers or any secret. Debug builds only.
     */
    private fun logVerificationOutcome(provider: AiCredentialProvider, error: Throwable) {
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        val providerName = if (provider == AiCredentialProvider.GEMINI) "GEMINI" else "OPENAI"
        val detail = when (error) {
            is com.myfitai.app.ai.AiTransportException.Http -> "status=${error.statusCode} kind=${error.failureKind}"
            is com.myfitai.app.ai.AiTransportException.Network -> "status=- kind=NETWORK"
            is com.myfitai.app.ai.AiTransportException.InvalidResponse -> "status=- kind=INVALID_RESPONSE"
            is com.myfitai.app.ai.AiTransportException.NotConfigured -> "status=- kind=NOT_CONFIGURED"
            else -> "status=- kind=UNKNOWN"
        }
        android.util.Log.w("MyFitAiVerify", "provider=$providerName $detail (key/body never logged)")
    }
}
