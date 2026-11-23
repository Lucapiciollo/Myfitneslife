package com.myfitai.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.WindowManager
import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.myfitai.app.R
import com.myfitai.app.ai.AiSettingsStore
import com.myfitai.app.ai.GeminiByokProvider
import com.myfitai.app.ai.OpenAiProvider
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.repository.HealthDeclarationSnapshot
import com.myfitai.app.data.profile.NutritionPlanSchedulePreferences
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import com.myfitai.app.domain.food.DietaryProfile
import com.myfitai.app.domain.profile.ActivityLevelChoices
import com.myfitai.app.domain.profile.AnamnesisDose
import com.myfitai.app.domain.profile.ProfileAgeRule
import com.myfitai.app.domain.profile.ProfileAnamnesis
import com.myfitai.app.domain.profile.ProfileAnamnesisCodec
import com.myfitai.app.domain.profile.ProfileAnamnesisDecodeResult
import com.myfitai.app.notifications.NotificationPreferences
import com.myfitai.app.security.AiCredentialProvider
import com.myfitai.app.security.SecureAiCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** First-run and re-profile wizard. Existing credentials are never read into the UI. */
class OnboardingWizardActivity : AppCompatActivity() {
    private val data by lazy { AppDataContainer.get(this) }
    private val credentials by lazy { SecureAiCredentialStore(this) }
    private val aiSettings by lazy { AiSettingsStore(this) }
    private val notificationPreferences by lazy { NotificationPreferences(this) }
    private val isNewProfile by lazy { intent.getBooleanExtra(EXTRA_NEW_PROFILE, false) }
    private val isAnamnesisOnly by lazy { intent.getBooleanExtra(EXTRA_ANAMNESIS_ONLY, false) }
    private val editingId by lazy { intent.getLongExtra(EXTRA_PROFILE_ID, 0L).takeIf { it > 0L } }
    private val isBootstrap by lazy { intent.getBooleanExtra(EXTRA_BOOTSTRAP, false) }
    /** API setup belongs only to the first app bootstrap, never to re-profile flows. */
    private val showAiStep by lazy {
        isBootstrap &&
            !credentials.exists(AiCredentialProvider.GEMINI) &&
            !credentials.exists(AiCredentialProvider.OPENAI)
    }

    private lateinit var stepContent: LinearLayout
    private lateinit var stepTitle: TextView
    private lateinit var stepDescription: TextView
    private lateinit var stepCount: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var previousButton: MaterialButton
    private lateinit var nextButton: MaterialButton
    private lateinit var validation: TextView

