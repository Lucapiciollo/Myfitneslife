package com.myfitai.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.myfitai.app.R
import com.myfitai.app.domain.profile.ProfileAgeRule
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.profile.ActivityLevelChoices
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.domain.safety.HealthDeclaration
import com.myfitai.app.domain.safety.HealthDeclarationDecodeResult
import com.myfitai.app.ui.profile.ProfileEditViewModel
import com.myfitai.app.ui.profile.ProfileEditViewModel.HealthDeclarationDraft
import com.myfitai.app.ui.profile.ProfileEditViewModel.HealthDeclarationSaveState
import com.myfitai.app.ui.profile.ProfileEditViewModel.HealthDeclarationSaveStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.time.temporal.TemporalAdjusters

class ProfileEditActivity : BaseShellActivity() {

    private val isBootstrap by lazy { intent.getBooleanExtra(EXTRA_BOOTSTRAP, false) }
    private val isCreate by lazy { isBootstrap || intent.getBooleanExtra(EXTRA_CREATE, false) }
    private val data by lazy { AppDataContainer.get(this) }
    private val viewModel: ProfileEditViewModel by viewModels {
        // Il ViewModel sopravvive alla rotazione: la lambda cattura solo l'application context, non l'Activity.
        val appContext = applicationContext
        ProfileEditViewModel.Factory(data.userProfileRepository, data.activeProfileStore, allowCreate = isCreate, ageMessage = { status ->
            ProfileAgeRule.message(status) { res, min, max -> appContext.getString(res, min, max) }.orEmpty()
        })
    }

    private lateinit var nameInput: TextInputEditText
    private lateinit var birthDateInput: TextInputEditText
    private lateinit var sexInput: AutoCompleteTextView
    private lateinit var heightInput: TextInputEditText
    private lateinit var weightInput: TextInputEditText
    private lateinit var goalInput: AutoCompleteTextView
    private lateinit var activityInput: AutoCompleteTextView
    private lateinit var wakeTimeInput: TextInputEditText
    private lateinit var sleepTimeInput: TextInputEditText
    private lateinit var saveButton: MaterialButton
    private lateinit var healthDeclarationCard: View
    private lateinit var healthDeclarationStatus: TextView
    private lateinit var healthDeclarationProgress: ProgressBar
    private lateinit var healthPregnancyGroup: RadioGroup
    private lateinit var healthConditionGroup: RadioGroup
    private lateinit var healthPregnancyMissing: TextView
    private lateinit var healthConditionMissing: TextView
    private lateinit var healthRetryButton: MaterialButton
    private lateinit var healthSaveButton: MaterialButton
    private lateinit var healthClearPregnancyButton: MaterialButton
    private lateinit var healthClearConditionButton: MaterialButton

    private var birthDateEpochDay: Long? = null
    private var wakeTimeMinutes: Int? = null
    private var sleepTimeMinutes: Int? = null
    private var initialRenderedProfileId: Long? = null
    private var healthDeclarationProfileId: Long? = null
    private var healthDeclarationStatusState = HealthDeclarationUiState.LOADING
    private var healthDeclarationOperationIsSave = false
    private var healthDeclarationSaving = false
    private var renderingHealthDeclaration = false
    private val healthDeclarationDraft: HealthDeclarationDraft
        get() = viewModel.healthDeclarationEditor.value.draft

