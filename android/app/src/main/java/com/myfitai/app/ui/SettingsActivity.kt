package com.myfitai.app.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.Uri
import android.view.View
import android.view.WindowManager
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.google.android.material.textfield.TextInputEditText
import com.myfitai.app.ui.dialog.HelpDialogSpec
import com.myfitai.app.ui.dialog.HelpSection
import com.myfitai.app.R
import com.myfitai.app.ai.AiProviderAccess
import com.myfitai.app.ai.AiProviderType
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.profile.MealCountPreferences
import com.myfitai.app.data.profile.BiaFrequencyPreferences
import com.myfitai.app.data.profile.AiAutomationPreferences
import com.myfitai.app.data.profile.NutritionPlanSchedulePreferences
import com.myfitai.app.data.profile.ActivityLevelFactors
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import com.myfitai.app.domain.progress.ProgressAnalysisPreferences
import com.myfitai.app.navigation.AiSettingsEntry
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.widgets.SettingRowView
import com.myfitai.app.domain.food.NutritionPathTrigger
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

class SettingsActivity : BaseShellActivity() {
    private val data by lazy { AppDataContainer.get(this) }
    private data class ActivityFactorField(
        val option: ActivityLevelFactors.Option,
        val input: TextInputEditText,
        val inputLayout: TextInputLayout,
        val status: TextView,
    )

