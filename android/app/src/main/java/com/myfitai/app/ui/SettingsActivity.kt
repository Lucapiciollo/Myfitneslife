package com.myfitai.app.ui

import android.os.Bundle
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

class SettingsActivity : BaseShellActivity() {
    private val data by lazy { AppDataContainer.get(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_settings)
        bindBottom(BottomNavBinder.Tab.MORE)
        bindBack()
        normalizeSettingsSurfaces()
        bindSectionHelp()
        bindWorkoutConfiguration()

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

        findViewById<View>(R.id.rowProfile).setOnClickListener {
            data.activeProfileStore.currentIdOrNull()?.let { profileId ->
                startActivity(android.content.Intent(this, OnboardingWizardActivity::class.java).putExtra(OnboardingWizardActivity.EXTRA_PROFILE_ID, profileId))
            }
        }
        findViewById<View>(R.id.rowAppGuide).setOnClickListener { showAppGuide() }
        findViewById<View>(R.id.rowMeasurements).setOnClickListener { go(MeasurementsActivity::class.java) }
        findViewById<View>(R.id.rowFoodPreferences).setOnClickListener { go(ProfileEditActivity::class.java) }
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

    private fun normalizeSettingsSurfaces() {
        fun visit(view: View) {
            when (view) {
                is MaterialCardView -> {
                    view.setCardBackgroundColor(getColor(R.color.white))
                    view.strokeColor = getColor(R.color.divider)
                    view.strokeWidth = dp(1)
                    view.cardElevation = 0f
                }
                is TextInputLayout -> {
                    view.boxBackgroundColor = getColor(R.color.white)
                    view.boxStrokeColor = getColor(R.color.myfitai_input_stroke)
                    view.boxStrokeWidth = dp(1)
                    view.boxStrokeWidthFocused = dp(2)
                    view.hintTextColor = android.content.res.ColorStateList.valueOf(getColor(R.color.myfitai_input_hint))
                }
            }
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(findViewById(android.R.id.content))
    }

    private fun bindWorkoutConfiguration() {
        val profileId = data.activeProfileStore.currentIdOrNull() ?: return
        val enabledSwitch = findViewById<MaterialSwitch>(R.id.workoutsEnabledSwitch)
        enabledSwitch.isChecked = data.workoutPreferences.isEnabled(profileId)
        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            data.workoutPreferences.setEnabled(profileId, checked)
        }
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
            minimumHeight = dp(48)
            setPadding(0, dp(8), 0, dp(8))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Generazione automatica piano"
            setTextAppearance(R.style.Text_MyFitAI_SettingsLabel)
        }
        val enabledSwitch = MaterialSwitch(this)
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(enabledSwitch)
        val value = TextView(this).apply { setTextAppearance(R.style.Text_MyFitAI_SettingsDescription) }
        row.addView(header)
        row.addView(value, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2)
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
            minimumHeight = dp(48)
            setPadding(0, dp(8), 0, dp(8))
        }
        val title = TextView(this).apply {
            text = "Analisi progressi automatica"
            setTextAppearance(R.style.Text_MyFitAI_SettingsLabel)
        }
        val value = TextView(this).apply {
            setTextAppearance(R.style.Text_MyFitAI_SettingsDescription)
        }
        fun renderValue() {
            val weeks = data.progressAnalysisPreferences.intervalWeeks
            value.text = "Ogni $weeks ${if (weeks == 1) "settimana" else "settimane"} · usa quota del provider IA"
        }
        renderValue()
        row.addView(title)
        row.addView(value, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
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

    private fun showAppGuide() {
        val sections = listOf(
            GuideSection("Il percorso MyFitAI", "Da dove parti e dove arrivi", "Inserisci profilo, rilevazioni, allenamenti e preferenze alimentari. L'app usa questi dati per costruire il percorso e lo aggiorna quando registri nuove informazioni."),
            GuideSection("1. Profilo e rilevazioni", "La base del calcolo", "Peso, altezza, età, sesso biologico, massa grassa, circonferenze e livello di attività descrivono il punto di partenza. Più i dati sono recenti e coerenti, più l'interpretazione è utile."),
            GuideSection("2. Calorie di base", "BMR e TDEE", "Il BMR è il consumo stimato a riposo. Con una massa grassa plausibile si usa Katch-McArdle: 370 + 21,6 × massa magra in kg. Altrimenti si usa Mifflin-St Jeor. Il TDEE è il BMR moltiplicato per l'attività: 1,20 sedentario, 1,375 leggero, 1,55 moderato, 1,725 molto attivo, 1,90 estremo."),
            GuideSection("3. Obiettivo e target", "Il numero calorico di riferimento", "Il target iniziale deriva dal TDEE: ricomposizione 95%, perdita di peso 85%, mantenimento 100%, aumento massa 110%, performance 100%. Sono fattori iniziali e non promesse sul risultato."),
            GuideSection("4. Macronutrienti", "Come vengono distribuiti i macro", "Le proteine sono 2,0 g/kg per perdita, ricomposizione e aumento massa, oppure 1,8 g/kg per mantenimento e performance. I grassi sono 0,8 g/kg, oppure 0,9 g/kg nella performance. I carboidrati ricevono le calorie rimanenti: (target - calorie di proteine e grassi) / 4."),
            GuideSection("5. Adattamento", "Il piano impara dai trend", "Il target cambia solo con evidenze sufficienti: almeno 21 giorni e almeno due segnali tra peso, massa grassa, massa muscolare, vita e addome. Uno stallo prolungato richiede almeno 28 giorni. La correzione è graduale, a passi del 2,5%, e resta dentro limiti conservativi."),
            GuideSection("6. Piano alimentare", "L'IA propone, l'app controlla", "L'IA propone pasti, quantità e preparazioni usando i target calcolati localmente. Prima del salvataggio l'app controlla struttura, calorie e macronutrienti. L'IA non decide autonomamente il deficit."),
            GuideSection("7. Diario e review", "Dal piano a ciò che accade davvero", "I pasti registrati vengono confrontati con il piano per mostrare calorie e macronutrienti consumati. Le review settimanali aiutano a leggere andamento, rispetto del piano e trend, ma una stima non diventa una misurazione certa."),
            GuideSection("8. Sgarro e serbatoio", "Il surplus viene distribuito nella settimana", "L'IA stima calorie e macro dello sgarro. Dopo la conferma, l'eccesso entra nel serbatoio e viene distribuito sui giorni futuri. Ogni giorno può essere ridotto al massimo del 15% del target; pasti già trascorsi o bloccati restano invariati. L'eventuale residuo non distribuito viene mostrato."),
            GuideSection("Da ricordare", "Una guida, non una diagnosi", "I risultati sono stime orientative basate sui dati inseriti. Idratazione, glicogeno, attività reale e qualità delle rilevazioni possono cambiare il risultato. Per condizioni mediche o obiettivi specifici, confrontati con un professionista."),
        )
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.white))
            setPadding(dp(4), dp(4), dp(4), dp(4))
            sections.forEach { addView(guideSectionCard(it)) }
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(getColor(R.color.white))
            addView(content)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Come funziona MyFitAI")
            .setView(scroll)
            .setPositiveButton("Ho capito", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_card)
            dialog.findViewById<View>(com.google.android.material.R.id.buttonPanel)?.setBackgroundColor(getColor(R.color.white))
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(getColor(R.color.accent_green_dark))
        }
        dialog.show()
    }

    private fun guideSectionCard(section: GuideSection): MaterialCardView {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
            setCardBackgroundColor(getColor(R.color.white))
            strokeColor = getColor(R.color.divider)
            strokeWidth = dp(1)
            radius = dp(14).toFloat()
            cardElevation = 0f
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.white))
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        TextView(this).apply {
            text = section.title
            textSize = 16f
            setTextColor(getColor(R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            content.addView(this)
        }
        TextView(this).apply {
            text = section.subtitle
            textSize = 12f
            setTextColor(getColor(R.color.accent_green_dark))
            setPadding(0, dp(4), 0, dp(8))
            content.addView(this)
        }
        TextView(this).apply {
            text = section.description
            textSize = 14f
            setTextColor(getColor(R.color.text_secondary))
            setLineSpacing(0f, 1.16f)
            content.addView(this)
        }
        card.addView(content)
        return card
    }

    private data class GuideSection(val title: String, val subtitle: String, val description: String)

    private fun bindDataDeletion() {
        val actions = listOf(
            R.id.rowDeletePlans to DeletionAction("piani alimentari salvati", "i piani e le giornate alimentari generate", data.dataDeletionService::deleteMealPlans),
            R.id.rowDeleteConsumptions to DeletionAction("pasti e consumi registrati", "gli alimenti e i pasti segnati come consumati", data.dataDeletionService::deleteFoodConsumptions),
            R.id.rowDeleteBia to DeletionAction("misurazioni BIA", "le rilevazioni BIA", data.dataDeletionService::deleteBiaMeasurements),
            R.id.rowDeleteBody to DeletionAction("misurazioni corporee", "peso, altezza e le altre rilevazioni corporee registrate", data.dataDeletionService::deleteBodyMeasurements),
            R.id.rowDeleteWorkouts to DeletionAction("allenamenti registrati", "le sessioni di allenamento", data.dataDeletionService::deleteWorkouts),
            R.id.rowDeleteCheats to DeletionAction("sgarri registrati", "gli sgarri e il relativo storico", data.dataDeletionService::deleteCheatEntries),
            R.id.rowDeleteReviews to DeletionAction("riepiloghi settimanali", "le review settimanali", data.dataDeletionService::deleteWeeklyReviews),
        )
        actions.forEach { (viewId, action) ->
            findViewById<View>(viewId).setOnClickListener { confirmDeletion(action, false) }
        }
        findViewById<View>(R.id.rowDeleteRecordedData).setOnClickListener {
            confirmDeletion(
                DeletionAction(
                    "tutti i dati di attività",
                    "piani alimentari, pasti e consumi, misurazioni BIA e corporee, allenamenti, sgarri e riepiloghi settimanali",
                    data.dataDeletionService::deleteRecordedData,
                ),
                true,
            )
        }
    }

    private fun confirmDeletion(action: DeletionAction, allData: Boolean) {
        MaterialAlertDialogBuilder(this)
            .setTitle(if (allData) "Eliminare tutti i dati di attività?" else "Eliminare ${action.label}?")
            .setMessage("Verranno eliminati: ${action.description}.\n\nL'operazione è irreversibile per il profilo attivo. Profilo e chiavi IA resteranno disponibili.")
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Elimina") { _, _ ->
                lifecycleScope.launch {
                    runCatching { action.delete() }
                        .onSuccess { Toast.makeText(this@SettingsActivity, "Eliminati: ${action.label}", Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(this@SettingsActivity, "Eliminazione non riuscita", Toast.LENGTH_LONG).show() }
                }
            }
            .show()
    }

    private data class DeletionAction(val label: String, val description: String, val delete: suspend () -> Unit)

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
