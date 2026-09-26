package com.myfitai.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
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
import com.myfitai.app.data.profile.NutritionPlanSchedulePreferences
import com.myfitai.app.domain.food.DietaryProfile
import com.myfitai.app.notifications.NotificationPreferences
import com.myfitai.app.security.AiCredentialProvider
import com.myfitai.app.security.SecureAiCredentialStore
import kotlinx.coroutines.launch
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

    private var nameInput: TextInputEditText? = null
    private var birthDateInput: TextInputEditText? = null
    private var sexInput: AutoCompleteTextView? = null
    private var heightInput: TextInputEditText? = null
    private var weightInput: TextInputEditText? = null
    private var activityInput: AutoCompleteTextView? = null
    private var goalInput: AutoCompleteTextView? = null
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
        get() = if (showAiStep) 4 else 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_onboarding_wizard)
        selectedProvider = if (aiSettings.useGemini) AiCredentialProvider.GEMINI else AiCredentialProvider.OPENAI
        bindShell()
        bindNavigation()
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
            profile?.let(::loadProfile)
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

    private fun loadProfile(value: UserProfileEntity) {
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
        nextButton.setText(if (step == totalSteps - 1) R.string.onboarding_finish else R.string.action_next)
        when (step) {
            0 -> renderProfileStep()
            1 -> renderMealsStep()
            2 -> renderScheduleStep()
            3 -> if (showAiStep) renderAiStep()
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
        activityInput = dropdown(content, R.string.profile_activity_hint, resources.getStringArray(R.array.profile_activity_levels).toList())
        goalInput = dropdown(content, R.string.profile_goal_hint, resources.getStringArray(R.array.profile_goals).toList())
        nameInput?.setText(profile?.name.orEmpty())
        birthDateInput?.setText(birthDateEpochDay?.let { LocalDate.ofEpochDay(it).format(formatter) }.orEmpty())
        sexInput?.setText(profile?.biologicalSex.orEmpty(), false)
        heightInput?.setText(profile?.heightCm?.let(::formatNumber).orEmpty())
        weightInput?.setText(profile?.currentWeightKg?.let(::formatNumber).orEmpty())
        activityInput?.setText(profile?.activityLevel.orEmpty(), false)
        goalInput?.setText(profile?.goal.orEmpty(), false)
        stepContent.addView(card)
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

    private fun captureCurrentStep() {
        when (step) {
            0 -> Unit
            1 -> {
                mealCount = mealCountInput?.text?.toString()?.substringBefore(' ')?.toIntOrNull() ?: mealCount
                wakeMinutes = parseTime(wakeInput?.text?.toString()) ?: wakeMinutes
                sleepMinutes = parseTime(sleepInput?.text?.toString()) ?: sleepMinutes
            }
            2 -> notificationsEnabled = notificationSwitch?.isChecked ?: notificationsEnabled
            3 -> Unit
        }
    }

    private fun validateStep(): Boolean {
        if (step != 0) return true
        val name = nameInput?.text?.toString()?.trim().orEmpty()
        val sex = sexInput?.text?.toString().orEmpty()
        val height = parseFloat(heightInput?.text?.toString())
        val weight = parseFloat(weightInput?.text?.toString())
        val errors = buildList {
            if (name.isBlank()) add(getString(R.string.validation_required_name))
            if (birthDateEpochDay == null) add(getString(R.string.validation_required_birth_date))
            if (sex !in setOf("Maschio", "Femmina")) add(getString(R.string.validation_required_sex))
            if (height == null || height !in 80f..250f) add(getString(R.string.validation_invalid_height))
            if (weight == null || weight !in 20f..400f) add(getString(R.string.validation_invalid_weight))
            if (activityInput?.text?.toString().orEmpty().isBlank()) add(getString(R.string.validation_required_activity))
        }
        if (errors.isEmpty()) return true
        validation.text = errors.joinToString("\n")
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
                val profileId = saveProfile()
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

    private suspend fun saveProfile(): Long {
        val now = System.currentTimeMillis()
        val name = nameInput?.text?.toString()?.trim().orEmpty()
        val birth = birthDateEpochDay
        val sex = sexInput?.text?.toString()?.trim().orEmpty()
        val height = parseFloat(heightInput?.text?.toString())
        val weight = parseFloat(weightInput?.text?.toString())
        val goal = goalInput?.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val activity = activityInput?.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val existing = profile
        val dietaryJson = existing?.dietaryPreferencesJson ?: DietaryProfile().toJson()
        if (existing == null) {
            return data.userProfileRepository.create(UserProfileEntity(
                name = name, birthDateEpochDay = birth, biologicalSex = sex,
                heightCm = height, currentWeightKg = weight, initialWeightKg = weight,
                goal = goal, activityLevel = activity, wakeTimeMinutes = wakeMinutes,
                sleepTimeMinutes = sleepMinutes, dietaryPreferencesJson = dietaryJson,
                photoPath = null, createdAtEpochMillis = now, updatedAtEpochMillis = now,
            )).also { data.activeProfileStore.selectProfile(it, makeDefault = true) }
        }
        data.userProfileRepository.update(existing.copy(
            name = name, birthDateEpochDay = birth, biologicalSex = sex,
            heightCm = height, currentWeightKg = weight,
            initialWeightKg = existing.initialWeightKg ?: weight, goal = goal,
            activityLevel = activity, wakeTimeMinutes = wakeMinutes, sleepTimeMinutes = sleepMinutes,
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
        strokeWidth = dimension(R.dimen.space_1)
        cardElevation = 0f
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
            this.inputType = when (inputType) { "numberDecimal" -> android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL; "textPassword" -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; "textPersonName" -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PERSON_NAME; else -> android.text.InputType.TYPE_NULL }
            this.isSingleLine = singleLine
            if (inputType == "textPassword") {
                transformationMethod = PasswordTransformationMethod.getInstance()
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                isLongClickable = false
            }
        }
        parent.addView(layout)
        return input
    }

    private fun dropdown(parent: LinearLayout, hintId: Int, values: List<String>): AutoCompleteTextView {
        val layout = layoutInflater.inflate(R.layout.view_onboarding_dropdown, parent, false) as TextInputLayout
        layout.apply {
            hint = getString(hintId)
            layoutParams = marginParams(top = if (parent.childCount == 0) 0 else R.dimen.space_8)
        }
        val input = layout.findViewById<AutoCompleteTextView>(R.id.onboardingDropdownInput).apply {
            setAdapter(ArrayAdapter(this@OnboardingWizardActivity, R.layout.item_dropdown_myfitai, values))
            setOnClickListener { showDropDown() }
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
    private fun stepTitleResource() = arrayOf(R.string.onboarding_step_profile, R.string.onboarding_step_meals, R.string.onboarding_step_schedule, R.string.onboarding_step_ai)[step]
    private fun stepDescriptionResource() = arrayOf(R.string.onboarding_step_profile_description, R.string.onboarding_step_meals_description, R.string.onboarding_step_schedule_description, R.string.onboarding_step_ai_description)[step]

    companion object {
        const val EXTRA_BOOTSTRAP = "onboarding_bootstrap"
        const val EXTRA_PROFILE_ID = "onboarding_profile_id"
        const val EXTRA_NEW_PROFILE = "onboarding_new_profile"
    }
}