    private val activityFactorFields = mutableMapOf<LocalCalculationEngine.ActivityLevel, ActivityFactorField>()
    private var activityFactorsScreenVisible = false
    private var activityFactorsLoading = false
    private var activityFactorsLoaded = false
    private var activityFactorsUnreadable = false
    private var activityFactorsPendingReset = false
    private var activityFactorsShowErrors = false
    private var activityFactorsSaving = false
    private var suppressActivityFactorWatcher = false
    private var persistedActivityFactors: Map<LocalCalculationEngine.ActivityLevel, Double>? = null
    private var activityFactorsRecoveryTarget: Map<LocalCalculationEngine.ActivityLevel, Double>? = null
    private var activityFactorsMessageOverride: String? = null
    private var generalSettingsBackButtonVisibility = View.VISIBLE
    private val activityFactorsBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showGeneralSettings()
    }
    private val backupOpenLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmBackupImport(uri)
    }
    private var profileAwaitingDeletionBackup: UserProfileEntity? = null
    private val deleteProfileBackupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val profile = profileAwaitingDeletionBackup
        profileAwaitingDeletionBackup = null
        if (uri != null && profile != null) saveProfileBackupBeforeDeletion(profile, uri)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_settings)
        bindBottom(BottomNavBinder.Tab.MORE)
        bindBack()
        generalSettingsBackButtonVisibility = findViewById<View>(R.id.backButton).visibility
        findViewById<View>(R.id.backButton).setOnClickListener {
            if (activityFactorsScreenVisible) showGeneralSettings() else finish()
        }
        onBackPressedDispatcher.addCallback(this, activityFactorsBackCallback)
        SettingsSurfaces.normalize(this)
        bindSectionHelp()
        bindAiEntryRows()
        bindActivityFactorsEditor()

        findViewById<View>(R.id.rowAppGuide).setOnClickListener { showAppGuide() }
        findViewById<View>(R.id.rowEmotionalDiary).setOnClickListener { go(EmotionalDiaryActivity::class.java) }
        findViewById<View>(R.id.rowMeasurements).setOnClickListener { go(MeasurementsActivity::class.java) }
        findViewById<SettingRowView>(R.id.rowBiaFrequency).setOnClickListener { showBiaFrequencySettings() }
        findViewById<SettingRowView>(R.id.rowActivityFactors).setOnClickListener { showActivityFactorSettings() }
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
        findViewById<View>(R.id.rowImportProfileBackup).setOnClickListener {
            backupOpenLauncher.launch(arrayOf("application/json", "text/json", "text/plain"))
        }
        findViewById<View>(R.id.rowDeleteProfile).setOnClickListener { selectProfileToDelete() }

        bindDataDeletion()

        restoreActivityFactorState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_ACTIVITY_FACTOR_SCREEN, activityFactorsScreenVisible)
        outState.putBoolean(STATE_ACTIVITY_FACTOR_LOADED, activityFactorsLoaded)
        outState.putBoolean(STATE_ACTIVITY_FACTOR_UNREADABLE, activityFactorsUnreadable)
        outState.putBoolean(STATE_ACTIVITY_FACTOR_PENDING_RESET, activityFactorsPendingReset)
        outState.putBoolean(STATE_ACTIVITY_FACTOR_SHOW_ERRORS, activityFactorsShowErrors)
        outState.putBoolean(STATE_ACTIVITY_FACTOR_SAVING, activityFactorsSaving)
        outState.putString(STATE_ACTIVITY_FACTOR_MESSAGE, activityFactorsMessageOverride)
        ActivityLevelFactors.options.forEachIndexed { index, option ->
            outState.putString(activityFactorDraftStateKey(index), activityFactorFields[option.level]?.input?.text?.toString())
            if (!activityFactorsSaving) {
                persistedActivityFactors?.get(option.level)?.let { outState.putDouble(activityFactorPersistedStateKey(index), it) }
            }
        }
        super.onSaveInstanceState(outState)
    }

    private fun restoreActivityFactorState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return
        activityFactorsSaving = savedInstanceState.getBoolean(STATE_ACTIVITY_FACTOR_SAVING)
        activityFactorsLoaded = !activityFactorsSaving && savedInstanceState.getBoolean(STATE_ACTIVITY_FACTOR_LOADED)
        activityFactorsUnreadable = !activityFactorsSaving && savedInstanceState.getBoolean(STATE_ACTIVITY_FACTOR_UNREADABLE)
        activityFactorsPendingReset = savedInstanceState.getBoolean(STATE_ACTIVITY_FACTOR_PENDING_RESET)
        activityFactorsShowErrors = savedInstanceState.getBoolean(STATE_ACTIVITY_FACTOR_SHOW_ERRORS)
        activityFactorsMessageOverride = if (activityFactorsSaving) null else savedInstanceState.getString(STATE_ACTIVITY_FACTOR_MESSAGE)
        persistedActivityFactors = null
        if (!activityFactorsSaving && activityFactorsLoaded && !activityFactorsUnreadable) {
            persistedActivityFactors = ActivityLevelFactors.options.mapIndexedNotNull { index, option ->
                savedInstanceState.getDouble(activityFactorPersistedStateKey(index)).takeIf {
                    savedInstanceState.containsKey(activityFactorPersistedStateKey(index))
                }?.let { option.level to it }
            }.toMap().takeIf(ActivityLevelFactors::isValid)
            if (persistedActivityFactors == null) {
                activityFactorsLoaded = false
                activityFactorsUnreadable = true
            }
        }
        suppressActivityFactorWatcher = true
        ActivityLevelFactors.options.forEachIndexed { index, option ->
            savedInstanceState.getString(activityFactorDraftStateKey(index))?.let { draft ->
                activityFactorFields[option.level]?.input?.setText(draft)
            }
        }
        suppressActivityFactorWatcher = false
        activityFactorsRecoveryTarget = if (activityFactorsSaving) validateActivityFactorDraft(showErrors = false) else null
        if (savedInstanceState.getBoolean(STATE_ACTIVITY_FACTOR_SCREEN)) showActivityFactorSettings()
        updateActivityFactorStatuses()
        if (activityFactorsShowErrors) validateActivityFactorDraft(showErrors = true)
        renderActivityFactorMessage()
    }

    private fun bindActivityFactorsEditor() {
        val inputIds = listOf(
            R.id.activityFactorInputSedentary,
            R.id.activityFactorInputLight,
            R.id.activityFactorInputModerate,
            R.id.activityFactorInputVeryActive,
            R.id.activityFactorInputExtreme,
        )
        val layoutIds = listOf(
            R.id.activityFactorLayoutSedentary,
            R.id.activityFactorLayoutLight,
            R.id.activityFactorLayoutModerate,
            R.id.activityFactorLayoutVeryActive,
            R.id.activityFactorLayoutExtreme,
        )
        val statusIds = listOf(
            R.id.activityFactorStatusSedentary,
            R.id.activityFactorStatusLight,
            R.id.activityFactorStatusModerate,
            R.id.activityFactorStatusVeryActive,
            R.id.activityFactorStatusExtreme,
        )
        ActivityLevelFactors.options.forEachIndexed { index, option ->
            val field = ActivityFactorField(
                option = option,
                input = findViewById(inputIds[index]),
                inputLayout = findViewById(layoutIds[index]),
                status = findViewById(statusIds[index]),
            )
            field.input.contentDescription = getString(R.string.activity_factor_input_cd, option.name)
            field.input.isSaveEnabled = false
            field.input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (suppressActivityFactorWatcher) return
                    activityFactorsPendingReset = false
                    activityFactorsMessageOverride = null
                    updateActivityFactorStatuses()
                    if (activityFactorsShowErrors) validateActivityFactorDraft(showErrors = true)
                    renderActivityFactorMessage()
                }
            })
            activityFactorFields[option.level] = field
        }
        findViewById<View>(R.id.resetActivityFactorsButton).setOnClickListener { stageActivityFactorDefaults() }
        findViewById<View>(R.id.saveActivityFactorsButton).setOnClickListener { confirmActivityFactorSave() }
    }

    private fun showActivityFactorSettings() {
        activityFactorsScreenVisible = true
        findViewById<View>(R.id.backButton).visibility = View.VISIBLE
        findViewById<View>(R.id.settingsScroll).visibility = View.GONE
        findViewById<View>(R.id.activityFactorsScroll).visibility = View.VISIBLE
        findViewById<TextView>(R.id.title).setText(R.string.activity_factors_title)
        activityFactorsBackCallback.isEnabled = true
        if (!activityFactorsLoaded && !activityFactorsLoading) loadActivityFactors()
    }

    private fun showGeneralSettings() {
        activityFactorsScreenVisible = false
        activityFactorsBackCallback.isEnabled = false
        findViewById<View>(R.id.backButton).visibility = generalSettingsBackButtonVisibility
        activityFactorFields.values.forEach { it.input.clearFocus() }
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(findViewById<View>(R.id.activityFactorsScroll).windowToken, 0)
        findViewById<View>(R.id.activityFactorsScroll).visibility = View.GONE
        findViewById<View>(R.id.settingsScroll).visibility = View.VISIBLE
        findViewById<TextView>(R.id.title).setText(R.string.settings_title)
    }

    private fun loadActivityFactors() {
        activityFactorsLoading = true
        renderActivityFactorMessage()
        setActivityFactorInputsEnabled(false)
        val recoveringSave = activityFactorsSaving
        val recoveryTarget = activityFactorsRecoveryTarget
        lifecycleScope.launch {
            val result = activityFactorStoreMutex.withLock {
                withContext(Dispatchers.IO) {
                    runCatching { data.activityLevelFactors.read() }
                }
            }
            activityFactorsLoading = false
            val factors = result.getOrNull()?.takeIf(ActivityLevelFactors::isValid)
            activityFactorsLoaded = true
            if (factors == null) {
                activityFactorsUnreadable = true
                persistedActivityFactors = null
                setActivityFactorValues(if (recoveringSave) recoveryTarget else null)
                activityFactorsMessageOverride = getString(R.string.activity_factor_load_error)
            } else {
                activityFactorsUnreadable = false
                persistedActivityFactors = factors
                if (recoveringSave && (recoveryTarget == null || recoveryTarget != factors)) {
                    setActivityFactorValues(recoveryTarget)
                    activityFactorsMessageOverride = getString(R.string.activity_factor_save_error)
                } else {
                    setActivityFactorValues(factors)
                    activityFactorsMessageOverride = if (recoveringSave) {
                        activityFactorsPendingReset = false
                        activityFactorsShowErrors = false
                        activityFactorFields.values.forEach { it.inputLayout.error = null }
                        getString(R.string.activity_factor_save_success)
                    } else {
                        null
                    }
                }
            }
            activityFactorsSaving = false
            activityFactorsRecoveryTarget = null
            setActivityFactorInputsEnabled(true)
            updateActivityFactorStatuses()
            renderActivityFactorMessage()
        }
    }

    private fun stageActivityFactorDefaults() {
        if (activityFactorsLoading || activityFactorsSaving) return
        activityFactorsPendingReset = true
        activityFactorsShowErrors = false
        activityFactorsMessageOverride = null
        activityFactorFields.values.forEach { it.inputLayout.error = null }
        setActivityFactorValues(ActivityLevelFactors.defaultCoefficients)
        updateActivityFactorStatuses()
        renderActivityFactorMessage()
    }

    private fun setActivityFactorValues(values: Map<LocalCalculationEngine.ActivityLevel, Double>?) {
        suppressActivityFactorWatcher = true
        activityFactorFields.values.forEach { field ->
            field.input.setText(values?.get(field.option.level)?.let(::formatActivityFactor).orEmpty())
        }
        suppressActivityFactorWatcher = false
    }

    private fun setActivityFactorInputsEnabled(enabled: Boolean) {
        activityFactorFields.values.forEach { field ->
            field.inputLayout.isEnabled = enabled
            field.input.isEnabled = enabled
        }
        findViewById<View>(R.id.resetActivityFactorsButton).isEnabled = enabled && !activityFactorsSaving
        findViewById<View>(R.id.saveActivityFactorsButton).isEnabled = enabled && !activityFactorsSaving
    }

    private fun updateActivityFactorStatuses() {
        activityFactorFields.values.forEach { field ->
            val parsed = parseActivityFactor(field.input.text?.toString().orEmpty()).value
            val reference = formatActivityFactor(field.option.defaultCoefficient)
            field.status.text = when {
                parsed == null -> getString(R.string.activity_factor_default_reference, reference)
                factorsMatchPersisted(field.option.level, parsed) -> {
                    if (parsed == field.option.defaultCoefficient) {
                        getString(R.string.activity_factor_default_value, reference)
                    } else {
                        getString(R.string.activity_factor_personalized_value, reference)
                    }
                }
                parsed == field.option.defaultCoefficient -> getString(R.string.activity_factor_draft_default_value, reference)
                else -> getString(R.string.activity_factor_draft_personalized_value, reference)
            }
        }
    }

    private fun factorsMatchPersisted(level: LocalCalculationEngine.ActivityLevel, value: Double): Boolean =
        persistedActivityFactors?.get(level) == value && !activityFactorsUnreadable

    private data class ParsedActivityFactor(val value: Double?, val error: String? = null)

    private fun parseActivityFactor(rawValue: String): ParsedActivityFactor {
        val value = rawValue.trim()
        if (value.isEmpty()) return ParsedActivityFactor(null, getString(R.string.activity_factor_error_required))
        if (value.contains(',') && value.contains('.')) {
            return ParsedActivityFactor(null, getString(R.string.activity_factor_error_format))
        }
        val separator = if (value.contains(',')) ',' else '.'
        if (value.count { it == separator } > 1 || !Regex("^[0-9]+(?:[.,][0-9]+)?$").matches(value)) {
            return ParsedActivityFactor(null, getString(R.string.activity_factor_error_format))
        }
        val decimals = value.substringAfter(separator, "")
        if (decimals.length > ActivityLevelFactors.MAX_DECIMAL_PLACES) {
            return ParsedActivityFactor(null, getString(R.string.activity_factor_error_precision))
        }
        val parsed = value.replace(',', '.').toDoubleOrNull()
            ?: return ParsedActivityFactor(null, getString(R.string.activity_factor_error_format))
        if (!parsed.isFinite() || parsed !in ActivityLevelFactors.MIN_COEFFICIENT..ActivityLevelFactors.MAX_COEFFICIENT) {
            return ParsedActivityFactor(null, getString(R.string.activity_factor_error_range))
        }
        return ParsedActivityFactor(parsed)
    }

    private fun validateActivityFactorDraft(showErrors: Boolean): Map<LocalCalculationEngine.ActivityLevel, Double>? {
        val values = linkedMapOf<LocalCalculationEngine.ActivityLevel, Double>()
        val errors = mutableMapOf<LocalCalculationEngine.ActivityLevel, String>()
        ActivityLevelFactors.options.forEach { option ->
            val parsed = parseActivityFactor(activityFactorFields.getValue(option.level).input.text?.toString().orEmpty())
            if (parsed.value == null) errors[option.level] = parsed.error.orEmpty() else values[option.level] = parsed.value
        }
        ActivityLevelFactors.options.zipWithNext().forEach { (previous, next) ->
            val previousValue = values[previous.level]
            val nextValue = values[next.level]
            if (previousValue != null && nextValue != null && previousValue >= nextValue) {
                errors[previous.level] = getString(R.string.activity_factor_error_less_next)
                errors[next.level] = getString(R.string.activity_factor_error_greater_previous)
            }
        }
        if (showErrors) activityFactorFields.forEach { (level, field) -> field.inputLayout.error = errors[level] }
        val orderedValues = ActivityLevelFactors.options.map { values[it.level] ?: return null }
        if (errors.isNotEmpty()) return null
        val result = ActivityLevelFactors.options.mapIndexed { index, option -> option.level to orderedValues[index] }.toMap()
        return result.takeIf(ActivityLevelFactors::isValid)
    }

    private fun confirmActivityFactorSave() {
        if (activityFactorsLoading || activityFactorsSaving || isFinishing || isDestroyed) return
        activityFactorsShowErrors = true
        val coefficients = validateActivityFactorDraft(showErrors = true)
        updateActivityFactorStatuses()
        if (coefficients == null) {
            activityFactorFields.values.firstOrNull { it.inputLayout.error != null }?.input?.requestFocus()
            return
        }
        val useResetApi = activityFactorsPendingReset && coefficients == ActivityLevelFactors.defaultCoefficients
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.activity_factor_confirm_title)
            .setMessage(R.string.activity_factor_confirm_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.activity_factor_confirm_save) { _, _ ->
                if (!isFinishing && !isDestroyed) persistActivityFactors(coefficients, useResetApi)
            }
            .create()
        if (!isFinishing && !isDestroyed) dialog.show()
    }

    private fun persistActivityFactors(
        coefficients: Map<LocalCalculationEngine.ActivityLevel, Double>,
        useResetApi: Boolean,
    ) {
        if (activityFactorsLoading || activityFactorsSaving || isFinishing || isDestroyed) return
        activityFactorsSaving = true
        activityFactorsRecoveryTarget = coefficients
        activityFactorsMessageOverride = getString(R.string.activity_factor_saving)
        setActivityFactorInputsEnabled(false)
        renderActivityFactorMessage()
        lifecycleScope.launch {
            val saved = activityFactorStoreMutex.withLock {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching {
                        if (useResetApi) data.activityLevelFactors.resetToDefaults()
                        else data.activityLevelFactors.save(coefficients)
                    }.getOrDefault(false)
                }
            }
            activityFactorsSaving = false
            activityFactorsRecoveryTarget = null
            setActivityFactorInputsEnabled(true)
            if (saved) {
                persistedActivityFactors = if (useResetApi) ActivityLevelFactors.defaultCoefficients else coefficients
                activityFactorsUnreadable = false
                activityFactorsPendingReset = false
                activityFactorsShowErrors = false
                activityFactorsMessageOverride = getString(R.string.activity_factor_save_success)
                setActivityFactorValues(persistedActivityFactors)
                activityFactorFields.values.forEach { it.inputLayout.error = null }
            } else {
                activityFactorsMessageOverride = getString(R.string.activity_factor_save_error)
            }
            updateActivityFactorStatuses()
            renderActivityFactorMessage()
        }
    }

    private fun renderActivityFactorMessage() {
        val status = findViewById<TextView>(R.id.activityFactorsStatus)
        val message = when {
            activityFactorsLoading -> getString(R.string.activity_factor_loading)
            activityFactorsMessageOverride != null -> activityFactorsMessageOverride
            activityFactorsUnreadable && activityFactorsPendingReset -> getString(R.string.activity_factor_load_error_reset_draft)
            activityFactorsUnreadable -> getString(R.string.activity_factor_load_error)
            activityFactorsPendingReset -> getString(R.string.activity_factor_reset_draft_notice)
            activityFactorsShowErrors && activityFactorFields.values.any { it.inputLayout.error != null } -> null
            hasUnsavedActivityFactorChanges() -> getString(R.string.activity_factor_draft_notice)
            else -> null
        }
        status.text = message.orEmpty()
        status.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
        val isError = activityFactorsUnreadable || activityFactorsMessageOverride == getString(R.string.activity_factor_save_error)
        status.setTextColor(getColor(if (isError) R.color.semantic_error else R.color.text_secondary))
        setActivityFactorInputsEnabled(!activityFactorsLoading && !activityFactorsSaving)
    }

    private fun hasUnsavedActivityFactorChanges(): Boolean {
        if (!activityFactorsLoaded || activityFactorsUnreadable) return false
        return activityFactorFields.values.any { field ->
            parseActivityFactor(field.input.text?.toString().orEmpty()).value != persistedActivityFactors?.get(field.option.level)
        }
    }

    private fun formatActivityFactor(value: Double): String = DecimalFormat(
        "0.00#",
        DecimalFormatSymbols.getInstance(Locale.ITALIAN),
    ).apply { isGroupingUsed = false }.format(value)

    private companion object {
        const val STATE_ACTIVITY_FACTOR_SCREEN = "settings_activity_factor_screen"
        const val STATE_ACTIVITY_FACTOR_LOADED = "settings_activity_factor_loaded"
        const val STATE_ACTIVITY_FACTOR_UNREADABLE = "settings_activity_factor_unreadable"
        const val STATE_ACTIVITY_FACTOR_PENDING_RESET = "settings_activity_factor_pending_reset"
        const val STATE_ACTIVITY_FACTOR_SHOW_ERRORS = "settings_activity_factor_show_errors"
        const val STATE_ACTIVITY_FACTOR_SAVING = "settings_activity_factor_saving"
        const val STATE_ACTIVITY_FACTOR_MESSAGE = "settings_activity_factor_message"
        val activityFactorStoreMutex = Mutex()

        fun activityFactorDraftStateKey(index: Int) = "settings_activity_factor_draft_$index"
        fun activityFactorPersistedStateKey(index: Int) = "settings_activity_factor_persisted_$index"
    }

    private fun confirmBackupImport(uri: Uri) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Carica profilo dal backup?")
            .setMessage("Stai caricando un profilo completo. Il profilo attivo verrà sostituito insieme a BIA, misure, alimentazione, storico, consumi e versioni dei piani. Le chiavi IA non sono incluse. L'app verrà riavviata.")
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Importa e riavvia") { _, _ ->
                lifecycleScope.launch {
                    runCatching { withContext(Dispatchers.IO) { data.profileBackupService.restore(uri) } }
                        .onSuccess { restartAfterRestore() }
                        .onFailure { error ->
                            MaterialAlertDialogBuilder(this@SettingsActivity)
                                .setTitle("Importazione non riuscita")
                                .setMessage(error.message ?: "Controlla il file JSON e riprova.")
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

    private fun selectProfileToDelete() {
        lifecycleScope.launch {
            val profiles = data.userProfileRepository.profiles.first()
            if (profiles.isEmpty()) {
                showProfileDeletionError("Non ci sono profili da eliminare.")
                return@launch
            }
            val selectedId = data.activeProfileStore.currentIdOrNull()
            if (profiles.size == 1) {
                confirmProfileDeletion(profiles.single(), isOnlyProfile = true)
                return@launch
            }
            val labels = profiles.map { profile ->
                if (profile.id == selectedId) "${profile.name} · attivo" else profile.name
            }.toTypedArray()
            MaterialAlertDialogBuilder(this@SettingsActivity)
                .setTitle("Seleziona il profilo da eliminare")
                .setItems(labels) { _, index -> profiles.getOrNull(index)?.let { confirmProfileDeletion(it, isOnlyProfile = false) } }
                .setNegativeButton("Annulla", null)
                .show()
        }
    }

    private fun confirmProfileDeletion(profile: UserProfileEntity, isOnlyProfile: Boolean) {
        val active = profile.id == data.activeProfileStore.currentIdOrNull()
        val consequence = if (isOnlyProfile) {
            "È l'unico profilo: dopo la cancellazione l'app tornerà alla configurazione iniziale."
        } else if (active) {
            "È il profilo attivo: al termine verrà selezionato un altro profilo."
        } else {
            "Gli altri profili non verranno modificati."
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Vuoi salvare i dati prima di procedere alla cancellazione del profilo?")
            .setMessage("Profilo: ${profile.name}\n$consequence\n\nPuoi salvare un backup JSON completo prima di eliminarlo.")
            .setNegativeButton("Annulla", null)
            .setNeutralButton("Senza backup") { _, _ -> confirmIrreversibleProfileDeletion(profile, isOnlyProfile) }
            .setPositiveButton("Salva backup") { _, _ ->
                profileAwaitingDeletionBackup = profile
                deleteProfileBackupLauncher.launch("myfitai-${safeBackupName(profile.name)}.json")
            }
            .show()
    }

    private fun saveProfileBackupBeforeDeletion(profile: UserProfileEntity, uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                val exported = withContext(Dispatchers.IO) { data.profileExportService.exportProfile(profile.id) }
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        exported.file.inputStream().use { input -> input.copyTo(output) }
                    } ?: error("Impossibile salvare il backup")
                }
            }.onSuccess {
                MaterialAlertDialogBuilder(this@SettingsActivity)
                    .setTitle("Backup salvato")
                    .setMessage("Il backup completo di ${profile.name} è stato salvato. Procedere con l'eliminazione definitiva del profilo e di tutti i suoi dati?")
                    .setNegativeButton("Annulla", null)
                    .setPositiveButton("Elimina profilo") { _, _ -> deleteProfile(profile) }
                    .show()
            }.onFailure { error ->
                showProfileDeletionError("Backup non salvato. Il profilo non è stato eliminato. ${error.message.orEmpty()}")
            }
        }
    }

    private fun confirmIrreversibleProfileDeletion(profile: UserProfileEntity, isOnlyProfile: Boolean) {
        val text = if (isOnlyProfile) {
            "Il profilo ${profile.name} e tutti i suoi dati verranno eliminati definitivamente. Essendo l'unico profilo, l'app tornerà all'onboarding. Vuoi continuare?"
        } else {
            "Il profilo ${profile.name} e tutti i suoi dati verranno eliminati definitivamente. Vuoi continuare?"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Conferma eliminazione definitiva")
            .setMessage(text)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Elimina definitivamente") { _, _ -> deleteProfile(profile) }
            .show()
    }

    private fun deleteProfile(profile: UserProfileEntity) {
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { data.dataDeletionService.deleteProfile(profile.id) } }
                .onSuccess {
                    Toast.makeText(this@SettingsActivity, "Profilo eliminato", Toast.LENGTH_LONG).show()
                    startActivity(Intent(this@SettingsActivity, SplashActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    })
                    finish()
                }
                .onFailure { error -> showProfileDeletionError("Eliminazione non riuscita. ${error.message.orEmpty()}") }
        }
    }

    private fun safeBackupName(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._-]+"), "-")
        .trim('-', '.', '_')
        .take(48)
        .ifBlank { "profilo" }

    private fun showProfileDeletionError(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Eliminazione profilo")
            .setMessage(message)
            .setPositiveButton("Chiudi", null)
            .show()
    }

    private fun showBiaFrequencySettings() {
        val prefs = com.myfitai.app.data.profile.BiaFrequencyPreferences(this)
        val content = layoutInflater.inflate(R.layout.bia_frequency_settings, null)
        val group = content.findViewById<android.widget.RadioGroup>(R.id.biaFrequencyGroup)
        val selectedId = when (prefs.intervalDays) {
            BiaFrequencyPreferences.WEEKLY -> R.id.biaFrequencyWeekly
            BiaFrequencyPreferences.BIMONTHLY -> R.id.biaFrequencyBimonthly
            else -> R.id.biaFrequencyMonthly
        }
        group.check(selectedId)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bia_frequency_title)
            .setView(normalizeRuntimeDialogContent(content))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save) { _, _ ->
                prefs.intervalDays = when (group.checkedRadioButtonId) {
                    R.id.biaFrequencyWeekly -> BiaFrequencyPreferences.WEEKLY
                    R.id.biaFrequencyBimonthly -> BiaFrequencyPreferences.BIMONTHLY
                    else -> BiaFrequencyPreferences.MONTHLY
                }
            }
            .show()
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
                "Impostazioni IA contiene provider, chiavi API, modelli e costi, uguali per tutti i profili. Automazioni IA contiene le funzioni automatiche del profilo attivo. Le chiavi vengono protette localmente e non sono mostrate in chiaro dopo il salvataggio.",
            )
        }
    }

    /** Righe d'ingresso verso Impostazioni IA (globali) e Automazioni IA (del profilo attivo). */
    private fun bindAiEntryRows() {
        findViewById<SettingRowView>(R.id.rowAiSettings).setOnClickListener { AiSettingsEntry.open(this) }
        val automationsRow = findViewById<SettingRowView>(R.id.rowAiAutomations)
        val profileId = data.activeProfileStore.currentIdOrNull()
        if (profileId == null) {
            automationsRow.visibility = View.GONE
            return
        }
        automationsRow.visibility = View.VISIBLE
        automationsRow.setOnClickListener { startActivity(Intent(this, AiAutomationsActivity::class.java)) }
        lifecycleScope.launch {
            val name = data.userProfileRepository.get(profileId)?.name?.trim().orEmpty()
            if (name.isNotBlank()) automationsRow.setDescription(getString(R.string.settings_row_automations_desc_named, name))
        }
    }

    override fun onResume() {
        super.onResume()
        renderAiProviderBadge()
    }

    private fun renderAiProviderBadge() {
        val row = findViewById<SettingRowView?>(R.id.rowAiSettings) ?: return
        val status = AiProviderAccess.status(this)
        if (status.needsAttention) {
            row.setTrailingBadge(getString(R.string.settings_row_ai_status_unconfigured), R.color.semantic_warning)
        } else {
            row.setTrailingBadge(if (status.selected == AiProviderType.GEMINI) "Gemini" else "OpenAI", R.color.accent_green_dark)
        }
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
            HelpSection("Il percorso MyFitAI", "Da dove parti e dove arrivi", "Inserisci profilo, rilevazioni e preferenze alimentari. L'app usa questi dati per costruire il percorso e lo aggiorna quando registri nuove informazioni."),
            HelpSection("1. Profilo e rilevazioni", "La base del calcolo", "Peso, altezza, età, sesso biologico, massa grassa, circonferenze e livello di attività descrivono il punto di partenza. Più i dati sono recenti e coerenti, più l'interpretazione è utile."),
            HelpSection("2. Calorie di base", "Metabolismo a riposo (BMR) e consumo giornaliero (TDEE)", "Il metabolismo a riposo (BMR) è il consumo stimato a riposo. Con una massa grassa plausibile si usa Katch-McArdle: 370 + 21,6 × massa magra in kg. Altrimenti si usa Mifflin-St Jeor. Il consumo giornaliero (TDEE) si calcola dal tuo livello di attività nel profilo: nessuna variazione giorno per giorno."),
            HelpSection("3. Obiettivo di oggi", "Profilo, piano e consumo", "In Alimentazione puoi confrontare il metabolismo a riposo (BMR), il consumo giornaliero (TDEE) calcolato sul profilo, l'obiettivo di oggi (dal piano o da profilo), i valori del menu e calorie/proteine registrate come consumate."),
            HelpSection("4. Obiettivo e calorie di riferimento", "Il numero calorico di riferimento", "L'obiettivo da profilo deriva dal consumo giornaliero (TDEE): ricomposizione 95%, perdita di peso 85%, mantenimento 100%, aumento massa 110%, performance 100%. Sono fattori iniziali e non promesse sul risultato."),
            HelpSection("5. Macronutrienti", "Come vengono distribuiti i macro", "Le proteine sono 2,0 g/kg per perdita, ricomposizione e aumento massa, oppure 1,8 g/kg per mantenimento e performance. I grassi sono 0,8 g/kg, oppure 0,9 g/kg nella performance. I carboidrati ricevono le calorie rimanenti: (obiettivo calorico - calorie di proteine e grassi) / 4."),
            HelpSection("6. Adattamento", "Il piano impara dai trend", "L'obiettivo cambia solo con evidenze sufficienti: almeno 21 giorni e almeno due segnali tra peso, massa grassa, massa muscolare, vita e addome. Uno stallo prolungato richiede almeno 28 giorni. La correzione è graduale, a passi del 2,5%, e resta dentro limiti conservativi."),
            HelpSection("7. Piano alimentare", "L'IA propone, l'app controlla", "L'IA propone pasti, quantità e preparazioni usando gli obiettivi calcolati localmente. Prima del salvataggio l'app controlla struttura, calorie e macronutrienti. L'IA non decide autonomamente il deficit."),
            HelpSection("8. Diario e review", "Dal piano a ciò che accade davvero", "Puoi confrontare calorie e proteine dell'obiettivo, del menu e degli elementi segnati come consumati. I consumi manuali e le stime del piano restano distinti. La review settimanale aiuta a leggere andamento e rispetto del piano, ma una stima non diventa una misurazione certa."),
            HelpSection("9. Sgarro e serbatoio", "Il surplus viene distribuito nella settimana", "L'IA stima calorie e macro dello sgarro. Dopo la conferma, l'app distribuisce una parte del surplus sui pasti futuri entro i limiti giornalieri di sicurezza; i pasti già trascorsi restano invariati. L'eventuale residuo non distribuito viene mostrato."),
            HelpSection("Da ricordare", "Una guida, non una diagnosi", "I risultati sono stime orientative basate sui dati inseriti. Idratazione, glicogeno, attività reale e qualità delle rilevazioni possono cambiare il risultato. Per condizioni mediche o obiettivi specifici, confrontati con un professionista."),
        )
        showHelpDialog(HelpDialogSpec(title = "Come funziona MyFitAI", sections = sections))
    }

    private fun bindDataDeletion() {
        findViewById<View>(R.id.rowDeleteRecordedData).setOnClickListener { showDeletionCategoryPicker() }
    }

    private fun showDeletionCategoryPicker() {
        val actions = listOf(
            DeletionAction("piani alimentari salvati", "i piani e le giornate alimentari generate", data.dataDeletionService::deleteMealPlans),
            DeletionAction("pasti e consumi registrati", "gli alimenti e i pasti segnati come consumati", data.dataDeletionService::deleteFoodConsumptions),
            DeletionAction("misurazioni BIA", "le rilevazioni BIA", data.dataDeletionService::deleteBiaMeasurements),
            DeletionAction("misurazioni corporee", "peso, altezza e le altre rilevazioni corporee registrate", data.dataDeletionService::deleteBodyMeasurements),
            DeletionAction("sgarri registrati", "gli sgarri e il relativo storico", data.dataDeletionService::deleteCheatEntries),
            DeletionAction("riepiloghi settimanali", "le review settimanali", data.dataDeletionService::deleteWeeklyReviews),
            DeletionAction("diario emotivo", "le registrazioni giornaliere e le note settimanali", data.dataDeletionService::deleteEmotionalDiary),
            DeletionAction(
                "tutti i dati di attività",
                "piani alimentari, pasti e consumi, misurazioni BIA e corporee, sgarri, riepiloghi settimanali e diario emotivo",
                data.dataDeletionService::deleteRecordedData,
            ),
        )
        var categoryDialog: androidx.appcompat.app.AlertDialog? = null
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = resources.getDimensionPixelSize(R.dimen.space_8)
            setPadding(inset, 0, inset, 0)
        }
        actions.forEachIndexed { index, action ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                val verticalPadding = resources.getDimensionPixelSize(R.dimen.space_8)
                setPadding(0, verticalPadding, 0, verticalPadding)
                contentDescription = "Elimina ${action.label}. ${action.description}"
                setOnClickListener {
                    categoryDialog?.dismiss()
                    confirmDeletion(action, index == actions.lastIndex)
                }
            }
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_delete_trash)
                imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.text_secondary))
                val iconSize = resources.getDimensionPixelSize(R.dimen.space_32)
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            })
            val textColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val startGap = resources.getDimensionPixelSize(R.dimen.space_16)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = startGap
                }
            }
            textColumn.addView(TextView(this).apply {
                text = "Elimina ${action.label}"
                setTextAppearance(R.style.Text_MyFitAI_SettingsLabel)
            })
            textColumn.addView(TextView(this).apply {
                text = action.description
                setTextAppearance(R.style.Text_MyFitAI_SettingsDescription)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            row.addView(textColumn)
            row.addView(TextView(this).apply {
                text = "›"
                setTextAppearance(R.style.Text_MyFitAI_CardTitle)
                setTextColor(getColor(R.color.text_muted))
            })
            content.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            if (index != actions.lastIndex) {
                content.addView(View(this).apply { setBackgroundColor(getColor(R.color.divider)) }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    resources.getDimensionPixelSize(R.dimen.divider_thickness),
                ))
            }
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        categoryDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Scegli cosa eliminare")
            .setView(normalizeRuntimeDialogContent(scroll))
            .setNegativeButton("Chiudi", null)
            .create()
        categoryDialog?.show()
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
}