    private enum class HealthDeclarationUiState {
        LOADING,
        ABSENT,
        INCOMPLETE,
        VALID,
        INVALID,
        UNSUPPORTED,
        ERROR,
        STALE,
        SAVED,
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_edit)
        when {
            isBootstrap -> {
                findViewById<View>(R.id.backButton).visibility = View.INVISIBLE
                findViewById<TextView>(R.id.title).text = "Crea il tuo profilo"
                findViewById<View>(R.id.bootstrapHint).visibility = View.VISIBLE
            }
            isCreate -> {
                bindBack()
                findViewById<TextView>(R.id.title).text = "Nuovo profilo"
                findViewById<View>(R.id.bootstrapHint).visibility = View.VISIBLE
            }
            else -> bindBack()
        }
        bindViews()
        bindHealthDeclaration()
        if (isCreate) {
            findViewById<View>(R.id.goalLayout).visibility = View.GONE
            findViewById<TextView>(R.id.goalSectionTitle).text = "Attività"
            findViewById<View>(R.id.goalAdvisorHint).visibility = View.VISIBLE
            findViewById<TextView>(R.id.bootstrapHint).text =
                "Inserisci i dati di base. MyFitAI ti consiglierà poi l'obiettivo più coerente; la BIA potrà essere aggiunta anche in seguito."
        }
        bindDropdowns()
        bindPickers()
        bindSave()
        observeState()
    }

    private fun bindViews() {
        nameInput = findViewById(R.id.nameInput)
        birthDateInput = findViewById(R.id.birthDateInput)
        sexInput = findViewById(R.id.sexInput)
        heightInput = findViewById(R.id.heightInput)
        weightInput = findViewById(R.id.weightInput)
        goalInput = findViewById(R.id.goalInput)
        activityInput = findViewById(R.id.activityInput)
        wakeTimeInput = findViewById(R.id.wakeTimeInput)
        sleepTimeInput = findViewById(R.id.sleepTimeInput)
        saveButton = findViewById(R.id.saveProfileButton)
    }

    private fun bindHealthDeclaration() {
        healthDeclarationCard = findViewById(R.id.healthDeclarationCard)
        healthDeclarationStatus = findViewById(R.id.healthDeclarationStatus)
        healthDeclarationProgress = findViewById(R.id.healthDeclarationProgress)
        healthPregnancyGroup = findViewById(R.id.healthPregnancyGroup)
        healthConditionGroup = findViewById(R.id.healthConditionGroup)
        healthPregnancyMissing = findViewById(R.id.healthPregnancyMissing)
        healthConditionMissing = findViewById(R.id.healthConditionMissing)
        healthRetryButton = findViewById(R.id.healthDeclarationRetryButton)
        healthSaveButton = findViewById(R.id.saveHealthDeclarationButton)
        healthClearPregnancyButton = findViewById(R.id.clearHealthPregnancyButton)
        healthClearConditionButton = findViewById(R.id.clearHealthConditionButton)

        healthPregnancyGroup.setOnCheckedChangeListener { _, checkedId ->
            if (!renderingHealthDeclaration) {
                updateHealthDeclarationDraft(
                    healthDeclarationDraft.copy(pregnancyOrLactation = answerFromRadio(checkedId, R.id.healthPregnancyYes, R.id.healthPregnancyNo)),
                )
            }
        }
        healthConditionGroup.setOnCheckedChangeListener { _, checkedId ->
            if (!renderingHealthDeclaration) {
                updateHealthDeclarationDraft(
                    healthDeclarationDraft.copy(clinicianManagedConditionOrMedication = answerFromRadio(checkedId, R.id.healthConditionYes, R.id.healthConditionNo)),
                )
            }
        }
        healthClearPregnancyButton.setOnClickListener {
            updateHealthDeclarationDraft(healthDeclarationDraft.copy(pregnancyOrLactation = null))
        }
        healthClearConditionButton.setOnClickListener {
            updateHealthDeclarationDraft(healthDeclarationDraft.copy(clinicianManagedConditionOrMedication = null))
        }
        healthRetryButton.setOnClickListener {
            val profileId = healthDeclarationProfileId ?: return@setOnClickListener
            if (healthDeclarationStatusState == HealthDeclarationUiState.STALE) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Abbandonare la bozza?")
                    .setMessage("Le risposte non salvate verranno eliminate e sarà ricaricata la dichiarazione corrente del profilo.")
                    .setNegativeButton("Annulla", null)
                    .setPositiveButton("Abbandona e ricarica") { _, _ ->
                        viewModel.abandonHealthDeclarationDraft(profileId)
                        if (data.activeProfileStore.currentIdOrNull() != profileId) {
                            finish()
                            return@setPositiveButton
                        }
                        loadHealthDeclaration(profileId, preserveDraft = false)
                    }
                    .show()
            } else {
                loadHealthDeclaration(profileId, preserveDraft = true)
            }
        }
        healthSaveButton.setOnClickListener { saveHealthDeclaration() }
    }

    private fun answerFromRadio(checkedId: Int, yesId: Int, noId: Int): Boolean? = when (checkedId) {
        yesId -> true
        noId -> false
        else -> null
    }

    private fun updateHealthDeclarationDraft(updated: HealthDeclarationDraft) {
        if (!canEditHealthDeclaration()) return
        healthDeclarationProfileId?.let { viewModel.updateHealthDeclarationDraft(it, updated) }
        if (healthDeclarationStatusState == HealthDeclarationUiState.SAVED) {
            healthDeclarationStatusState = viewModel.healthDeclarationEditor.value.baseline?.decoded?.toUiState()
                ?: HealthDeclarationUiState.ABSENT
        }
        renderHealthDeclaration()
    }

    private fun canEditHealthDeclaration(): Boolean {
        val editor = viewModel.healthDeclarationEditor.value
        val loaded = editor.profileId == healthDeclarationProfileId && editor.baseline != null && !editor.stale && healthDeclarationStatusState in setOf(
            HealthDeclarationUiState.ABSENT,
            HealthDeclarationUiState.INCOMPLETE,
            HealthDeclarationUiState.VALID,
            HealthDeclarationUiState.INVALID,
            HealthDeclarationUiState.UNSUPPORTED,
            HealthDeclarationUiState.SAVED,
        )
        val retryableSaveError = healthDeclarationStatusState == HealthDeclarationUiState.ERROR &&
            healthDeclarationOperationIsSave && editor.baseline != null && !editor.stale
        return !healthDeclarationSaving && (loaded || retryableSaveError) &&
            data.activeProfileStore.currentIdOrNull() == healthDeclarationProfileId
    }

    private fun loadHealthDeclaration(profileId: Long, preserveDraft: Boolean) {
        if (isCreate || profileId <= 0L) return
        healthDeclarationProfileId = profileId
        healthDeclarationStatusState = HealthDeclarationUiState.LOADING
        healthDeclarationOperationIsSave = false
        renderHealthDeclaration()
        applyHealthDeclarationSaveState(viewModel.healthDeclarationSaveState.value)

        lifecycleScope.launch {
            val result = try {
                viewModel.readHealthDeclaration(profileId, refresh = preserveDraft)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (healthDeclarationProfileId != profileId) return@launch
                healthDeclarationStatusState = HealthDeclarationUiState.ERROR
                healthDeclarationOperationIsSave = false
                renderHealthDeclaration()
                return@launch
            }
            if (healthDeclarationProfileId != profileId) return@launch
            val editor = viewModel.healthDeclarationEditor.value
            if (viewModel.healthDeclarationSaveState.value.let {
                    it.profileId == profileId && it.status == HealthDeclarationSaveStatus.SAVED
                }
            ) {
                // A retained committed save remains SAVED even if the active-profile read is now rejected.
                healthDeclarationStatusState = HealthDeclarationUiState.SAVED
                renderHealthDeclaration()
                return@launch
            }
            if (result == null || data.activeProfileStore.currentIdOrNull() != profileId) {
                healthDeclarationStatusState = HealthDeclarationUiState.STALE
                renderHealthDeclaration()
                return@launch
            }
            if (editor.profileId != profileId || editor.baseline == null || editor.stale) {
                // Una bozza stale resta legata alla baseline originale finché questa schermata non viene riaperta.
                healthDeclarationStatusState = HealthDeclarationUiState.STALE
                renderHealthDeclaration()
                return@launch
            }
            healthDeclarationStatusState = if (viewModel.healthDeclarationSaveState.value.let {
                    it.profileId == profileId && it.status == HealthDeclarationSaveStatus.SAVED
                }
            ) HealthDeclarationUiState.SAVED else result.toUiState()
            renderHealthDeclaration()
        }
    }

    private fun saveHealthDeclaration() {
        val profileId = healthDeclarationProfileId ?: return
        if (!canEditHealthDeclaration() || !canSaveHealthDeclaration()) return
        if (initialRenderedProfileId != profileId || data.activeProfileStore.currentIdOrNull() != profileId) {
            healthDeclarationStatusState = HealthDeclarationUiState.STALE
            renderHealthDeclaration()
            return
        }
        val editor = viewModel.healthDeclarationEditor.value
        val declaration = HealthDeclaration(
            pregnancyOrLactation = editor.draft.pregnancyOrLactation,
            clinicianManagedConditionOrMedication = editor.draft.clinicianManagedConditionOrMedication,
            confirmedAtEpochMillis = System.currentTimeMillis(),
        )
        healthDeclarationSaving = true
        healthDeclarationOperationIsSave = true
        renderHealthDeclaration()
        lifecycleScope.launch {
            try {
                if (healthDeclarationProfileId != profileId || data.activeProfileStore.currentIdOrNull() != profileId) {
                    healthDeclarationStatusState = HealthDeclarationUiState.STALE
                    return@launch
                }
                if (!viewModel.saveHealthDeclaration(profileId, declaration)) {
                    return@launch
                }
                if (healthDeclarationProfileId == profileId) {
                    Toast.makeText(this@ProfileEditActivity, R.string.profile_health_saved, Toast.LENGTH_SHORT).show()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (healthDeclarationProfileId != profileId) return@launch
                healthDeclarationStatusState = HealthDeclarationUiState.ERROR
                healthDeclarationOperationIsSave = true
                healthDeclarationSaving = false
                renderHealthDeclaration()
            }
        }
    }

    private fun canSaveHealthDeclaration(): Boolean {
        val editor = viewModel.healthDeclarationEditor.value
        val baseline = editor.baseline?.decoded ?: return false
        if (!editor.dirty || editor.stale || !canEditHealthDeclaration()) return false
        val requiresFullReAnswer = baseline is HealthDeclarationDecodeResult.Invalid ||
            baseline is HealthDeclarationDecodeResult.UnsupportedVersion
        return !requiresFullReAnswer || (
            editor.draft.pregnancyOrLactation != null &&
                editor.draft.clinicianManagedConditionOrMedication != null
        )
    }

    private fun renderHealthDeclaration() {
        if (!::healthDeclarationStatus.isInitialized) return
        val editor = viewModel.healthDeclarationEditor.value
        val draft = editor.draft
        val baseline = editor.baseline?.decoded
        val state = healthDeclarationStatusState
        val statusRes = when (state) {
            HealthDeclarationUiState.LOADING -> R.string.profile_health_loading
            HealthDeclarationUiState.ABSENT -> R.string.profile_health_absent
            HealthDeclarationUiState.INCOMPLETE -> R.string.profile_health_incomplete
            HealthDeclarationUiState.VALID -> R.string.profile_health_valid
            HealthDeclarationUiState.INVALID -> R.string.profile_health_invalid
            HealthDeclarationUiState.UNSUPPORTED -> R.string.profile_health_unsupported
            HealthDeclarationUiState.ERROR -> if (healthDeclarationOperationIsSave) R.string.profile_health_save_error else R.string.profile_health_load_error
            HealthDeclarationUiState.STALE -> R.string.profile_health_stale
            HealthDeclarationUiState.SAVED -> if (editor.stale || data.activeProfileStore.currentIdOrNull() != healthDeclarationProfileId) {
                R.string.profile_health_saved_inactive
            } else {
                R.string.profile_health_saved
            }
        }
        val baseStatus = getString(statusRes)
        healthDeclarationStatus.text = if (editor.dirty && state in setOf(
                HealthDeclarationUiState.ABSENT,
                HealthDeclarationUiState.INCOMPLETE,
                HealthDeclarationUiState.VALID,
                HealthDeclarationUiState.INVALID,
                HealthDeclarationUiState.UNSUPPORTED,
            )
        ) getString(R.string.profile_health_draft_status, baseStatus) else baseStatus
        if (healthDeclarationSaving) healthDeclarationStatus.setText(R.string.profile_health_saving)

        renderingHealthDeclaration = true
        healthPregnancyGroup.check(radioId(draft.pregnancyOrLactation, R.id.healthPregnancyYes, R.id.healthPregnancyNo))
        healthConditionGroup.check(radioId(draft.clinicianManagedConditionOrMedication, R.id.healthConditionYes, R.id.healthConditionNo))
        renderingHealthDeclaration = false

        val answersUnavailable = baseline == null ||
            baseline is HealthDeclarationDecodeResult.Invalid ||
            baseline is HealthDeclarationDecodeResult.UnsupportedVersion
        renderMissingAnswer(healthPregnancyMissing, draft.pregnancyOrLactation, answersUnavailable)
        renderMissingAnswer(healthConditionMissing, draft.clinicianManagedConditionOrMedication, answersUnavailable)
        healthDeclarationProgress.visibility = if (state == HealthDeclarationUiState.LOADING || healthDeclarationSaving) View.VISIBLE else View.GONE

        val canEdit = canEditHealthDeclaration()
        healthPregnancyGroup.isEnabled = canEdit
        healthConditionGroup.isEnabled = canEdit
        for (index in 0 until healthPregnancyGroup.childCount) healthPregnancyGroup.getChildAt(index).isEnabled = canEdit
        for (index in 0 until healthConditionGroup.childCount) healthConditionGroup.getChildAt(index).isEnabled = canEdit
        healthClearPregnancyButton.isEnabled = canEdit && draft.pregnancyOrLactation != null
        healthClearConditionButton.isEnabled = canEdit && draft.clinicianManagedConditionOrMedication != null
        healthClearPregnancyButton.visibility = if (draft.pregnancyOrLactation != null) View.VISIBLE else View.GONE
        healthClearConditionButton.visibility = if (draft.clinicianManagedConditionOrMedication != null) View.VISIBLE else View.GONE
        healthSaveButton.isEnabled = canSaveHealthDeclaration()
        healthRetryButton.visibility = if (!healthDeclarationSaving && state in setOf(HealthDeclarationUiState.ERROR, HealthDeclarationUiState.STALE)) View.VISIBLE else View.GONE
        healthRetryButton.setText(if (state == HealthDeclarationUiState.STALE) R.string.profile_health_refresh else R.string.profile_health_retry)
    }

    private fun radioId(answer: Boolean?, yesId: Int, noId: Int): Int = when (answer) {
        true -> yesId
        false -> noId
        null -> View.NO_ID
    }

    private fun renderMissingAnswer(view: TextView, answer: Boolean?, unavailable: Boolean) {
        if (answer != null) {
            view.visibility = View.GONE
            return
        }
        view.setText(if (unavailable) R.string.profile_health_answer_unavailable else R.string.profile_health_answer_missing)
        view.visibility = View.VISIBLE
    }

    private fun HealthDeclarationDecodeResult.toUiState(): HealthDeclarationUiState = when (this) {
        HealthDeclarationDecodeResult.Absent -> HealthDeclarationUiState.ABSENT
        is HealthDeclarationDecodeResult.Incomplete -> HealthDeclarationUiState.INCOMPLETE
        is HealthDeclarationDecodeResult.Valid -> HealthDeclarationUiState.VALID
        is HealthDeclarationDecodeResult.Invalid -> HealthDeclarationUiState.INVALID
        is HealthDeclarationDecodeResult.UnsupportedVersion -> HealthDeclarationUiState.UNSUPPORTED
    }

    private fun bindDropdowns() {
        sexInput.setMyFitAiDropdownItems(listOf("Maschio", "Femmina"))
        goalInput.setMyFitAiDropdownItems(resources.getStringArray(R.array.profile_goals).toList())
        activityInput.setMyFitAiDropdownItems(activityBaseChoices())
        findViewById<TextInputLayout>(R.id.activityLayout).helperText = getString(R.string.profile_activity_helper)
    }

    private fun activityBaseChoices(): List<String> = resources.getStringArray(R.array.profile_activity_levels).toList()

    private fun bindPickers() {
        birthDateInput.setOnClickListener {
            val builder = MaterialDatePicker.Builder.datePicker().setTheme(R.style.ThemeOverlay_MyFitAI_MaterialCalendar).setTitleText("Data di nascita")
            birthDateEpochDay?.let { epochDay ->
                builder.setSelection(LocalDate.ofEpochDay(epochDay).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            }
            val picker = builder.build()
            picker.addOnPositiveButtonClickListener { millis ->
                val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                birthDateEpochDay = date.toEpochDay()
                birthDateInput.setText(date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ITALIAN)))
                showBirthDateIssue()
            }
            picker.show(supportFragmentManager, "profile_birth_date")
        }
        wakeTimeInput.setOnClickListener { showTimePicker("Ora di sveglia", wakeTimeMinutes) { value -> wakeTimeMinutes = value; wakeTimeInput.setText(formatMinutes(value)) } }
        sleepTimeInput.setOnClickListener { showTimePicker("Ora di sonno", sleepTimeMinutes) { value -> sleepTimeMinutes = value; sleepTimeInput.setText(formatMinutes(value)) } }
    }

    private fun showTimePicker(title: String, current: Int?, onSelected: (Int) -> Unit) {
        val picker = MaterialTimePicker.Builder()
            .setTheme(R.style.ThemeOverlay_MyFitAI_MaterialTimePicker)
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(current?.div(60) ?: 7)
            .setMinute(current?.rem(60) ?: 0)
            .setTitleText(title)
            .build()
        picker.addOnPositiveButtonClickListener { onSelected(picker.hour * 60 + picker.minute) }
        picker.show(supportFragmentManager, "profile_time_picker")
    }

    private fun bindSave() {
        saveButton.setOnClickListener {
            clearErrors()
            val name = nameInput.text?.toString()?.trim().orEmpty()
            val sex = sexInput.text?.toString()?.trim().orEmpty()
            val height = parseFloat(heightInput.text?.toString())
            val weight = parseFloat(weightInput.text?.toString())
            val goal = goalInput.text?.toString()?.trim().orEmpty()
            val activity = activityInput.text?.toString()?.trim().orEmpty()

            var valid = true
            fun fail(layoutId: Int, message: String) {
                findViewById<TextInputLayout>(layoutId).error = message
                valid = false
            }

            if (name.isBlank()) fail(R.id.nameLayout, "Inserisci un nome")
            if (birthDateEpochDay != null) {
                ProfileAgeRule.message(ProfileAgeRule.check(birthDateEpochDay, LocalDate.now()), ::ageText)?.let { fail(R.id.birthDateLayout, it) }
            }
            if (height != null && (height < 80f || height > 250f)) fail(R.id.heightLayout, "Altezza non valida")
            if (weight != null && (weight < 20f || weight > 400f)) fail(R.id.weightLayout, "Peso non valido")

            if (isCreate) {
                if (birthDateEpochDay == null) fail(R.id.birthDateLayout, "Inserisci la data di nascita")
                if (sex !in setOf("Maschio", "Femmina")) fail(R.id.sexLayout, "Seleziona il sesso biologico")
                if (height == null) fail(R.id.heightLayout, "Inserisci l'altezza")
                if (weight == null) fail(R.id.weightLayout, "Inserisci il peso iniziale")
                if (activity.isBlank()) fail(R.id.activityLayout, "Seleziona il livello di attività")
            }
            if (!valid) return@setOnClickListener

            viewModel.save(
                name = name,
                birthDateEpochDay = birthDateEpochDay,
                biologicalSex = sex.takeIf { it.isNotBlank() },
                heightCm = height,
                currentWeightKg = weight,
                goal = if (isCreate) null else goal,
                activityLevel = activity,
                wakeTimeMinutes = wakeTimeMinutes,
                sleepTimeMinutes = sleepTimeMinutes,
                dietaryPreferencesJson = if (isCreate) null else viewModel.profile.value?.dietaryPreferencesJson,
            )
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.profile.collect { profile ->
                        if (!isCreate && profile != null && initialRenderedProfileId != profile.id) {
                            render(profile)
                            initialRenderedProfileId = profile.id
                        }
                    }
                }
                launch {
                    viewModel.saving.collect { saving ->
                        saveButton.isEnabled = !saving
                        saveButton.text = if (saving) "Salvataggio…" else if (isCreate) "Crea profilo e continua" else "Salva profilo"
                    }
                }
                launch {
                    viewModel.saved.collect { savedEvent ->
                        val profileId = savedEvent.profileId
                        Toast.makeText(this@ProfileEditActivity, if (isCreate) "Profilo creato" else "Profilo salvato", Toast.LENGTH_SHORT).show()
                        if (isCreate) {
                            startActivity(Intent(this@ProfileEditActivity, DietaryPreferencesActivity::class.java).apply {
                                putExtra(DietaryPreferencesActivity.EXTRA_BOOTSTRAP, true)
                                if (isBootstrap) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            })
                            finish()
                        } else if (savedEvent.nutritionDataChanged && hasCurrentPlan(profileId)) {
                            data.nutritionPlanUpdatePreferences.setPending(profileId, true)
                            showPlanUpdateDialog()
                        } else finish()
                    }
                }
                launch { viewModel.error.collect { message -> Toast.makeText(this@ProfileEditActivity, message, Toast.LENGTH_SHORT).show() } }
                launch { viewModel.healthDeclarationSaveState.collect(::applyHealthDeclarationSaveState) }
                launch {
                    data.activeProfileStore.activeProfileId.collect { activeId ->
                        val renderedId = initialRenderedProfileId
                        if (renderedId != null && activeId != renderedId && healthDeclarationStatusState != HealthDeclarationUiState.STALE) {
                            // Keep the draft attached to its original profile and refuse writes after a profile switch.
                            viewModel.markHealthDeclarationStale(renderedId)
                            healthDeclarationStatusState = if (viewModel.healthDeclarationSaveState.value.let {
                                    it.profileId == renderedId && it.status == HealthDeclarationSaveStatus.SAVED
                                }
                            ) HealthDeclarationUiState.SAVED else HealthDeclarationUiState.STALE
                            renderHealthDeclaration()
                        }
                    }
                }
            }
        }
    }

    private suspend fun hasCurrentPlan(profileId: Long): Boolean {
        val weekStart = LocalDate.now().with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).toEpochDay()
        return data.mealPlanRepository.getPlanForWeek(profileId, weekStart) != null
    }

    private fun showPlanUpdateDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Aggiornare il piano alimentare?")
            .setMessage("Hai aggiornato il profilo. Vuoi rigenerare il piano alimentare con i nuovi dati?")
            .setNegativeButton("Non ora") { _, _ -> finish() }
            .setPositiveButton("Rigenera alimentazione") { _, _ ->
                startActivity(Intent(this, FoodPlanActivity::class.java))
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun render(profile: UserProfileEntity) {
        healthDeclarationCard.visibility = View.VISIBLE
        findViewById<TextView>(R.id.title).text = "Modifica ${profile.name}"
        nameInput.setText(profile.name)
        birthDateEpochDay = profile.birthDateEpochDay
        birthDateInput.setText(profile.birthDateEpochDay?.let { LocalDate.ofEpochDay(it).format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ITALIAN)) }.orEmpty())
        sexInput.setText(profile.biologicalSex.orEmpty(), false)
        heightInput.setText(profile.heightCm?.let(::formatNumber).orEmpty())
        weightInput.setText(profile.currentWeightKg?.let(::formatNumber).orEmpty())
        goalInput.setText(profile.goal.orEmpty(), false)
        // Un valore storico fuori dalle 3 scelte si mostra com'e e resta salvato finche l'utente non ne sceglie un altro.
        activityInput.setMyFitAiDropdownItems(ActivityLevelChoices.options(activityBaseChoices(), profile.activityLevel))
        activityInput.setText(profile.activityLevel.orEmpty(), false)
        wakeTimeMinutes = profile.wakeTimeMinutes
        sleepTimeMinutes = profile.sleepTimeMinutes
        wakeTimeInput.setText(profile.wakeTimeMinutes?.let(::formatMinutes).orEmpty())
        sleepTimeInput.setText(profile.sleepTimeMinutes?.let(::formatMinutes).orEmpty())
        // Un profilo già salvato con età fuori range viene segnalato subito, prima di qualsiasi salvataggio.
        showBirthDateIssue()
        loadHealthDeclaration(profile.id, preserveDraft = false)
    }

    private fun applyHealthDeclarationSaveState(saveState: HealthDeclarationSaveState) {
        if (saveState.profileId == null || saveState.profileId != healthDeclarationProfileId) return
        if (saveState.status == HealthDeclarationSaveStatus.IDLE) return
        healthDeclarationOperationIsSave = true
        when (saveState.status) {
            HealthDeclarationSaveStatus.IDLE -> return
            HealthDeclarationSaveStatus.SAVING -> {
                healthDeclarationSaving = true
            }
            HealthDeclarationSaveStatus.SAVED -> {
                healthDeclarationSaving = false
                healthDeclarationStatusState = HealthDeclarationUiState.SAVED
            }
            HealthDeclarationSaveStatus.STALE,
            HealthDeclarationSaveStatus.CONFLICT -> {
                healthDeclarationSaving = false
                healthDeclarationStatusState = HealthDeclarationUiState.STALE
            }
            HealthDeclarationSaveStatus.FAILED -> {
                healthDeclarationSaving = false
                healthDeclarationStatusState = HealthDeclarationUiState.ERROR
            }
        }
        renderHealthDeclaration()
    }

    private fun ageText(messageRes: Int, minAge: Int, maxAge: Int): String = getString(messageRes, minAge, maxAge)

    /** Mostra l'errore sulla data di nascita solo se presente ma con età fuori 14-120 (o nel futuro). */
    private fun showBirthDateIssue() {
        val layout = findViewById<TextInputLayout>(R.id.birthDateLayout)
        val epochDay = birthDateEpochDay
        layout.error = if (epochDay != null) {
            ProfileAgeRule.message(ProfileAgeRule.check(epochDay, LocalDate.now()), ::ageText)
        } else {
            null
        }
    }

    private fun clearErrors() {
        listOf(R.id.nameLayout, R.id.birthDateLayout, R.id.sexLayout, R.id.heightLayout, R.id.weightLayout, R.id.goalLayout, R.id.activityLayout)
            .forEach { findViewById<TextInputLayout>(it).error = null }
    }

    private fun parseFloat(value: String?): Float? = value?.trim()?.replace(',', '.')?.takeIf { it.isNotEmpty() }?.toFloatOrNull()
    private fun formatMinutes(total: Int): String = String.format(Locale.ITALIAN, "%02d:%02d", total / 60, total % 60)
    private fun formatNumber(value: Float): String = if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.ITALIAN, "%.1f", value)

    companion object {
        const val EXTRA_BOOTSTRAP = "profile_bootstrap"
        const val EXTRA_CREATE = "profile_create"
    }
}