    private var profile: UserProfileEntity? = null
    private var activityFactors: Map<LocalCalculationEngine.ActivityLevel, Double>? = null
    private var activityChoices: List<ActivityLevelChoices.Choice> = emptyList()
    private var activityLevelDraft: String? = null
    private var step = 0
    private var saving = false
    private var birthDateEpochDay: Long? = null
    private var wakeMinutes: Int? = null
    private var sleepMinutes: Int? = null
    private var mealCount = 5
    private var notificationsEnabled = true
    private var planDay = NutritionPlanSchedulePreferences.DEFAULT_DAY
    private var planTimeMinutes = NutritionPlanSchedulePreferences.DEFAULT_TIME_MINUTES
    private var selectedProvider = AiCredentialProvider.GEMINI
    private var dietaryDraft = DietaryProfile()
    private var anamnesisDraft = ProfileAnamnesis()
    private var anamnesisUnreadable = false
    private var healthDeclarationBaseline: HealthDeclarationSnapshot? = null
    private val anamnesisTextInputs = mutableMapOf<String, TextInputEditText>()
    private val anamnesisDropdowns = mutableMapOf<String, AutoCompleteTextView>()
    private val selectedAnamnesisDocuments = mutableListOf<Uri>()
    private var anamnesisDocumentSummary: TextView? = null
    private var addAnamnesisDocumentsButton: MaterialButton? = null
    private var removeAnamnesisDocumentsButton: MaterialButton? = null
    private val backupOpenLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmBackupImport(uri)
    }
    private val anamnesisDocumentsLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val remaining = MAX_ANAMNESIS_DOCUMENTS - anamnesisDraft.documents.size - selectedAnamnesisDocuments.size
        selectedAnamnesisDocuments += uris.take(remaining.coerceAtLeast(0))
        if (uris.size > remaining.coerceAtLeast(0)) {
            Toast.makeText(this, R.string.anamnesis_documents_limit, Toast.LENGTH_SHORT).show()
        }
        updateAnamnesisDocumentSummary()
    }

    private var nameInput: TextInputEditText? = null
    private var birthDateInput: TextInputEditText? = null
    private var sexInput: AutoCompleteTextView? = null
    private var heightInput: TextInputEditText? = null
    private var weightInput: TextInputEditText? = null
    private var activityInput: AutoCompleteTextView? = null
    private var goalInput: AutoCompleteTextView? = null
    private var preferredFoodsInput: TextInputEditText? = null
    private var dislikedFoodsInput: TextInputEditText? = null
    private var excludedFoodsInput: TextInputEditText? = null
    private var intolerancesInput: TextInputEditText? = null
    private var allergiesInput: TextInputEditText? = null
    private var dietStyleInput: AutoCompleteTextView? = null
    private var foodNotesInput: TextInputEditText? = null
    private var mealCountInput: AutoCompleteTextView? = null
    private var wakeInput: TextInputEditText? = null
    private var sleepInput: TextInputEditText? = null
    private var notificationSwitch: MaterialSwitch? = null
    private var planDayInput: AutoCompleteTextView? = null
    private var planTimeInput: TextInputEditText? = null
    private var planSummary: TextView? = null
    private var providerInput: AutoCompleteTextView? = null
    private var apiKeyInput: TextInputEditText? = null
    private var aiStatus: TextView? = null

    private val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ITALIAN)
    private val totalSteps: Int
        get() = if (isAnamnesisOnly) 1 else if (showAiStep) 6 else 5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_onboarding_wizard)
        findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0).applySystemBarInsetsAsPadding(includeIme = true)
        selectedProvider = if (aiSettings.useGemini) AiCredentialProvider.GEMINI else AiCredentialProvider.OPENAI
        bindShell()
        bindNavigation()
        findViewById<View>(R.id.importProfileBackupButton).apply {
            visibility = if (isBootstrap) View.VISIBLE else View.GONE
            setOnClickListener { backupOpenLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (step > 0) {
                    captureCurrentStep()
                    step--
                    renderStep()
                } else if (!isBootstrap) {
                    finish()
                }
            }
        })
        lifecycleScope.launch {
            val id = if (isNewProfile) null else editingId ?: data.activeProfileStore.currentIdOrNull()
            profile = id?.let { data.userProfileRepository.get(it) }
            if (isAnamnesisOnly && profile == null) {
                Toast.makeText(this@OnboardingWizardActivity, R.string.onboarding_save_error, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            profile?.let { loadProfile(it) }
            renderStep()
        }
    }

    private fun bindShell() {
        stepContent = findViewById(R.id.stepContent)
        stepTitle = findViewById(R.id.stepTitle)
        stepDescription = findViewById(R.id.stepDescription)
        stepCount = findViewById(R.id.stepCount)
        progress = findViewById(R.id.progressIndicator)
        previousButton = findViewById(R.id.previousButton)
        nextButton = findViewById(R.id.nextButton)
        validation = findViewById(R.id.validationMessage)
        progress.max = totalSteps
        findViewById<TextView>(R.id.title).text = getString(R.string.onboarding_wizard_title)
    }

    private fun bindNavigation() {
        findViewById<View>(R.id.backButton).setOnClickListener {
            if (step > 0) {
                captureCurrentStep()
                step--
                renderStep()
            } else if (!isBootstrap) {
                finish()
            }
        }
        previousButton.setOnClickListener {
            if (step > 0 && !saving) {
                captureCurrentStep()
                step--
                renderStep()
            }
        }
        nextButton.setOnClickListener {
            if (saving) return@setOnClickListener
            captureCurrentStep()
            if (step == totalSteps - 1) saveWizard() else if (validateStep()) {
                step++
                renderStep()
            }
        }
    }

    private suspend fun loadProfile(value: UserProfileEntity) {
        activityLevelDraft = value.activityLevel
        dietaryDraft = DietaryProfile.parse(value.dietaryPreferencesJson)
        when (val decoded = ProfileAnamnesisCodec.decode(value.anamnesisJson)) {
            ProfileAnamnesisDecodeResult.Absent -> anamnesisDraft = ProfileAnamnesis()
            is ProfileAnamnesisDecodeResult.Valid -> anamnesisDraft = decoded.anamnesis
            ProfileAnamnesisDecodeResult.Invalid, is ProfileAnamnesisDecodeResult.UnsupportedVersion -> anamnesisUnreadable = true
        }
        healthDeclarationBaseline = data.userProfileRepository.healthDeclarationSnapshot(value.id)
        birthDateEpochDay = value.birthDateEpochDay
        wakeMinutes = value.wakeTimeMinutes
        sleepMinutes = value.sleepTimeMinutes
        data.mealCountPreferences.get(value.id).let { mealCount = it }
        data.nutritionPlanSchedulePreferences.get(value.id).let {
            planDay = it.dayOfWeek
            planTimeMinutes = it.timeMinutes
        }
        notificationsEnabled = notificationPreferences.mealRemindersEnabled || notificationPreferences.weeklyReviewEnabled
    }

    private fun renderStep() {
        stepContent.removeAllViews()
        validation.visibility = View.GONE
        stepCount.text = getString(R.string.onboarding_step_count, step + 1, totalSteps)
        progress.setProgressCompat(step + 1, true)
        stepTitle.setText(stepTitleResource())
        stepDescription.setText(stepDescriptionResource())
        previousButton.visibility = if (step == 0) View.INVISIBLE else View.VISIBLE
        nextButton.setText(when {
            isAnamnesisOnly -> R.string.anamnesis_save
            step == totalSteps - 1 -> R.string.onboarding_finish
            else -> R.string.action_next
        })
        nextButton.isEnabled = !saving
        if (isAnamnesisOnly) {
            renderAnamnesisStep()
        } else {
            when (step) {
                0 -> renderProfileStep()
                1 -> renderFoodPreferencesStep()
                2 -> renderMealsStep()
                3 -> renderScheduleStep()
                4 -> renderAnamnesisStep()
                5 -> if (showAiStep) renderAiStep()
            }
        }
    }

    private fun renderProfileStep() {
        val card = card()
        val content = content(card)
        nameInput = textInput(content, R.string.profile_name_hint, "textPersonName", singleLine = true)
        birthDateInput = textInput(content, R.string.profile_birth_date_hint, "none", singleLine = true).also { input ->
            input.isFocusable = false
            input.isClickable = true
            input.setOnClickListener { showDatePicker(input) }
        }
        sexInput = dropdown(content, R.string.profile_sex_hint, listOf("Maschio", "Femmina"))
        heightInput = textInput(content, R.string.profile_height_hint, "numberDecimal", singleLine = true)
        weightInput = textInput(content, R.string.profile_weight_hint, "numberDecimal", singleLine = true)
        // read() returns null for a present-but-unreadable table; do not silently substitute defaults.
        activityFactors = runCatching { data.activityLevelFactors.read() }.getOrNull()
        activityChoices = ActivityLevelChoices.catalogOptions(
            resources.getStringArray(R.array.profile_activity_levels).toList(),
            activityFactors,
        )
        activityInput = dropdown(content, R.string.profile_activity_hint, activityChoices.map { it.label })
        activityInput?.isEnabled = activityFactors != null
        activityInput?.setOnItemClickListener { _, _, position, _ ->
            activityChoices.getOrNull(position)?.let { choice ->
                activityLevelDraft = choice.value
                (activityInput?.parent as? TextInputLayout)?.helperText = choice.description
            }
        }
        goalInput = dropdown(content, R.string.profile_goal_hint, resources.getStringArray(R.array.profile_goals).toList())
        nameInput?.setText(profile?.name.orEmpty())
        birthDateInput?.setText(birthDateEpochDay?.let { LocalDate.ofEpochDay(it).format(formatter) }.orEmpty())
        sexInput?.setText(profile?.biologicalSex.orEmpty(), false)
        heightInput?.setText(profile?.heightCm?.let(::formatNumber).orEmpty())
        weightInput?.setText(profile?.currentWeightKg?.let(::formatNumber).orEmpty())
        val selectedActivity = activityLevelDraft ?: profile?.activityLevel
        activityInput?.setText(ActivityLevelChoices.displayValue(selectedActivity, activityFactors), false)
        (activityInput?.parent as? TextInputLayout)?.helperText =
            if (activityFactors == null) getString(R.string.activity_factor_unreadable_profile)
            else ActivityLevelChoices.descriptionFor(selectedActivity)
        goalInput?.setText(profile?.goal.orEmpty(), false)
        stepContent.addView(card)
    }

    private fun confirmBackupImport(uri: Uri) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Ripristina profilo dal backup?")
            .setMessage("Il profilo attivo e tutti i dati eventualmente presenti verranno sostituiti con il backup: BIA, misure, alimentazione, storico, consumi e versioni dei piani. Le chiavi IA non sono incluse.")
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Importa e riavvia") { _, _ ->
                lifecycleScope.launch {
                    nextButton.isEnabled = false
                    runCatching { data.profileBackupService.restore(uri) }
                        .onSuccess { restartAfterRestore() }
                        .onFailure {
                            nextButton.isEnabled = true
                            MaterialAlertDialogBuilder(this@OnboardingWizardActivity)
                                .setTitle("Importazione non riuscita")
                                .setMessage(it.message ?: "Controlla il file JSON e riprova.")
                                .setPositiveButton("Chiudi", null)
                                .show()
                        }
                }
            }
            .show()
    }

    private fun restartAfterRestore() {
        startActivity(Intent(this, SplashActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        finish()
    }

    private fun renderFoodPreferencesStep() {
        val foodCard = card()
        val content = content(foodCard)
        TextView(this).apply {
            setText(R.string.onboarding_food_preferences_title)
            setTextAppearance(R.style.Text_MyFitAI_Section)
            content.addView(this)
        }
        addDescription(content, R.string.onboarding_food_preferences_description)
        val dietary = dietaryDraft
        preferredFoodsInput = textInput(content, R.string.profile_preferred_foods_hint, "textCapSentencesMultiLine", singleLine = false).apply { id = R.id.preferredFoodsInput }
        dislikedFoodsInput = textInput(content, R.string.profile_disliked_foods_hint, "textCapSentencesMultiLine", singleLine = false).apply { id = R.id.dislikedFoodsInput }
        excludedFoodsInput = textInput(content, R.string.profile_excluded_foods_hint, "textCapSentencesMultiLine", singleLine = false).apply { id = R.id.excludedFoodsInput }
        intolerancesInput = textInput(content, R.string.profile_intolerances_hint, "textCapSentencesMultiLine", singleLine = false).apply { id = R.id.intolerancesInput }
        allergiesInput = textInput(content, R.string.profile_allergies_hint, "textCapSentencesMultiLine", singleLine = false).apply { id = R.id.allergiesInput }
        dietStyleInput = dropdown(content, R.string.profile_diet_style_hint, listOf("Nessuno", "Onnivoro", "Vegetariano", "Vegano", "Pescetariano"))
        foodNotesInput = textInput(content, R.string.profile_food_notes_hint, "textMultiLine", singleLine = false).apply { id = R.id.preferencesInput }
        preferredFoodsInput?.setText(dietary.preferredFoods.joinToString(", "))
        dislikedFoodsInput?.setText(dietary.dislikedFoods.joinToString(", "))
        excludedFoodsInput?.setText(dietary.excludedFoods.joinToString(", "))
        intolerancesInput?.setText(dietary.intolerances.joinToString(", "))
        allergiesInput?.setText(dietary.allergies.joinToString(", "))
        dietStyleInput?.setText(dietary.dietStyle ?: "Nessuno", false)
        foodNotesInput?.setText(dietary.notes.orEmpty())
        stepContent.addView(foodCard)
    }

    private fun renderMealsStep() {
        val card = card()
        val content = content(card)
        mealCountInput = dropdown(content, R.string.onboarding_meal_count_hint, listOf("4 pasti", "5 pasti", "6 pasti"))
            .also { it.setText("$mealCount pasti", false) }
        addDescription(content, R.string.onboarding_meal_count_description)
        wakeInput = textInput(content, R.string.profile_wake_hint, "none", singleLine = true).also { input ->
            input.isFocusable = false
            input.setOnClickListener { showTimePicker(input, R.string.profile_wake_hint, wakeMinutes ?: 7 * 60) { wakeMinutes = it; input.setText(formatMinutes(it)) } }
        }
        sleepInput = textInput(content, R.string.profile_sleep_hint, "none", singleLine = true).also { input ->
            input.isFocusable = false
            input.setOnClickListener { showTimePicker(input, R.string.profile_sleep_hint, sleepMinutes ?: 23 * 60) { sleepMinutes = it; input.setText(formatMinutes(it)) } }
        }
        wakeInput?.setText(wakeMinutes?.let(::formatMinutes).orEmpty())
        sleepInput?.setText(sleepMinutes?.let(::formatMinutes).orEmpty())
        stepContent.addView(card)
    }

    private fun renderScheduleStep() {
        val card = card()
        val content = content(card)
        notificationSwitch = MaterialSwitch(this).apply {
            applyMyFitAiSwitchTints()
            setTextColor(getColor(R.color.text_primary))
            setTextAppearance(R.style.Text_MyFitAI_SettingsLabel)
            text = getString(R.string.onboarding_notifications_label)
            minHeight = dimension(R.dimen.control_min_height)
            isChecked = notificationsEnabled
        }
        content.addView(notificationSwitch)
        addDescription(content, R.string.onboarding_notifications_description)
        planDayInput = dropdown(content, R.string.onboarding_plan_day_hint, DayOfWeek.entries.map(::dayLabel)).also {
            it.setText(dayLabel(planDay), false)
            it.setOnItemClickListener { _, _, position, _ -> planDay = DayOfWeek.entries[position]; renderPlanSummary() }
        }
        planTimeInput = textInput(content, R.string.onboarding_plan_time_hint, "none", singleLine = true).also { input ->
            input.isFocusable = false
            input.setText(formatMinutes(planTimeMinutes))
            input.setOnClickListener { showTimePicker(input, R.string.onboarding_plan_time_hint, planTimeMinutes) { planTimeMinutes = it; input.setText(formatMinutes(it)); renderPlanSummary() } }
        }
        planSummary = TextView(this).apply { setTextAppearance(R.style.Text_MyFitAI_SettingsDescription) }
        content.addView(planSummary, marginParams(top = R.dimen.space_8))
        renderPlanSummary()
        stepContent.addView(card)
    }

    private fun renderAiStep() {
        val card = card()
        val content = content(card)
        aiStatus = TextView(this).apply { setTextAppearance(R.style.Text_MyFitAI_SettingsDescription) }
        content.addView(aiStatus)
        providerInput = dropdown(content, R.string.onboarding_provider_hint, listOf("Gemini", "OpenAI")).also {
            it.setText(if (selectedProvider == AiCredentialProvider.GEMINI) "Gemini" else "OpenAI", false)
            it.setOnItemClickListener { _, _, position, _ -> selectedProvider = if (position == 0) AiCredentialProvider.GEMINI else AiCredentialProvider.OPENAI; renderAiStatus() }
        }
        apiKeyInput = textInput(content, R.string.onboarding_api_key_hint, "textPassword", singleLine = true)
            .apply {
                transformationMethod = PasswordTransformationMethod.getInstance()
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                isLongClickable = false
            }
        addDescription(content, R.string.onboarding_ai_optional_description)
        renderAiStatus()
        stepContent.addView(card)
    }

    private fun renderAnamnesisStep() {
        anamnesisTextInputs.clear()
        anamnesisDropdowns.clear()
        val card = card()
        val fields = content(card)
        if (anamnesisUnreadable) {
            addDescription(fields, R.string.anamnesis_unreadable)
            stepContent.addView(card)
            return
        }
        addDescription(fields, R.string.anamnesis_privacy)

        addAnamnesisSection(fields, R.string.anamnesis_activity_title)
        anamnesisInput(fields, "usualWeight", R.string.anamnesis_usual_weight_hint, "numberDecimal", anamnesisDraft.usualWeightKg?.let(::formatNumber))
        anamnesisInput(fields, "profession", R.string.anamnesis_profession_hint, "textCapSentences", anamnesisDraft.profession)
        anamnesisInput(fields, "steps", R.string.anamnesis_steps_hint, "number", anamnesisDraft.averageDailySteps?.toString())
        anamnesisInput(fields, "trainingType", R.string.anamnesis_training_type_hint, "textCapSentences", anamnesisDraft.trainingType)
        anamnesisInput(fields, "trainingFrequency", R.string.anamnesis_training_frequency_hint, "number", anamnesisDraft.trainingSessionsPerWeek?.toString())
        anamnesisInput(fields, "water", R.string.anamnesis_water_hint, "number", anamnesisDraft.dailyWaterMl?.toString())

        addAnamnesisSection(fields, R.string.anamnesis_women_title)
        anamnesisDropdown(fields, "cycle", R.string.anamnesis_cycle_hint, listOf(
            getString(R.string.anamnesis_cycle_regular),
            getString(R.string.anamnesis_cycle_irregular),
            getString(R.string.anamnesis_cycle_absent),
            getString(R.string.anamnesis_not_applicable),
        ), cycleLabel(anamnesisDraft.cycleStatus))
        anamnesisDropdown(fields, "contraceptives", R.string.anamnesis_contraceptives_hint, yesNoOptions(), boolLabel(anamnesisDraft.contraceptivesUsed))
        anamnesisInput(fields, "contraceptiveDetails", R.string.anamnesis_contraceptives_details_hint, "textCapSentences", anamnesisDraft.contraceptiveDetails)
        anamnesisInput(fields, "gynecologicalConditions", R.string.anamnesis_gynecological_conditions_hint, "textCapSentencesMultiLine", anamnesisDraft.gynecologicalConditions, multiline = true)
        anamnesisDropdown(fields, "menopause", R.string.anamnesis_menopause_hint, yesNoOptions(), boolLabel(anamnesisDraft.menopause))

        addAnamnesisSection(fields, R.string.anamnesis_health_title)
        anamnesisInput(fields, "healthConditions", R.string.anamnesis_health_conditions_hint, "textCapSentencesMultiLine", anamnesisDraft.healthConditions, multiline = true)
        anamnesisInput(fields, "gastrointestinalSymptoms", R.string.anamnesis_gastrointestinal_symptoms_hint, "textCapSentencesMultiLine", anamnesisDraft.gastrointestinalSymptoms, multiline = true)
        anamnesisInput(fields, "bowelHabits", R.string.anamnesis_bowel_habits_hint, "textCapSentencesMultiLine", anamnesisDraft.bowelHabits, multiline = true)
        anamnesisInput(fields, "medications", R.string.anamnesis_medications_hint, "textCapSentencesMultiLine", doseLines(anamnesisDraft.medications), multiline = true)
        anamnesisInput(fields, "supplements", R.string.anamnesis_supplements_hint, "textCapSentencesMultiLine", doseLines(anamnesisDraft.supplements), multiline = true)

        addAnamnesisSection(fields, R.string.anamnesis_exams_goal_title)
        anamnesisDropdown(fields, "recentExams", R.string.anamnesis_recent_exams_hint, yesNoOptions(), boolLabel(anamnesisDraft.recentExams))
        anamnesisDocumentSummary = TextView(this).apply {
            setTextAppearance(R.style.Text_MyFitAI_SettingsDescription)
            layoutParams = marginParams(R.dimen.space_8)
        }
        fields.addView(anamnesisDocumentSummary)
        addAnamnesisDocumentsButton = MaterialButton(this).apply {
            setText(R.string.anamnesis_documents_add)
            layoutParams = marginParams(R.dimen.space_8)
            setOnClickListener {
                if (anamnesisDraft.documents.size + selectedAnamnesisDocuments.size >= MAX_ANAMNESIS_DOCUMENTS) {
                    Toast.makeText(this@OnboardingWizardActivity, R.string.anamnesis_documents_limit, Toast.LENGTH_SHORT).show()
                } else {
                    anamnesisDocumentsLauncher.launch(arrayOf("application/pdf", "image/png", "image/jpeg"))
                }
            }
        }
        fields.addView(addAnamnesisDocumentsButton)
        removeAnamnesisDocumentsButton = MaterialButton(this).apply {
            setText(R.string.anamnesis_documents_remove)
            layoutParams = marginParams(R.dimen.space_8)
            setOnClickListener {
                selectedAnamnesisDocuments.clear()
                anamnesisDraft = anamnesisDraft.copy(documents = emptyList())
                updateAnamnesisDocumentSummary()
            }
        }
        fields.addView(removeAnamnesisDocumentsButton)
        addDescription(fields, R.string.anamnesis_documents_limit)
        anamnesisDropdown(fields, "desiredOutcome", R.string.anamnesis_outcome_hint, listOf(
            getString(R.string.anamnesis_outcome_weight_loss),
            getString(R.string.anamnesis_outcome_recomposition),
            getString(R.string.anamnesis_outcome_muscle),
            getString(R.string.anamnesis_outcome_performance),
            getString(R.string.anamnesis_outcome_health),
        ), outcomeLabel(anamnesisDraft.desiredOutcome))
        anamnesisInput(fields, "desiredTimeframe", R.string.anamnesis_timeframe_hint, "textCapSentences", anamnesisDraft.desiredTimeframe)
        updateAnamnesisDocumentSummary()
        stepContent.addView(card)
    }

    private fun addAnamnesisSection(parent: LinearLayout, titleId: Int) {
        parent.addView(TextView(this).apply {
            setText(titleId)
            setTextAppearance(R.style.Text_MyFitAI_SettingsDescription)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = marginParams(R.dimen.space_12)
        })
    }

    private fun anamnesisInput(parent: LinearLayout, key: String, hintId: Int, inputType: String, value: String?, multiline: Boolean = false) {
        val input = textInput(parent, hintId, inputType, singleLine = !multiline)
        input.setText(value.orEmpty())
        anamnesisTextInputs[key] = input
    }

    private fun anamnesisDropdown(parent: LinearLayout, key: String, hintId: Int, options: List<String>, value: String?) {
        val input = dropdown(parent, hintId, options)
        if (value != null) input.setText(value, false)
        anamnesisDropdowns[key] = input
    }

    private fun yesNoOptions() = listOf(getString(R.string.profile_health_yes), getString(R.string.profile_health_no))

    private fun boolLabel(value: Boolean?): String? = when (value) {
        true -> getString(R.string.profile_health_yes)
        false -> getString(R.string.profile_health_no)
        null -> null
    }

    private fun cycleLabel(value: ProfileAnamnesis.CycleStatus?): String? = when (value) {
        ProfileAnamnesis.CycleStatus.REGULAR -> getString(R.string.anamnesis_cycle_regular)
        ProfileAnamnesis.CycleStatus.IRREGULAR -> getString(R.string.anamnesis_cycle_irregular)
        ProfileAnamnesis.CycleStatus.ABSENT -> getString(R.string.anamnesis_cycle_absent)
        ProfileAnamnesis.CycleStatus.NOT_APPLICABLE -> getString(R.string.anamnesis_not_applicable)
        null -> null
    }

    private fun outcomeLabel(value: ProfileAnamnesis.DesiredOutcome?): String? = when (value) {
        ProfileAnamnesis.DesiredOutcome.WEIGHT_LOSS -> getString(R.string.anamnesis_outcome_weight_loss)
        ProfileAnamnesis.DesiredOutcome.BODY_RECOMPOSITION -> getString(R.string.anamnesis_outcome_recomposition)
        ProfileAnamnesis.DesiredOutcome.MUSCLE_GAIN -> getString(R.string.anamnesis_outcome_muscle)
        ProfileAnamnesis.DesiredOutcome.PERFORMANCE -> getString(R.string.anamnesis_outcome_performance)
        ProfileAnamnesis.DesiredOutcome.HEALTH_EDUCATION -> getString(R.string.anamnesis_outcome_health)
        null -> null
    }

    private fun doseLines(doses: List<AnamnesisDose>) = doses.joinToString("\n") { "${it.name} | ${it.dosage}" }

    private fun updateAnamnesisDocumentSummary() {
        val count = anamnesisDraft.documents.size + selectedAnamnesisDocuments.size
        val savedNames = anamnesisDraft.documents.map { it.displayName }
        val summary = buildList {
            if (count > 0) add(getString(R.string.anamnesis_documents_count, count))
            addAll(savedNames)
        }
        anamnesisDocumentSummary?.text = if (count == 0) getString(R.string.anamnesis_documents_none) else summary.joinToString("\n")
        removeAnamnesisDocumentsButton?.visibility = if (count == 0) View.GONE else View.VISIBLE
        addAnamnesisDocumentsButton?.isEnabled = count < MAX_ANAMNESIS_DOCUMENTS
    }

    private fun captureCurrentStep() {
        if (isAnamnesisOnly || step == 4) {
            captureAnamnesisDraft()
            return
        }
        when (step) {
            0 -> activityLevelDraft = ActivityLevelChoices.storedValue(
                displayed = activityInput?.text?.toString(),
                original = activityLevelDraft ?: profile?.activityLevel,
                coefficients = activityFactors,
                choices = activityChoices,
            )
            1 -> dietaryDraft = DietaryProfile(
                preferredFoods = DietaryProfile.csv(preferredFoodsInput?.text?.toString()),
                dislikedFoods = DietaryProfile.csv(dislikedFoodsInput?.text?.toString()),
                excludedFoods = DietaryProfile.csv(excludedFoodsInput?.text?.toString()),
                intolerances = DietaryProfile.csv(intolerancesInput?.text?.toString()),
                allergies = DietaryProfile.csv(allergiesInput?.text?.toString()),
                dietStyle = dietStyleInput?.text?.toString()?.trim()?.takeIf { it.isNotBlank() && !it.equals("Nessuno", ignoreCase = true) },
                notes = foodNotesInput?.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
            )
            2 -> {
                mealCount = mealCountInput?.text?.toString()?.substringBefore(' ')?.toIntOrNull() ?: mealCount
                wakeMinutes = parseTime(wakeInput?.text?.toString()) ?: wakeMinutes
                sleepMinutes = parseTime(sleepInput?.text?.toString()) ?: sleepMinutes
            }
            3 -> notificationsEnabled = notificationSwitch?.isChecked ?: notificationsEnabled
            4, 5 -> Unit
        }
    }

    private fun validateStep(): Boolean {
        if (isAnamnesisOnly || step == 4) return validateAnamnesis()
        if (step != 0) return true
        val name = nameInput?.text?.toString()?.trim().orEmpty()
        val sex = sexInput?.text?.toString().orEmpty()
        val height = parseFloat(heightInput?.text?.toString())
        val weight = parseFloat(weightInput?.text?.toString())
        val errors = buildList {
            if (name.isBlank()) add(getString(R.string.validation_required_name))
            ProfileAgeRule.message(ProfileAgeRule.check(birthDateEpochDay, LocalDate.now())) { res, min, max -> getString(res, min, max) }?.let { add(it) }
            if (sex !in setOf("Maschio", "Femmina")) add(getString(R.string.validation_required_sex))
            if (height == null || height !in 80f..250f) add(getString(R.string.validation_invalid_height))
            if (weight == null || weight !in 20f..400f) add(getString(R.string.validation_invalid_weight))
            if (profile == null) {
                if (activityFactors == null) add(getString(R.string.activity_factor_unreadable_profile))
                else if (activityInput?.text?.toString().orEmpty().isBlank()) {
                    add(getString(R.string.validation_required_activity))
                }
            }
        }
        if (errors.isEmpty()) return true
        validation.text = errors.joinToString("\n")
        validation.visibility = View.VISIBLE
        return false
    }

    private fun captureAnamnesisDraft() {
        fun text(key: String) = anamnesisTextInputs[key]?.text?.toString()?.trim()?.takeIf(String::isNotBlank)
        fun boolean(key: String): Boolean? = when (anamnesisDropdowns[key]?.text?.toString()) {
            getString(R.string.profile_health_yes) -> true
            getString(R.string.profile_health_no) -> false
            else -> null
        }
        anamnesisDraft = anamnesisDraft.copy(
            usualWeightKg = parseFloat(text("usualWeight")),
            profession = text("profession"),
            averageDailySteps = text("steps")?.toIntOrNull(),
            trainingType = text("trainingType"),
            trainingSessionsPerWeek = text("trainingFrequency")?.toIntOrNull(),
            dailyWaterMl = text("water")?.toIntOrNull(),
            cycleStatus = when (anamnesisDropdowns["cycle"]?.text?.toString()) {
                getString(R.string.anamnesis_cycle_regular) -> ProfileAnamnesis.CycleStatus.REGULAR
                getString(R.string.anamnesis_cycle_irregular) -> ProfileAnamnesis.CycleStatus.IRREGULAR
                getString(R.string.anamnesis_cycle_absent) -> ProfileAnamnesis.CycleStatus.ABSENT
                getString(R.string.anamnesis_not_applicable) -> ProfileAnamnesis.CycleStatus.NOT_APPLICABLE
                else -> null
            },
            contraceptivesUsed = boolean("contraceptives"),
            contraceptiveDetails = text("contraceptiveDetails"),
            gynecologicalConditions = text("gynecologicalConditions"),
            menopause = boolean("menopause"),
            healthConditions = text("healthConditions"),
            gastrointestinalSymptoms = text("gastrointestinalSymptoms"),
            bowelHabits = text("bowelHabits"),
            medications = parseDoseLines(text("medications")),
            supplements = parseDoseLines(text("supplements")),
            recentExams = boolean("recentExams"),
            desiredOutcome = when (anamnesisDropdowns["desiredOutcome"]?.text?.toString()) {
                getString(R.string.anamnesis_outcome_weight_loss) -> ProfileAnamnesis.DesiredOutcome.WEIGHT_LOSS
                getString(R.string.anamnesis_outcome_recomposition) -> ProfileAnamnesis.DesiredOutcome.BODY_RECOMPOSITION
                getString(R.string.anamnesis_outcome_muscle) -> ProfileAnamnesis.DesiredOutcome.MUSCLE_GAIN
                getString(R.string.anamnesis_outcome_performance) -> ProfileAnamnesis.DesiredOutcome.PERFORMANCE
                getString(R.string.anamnesis_outcome_health) -> ProfileAnamnesis.DesiredOutcome.HEALTH_EDUCATION
                else -> null
            },
            desiredTimeframe = text("desiredTimeframe"),
        )
    }

    private fun parseDoseLines(value: String?): List<AnamnesisDose> = value.orEmpty().lines()
        .map(String::trim)
        .filter(String::isNotBlank)
        .map { line ->
            val parts = line.split('|', limit = 2)
            AnamnesisDose(parts[0].trim(), parts.getOrNull(1)?.trim().orEmpty())
        }

    private fun validateAnamnesis(): Boolean {
        if (anamnesisUnreadable) {
            return true
        }
        val numberKeys = listOf("usualWeight", "steps", "trainingFrequency", "water")
        val numbersValid = numberKeys.all { key ->
            val value = anamnesisTextInputs[key]?.text?.toString()?.trim().orEmpty()
            value.isBlank() || when (key) {
                "usualWeight" -> parseFloat(value) != null
                else -> value.toIntOrNull() != null
            }
        }
        val doseLinesValid = listOf("medications", "supplements").all { key ->
            anamnesisTextInputs[key]?.text?.toString().orEmpty().lines().all { line ->
                line.isBlank() || line.split('|', limit = 2).let { it.size == 2 && it[0].isNotBlank() && it[1].isNotBlank() }
            }
        }
        captureAnamnesisDraft()
        val modelValid = runCatching { ProfileAnamnesisCodec.encode(anamnesisDraft) }.isSuccess
        if (numbersValid && doseLinesValid && modelValid && anamnesisDraft.documents.size + selectedAnamnesisDocuments.size <= MAX_ANAMNESIS_DOCUMENTS) return true
        validation.setText(R.string.anamnesis_invalid)
        validation.visibility = View.VISIBLE
        return false
    }

    private fun saveWizard() {
        if (!validateStep() || saving) return
        captureCurrentStep()
        saving = true
        nextButton.isEnabled = false
        lifecycleScope.launch {
            runCatching {
                if (isAnamnesisOnly) {
                    persistAnamnesis(profile?.id ?: error(getString(R.string.onboarding_save_error)))
                    return@runCatching
                }
                val profileId = saveProfile()
                persistAnamnesis(profileId)
                data.mealCountPreferences.set(profileId, mealCount)
                data.nutritionPlanSchedulePreferences.setFrequency(profileId, NutritionPlanSchedulePreferences.Frequency.WEEKLY)
                data.nutritionPlanSchedulePreferences.setDayOfWeek(profileId, planDay)
                data.nutritionPlanSchedulePreferences.setTimeMinutes(profileId, planTimeMinutes)
                notificationPreferences.mealRemindersEnabled = notificationsEnabled
                notificationPreferences.weeklyReviewEnabled = notificationsEnabled
                val providerConfigured = if (showAiStep) {
                    saveOptionalCredential()
                } else {
                    // Re-profile/new-profile flows must not alter existing provider state.
                    com.myfitai.app.ai.AiProviderAccess.isConfigured(this@OnboardingWizardActivity)
                }
                if (showAiStep) {
                    data.nutritionPlanSchedulePreferences.setEnabled(profileId, providerConfigured)
                    if (providerConfigured) data.nutritionPlanScheduler.reschedule(profileId) else data.nutritionPlanScheduler.cancel(profileId)
                }
                data.notificationScheduler.refresh()
            }.onSuccess {
                Toast.makeText(this@OnboardingWizardActivity, R.string.onboarding_saved, Toast.LENGTH_SHORT).show()
                if (isAnamnesisOnly) {
                    finish()
                    return@onSuccess
                }
                startActivity(Intent(this@OnboardingWizardActivity, TabHostActivity::class.java).apply {
                    putExtra(com.myfitai.app.navigation.BottomNavBinder.EXTRA_SELECTED_TAB, com.myfitai.app.navigation.BottomNavBinder.Tab.HOME.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                })
                finish()
            }.onFailure { error ->
                saving = false
                nextButton.isEnabled = true
                validation.text = error.message ?: getString(R.string.onboarding_save_error)
                validation.visibility = View.VISIBLE
            }
        }
    }

    private suspend fun persistAnamnesis(profileId: Long) {
        if (anamnesisUnreadable) return
        val store = data.profileAnamnesisDocumentStore
        try {
            val addedDocuments = withContext(Dispatchers.IO) {
                selectedAnamnesisDocuments.map { store.copyFrom(profileId, it) }
            }
            val updated = anamnesisDraft.copy(documents = anamnesisDraft.documents + addedDocuments)
            val json = ProfileAnamnesisCodec.encode(updated)
            check(data.userProfileRepository.saveAnamnesis(
                profileId = profileId,
                json = json,
                updatedAt = System.currentTimeMillis(),
                healthBaseline = healthDeclarationBaseline,
                healthDeclaration = null,
            ) != null) { getString(R.string.onboarding_save_error) }
            withContext(Dispatchers.IO) {
                store.deleteExcept(profileId, updated.documents.mapTo(mutableSetOf()) { it.id })
            }
            anamnesisDraft = updated
            selectedAnamnesisDocuments.clear()
        } catch (error: Throwable) {
            withContext(Dispatchers.IO) {
                store.deleteExcept(profileId, anamnesisDraft.documents.mapTo(mutableSetOf()) { it.id })
            }
            throw error
        }
    }

    private suspend fun saveProfile(): Long {
        val now = System.currentTimeMillis()
        val name = nameInput?.text?.toString()?.trim().orEmpty()
        val birth = birthDateEpochDay
        // Ultima difesa prima di scrivere: nessun salvataggio con età fuori 14-120.
        ProfileAgeRule.message(ProfileAgeRule.check(birth, LocalDate.now())) { res, min, max -> getString(res, min, max) }?.let { throw IllegalArgumentException(it) }
        val sex = sexInput?.text?.toString()?.trim().orEmpty()
        val height = parseFloat(heightInput?.text?.toString())
        val weight = parseFloat(weightInput?.text?.toString())
        val goal = goalInput?.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val activity = ActivityLevelChoices.storedValue(
            displayed = activityInput?.text?.toString(),
            original = profile?.activityLevel,
            coefficients = activityFactors,
            choices = activityChoices,
        )
        val existing = profile
        val dietaryJson = dietaryDraft.toJson()
        if (existing == null) {
            return data.userProfileRepository.create(UserProfileEntity(
                name = name, birthDateEpochDay = birth, biologicalSex = sex,
                heightCm = height, currentWeightKg = weight, initialWeightKg = weight,
                goal = goal, activityLevel = activity, wakeTimeMinutes = wakeMinutes,
                sleepTimeMinutes = sleepMinutes, dietaryPreferencesJson = dietaryJson,
                photoPath = null, createdAtEpochMillis = now, updatedAtEpochMillis = now,
            )).also {
                data.activeProfileStore.selectProfile(it, makeDefault = true)
            }
        }
        val calorieInputsChanged = goal != existing.goal || activity != existing.activityLevel ||
            birth != existing.birthDateEpochDay || sex != existing.biologicalSex ||
            height != existing.heightCm || weight != existing.currentWeightKg
        if (calorieInputsChanged) {
            val weekStart = LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay()
            if (data.mealPlanRepository.getPlanForWeek(existing.id, weekStart) != null) {
                data.nutritionPlanUpdatePreferences.setPending(existing.id, true)
            }
        }
        data.userProfileRepository.update(existing.copy(
            name = name, birthDateEpochDay = birth, biologicalSex = sex,
            heightCm = height, currentWeightKg = weight,
            initialWeightKg = existing.initialWeightKg ?: weight, goal = goal,
            activityLevel = activity, wakeTimeMinutes = wakeMinutes, sleepTimeMinutes = sleepMinutes,
            dietaryPreferencesJson = dietaryJson,
            updatedAtEpochMillis = now,
        ))
        return existing.id
    }

    private suspend fun saveOptionalCredential(): Boolean {
        val candidate = apiKeyInput?.text?.toString()?.trim().orEmpty()
        if (candidate.isNotBlank()) {
            val response = if (selectedProvider == AiCredentialProvider.GEMINI) {
                GeminiByokProvider(credentials).verifyApiKey(candidate)
            } else {
                OpenAiProvider(credentials).verifyApiKey(candidate)
            }
            credentials.save(selectedProvider, candidate)
            if (selectedProvider == AiCredentialProvider.GEMINI) aiSettings.geminiVerifiedModel = response.model
        }
        if (credentials.exists(AiCredentialProvider.GEMINI) || credentials.exists(AiCredentialProvider.OPENAI)) {
            aiSettings.useGemini = if (credentials.exists(AiCredentialProvider.GEMINI)) {
                selectedProvider == AiCredentialProvider.GEMINI || !credentials.exists(AiCredentialProvider.OPENAI)
            } else false
        }
        apiKeyInput?.text?.clear()
        return com.myfitai.app.ai.AiProviderAccess.isConfigured(this)
    }

    private fun renderAiStatus() {
        aiStatus?.text = when {
            credentials.exists(AiCredentialProvider.GEMINI) && credentials.exists(AiCredentialProvider.OPENAI) -> getString(R.string.onboarding_ai_both_configured)
            credentials.exists(AiCredentialProvider.GEMINI) -> getString(R.string.onboarding_ai_gemini_configured)
            credentials.exists(AiCredentialProvider.OPENAI) -> getString(R.string.onboarding_ai_openai_configured)
            else -> getString(R.string.onboarding_ai_not_configured)
        }
    }

    private fun renderPlanSummary() {
        val configured = com.myfitai.app.ai.AiProviderAccess.isConfigured(this)
        planSummary?.text = if (configured) getString(R.string.onboarding_weekly_plan_ready, dayLabel(planDay), formatMinutes(planTimeMinutes)) else getString(R.string.onboarding_weekly_plan_waiting_ai)
    }

    private fun showDatePicker(input: TextInputEditText) {
        val picker = MaterialDatePicker.Builder.datePicker().setTheme(R.style.ThemeOverlay_MyFitAI_MaterialCalendar).setTitleText(getString(R.string.profile_birth_date_title)).build()
        picker.addOnPositiveButtonClickListener { value ->
            birthDateEpochDay = Instant.ofEpochMilli(value).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
            input.setText(LocalDate.ofEpochDay(birthDateEpochDay!!).format(formatter))
        }
        picker.show(supportFragmentManager, "wizard_birth_date")
    }

    private fun showTimePicker(input: TextInputEditText, titleId: Int, current: Int, onSelected: (Int) -> Unit) {
        val picker = MaterialTimePicker.Builder().setTheme(R.style.ThemeOverlay_MyFitAI_MaterialTimePicker).setTimeFormat(TimeFormat.CLOCK_24H).setHour(current / 60).setMinute(current % 60).setTitleText(getString(titleId)).build()
        picker.addOnPositiveButtonClickListener { onSelected(picker.hour * 60 + picker.minute) }
        picker.show(supportFragmentManager, "wizard_time_${input.id}")
    }

    private fun card(): MaterialCardView = MaterialCardView(this).apply {
        setCardBackgroundColor(getColor(R.color.white))
        strokeColor = getColor(R.color.divider)
        strokeWidth = dimension(R.dimen.border_width_default)
        cardElevation = resources.getDimension(R.dimen.elevation_none)
        radius = dimension(R.dimen.radius_card).toFloat()
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dimension(R.dimen.space_12)
        }
    }

    private fun content(card: MaterialCardView): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            dimension(R.dimen.space_12),
            dimension(R.dimen.space_10),
            dimension(R.dimen.space_12),
            dimension(R.dimen.space_12),
        )
        card.addView(this)
    }

    private fun textInput(parent: LinearLayout, hintId: Int, inputType: String, singleLine: Boolean): TextInputEditText {
        val layout = layoutInflater.inflate(R.layout.view_onboarding_text_input, parent, false) as TextInputLayout
        layout.apply {
            hint = getString(hintId)
            layoutParams = marginParams(top = if (parent.childCount == 0) 0 else R.dimen.space_8)
        }
        val input = layout.findViewById<TextInputEditText>(R.id.onboardingTextInput).apply {
            this.inputType = when (inputType) {
                "number" -> android.text.InputType.TYPE_CLASS_NUMBER
                "numberDecimal" -> android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                "textPassword" -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                "textPersonName" -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PERSON_NAME
                "textMultiLine" -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                "textCapSentences" -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                "textCapSentencesMultiLine" -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                else -> android.text.InputType.TYPE_NULL
            }
            this.isSingleLine = singleLine
            if (!singleLine) {
                minLines = 2
                maxLines = if (inputType == "textMultiLine") 4 else 2
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
            }
            if (inputType == "textPassword") {
                transformationMethod = PasswordTransformationMethod.getInstance()
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                isLongClickable = false
            }
            if (inputType != "none") {
                val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                setOnFocusChangeListener { view, focused ->
                    if (focused) view.postDelayed({ inputMethodManager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT) }, 100L)
                }
                setOnClickListener { view ->
                    view.requestFocus()
                    view.postDelayed({ inputMethodManager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT) }, 100L)
                }
            }
        }
        parent.addView(layout)
        return input
    }

    private fun dropdown(parent: LinearLayout, hintId: Int, values: List<String>, helperId: Int? = null): AutoCompleteTextView {
        val layout = layoutInflater.inflate(R.layout.view_onboarding_dropdown, parent, false) as TextInputLayout
        layout.apply {
            hint = getString(hintId)
            helperId?.let { helperText = getString(it) }
            layoutParams = marginParams(top = if (parent.childCount == 0) 0 else R.dimen.space_8)
        }
        val input = layout.findViewById<AutoCompleteTextView>(R.id.onboardingDropdownInput).apply {
            setMyFitAiDropdownItems(values)
        }
        parent.addView(layout)
        return input
    }

    private fun addDescription(parent: LinearLayout, textId: Int) {
        parent.addView(TextView(this).apply {
            setText(textId)
            setTextAppearance(R.style.Text_MyFitAI_SettingsDescription)
            layoutParams = marginParams(top = R.dimen.space_8)
        })
    }

    private fun marginParams(top: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = if (top == 0) 0 else dimension(top)
    }
    private fun parseFloat(value: String?): Float? = value?.trim()?.replace(',', '.')?.takeIf { it.isNotEmpty() }?.toFloatOrNull()
    private fun parseTime(value: String?): Int? = value?.split(':')?.takeIf { it.size == 2 }?.let { (h, m) -> h.toIntOrNull()?.times(60)?.plus(m.toIntOrNull() ?: return@let null) }
    private fun formatMinutes(value: Int) = String.format(Locale.ITALIAN, "%02d:%02d", value / 60, value % 60)
    private fun formatNumber(value: Float) = if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.ITALIAN, "%.1f", value)
    private fun dimension(resourceId: Int) = resources.getDimensionPixelSize(resourceId)
    private fun dayLabel(day: DayOfWeek) = when (day) {
        DayOfWeek.MONDAY -> "Lunedì"; DayOfWeek.TUESDAY -> "Martedì"; DayOfWeek.WEDNESDAY -> "Mercoledì"; DayOfWeek.THURSDAY -> "Giovedì"; DayOfWeek.FRIDAY -> "Venerdì"; DayOfWeek.SATURDAY -> "Sabato"; DayOfWeek.SUNDAY -> "Domenica"
    }
    private fun stepTitleResource(): Int = when {
        isAnamnesisOnly || step == 4 -> R.string.onboarding_step_anamnesis
        step == 0 -> R.string.onboarding_step_profile
        step == 1 -> R.string.onboarding_step_food
        step == 2 -> R.string.onboarding_step_meals
        step == 3 -> R.string.onboarding_step_schedule
        else -> R.string.onboarding_step_ai
    }

    private fun stepDescriptionResource(): Int = when {
        isAnamnesisOnly || step == 4 -> R.string.onboarding_step_anamnesis_description
        step == 0 -> R.string.onboarding_step_profile_description
        step == 1 -> R.string.onboarding_food_preferences_description
        step == 2 -> R.string.onboarding_step_meals_description
        step == 3 -> R.string.onboarding_step_schedule_description
        else -> R.string.onboarding_step_ai_description
    }

    companion object {
        const val EXTRA_BOOTSTRAP = "onboarding_bootstrap"
        const val EXTRA_PROFILE_ID = "onboarding_profile_id"
        const val EXTRA_NEW_PROFILE = "onboarding_new_profile"
        const val EXTRA_ANAMNESIS_ONLY = "onboarding_anamnesis_only"
        private const val MAX_ANAMNESIS_DOCUMENTS = 5
    }
}
