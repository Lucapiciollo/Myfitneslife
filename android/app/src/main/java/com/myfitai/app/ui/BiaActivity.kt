package com.myfitai.app.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewParent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import androidx.work.WorkInfo
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.myfitai.app.R
import com.myfitai.app.ai.AiFailure
import com.myfitai.app.ai.AiFailureMessages
import com.myfitai.app.navigation.AiSettingsEntry
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.domain.body.AiImageProcessor
import com.myfitai.app.domain.body.AiImageTempStore
import com.myfitai.app.domain.body.BiaAnalysisAiJobHandler
import com.myfitai.app.domain.body.BiaAnalysisPolicy
import com.myfitai.app.domain.body.BiaAnalysisPresentation
import com.myfitai.app.domain.body.BiaAnalysisReport
import com.myfitai.app.domain.body.BiaFlowPresentation
import com.myfitai.app.domain.body.BiaFormRules
import com.myfitai.app.domain.body.BiaHistoryText
import com.myfitai.app.domain.body.BiaImportAiJobHandler
import com.myfitai.app.domain.body.BiaImportContract
import com.myfitai.app.domain.body.BodyValueRanges
import com.myfitai.app.domain.body.BodyValueRanges.BiaField
import com.myfitai.app.domain.food.NutritionPathTrigger
import com.myfitai.app.domain.ai.AiJobType
import com.myfitai.app.domain.ai.AiJobWorker
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.bia.BiaFailurePanel
import com.myfitai.app.ui.bia.BiaViewModel
import com.myfitai.app.ui.bia.BiaWaitPanel
import com.myfitai.app.ui.widgets.MeasurementRowView
import com.myfitai.app.ui.widgets.SelectableSegmentView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class BiaActivity : BaseShellActivity() {

    private val data by lazy { AppDataContainer.get(this) }
    private val viewModel: BiaViewModel by viewModels {
        BiaViewModel.Factory(data.biaRepository, data.activeProfileStore, data.nutritionPathTrigger, data.biaProgressCoachScheduler)
    }

    private lateinit var dateInput: TextInputEditText
    private lateinit var dateInputLayout: TextInputLayout
    private lateinit var timeInput: TextInputEditText
    private lateinit var newMeasurementContainer: View
    private lateinit var historyContainer: View
    private lateinit var historyList: LinearLayout
    private lateinit var historySummary: TextView
    private lateinit var reviewSavedBiaAnalysisButton: MaterialButton
    private lateinit var formErrorText: TextView
    private lateinit var importWaitPanel: BiaWaitPanel
    private lateinit var importFailurePanel: BiaFailurePanel
    private lateinit var analysisWaitPanel: BiaWaitPanel
    private lateinit var analysisFailurePanel: BiaFailurePanel

    /** Un campo del form: riga, campo dell'anteprima import, etichetta e unita'. */
    private class RowSpec(val field: BiaField, val rowId: Int, val importInputId: Int, val label: String, val unit: String)

    private val rowSpecs = listOf(
        RowSpec(BiaField.WEIGHT, R.id.rowWeight, R.id.biaImportWeight, "Peso", "kg"),
        RowSpec(BiaField.BODY_FAT, R.id.rowBodyFat, R.id.biaImportBodyFat, "Grasso corporeo", "%"),
        RowSpec(BiaField.VISCERAL, R.id.rowVisceralFat, R.id.biaImportVisceralFat, "Grasso viscerale", ""),
        RowSpec(BiaField.MUSCLE, R.id.rowMuscleMass, R.id.biaImportMuscleMass, "Massa muscolare", "kg"),
        RowSpec(BiaField.SKELETAL, R.id.rowSkeletalMuscle, R.id.biaImportSkeletalMuscle, "Muscolo scheletrico", "kg"),
        RowSpec(BiaField.WATER_PERCENT, R.id.rowBodyWater, R.id.biaImportBodyWater, "Acqua corporea", "%"),
        RowSpec(BiaField.BMR, R.id.rowBmr, R.id.biaImportBmr, "BMR", "kcal"),
        RowSpec(BiaField.FAT_MASS, R.id.rowFatMass, R.id.biaImportFatMass, "Massa grassa", "kg"),
        RowSpec(BiaField.LEAN_MASS, R.id.rowLeanMass, R.id.biaImportLeanMass, "Massa magra", "kg"),
        RowSpec(BiaField.WATER_KG, R.id.rowBodyWaterKg, R.id.biaImportBodyWaterKg, "Acqua corporea", "kg"),
        RowSpec(BiaField.SUBCUTANEOUS, R.id.rowSubcutaneousFat, R.id.biaImportSubcutaneousFat, "Grasso sottocutaneo", "%"),
        RowSpec(BiaField.BONE, R.id.rowBoneMass, R.id.biaImportBoneMass, "Massa ossea", "kg"),
        RowSpec(BiaField.PROTEIN_PCT, R.id.rowProteinPercent, R.id.biaImportProteinPercent, "Proteine", "%"),
        RowSpec(BiaField.PROTEIN_MASS, R.id.rowProteinKg, R.id.biaImportProteinKg, "Proteine", "kg"),
        RowSpec(BiaField.BODY_AGE, R.id.rowBodyAge, R.id.biaImportBodyAge, "Età corporea", "anni"),
        RowSpec(BiaField.BMI_FIELD, R.id.rowBmi, R.id.biaImportBmi, "BMI", ""),
    )

    private val values = linkedMapOf<BiaField, Float?>().apply { BiaField.entries.forEach { put(it, null) } }

    private var selectedDateMillis: Long = System.currentTimeMillis()
    private var selectedHour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    private var selectedMinute: Int = Calendar.getInstance().get(Calendar.MINUTE)
    private var pendingImportFile: File? = null
    private var editingMeasurement: BiaMeasurementEntity? = null
    /** Modifica ripristinata dopo una ricreazione: la rilevazione torna da [BiaViewModel.history] senza toccare i valori digitati. */
    private var restoredEditId = 0L
    private var pendingEditId: Long = 0L
    private var openingExistingForEdit = false

    /** Stato di partenza per l'avviso di uscita: nullo finche' una modifica non e' stata caricata. */
    private var baseline: BiaFormRules.Snapshot? = null
    private var finishAfterSave = false

    /** Provenienza dell'import foto (note) da salvare con la prossima nuova rilevazione. */
    private var pendingImportNotes: String? = null
    /** Risultato della lettura foto non ancora confermato o scartato: sopravvive alla rotazione. */
    private var pendingImportPayload: String? = null
    private var importJobKey: String? = null
    private var importImagePath: String? = null
    private var importObserver: Job? = null
    /** Ultimo job di lettura foto concluso: evita di riaprire due volte l'anteprima se l'osservazione dei job attivi arriva in ritardo. */
    private var lastFinishedImportKey: String? = null

    /** Job started from this screen; null when idle. */
    private var biaAnalysisJobKey: String? = null
    private var analysisObserver: Job? = null
    /** Analysis already running elsewhere (automatic run, previous Activity instance). */
    private var backgroundBiaAnalysisRunning = false
    private var goalAdviceQueuedKey: String? = null
    private val imageTempStore by lazy { AiImageTempStore(this) }

    private val exitGuard = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = requestExit()
    }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) processImportUri(uri)
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = pendingImportFile
        pendingImportFile = null
        if (success && file != null) processImportFile(file) else imageTempStore.delete(file)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bia)
        onBackPressedDispatcher.addCallback(this, exitGuard)
        bindBottom(BottomNavBinder.Tab.MORE)
        bindBack()
        findViewById<View>(R.id.backButton)?.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        bindViews()
        normalizeBiaCards()
        bindSegments()
        findViewById<View>(R.id.aiConfigurationNoticeButton).setOnClickListener { AiSettingsEntry.open(this) }
        findViewById<View>(R.id.addBiaFromHistoryButton).setOnClickListener {
            resetForm()
            findViewById<SelectableSegmentView>(R.id.biaSegment).getChildAt(0)?.performClick()
        }
        restoreState(savedInstanceState)
        bindDateTime()
        bindHelpCards()
        bindMeasurementRows()
        bindSave()
        bindPhotoImport()
        bindGoalAdviceCard()
        gateAiClick(findViewById(R.id.analyzeBiaButton)) { analyzeBiaWithAi(findViewById(R.id.analyzeBiaButton)) }
        setAiActionEnabled(findViewById(R.id.analyzeBiaButton))
        normalizeAnalysisButtons()
        findViewById<View>(R.id.aiConfigurationNoticeCard).visibility =
            if (aiProviderConfigured) View.GONE else View.VISIBLE
        observeState()
        renderDateTime()
        if (savedInstanceState != null) {
            if (restoredEditId > 0L) setSaveLabel(editing = true)
            if (savedInstanceState.getInt(STATE_TAB, 0) == 1) {
                findViewById<SelectableSegmentView>(R.id.biaSegment).getChildAt(1)?.performClick()
            }
            reattachRunningJobs()
        } else {
            pendingEditId = intent.getLongExtra(EXTRA_EDIT_ID, 0L)
            if (pendingEditId > 0L) loadPendingEdit() else baseline = currentSnapshot()
            if (intent.getBooleanExtra(EXTRA_OPEN_HISTORY, false) && pendingEditId == 0L) {
                findViewById<SelectableSegmentView>(R.id.biaSegment).getChildAt(1)?.performClick()
                // Only on the first creation: the extra must not re-open the paid request after rotation.
                if (intent.getBooleanExtra(EXTRA_ANALYZE_LATEST, false)) {
                    intent.removeExtra(EXTRA_ANALYZE_LATEST)
                    analyzeLatestWhenReady()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (this::dateInput.isInitialized) {
            setAiActionEnabled(findViewById(R.id.importPhotoButton))
            refreshAiActionAvailability()
            findViewById<View>(R.id.aiConfigurationNoticeCard).visibility =
                if (aiProviderConfigured) View.GONE else View.VISIBLE
        }
    }

    /**
     * I 16 valori, data, ora, rilevazione in modifica, provenienza dell'import e job in corso sopravvivono a rotazione,
     * cambio di dimensione del carattere e ricreazione del processo (le condizioni sono ripristinate dalle caselle).
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putFloatArray(STATE_VALUES, BiaFormRules.toArray(BiaField.entries.map { values[it] }))
        outState.putLong(STATE_DATE, selectedDateMillis)
        outState.putInt(STATE_HOUR, selectedHour)
        outState.putInt(STATE_MINUTE, selectedMinute)
        baseline?.let {
            outState.putBoolean(STATE_HAS_BASELINE, true)
            outState.putFloatArray(STATE_BASE_VALUES, BiaFormRules.toArray(it.values))
            outState.putLong(STATE_BASE_AT, it.measuredAtMillis)
            outState.putBooleanArray(STATE_BASE_CONDITIONS, it.conditions.toBooleanArray())
        }
        outState.putLong(STATE_EDIT_ID, editingMeasurement?.id ?: restoredEditId)
        outState.putString(STATE_NOTES, pendingImportNotes)
        outState.putString(STATE_IMPORT_PAYLOAD, pendingImportPayload)
        outState.putString(STATE_IMPORT_JOB, importJobKey)
        outState.putString(STATE_IMPORT_IMAGE, importImagePath)
        outState.putString(STATE_ANALYSIS_JOB, biaAnalysisJobKey)
        outState.putInt(STATE_TAB, if (this::historyContainer.isInitialized && historyContainer.visibility == View.VISIBLE) 1 else 0)
    }

    private fun restoreState(state: Bundle?) {
        if (state == null) return
        BiaFormRules.fromArray(state.getFloatArray(STATE_VALUES)).forEachIndexed { index, value -> values[BiaField.entries[index]] = value }
        selectedDateMillis = state.getLong(STATE_DATE, selectedDateMillis)
        selectedHour = state.getInt(STATE_HOUR, selectedHour)
        selectedMinute = state.getInt(STATE_MINUTE, selectedMinute)
        if (state.getBoolean(STATE_HAS_BASELINE, false)) {
            baseline = BiaFormRules.Snapshot(
                BiaFormRules.fromArray(state.getFloatArray(STATE_BASE_VALUES)),
                state.getLong(STATE_BASE_AT),
                state.getBooleanArray(STATE_BASE_CONDITIONS)?.toList() ?: List(CONDITION_COUNT) { false },
            )
        }
        restoredEditId = state.getLong(STATE_EDIT_ID, 0L)
        pendingImportNotes = state.getString(STATE_NOTES)
        pendingImportPayload = state.getString(STATE_IMPORT_PAYLOAD)
        importJobKey = state.getString(STATE_IMPORT_JOB)
        importImagePath = state.getString(STATE_IMPORT_IMAGE)
        biaAnalysisJobKey = state.getString(STATE_ANALYSIS_JOB)
    }

    /** Dopo una ricreazione: riattacca l'osservazione dei job avviati da questa schermata e riapre la lettura foto non confermata. */
    private fun reattachRunningJobs() {
        val profileId = data.activeProfileStore.currentIdOrNull()
        pendingImportPayload?.let { payload ->
            if (importJobKey == null) showImportResult(payload)
        }
        if (profileId == null) return
        importJobKey?.let { observeImportJob(profileId, it) }
        biaAnalysisJobKey?.let { key ->
            showAnalysisWait(AiJobTypeState.QUEUED)
            observeAnalysisJob(profileId, key, viewModel.history.value.firstOrNull()?.id ?: 0L)
        }
    }

    private fun bindViews() {
        dateInput = findViewById(R.id.dateInput)
        dateInputLayout = findViewById(R.id.dateInputLayout)
        timeInput = findViewById(R.id.timeInput)
        newMeasurementContainer = findViewById(R.id.newMeasurementContainer)
        historyContainer = findViewById(R.id.historyContainer)
        historyList = findViewById(R.id.historyList)
        historySummary = findViewById(R.id.historySummary)
        formErrorText = findViewById(R.id.biaFormErrorText)
        reviewSavedBiaAnalysisButton = findViewById(R.id.reviewSavedBiaAnalysisButton)
        reviewSavedBiaAnalysisButton.setOnClickListener { reviewLatestSavedAnalysis() }
        importWaitPanel = BiaWaitPanel.inflate(findViewById<LinearLayout>(R.id.importPanelsHost)) { cancelImport() }
        importFailurePanel = BiaFailurePanel.inflate(
            findViewById<LinearLayout>(R.id.importPanelsHost),
            onRetry = { showPhotoSourceChooser() },
            onOpenSettings = { AiSettingsEntry.open(this) },
        )
        analysisWaitPanel = BiaWaitPanel.inflate(findViewById<LinearLayout>(R.id.analysisPanelsHost)) { cancelAnalysis() }
        analysisFailurePanel = BiaFailurePanel.inflate(
            findViewById<LinearLayout>(R.id.analysisPanelsHost),
            onRetry = { analyzeBiaWithAi(findViewById(R.id.analyzeBiaButton)) },
            onOpenSettings = { AiSettingsEntry.open(this) },
        )
    }

    /**
     * The analysis buttons get their text from resources and may grow with the system font size: a fixed 48dp
     * height would clip a larger font, so the height wraps and 48dp stays the minimum touch target.
     */
    private fun normalizeAnalysisButtons() {
        val minHeight = resources.getDimensionPixelSize(R.dimen.button_secondary_min_height)
        listOf(
            findViewById<MaterialButton>(R.id.analyzeBiaButton) to R.string.bia_analysis_action,
            reviewSavedBiaAnalysisButton to R.string.bia_analysis_review_saved_action,
        ).forEach { (button, textRes) ->
            button.setText(textRes)
            button.minHeight = minHeight
            button.layoutParams = button.layoutParams.apply { height = LinearLayout.LayoutParams.WRAP_CONTENT }
        }
    }

    private fun normalizeBiaCards() {
        listOf(R.id.biaDateCard, R.id.biaConditionsCard, R.id.biaResultsCard).forEach { id ->
            findViewById<MaterialCardView>(id).apply {
                setCardBackgroundColor(getColor(R.color.white))
                strokeColor = getColor(R.color.divider)
                strokeWidth = resources.getDimensionPixelSize(R.dimen.border_width_default)
                cardElevation = resources.getDimension(R.dimen.elevation_none)
                (getChildAt(0) as? View)?.setBackgroundColor(getColor(R.color.white))
            }
        }
        historySummary.setBackgroundResource(R.drawable.bg_card)
    }

    private fun loadPendingEdit() {
        val editId = pendingEditId
        lifecycleScope.launch {
            val profileId = data.activeProfileStore.currentIdOrNull() ?: return@launch
            val history = data.biaRepository.all(profileId).first()
            val item = history.firstOrNull { it.id == editId }
            if (item == null) return@launch
            if (pendingEditId == editId) {
                pendingEditId = 0L
                editReading(item)
            }
        }
    }

    private fun bindSegments() {
        val segment = findViewById<SelectableSegmentView>(R.id.biaSegment)
        segment.setSegments(listOf("Nuova misurazione", "Storico"), selectedIndex = 0)
        segment.setOnSegmentSelectedListener { index ->
            // Tornando al form dopo aver guardato lo storico si riparte da vuoto, ma mai scartando modifiche non salvate.
            if (index == 0 && editingMeasurement != null && !openingExistingForEdit && !isDirty()) resetForm()
            newMeasurementContainer.visibility = if (index == 0) View.VISIBLE else View.GONE
            historyContainer.visibility = if (index == 1) View.VISIBLE else View.GONE
            if (index == 1) renderHistory(viewModel.history.value)
        }
    }

    private fun bindDateTime() {
        dateInput.setOnClickListener {
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTheme(R.style.ThemeOverlay_MyFitAI_MaterialCalendar)
                .setTitleText("Data misurazione")
                // Le date future non sono selezionabili (come per le misure corporee).
                .setCalendarConstraints(CalendarConstraints.Builder().setValidator(DateValidatorPointBackward.now()).build())
                .setSelection(selectedDateMillis)
                .build()
            picker.addOnPositiveButtonClickListener { selection ->
                selectedDateMillis = selection
                dateInputLayout.error = null
                renderDateTime()
            }
            picker.show(supportFragmentManager, "bia_date_picker")
        }
        dateInputLayout.setEndIconOnClickListener { dateInput.performClick() }

        timeInput.setOnClickListener {
            val picker = MaterialTimePicker.Builder()
                .setTheme(R.style.ThemeOverlay_MyFitAI_MaterialTimePicker)
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(selectedHour)
                .setMinute(selectedMinute)
                .setTitleText("Ora misurazione")
                .build()
            picker.addOnPositiveButtonClickListener {
                selectedHour = picker.hour
                selectedMinute = picker.minute
                renderDateTime()
            }
            picker.show(supportFragmentManager, "bia_time_picker")
        }
    }

    private fun bindHelpCards() {
        findViewById<View>(R.id.biaDateHelpButton).setOnClickListener {
            showHelpCard(
                "Data e ora",
                "Indicano quando è stata eseguita la rilevazione BIA. Usa la data e l'ora riportate dalla bilancia o dall'app del dispositivo, così lo storico resta ordinato correttamente.",
            )
        }
        findViewById<View>(R.id.biaConditionsHelpButton).setOnClickListener {
            showHelpCard(
                "Condizioni della misura",
                "Segna le condizioni più vicine al momento della rilevazione. Servono a dare contesto ai valori: non modificano automaticamente i dati registrati.",
            )
        }
        findViewById<View>(R.id.biaResultsHelpButton).setOnClickListener {
            showHelpCard(
                "Risultati",
                "Inserisci i valori letti dalla bilancia BIA. I dati vengono salvati nello storico del profilo attivo e usati per mostrare l'andamento nel tempo.",
            )
        }
    }

    private fun bindMeasurementRows() {
        rowSpecs.forEach { spec ->
            findViewById<MeasurementRowView>(spec.rowId).apply {
                showIcon()
                setLabel(spec.label)
                setValue(values[spec.field]?.let { formatValue(it, spec.unit) } ?: "—")
                isClickable = true
                isFocusable = true
                setOnClickListener { showValueDialog(spec, this) }
            }
        }
    }

    /** Errore sotto il campo e nessun cambiamento silenzioso: un testo non valido non diventa mai "vuoto". */
    private fun errorFor(spec: RowSpec, check: BiaFormRules.Check): String? = when (check) {
        BiaFormRules.Check.NotANumber -> getString(R.string.bia_k4_error_not_number, spec.field.label)
        BiaFormRules.Check.NotWhole -> getString(R.string.bia_k4_error_not_whole, spec.field.label)
        is BiaFormRules.Check.OutOfRange -> getString(R.string.bia_k4_error_range, spec.field.label, check.range.describe())
        else -> null
    }

    private fun showValueDialog(spec: RowSpec, row: MeasurementRowView) {
        val content = layoutInflater.inflate(R.layout.dialog_bia_value_input, null, false)
        val inputLayout = content.findViewById<TextInputLayout>(R.id.valueInputLayout)
        val input = content.findViewById<TextInputEditText>(R.id.valueInput)

        inputLayout.hint = if (spec.unit.isBlank()) spec.label else "${spec.label} (${spec.unit})"
        values[spec.field]?.let { input.setText(BiaFormRules.inputText(it)) }
        input.setSelectAllOnFocus(true)
        input.doAfterTextChanged { inputLayout.error = null }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(spec.label)
            .setView(normalizeRuntimeDialogContent(content))
            .setNegativeButton("Annulla", null)
            .setNeutralButton("Svuota") { _, _ -> updateValue(spec, null, row) }
            // Il pulsante e' collegato dopo la visualizzazione: con un valore non valido la finestra resta aperta con l'errore.
            .setPositiveButton("Conferma", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                when (val check = BiaFormRules.check(spec.field, input.text?.toString())) {
                    BiaFormRules.Check.Empty -> {
                        updateValue(spec, null, row)
                        dialog.dismiss()
                    }
                    is BiaFormRules.Check.Valid -> {
                        updateValue(spec, check.value, row)
                        dialog.dismiss()
                    }
                    else -> {
                        inputLayout.error = errorFor(spec, check)
                        input.requestFocus()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun updateValue(spec: RowSpec, value: Float?, row: MeasurementRowView) {
        values[spec.field] = value
        row.setValue(value?.let { formatValue(it, spec.unit) } ?: "—")
        formErrorText.visibility = View.GONE
    }

    private fun bindSave() {
        findViewById<View>(R.id.saveButton).setOnClickListener { trySave() }
    }

    /** Controlla ogni valore e la data: elenca tutti gli errori insieme (testo, non solo colore) e non salva se ce ne sono. */
    private fun validateForm(): List<String> {
        val messages = mutableListOf<String>()
        dateInputLayout.error = null
        if (BiaFormRules.isFutureDay(composeMeasurementMillis(), System.currentTimeMillis())) {
            val message = getString(R.string.measure_error_future_date)
            dateInputLayout.error = message
            messages += message
        }
        rowSpecs.forEach { spec ->
            val value = values[spec.field] ?: return@forEach
            val check = when {
                value !in spec.field.range -> BiaFormRules.Check.OutOfRange(spec.field.range)
                spec.field == BiaField.BODY_AGE && value % 1f != 0f -> BiaFormRules.Check.NotWhole
                else -> BiaFormRules.Check.Valid(value)
            }
            errorFor(spec, check)?.let { messages += it }
        }
        if (messages.isEmpty()) BodyValueRanges.validateBia(currentBiaValues())?.let { messages += it }
        return messages
    }

    private fun currentBiaValues() = BodyValueRanges.BiaValues(
        values[BiaField.WEIGHT], values[BiaField.BODY_FAT], values[BiaField.VISCERAL], values[BiaField.MUSCLE],
        values[BiaField.SKELETAL], values[BiaField.WATER_PERCENT], values[BiaField.BMR], values[BiaField.FAT_MASS],
        values[BiaField.LEAN_MASS], values[BiaField.WATER_KG], values[BiaField.SUBCUTANEOUS], values[BiaField.BONE],
        values[BiaField.PROTEIN_PCT], values[BiaField.PROTEIN_MASS], values[BiaField.BODY_AGE], values[BiaField.BMI_FIELD],
    )

    private fun trySave(): Boolean {
        val errors = validateForm()
        if (errors.isNotEmpty()) {
            formErrorText.text = (listOf(getString(R.string.bia_k4_form_errors_title)) + errors.map { "• $it" }).joinToString("\n")
            formErrorText.visibility = View.VISIBLE
            formErrorText.post { formErrorText.requestRectangleOnScreen(Rect(0, 0, formErrorText.width, formErrorText.height)) }
            return false
        }
        formErrorText.visibility = View.GONE
        val original = editingMeasurement ?: viewModel.history.value.firstOrNull { it.id == restoredEditId && restoredEditId > 0L }
        if (restoredEditId > 0L && original == null) return false // la rilevazione in modifica non e' ancora stata ricaricata
        val measuredAt = composeMeasurementMillis().let { composed ->
            if (original != null) {
                val previous = Calendar.getInstance().apply { timeInMillis = original.measuredAtEpochMillis }
                val proposed = Calendar.getInstance().apply { timeInMillis = composed }
                if (previous.get(Calendar.YEAR) == proposed.get(Calendar.YEAR) &&
                    previous.get(Calendar.DAY_OF_YEAR) == proposed.get(Calendar.DAY_OF_YEAR) &&
                    previous.get(Calendar.HOUR_OF_DAY) == proposed.get(Calendar.HOUR_OF_DAY) &&
                    previous.get(Calendar.MINUTE) == proposed.get(Calendar.MINUTE)
                ) original.measuredAtEpochMillis else composed
            } else composed
        }
        val (fasting, justWokeUp, afterBathroom, noRecentWorkout) = conditionsState()
        if (original == null) {
            viewModel.save(
                measuredAtEpochMillis = measuredAt,
                weightKg = values[BiaField.WEIGHT],
                bodyFatPercent = values[BiaField.BODY_FAT],
                visceralFatLevel = values[BiaField.VISCERAL],
                muscleMassKg = values[BiaField.MUSCLE],
                skeletalMuscleKg = values[BiaField.SKELETAL],
                bodyWaterPercent = values[BiaField.WATER_PERCENT],
                bmrKcal = values[BiaField.BMR],
                fatMassKg = values[BiaField.FAT_MASS],
                leanMassKg = values[BiaField.LEAN_MASS],
                bodyWaterKg = values[BiaField.WATER_KG],
                subcutaneousFatPercent = values[BiaField.SUBCUTANEOUS],
                boneMassKg = values[BiaField.BONE],
                proteinPercent = values[BiaField.PROTEIN_PCT],
                proteinKg = values[BiaField.PROTEIN_MASS],
                bodyAgeYears = values[BiaField.BODY_AGE]?.toInt(),
                bmi = values[BiaField.BMI_FIELD],
                fasting = fasting,
                justWokeUp = justWokeUp,
                afterBathroom = afterBathroom,
                noRecentWorkout = noRecentWorkout,
                // Provenienza dell'import (sorgente, valori derivati, avvisi di lettura): non si perde al salvataggio.
                notes = pendingImportNotes,
            )
        } else {
            viewModel.update(
                original = original,
                measuredAtEpochMillis = measuredAt,
                weightKg = values[BiaField.WEIGHT],
                bodyFatPercent = values[BiaField.BODY_FAT],
                visceralFatLevel = values[BiaField.VISCERAL],
                muscleMassKg = values[BiaField.MUSCLE],
                skeletalMuscleKg = values[BiaField.SKELETAL],
                bodyWaterPercent = values[BiaField.WATER_PERCENT],
                bmrKcal = values[BiaField.BMR],
                fatMassKg = values[BiaField.FAT_MASS],
                leanMassKg = values[BiaField.LEAN_MASS],
                bodyWaterKg = values[BiaField.WATER_KG],
                subcutaneousFatPercent = values[BiaField.SUBCUTANEOUS],
                boneMassKg = values[BiaField.BONE],
                proteinPercent = values[BiaField.PROTEIN_PCT],
                proteinKg = values[BiaField.PROTEIN_MASS],
                bodyAgeYears = values[BiaField.BODY_AGE]?.toInt(),
                bmi = values[BiaField.BMI_FIELD],
                fasting = fasting,
                justWokeUp = justWokeUp,
                afterBathroom = afterBathroom,
                noRecentWorkout = noRecentWorkout,
            )
        }
        return true
    }

    private fun conditionsState(): List<Boolean> = listOf(
        findViewById<MaterialCheckBox>(R.id.checkFasting).isChecked,
        findViewById<MaterialCheckBox>(R.id.checkJustWoken).isChecked,
        findViewById<MaterialCheckBox>(R.id.checkAfterShower).isChecked,
        findViewById<MaterialCheckBox>(R.id.checkNoWorkout).isChecked,
    )

    private fun setConditions(fasting: Boolean, justWoken: Boolean, afterBathroom: Boolean, noWorkout: Boolean) {
        findViewById<MaterialCheckBox>(R.id.checkFasting).isChecked = fasting
        findViewById<MaterialCheckBox>(R.id.checkJustWoken).isChecked = justWoken
        findViewById<MaterialCheckBox>(R.id.checkAfterShower).isChecked = afterBathroom
        findViewById<MaterialCheckBox>(R.id.checkNoWorkout).isChecked = noWorkout
    }

    private fun currentSnapshot() = BiaFormRules.snapshot(values, composeMeasurementMillis(), conditionsState())

    private fun isDirty(): Boolean = BiaFormRules.isDirty(baseline, currentSnapshot())

    /** Uscita con dati non salvati: Salva / Esci senza salvare / Continua (stesso schema delle misure corporee). */
    private fun requestExit() {
        if (!isDirty()) {
            leaveForm()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.measure_discard_title)
            .setMessage(R.string.measure_discard_message)
            .setNeutralButton(R.string.measure_discard_keep, null)
            .setPositiveButton(R.string.measure_discard_save) { _, _ ->
                finishAfterSave = true
                if (!trySave()) finishAfterSave = false
            }
            .setNegativeButton(R.string.measure_discard_exit) { _, _ -> leaveForm() }
            .show()
    }

    private fun leaveForm() {
        exitGuard.isEnabled = false
        onBackPressedDispatcher.onBackPressed()
        exitGuard.isEnabled = true
    }

    private fun bindPhotoImport() {
        val importButton = findViewById<View>(R.id.importPhotoButton)
        setAiActionEnabled(importButton)
        gateAiClick(importButton) { showPhotoSourceChooser() }
    }

    private fun showPhotoSourceChooser() {
        if (!aiProviderConfigured) {
            AiSettingsEntry.open(this)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Importa BIA da foto")
            .setItems(arrayOf("Scatta foto", "Scegli dalla galleria")) { _, which ->
                if (which == 0) {
                    val file = imageTempStore.create()
                    pendingImportFile = file
                    cameraLauncher.launch(FileProvider.getUriForFile(this, "$packageName.fileprovider", file))
                } else {
                    galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }
            .show()
    }

    private fun processImportUri(uri: Uri) {
        importFailurePanel.hide()
        showImportStatus(getString(R.string.bia_k4_import_preparing))
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { AiImageProcessor.fromUri(this@BiaActivity, uri) } }
                .onSuccess { importImage(it) }
                .onFailure { showImportReadError() }
        }
    }

    private fun processImportFile(file: File) {
        importFailurePanel.hide()
        showImportStatus(getString(R.string.bia_k4_import_preparing))
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { AiImageProcessor.fromFile(file) } }
                .onSuccess { importImage(it) }
                .onFailure { showImportReadError() }
                .also { withContext(Dispatchers.IO) { imageTempStore.delete(file) } }
        }
    }

    private fun showImportReadError() {
        hideImportStatus()
        importFailurePanel.show(
            R.string.bia_k4_import_read_error,
            AiFailureMessages.Presentation(R.string.bia_k4_import_read_error, AiFailureMessages.Action.NONE, canRetry = true, technicalCode = null),
        )
    }

    /**
     * Conferma IA specifica per la foto: cosa viene inviato e quanto resta sul telefono. Vero perche' la copia temporanea
     * (cache `ai-job-images`) e' cancellata dal job a fine lettura e da [cancelImport] se l'utente annulla.
     */
    private fun importImage(image: com.myfitai.app.ai.AiImageInput) {
        hideImportStatus()
        confirmAiRequest(getString(R.string.bia_k4_import_action), getString(R.string.bia_k4_import_privacy)) {
            startImportJob(image)
        }
    }

    private fun startImportJob(image: com.myfitai.app.ai.AiImageInput) {
        val profileId = data.activeProfileStore.currentIdOrNull()
        if (profileId == null) {
            showImportStatus(getString(R.string.bia_k4_import_no_profile))
            return
        }
        val key = System.currentTimeMillis().toString()
        importFailurePanel.hide()
        showImportWait(queued = true)
        lifecycleScope.launch {
            val path = withContext(Dispatchers.IO) { data.aiImageJobStore.write(image) }
            importImagePath = path
            importJobKey = key
            val queued = runCatching {
                data.aiJobScheduler.enqueue(
                    AiJobType.BIA_IMPORT, profileId, key,
                    params = androidx.work.Data.Builder().putString(AiJobWorker.KEY_IMAGE_PATH, path).build(),
                )
            }
            if (queued.isFailure) {
                withContext(Dispatchers.IO) { data.aiImageJobStore.delete(path) }
                importImagePath = null
                importJobKey = null
                importWaitPanel.hide()
                importFailurePanel.show(AiFailureMessages.couldNotStart())
                return@launch
            }
            observeImportJob(profileId, key)
        }
    }

    private fun showImportWait(queued: Boolean) {
        importWaitPanel.show(
            getString(if (queued) R.string.bia_k4_import_wait_queued_title else R.string.bia_k4_import_wait_title),
            getString(if (queued) R.string.bia_k4_import_wait_queued_message else R.string.bia_k4_import_wait_message),
            cancellable = true,
            cancelDescription = getString(R.string.bia_k4_import_cancel_cd),
        )
    }

    /** Segue il job di lettura foto: sopravvive alla ricreazione perche' la chiave e' nello stato salvato o nelle etichette del job. */
    private fun observeImportJob(profileId: Long, key: String) {
        importObserver?.cancel()
        importJobKey = key
        importObserver = lifecycleScope.launch {
            data.aiJobScheduler.observe(AiJobType.BIA_IMPORT, profileId, key)
                .transformWhile { info ->
                    emit(info)
                    info?.state?.isFinished != true
                }
                .collect { info ->
                    when (info?.state) {
                        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> showImportWait(queued = true)
                        WorkInfo.State.RUNNING -> showImportWait(queued = false)
                        WorkInfo.State.SUCCEEDED -> {
                            finishImportJob()
                            val payload = info.outputData.getString(BiaImportAiJobHandler.KEY_PAYLOAD).orEmpty()
                            showImportResult(payload)
                        }
                        WorkInfo.State.CANCELLED -> {
                            finishImportJob()
                            importFailurePanel.show(AiFailureMessages.forFailure(AiFailure.Cancelled))
                        }
                        WorkInfo.State.FAILED -> {
                            finishImportJob()
                            val raw = info.outputData.getString(AiJobWorker.KEY_ERROR)
                            if (BiaFlowPresentation.isNotBiaPhoto(raw)) {
                                importFailurePanel.show(
                                    R.string.bia_k4_import_not_bia,
                                    AiFailureMessages.Presentation(R.string.bia_k4_import_not_bia, AiFailureMessages.Action.NONE, canRetry = true, technicalCode = null),
                                )
                            } else {
                                importFailurePanel.show(AiFailureMessages.resolve(raw))
                            }
                        }
                        null -> Unit
                    }
                }
        }
    }

    /** Chiude l'attesa e toglie la copia temporanea della foto (il job l'ha gia' cancellata; qui e' solo una garanzia). */
    private fun finishImportJob() {
        importWaitPanel.hide()
        lastFinishedImportKey = importJobKey
        importJobKey = null
        val path = importImagePath
        importImagePath = null
        if (path != null) lifecycleScope.launch(Dispatchers.IO) { data.aiImageJobStore.delete(path) }
    }

    private fun cancelImport() {
        val profileId = data.activeProfileStore.currentIdOrNull() ?: return
        val key = importJobKey ?: return
        data.aiJobScheduler.cancel(AiJobType.BIA_IMPORT, profileId, key)
        // L'annullamento ferma il job senza passare dalla pulizia del worker: la copia temporanea si cancella qui.
        val path = importImagePath
        importImagePath = null
        if (path != null) lifecycleScope.launch(Dispatchers.IO) { data.aiImageJobStore.delete(path) }
    }

    private fun showImportResult(payloadJson: String) {
        val result = runCatching { BiaFlowPresentation.parseImportPayload(payloadJson) }.getOrNull()
        if (result == null) {
            pendingImportPayload = null
            importFailurePanel.show(AiFailureMessages.forFailure(AiFailure.MalformedJson))
            return
        }
        pendingImportPayload = payloadJson
        showImportStatus(getString(R.string.bia_k4_import_done))
        showImportPreview(result.preview, result.provider, result.model)
    }

    private fun showImportStatus(message: String) {
        findViewById<TextView>(R.id.importStatusText)?.apply {
            text = message
            visibility = View.VISIBLE
        }
    }

    private fun hideImportStatus() {
        findViewById<TextView>(R.id.importStatusText)?.visibility = View.GONE
    }

    private fun layoutOf(input: View): TextInputLayout? {
        var parent: ViewParent? = input.parent
        while (parent != null && parent !is TextInputLayout) parent = parent.parent
        return parent as? TextInputLayout
    }

    private fun confidenceLabel(level: BiaFlowPresentation.Confidence): String = getString(
        when (level) {
            BiaFlowPresentation.Confidence.HIGH -> R.string.bia_k4_confidence_high
            BiaFlowPresentation.Confidence.MEDIUM -> R.string.bia_k4_confidence_medium
            BiaFlowPresentation.Confidence.LOW -> R.string.bia_k4_confidence_low
            BiaFlowPresentation.Confidence.UNKNOWN -> R.string.bia_k4_confidence_unknown
        },
    )

    /**
     * Anteprima della lettura: confidenza in parole, valori da ricontrollare indicati con un testo (non solo con il colore),
     * nessuna foto (non e' conservata) e validazione per campo prima di usare i valori.
     */
    private fun showImportPreview(preview: BiaImportContract.Preview, provider: String, model: String) {
        val content = layoutInflater.inflate(R.layout.dialog_bia_import_preview, null, false)
        val meta = content.findViewById<TextView>(R.id.biaImportMeta)
        val date = content.findViewById<TextView>(R.id.biaImportDate)
        val confidenceNote = content.findViewById<TextView>(R.id.biaImportConfidenceNote)
        val warningsText = content.findViewById<TextView>(R.id.biaImportWarnings)
        val level = BiaFlowPresentation.confidence(preview.confidence)
        val previewValues = BiaFlowPresentation.importValues(preview)
        val toDoubleCheck = BiaFlowPresentation.fieldsToDoubleCheck(level, previewValues)

        val inputs = rowSpecs.associate { spec ->
            val input = content.findViewById<TextInputEditText>(spec.importInputId)
            input.setText(BiaFormRules.inputText(previewValues[spec.field]))
            val layout = layoutOf(input)
            if (spec.field in toDoubleCheck) layout?.helperText = getString(R.string.bia_k4_preview_check_hint)
            input.doAfterTextChanged { layout?.error = null }
            spec.field to input
        }

        meta.text = buildString {
            append(getString(R.string.bia_k4_preview_provider, provider, model))
            append("\n").append(getString(R.string.bia_k4_preview_confidence, confidenceLabel(level)))
            preview.notes.takeIf { it.isNotBlank() }?.let { append("\n").append(getString(R.string.bia_k4_preview_provenance, it)) }
        }
        val levelNote = when (level) {
            BiaFlowPresentation.Confidence.LOW -> R.string.bia_k4_preview_low_note
            BiaFlowPresentation.Confidence.MEDIUM -> R.string.bia_k4_preview_medium_note
            else -> null
        }
        levelNote?.let {
            confidenceNote.text = getString(it)
            confidenceNote.visibility = View.VISIBLE
        }
        if (preview.warnings.isNotEmpty()) {
            warningsText.text = getString(R.string.bia_k4_preview_warnings, preview.warnings.joinToString(" "))
            warningsText.visibility = View.VISIBLE
        }
        date.text = preview.measuredAtEpochMillis?.let { timestamp ->
            getString(R.string.bia_k4_preview_date, SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALIAN).format(Date(timestamp)))
        } ?: getString(R.string.bia_k4_preview_date_missing)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Controlla importazione BIA")
            .setView(normalizeRuntimeDialogContent(content))
            .setNegativeButton("Annulla") { _, _ -> pendingImportPayload = null }
            .setPositiveButton("Usa valori", null)
            .setOnCancelListener { pendingImportPayload = null }
            .create()

        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(getColor(R.color.text_secondary))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(getColor(R.color.accent_green_dark))
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels *
                    resources.getFraction(R.fraction.bia_dialog_width_fraction, 1, 1)).toInt(),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            )
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                applyImportedValues(dialog, content, preview, inputs)
            }
        }
        dialog.show()
    }

    /** "Usa valori": ogni campo e' controllato (errore sul campo, finestra aperta); solo valori validi arrivano al form. */
    private fun applyImportedValues(
        dialog: androidx.appcompat.app.AlertDialog,
        content: View,
        preview: BiaImportContract.Preview,
        inputs: Map<BiaField, TextInputEditText>,
    ) {
        val formError = content.findViewById<TextView>(R.id.biaImportFormError)
        formError.visibility = View.GONE
        val parsed = linkedMapOf<BiaField, Float?>()
        var firstInvalid: View? = null
        for (spec in rowSpecs) {
            val input = inputs.getValue(spec.field)
            when (val check = BiaFormRules.check(spec.field, input.text?.toString())) {
                is BiaFormRules.Check.Valid -> {
                    parsed[spec.field] = check.value
                }
                BiaFormRules.Check.Empty -> {
                    parsed[spec.field] = null
                }
                else -> {
                    layoutOf(input)?.error = errorFor(spec, check)
                    if (firstInvalid == null) firstInvalid = input
                }
            }
        }
        val invalid = firstInvalid
        if (invalid != null) {
            invalid.requestFocus()
            return
        }
        val coherence = BodyValueRanges.validateBia(
            BodyValueRanges.BiaValues(
                parsed[BiaField.WEIGHT], parsed[BiaField.BODY_FAT], parsed[BiaField.VISCERAL], parsed[BiaField.MUSCLE],
                parsed[BiaField.SKELETAL], parsed[BiaField.WATER_PERCENT], parsed[BiaField.BMR], parsed[BiaField.FAT_MASS],
                parsed[BiaField.LEAN_MASS], parsed[BiaField.WATER_KG], parsed[BiaField.SUBCUTANEOUS], parsed[BiaField.BONE],
                parsed[BiaField.PROTEIN_PCT], parsed[BiaField.PROTEIN_MASS], parsed[BiaField.BODY_AGE], parsed[BiaField.BMI_FIELD],
            ),
        )
        if (coherence != null) {
            formError.text = coherence
            formError.visibility = View.VISIBLE
            formError.requestRectangleOnScreen(Rect(0, 0, formError.width, formError.height))
            return
        }
        parsed.forEach { (field, value) -> values[field] = value }
        preview.measuredAtEpochMillis?.let { timestamp ->
            Calendar.getInstance().apply { timeInMillis = timestamp }.also { detected ->
                selectedDateMillis = detected.timeInMillis
                selectedHour = detected.get(Calendar.HOUR_OF_DAY)
                selectedMinute = detected.get(Calendar.MINUTE)
            }
        }
        pendingImportNotes = BiaFlowPresentation.notesForSave(preview.notes, preview.warnings)
        pendingImportPayload = null
        formErrorText.visibility = View.GONE
        bindMeasurementRows()
        renderDateTime()
        showImportStatus(
            getString(
                if (preview.measuredAtEpochMillis != null) R.string.bia_k4_preview_loaded_with_date else R.string.bia_k4_preview_loaded_no_date,
            ),
        )
        dialog.dismiss()
    }

    private fun bindGoalAdviceCard() {
        findViewById<View>(R.id.biaGoalAdviceNo).setOnClickListener { hideGoalAdvice() }
        findViewById<View>(R.id.biaGoalAdviceYes).setOnClickListener {
            val queuedKey = goalAdviceQueuedKey
            if (queuedKey != null) {
                openGoalAdvice(queuedKey)
            } else {
                confirmAiRequest(getString(R.string.bia_k4_goal_action)) { requestGoalAdvice() }
            }
        }
    }

    /** Invito non invasivo: nessuna richiesta IA e nessun cambio di schermata senza il consenso dell'utente. */
    private fun showGoalAdvice(advice: NutritionPathTrigger.Advice) {
        val yes = findViewById<MaterialButton>(R.id.biaGoalAdviceYes)
        val no = findViewById<MaterialButton>(R.id.biaGoalAdviceNo)
        val text = findViewById<TextView>(R.id.biaGoalAdviceText)
        when (advice) {
            is NutritionPathTrigger.Advice.Suggest -> {
                goalAdviceQueuedKey = null
                text.setText(R.string.bia_k4_goal_title)
                yes.setText(R.string.bia_k4_goal_yes)
                yes.contentDescription = getString(R.string.bia_k4_goal_yes_cd)
                no.setText(R.string.bia_k4_goal_no)
                no.contentDescription = getString(R.string.bia_k4_goal_no_cd)
            }
            is NutritionPathTrigger.Advice.Queued -> {
                goalAdviceQueuedKey = advice.jobKey
                text.setText(R.string.bia_k4_goal_queued_title)
                yes.setText(R.string.bia_k4_goal_open)
                yes.contentDescription = null
                no.setText(R.string.bia_k4_goal_dismiss)
                no.contentDescription = null
            }
        }
        findViewById<View>(R.id.biaGoalAdviceCard).visibility = View.VISIBLE
    }

    private fun hideGoalAdvice() {
        goalAdviceQueuedKey = null
        findViewById<View>(R.id.biaGoalAdviceCard).visibility = View.GONE
    }

    private fun requestGoalAdvice() {
        lifecycleScope.launch {
            val profileId = data.activeProfileStore.currentIdOrNull()
            val key = profileId?.let { runCatching { data.nutritionPathTrigger.maybeEnqueue(it) }.getOrNull() }
            if (key == null) {
                Toast.makeText(this@BiaActivity, R.string.bia_k4_goal_start_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            openGoalAdvice(key)
        }
    }

    private fun openGoalAdvice(jobKey: String) {
        hideGoalAdvice()
        startActivity(Intent(this, NutritionPathActivity::class.java).putExtra(NutritionPathActivity.EXTRA_JOB_KEY, jobKey))
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.history.collect { history ->
                        if (pendingEditId > 0L) {
                            val found = history.firstOrNull { it.id == pendingEditId }
                            if (found != null) {
                                pendingEditId = 0L
                                editReading(found)
                            }
                        }
                        if (restoredEditId > 0L && editingMeasurement == null) {
                            history.firstOrNull { it.id == restoredEditId }?.let {
                                editingMeasurement = it
                                restoredEditId = 0L
                            }
                        }
                        if (historyContainer.visibility == View.VISIBLE) renderHistory(history)
                    }
                }
                launch {
                    viewModel.saved.collect {
                        Toast.makeText(this@BiaActivity, "Misurazione BIA aggiunta allo storico", Toast.LENGTH_SHORT).show()
                        resetForm()
                        if (finishAfterSave) {
                            finishAfterSave = false
                            leaveForm()
                        }
                    }
                }
                launch {
                    // Nuovi dati: solo un invito (o l'avviso che l'automazione attiva l'ha richiesto), mai una navigazione automatica.
                    viewModel.goalAdvice.collect { showGoalAdvice(it) }
                }
                launch {
                    viewModel.updated.collect {
                        Toast.makeText(this@BiaActivity, "Misurazione BIA aggiornata", Toast.LENGTH_SHORT).show()
                        editingMeasurement = null
                        resetForm()
                        if (finishAfterSave) {
                            finishAfterSave = false
                            leaveForm()
                        } else {
                            findViewById<SelectableSegmentView>(R.id.biaSegment).getChildAt(1)?.performClick()
                        }
                    }
                }
                launch {
                    viewModel.deleted.collect {
                        Toast.makeText(this@BiaActivity, "Misurazione BIA eliminata dallo storico", Toast.LENGTH_SHORT).show()
                    }
                }
                launch {
                    viewModel.error.collect { message ->
                        finishAfterSave = false
                        Toast.makeText(this@BiaActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }
                launch {
                    // A result saved by the job (even while this screen was closed) swaps "Analizza" for "Rivedi".
                    data.activeProfileStore.activeProfileId
                        .flatMapLatest { id -> if (id > 0) data.biaAnalysisResultRepository.all(id) else flowOf(emptyList()) }
                        .collect { if (historyContainer.visibility == View.VISIBLE) renderHistory(viewModel.history.value) }
                }
                launch {
                    // Keeps progress visible and blocks duplicate paid requests after the screen is recreated.
                    data.activeProfileStore.activeProfileId
                        .flatMapLatest { id -> if (id > 0) data.aiJobScheduler.observeActive(AiJobType.BIA_ANALYSIS, id) else flowOf(null) }
                        .collect { info ->
                            backgroundBiaAnalysisRunning = info != null
                            if (biaAnalysisJobKey == null) {
                                if (info != null) showAnalysisWait(AiJobTypeState.RUNNING, cancellable = false)
                                else analysisWaitPanel.hide()
                            }
                        }
                }
                launch {
                    // Lettura foto in corso (anche avviata da un'istanza precedente della schermata): riattacca l'osservazione.
                    data.activeProfileStore.activeProfileId
                        .flatMapLatest { id -> if (id > 0) data.aiJobScheduler.observeActive(AiJobType.BIA_IMPORT, id) else flowOf(null) }
                        .collect { info ->
                            val key = info?.let { BiaFlowPresentation.jobKeyFromTags(it.tags) }
                            val profileId = data.activeProfileStore.currentIdOrNull()
                            if (key != null && profileId != null && importJobKey == null && key != lastFinishedImportKey) {
                                observeImportJob(profileId, key)
                            }
                        }
                }
            }
        }
    }

    private fun renderHistory(history: List<BiaMeasurementEntity>) {
        historyList.removeAllViews()
        val analyzeButton = findViewById<MaterialButton>(R.id.analyzeBiaButton)
        val obsoleteNotice = findViewById<View>(R.id.biaAnalysisObsoleteNotice)
        if (history.isEmpty()) {
            historySummary.text = "Nessuna misurazione BIA salvata per questo profilo."
            historySummary.visibility = View.GONE
            findViewById<View>(R.id.historyEmptyText).visibility = View.VISIBLE
            analyzeButton.visibility = View.GONE
            reviewSavedBiaAnalysisButton.visibility = View.GONE
            obsoleteNotice.visibility = View.GONE
            return
        }
        setAiActionEnabled(analyzeButton, hasAnalysisValue(history.first()))
        historySummary.visibility = View.VISIBLE
        findViewById<View>(R.id.historyEmptyText).visibility = View.GONE
        analyzeButton.visibility = View.VISIBLE
        val latest = history.first()
        reviewSavedBiaAnalysisButton.visibility = View.GONE
        obsoleteNotice.visibility = View.GONE
        lifecycleScope.launch {
            val profileId = data.activeProfileStore.currentIdOrNull() ?: return@launch
            val saved = data.biaAnalysisResultRepository.latestForMeasurement(profileId, latest.id)
            // Una modifica dei valori dopo l'analisi la rende obsoleta: la si segnala e si propone di rifarla.
            val obsolete = saved != null && BiaAnalysisPolicy.isObsolete(saved.payloadJson, latest)
            obsoleteNotice.visibility = if (obsolete) View.VISIBLE else View.GONE
            if (saved == null || obsolete) {
                analyzeButton.setText(if (obsolete) R.string.bia_k4_obsolete_action else R.string.bia_analysis_action)
                analyzeButton.visibility = View.VISIBLE
                setAiActionEnabled(analyzeButton, hasAnalysisValue(latest))
            } else {
                analyzeButton.visibility = View.GONE
            }
            reviewSavedBiaAnalysisButton.visibility = if (saved != null) View.VISIBLE else View.GONE
        }

        historySummary.text = buildSummary(history, latest)

        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALIAN)
        history.forEach { item ->
            val dateText = dateFormat.format(Date(item.measuredAtEpochMillis))
            val valuesText = BiaHistoryText.values(item)
            val conditionsText = BiaHistoryText.conditions(item)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_card)
                elevation = resources.getDimension(R.dimen.elevation_none)
                val horizontalPadding = resources.getDimensionPixelSize(R.dimen.space_16)
                val verticalPadding = resources.getDimensionPixelSize(R.dimen.space_12)
                setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = resources.getDimensionPixelSize(R.dimen.space_8) }
                setOnClickListener { editReading(item) }
                // Data, valori e condizioni a voce; Modifica ed Elimina anche come azioni di accessibilita' (non solo pressione prolungata).
                contentDescription = BiaHistoryText.cardDescription(
                    getString(R.string.bia_k4_history_card_description), dateText, valuesText, conditionsText,
                )
                ViewCompat.addAccessibilityAction(this, getString(R.string.bia_k4_history_edit)) { _, _ ->
                    editReading(item)
                    true
                }
                ViewCompat.addAccessibilityAction(this, getString(R.string.bia_k4_history_delete)) { _, _ ->
                    confirmDelete(item)
                    true
                }
                setOnLongClickListener {
                    confirmDelete(item)
                    true
                }
            }

            card.addView(TextView(this).apply {
                text = getString(R.string.bia_k4_history_date_line, dateText)
                setTextAppearance(R.style.Text_MyFitAI_SettingsLabel)
            })
            card.addView(TextView(this).apply {
                text = valuesText
                setTextAppearance(R.style.Text_MyFitAI_SettingsDescription)
                setPadding(0, resources.getDimensionPixelSize(R.dimen.space_5), 0, 0)
            })
            conditionsText?.let { conditions ->
                card.addView(TextView(this).apply {
                    text = conditions
                    setTextAppearance(R.style.Text_MyFitAI_SettingsDescription)
                    setTextColor(getColor(R.color.text_secondary))
                    setPadding(0, resources.getDimensionPixelSize(R.dimen.space_6), 0, 0)
                })
            }
            // Azioni visibili (sostituiscono il suggerimento "Tieni premuto"), con area di tocco di almeno 48 dp.
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, resources.getDimensionPixelSize(R.dimen.space_6), 0, 0)
                addView(inlineActionButton(R.string.bia_k4_history_edit, getString(R.string.bia_k4_history_edit_cd, dateText)) { editReading(item) })
                addView(inlineActionButton(R.string.bia_k4_history_delete, getString(R.string.bia_k4_history_delete_cd, dateText)) { confirmDelete(item) })
            })
            historyList.addView(card)
        }
    }

    private fun inlineActionButton(label: Int, description: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = getString(label)
            contentDescription = description
            isAllCaps = false
            val minSize = resources.getDimensionPixelSize(R.dimen.control_min_height)
            minimumHeight = minSize
            minHeight = minSize
            minimumWidth = minSize
            setOnClickListener { onClick() }
        }

    private enum class AiJobTypeState { QUEUED, WAITING, RUNNING }

    private fun showAnalysisWait(state: AiJobTypeState, cancellable: Boolean = true) {
        val (title, message) = when (state) {
            AiJobTypeState.QUEUED -> R.string.bia_k4_analysis_queued_title to R.string.bia_k4_analysis_queued_message
            AiJobTypeState.WAITING -> R.string.bia_k4_analysis_waiting_title to R.string.bia_k4_analysis_waiting_message
            AiJobTypeState.RUNNING -> R.string.bia_k4_analysis_running_title to R.string.bia_k4_analysis_running_message
        }
        analysisWaitPanel.show(
            getString(title), getString(message),
            cancellable = cancellable && biaAnalysisJobKey != null,
            cancelDescription = getString(R.string.bia_k4_analysis_cancel_cd),
        )
    }

    private fun analyzeBiaWithAi(button: View) {
        if (biaAnalysisJobKey != null || backgroundBiaAnalysisRunning) {
            Toast.makeText(this, R.string.bia_k4_analysis_already_running, Toast.LENGTH_SHORT).show()
            return
        }
        val target = viewModel.history.value.firstOrNull()
        if (target == null || BiaAnalysisReport.measurementValues(target).isEmpty()) {
            Toast.makeText(this, "Nessuna rilevazione BIA con valori da analizzare.", Toast.LENGTH_LONG).show()
            return
        }
        confirmAiRequest("L'analisi MyFitAI Progress Coach della rilevazione BIA", getString(R.string.bia_k4_analysis_privacy)) {
            startBiaAnalysis(button, target)
        }
    }

    /**
     * The job receives only the measurement id: WorkManager Data is capped at 10 KB, so the
     * report is rebuilt from Room by the handler, which also persists the result.
     */
    private fun startBiaAnalysis(button: View, target: BiaMeasurementEntity) {
        val profileId = data.activeProfileStore.currentIdOrNull() ?: return
        val jobKey = System.currentTimeMillis().toString()
        biaAnalysisJobKey = jobKey
        analysisFailurePanel.hide()
        setAiActionEnabled(button, false)
        showAnalysisWait(AiJobTypeState.QUEUED)
        val queued = runCatching {
            data.aiJobScheduler.enqueue(
                AiJobType.BIA_ANALYSIS,
                profileId,
                jobKey,
                params = BiaAnalysisAiJobHandler.params(target.id),
            )
        }
        if (queued.isFailure) {
            finishBiaAnalysis(button)
            analysisFailurePanel.show(AiFailureMessages.couldNotStart())
            return
        }
        observeAnalysisJob(profileId, jobKey, target.id)
    }

    private fun observeAnalysisJob(profileId: Long, jobKey: String, fallbackMeasurementId: Long) {
        analysisObserver?.cancel()
        analysisObserver = lifecycleScope.launch {
            data.aiJobScheduler.observe(AiJobType.BIA_ANALYSIS, profileId, jobKey)
                .transformWhile { info ->
                    emit(info)
                    info?.state?.isFinished != true
                }
                .collect { info ->
                    val button = findViewById<View>(R.id.analyzeBiaButton)
                    when (info?.state) {
                        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> showAnalysisWait(AiJobTypeState.WAITING)
                        WorkInfo.State.RUNNING -> showAnalysisWait(AiJobTypeState.RUNNING)
                        WorkInfo.State.SUCCEEDED -> {
                            finishBiaAnalysis(button)
                            val measurementId = info.outputData.getLong(BiaAnalysisAiJobHandler.KEY_MEASUREMENT_ID, fallbackMeasurementId)
                            showSavedBiaAnalysis(profileId, measurementId, saved = false)
                        }
                        WorkInfo.State.CANCELLED -> {
                            finishBiaAnalysis(button)
                            analysisFailurePanel.show(AiFailureMessages.forFailure(AiFailure.Cancelled))
                        }
                        WorkInfo.State.FAILED -> {
                            finishBiaAnalysis(button)
                            // Un'analisi fallita non salva nulla: la frase "nessun dato e' stato modificato" della tabella e' vera anche qui.
                            val raw = BiaFlowPresentation.analysisError(info.outputData.getString(AiJobWorker.KEY_ERROR))
                            analysisFailurePanel.show(AiFailureMessages.resolveForBiaAnalysis(raw))
                        }
                        null -> Unit
                    }
                }
        }
    }

    private fun cancelAnalysis() {
        val profileId = data.activeProfileStore.currentIdOrNull() ?: return
        val key = biaAnalysisJobKey ?: return
        data.aiJobScheduler.cancel(AiJobType.BIA_ANALYSIS, profileId, key)
    }

    private fun finishBiaAnalysis(button: View) {
        biaAnalysisJobKey = null
        analysisWaitPanel.hide()
        setAiActionEnabled(button)
    }

    /** Opens the analysis persisted by the job; the Activity never owns the only copy of the result. */
    private fun showSavedBiaAnalysis(profileId: Long, measurementId: Long, saved: Boolean) {
        lifecycleScope.launch {
            val result = data.biaAnalysisResultRepository.latestForMeasurement(profileId, measurementId)
            val payload = result?.let { runCatching { org.json.JSONObject(it.payloadJson) }.getOrNull() }
            if (result == null || payload == null) {
                Toast.makeText(this@BiaActivity, "Analisi non disponibile: il risultato salvato non è leggibile.", Toast.LENGTH_LONG).show()
                return@launch
            }
            val measurement = viewModel.history.value.firstOrNull { it.id == measurementId }
            val obsolete = measurement != null && BiaAnalysisPolicy.isObsolete(result.payloadJson, measurement)
            if (!isFinishing && !isDestroyed) showBiaAnalysisDialog(payload, saved, obsolete)
        }
    }

    /** Navigation entry "Analizza ultima BIA": waits for the history instead of racing a fixed delay. */
    private fun analyzeLatestWhenReady() {
        lifecycleScope.launch {
            val history = withTimeoutOrNull(HISTORY_LOAD_TIMEOUT_MS) { viewModel.history.first { it.isNotEmpty() } }
            if (history == null) {
                Toast.makeText(this@BiaActivity, "Nessuna rilevazione BIA da analizzare.", Toast.LENGTH_LONG).show()
                return@launch
            }
            lifecycle.withResumed { analyzeBiaWithAi(findViewById(R.id.analyzeBiaButton)) }
        }
    }

    private fun reviewLatestSavedAnalysis() {
        val profileId = data.activeProfileStore.currentIdOrNull() ?: return
        val measurementId = viewModel.history.value.firstOrNull()?.id ?: return
        showSavedBiaAnalysis(profileId, measurementId, saved = true)
    }

    /**
     * Analisi come contenuto scorrevole: ogni sezione ha un titolo che funge da intestazione per TalkBack, il titolo
     * della finestra e' lo stesso del pulsante ("Analisi nutrizionale BIA") e, se i valori sono cambiati dopo l'analisi, un avviso in testa.
     */
    private fun showBiaAnalysisDialog(payload: org.json.JSONObject, saved: Boolean = false, obsolete: Boolean = false) {
        if (isFinishing || isDestroyed) return
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(resources.getDimensionPixelSize(R.dimen.space_16), 0, resources.getDimensionPixelSize(R.dimen.space_16), resources.getDimensionPixelSize(R.dimen.space_8))
        }
        if (obsolete) {
            content.addView(TextView(this).apply {
                text = getString(R.string.bia_k4_obsolete_notice)
                setTextAppearance(R.style.Text_MyFitAI_Body)
                setBackgroundResource(R.drawable.bg_error_soft)
                val pad = resources.getDimensionPixelSize(R.dimen.space_12)
                setPadding(pad, pad, pad, pad)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = resources.getDimensionPixelSize(R.dimen.space_8)
            })
        }
        fun section(title: String, body: String, emphasis: Boolean = false) {
            if (body.isBlank() || body == "?") return
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(getColor(R.color.white))
                setPadding(0, resources.getDimensionPixelSize(R.dimen.space_8), 0, resources.getDimensionPixelSize(R.dimen.space_8))
            }
            // The title is a heading for TalkBack navigation; children follow the visual (and reading) order.
            box.addView(TextView(this).apply {
                text = title
                setTextAppearance(if (emphasis) R.style.Text_MyFitAI_CardTitle else R.style.Text_MyFitAI_SettingsLabel)
                ViewCompat.setAccessibilityHeading(this, true)
            })
            box.addView(TextView(this).apply {
                text = body
                setTextAppearance(R.style.Text_MyFitAI_SettingsDescription)
                setPadding(0, resources.getDimensionPixelSize(R.dimen.space_4), 0, 0)
            })
            content.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val safetyNote = BiaAnalysisPresentation.safetyNoteToShow(payload.optString("safetyNote")).orEmpty()
        // Shown only when the model wrote a real note (mandatory only with SAFETY_FLAG): empty, `?`, "nessuna nota di sicurezza" and the
        // fixed sentence saved before T3 are not notes.
        val hasSafetyNote = safetyNote.isNotBlank()
        val safetyFlagActive = payload.optBoolean("safetyFlagActive", false)
        // Classification as an Italian label; the verdict is hidden when empty, a code or the classification again.
        section(getString(R.string.bia_analysis_section_verdict), BiaAnalysisPresentation.summaryText(payload.optString("classification"), payload.optString("verdict")), true)
        // With the safety flag the note comes right after the summary; otherwise it closes the analysis.
        if (hasSafetyNote && safetyFlagActive) section(getString(R.string.bia_analysis_section_safety), safetyNote, true)
        // Reliability is written in words (Alta / Media / Bassa), never conveyed by colour.
        section(
            getString(R.string.bia_analysis_section_reliability),
            listOf(
                getString(reliabilityLabelRes(payload.optString("reliability"))),
                BiaAnalysisPresentation.displayText(payload.optString("reliabilityReason")).takeIf { it.isNotBlank() && it != "?" },
            ).filterNotNull().joinToString(". "),
        )
        section(getString(R.string.bia_analysis_section_happening), BiaAnalysisPresentation.displayText(payload.optString("whatIsHappening")))
        // The fat-loss text is an estimate (noise-limited), never a measured fact.
        section(getString(R.string.bia_analysis_section_fat_loss), BiaAnalysisPresentation.displayText(payload.optString("fatLoss")))
        // Calories and macros are written by the app (`appCalories`); the model only adds the qualitative comment. Results saved
        // before T4 have no `appCalories`: they keep showing the model text as before.
        section(
            getString(R.string.bia_analysis_section_objective),
            listOf(payload.optString("objective"), payload.optString("appCalories"), payload.optString("caloriesAndMacros"))
                .map { BiaAnalysisPresentation.displayText(it.trim()) }.filter { it.isNotBlank() && it != "?" }.joinToString("\n"),
        )
        val comparisons = jsonObjects(payload, "comparisons").joinToString("\n\n") { row ->
            val indicator = row.optString("indicator")
            val text = BiaAnalysisPresentation::displayText
            "${BiaAnalysisPresentation.indicatorLabel(indicator)}: da ${text(row.optString("previous"))} a ${text(row.optString("current"))} " +
                "(${BiaAnalysisPresentation.comparisonDifference(indicator, row.optString("difference"))})\n${text(row.optString("interpretation"))}"
        }
        section(getString(R.string.bia_analysis_section_comparisons), comparisons)
        val scenarios = jsonObjects(payload, "scenarios").joinToString("\n\n") { row ->
            getString(
                R.string.bia_analysis_scenario_line,
                row.optString("label"), row.optString("targetBodyFat"), row.optString("targetWeight"), row.optString("fatToLose"),
                // Results saved before T5 can carry the reliability glued to the comment (`...|MODERATE`): the stored text is left as is.
                BiaAnalysisPresentation.scenarioComment(row.optString("assumption")),
            )
        }
        section(getString(R.string.bia_analysis_section_scenarios), scenarios)
        section(getString(R.string.bia_analysis_section_positives), BiaAnalysisPresentation.displayItems(jsonLines(payload, "positives")).joinToString("\n") { "• $it" })
        section(getString(R.string.bia_analysis_section_monitor), BiaAnalysisPresentation.displayItems(jsonLines(payload, "monitor")).joinToString("\n") { "• $it" })
        val actions = jsonLines(payload, "actions").filter { it.isNotBlank() }.map(BiaAnalysisPresentation::displayText)
        section(
            getString(actionsTitleRes(payload.optString("classification"), actions.size)),
            actions.mapIndexed { index, value -> "${index + 1}. $value" }.joinToString("\n"),
            true,
        )
        section(getString(R.string.bia_analysis_section_next_check), BiaAnalysisPresentation.displayText(payload.optString("nextCheck")))
        if (hasSafetyNote && !safetyFlagActive) section(getString(R.string.bia_analysis_section_safety), safetyNote)
        section(getString(R.string.bia_analysis_section_question), BiaAnalysisPresentation.displayText(payload.optString("question")))
        val source = listOf(payload.optString("provider"), payload.optString("model")).filter { it.isNotBlank() && it != "-" }.joinToString(" · ")
        section(
            getString(if (saved) R.string.bia_analysis_section_saved else R.string.bia_analysis_section_saved_locally),
            if (source.isBlank()) getString(R.string.bia_analysis_offline) else "$source\n${getString(R.string.bia_analysis_offline)}",
        )
        val scroll = androidx.core.widget.NestedScrollView(this).apply { addView(content) }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bia_analysis_dialog_title)
            .setView(normalizeRuntimeDialogContent(scroll))
            .setPositiveButton(R.string.bia_analysis_close, null)
        if (obsolete) {
            builder.setNeutralButton(R.string.bia_k4_obsolete_action) { _, _ -> analyzeBiaWithAi(findViewById(R.id.analyzeBiaButton)) }
        }
        builder.show()
    }

    private fun reliabilityLabelRes(raw: String): Int = when (BiaAnalysisPresentation.reliabilityLabel(raw)) {
        BiaAnalysisPresentation.ReliabilityLabel.HIGH -> R.string.bia_reliability_high
        BiaAnalysisPresentation.ReliabilityLabel.MEDIUM -> R.string.bia_reliability_medium
        BiaAnalysisPresentation.ReliabilityLabel.LOW -> R.string.bia_reliability_low
        BiaAnalysisPresentation.ReliabilityLabel.UNKNOWN -> R.string.bia_reliability_unknown
    }

    private fun actionsTitleRes(classification: String, actionCount: Int): Int = when (BiaAnalysisPresentation.actionsTitle(classification, actionCount)) {
        BiaAnalysisPresentation.ActionsTitle.THREE_PRIORITY -> R.string.bia_analysis_section_actions_three
        BiaAnalysisPresentation.ActionsTitle.DO_NOW -> R.string.bia_analysis_section_actions_now
        BiaAnalysisPresentation.ActionsTitle.DATA_TO_COLLECT -> R.string.bia_analysis_section_actions_data
    }

    private fun jsonLines(payload: org.json.JSONObject, key: String): List<String> {
        val values = payload.optJSONArray(key) ?: return emptyList()
        return buildList { for (index in 0 until values.length()) add(values.optString(index)) }
    }

    private fun jsonObjects(payload: org.json.JSONObject, key: String): List<org.json.JSONObject> {
        val values = payload.optJSONArray(key) ?: return emptyList()
        return buildList { for (index in 0 until values.length()) add(values.optJSONObject(index) ?: return@buildList) }
    }

    private fun hasAnalysisValue(item: BiaMeasurementEntity): Boolean = listOf(
        item.weightKg,
        item.bodyFatPercent,
        item.muscleMassKg,
        item.skeletalMuscleKg,
    ).any { it != null }

    private fun buildSummary(history: List<BiaMeasurementEntity>, latest: BiaMeasurementEntity): String {
        val lines = mutableListOf("${history.size} misurazioni salvate")
        latest.weightKg?.let { current ->
            val previous = previousValue(history, latest) { it.weightKg }
            val delta = previous?.let { current - it }
            lines += "Peso attuale ${formatValue(current, "kg")}${delta?.let { "  (${formatSigned(it)} kg vs precedente disponibile)" }.orEmpty()}"
        }
        latest.bodyFatPercent?.let { current ->
            val previous = previousValue(history, latest) { it.bodyFatPercent }
            val delta = previous?.let { current - it }
            lines += "Grasso ${formatValue(current, "%")}${delta?.let { "  (${formatSigned(it)} pp vs precedente disponibile)" }.orEmpty()}"
        }
        latest.muscleMassKg?.let { current ->
            val previous = previousValue(history, latest) { it.muscleMassKg }
            val delta = previous?.let { current - it }
            lines += "Massa muscolare ${formatValue(current, "kg")}${delta?.let { "  (${formatSigned(it)} kg)" }.orEmpty()}"
        }
        val avgWeight = history.mapNotNull { it.weightKg }.takeIf { it.isNotEmpty() }?.average()
        if (avgWeight != null) lines += "Media peso storico ${String.format(Locale.ITALIAN, "%.1f kg", avgWeight)}"
        return lines.joinToString("\n")
    }

    private fun previousValue(
        history: List<BiaMeasurementEntity>,
        current: BiaMeasurementEntity,
        selector: (BiaMeasurementEntity) -> Float?,
    ): Float? {
        val index = history.indexOfFirst { it.id == current.id }
        if (index < 0) return null
        return history.drop(index + 1).firstNotNullOfOrNull(selector)
    }

    private fun editReading(item: BiaMeasurementEntity) {
        editingMeasurement = item
        restoredEditId = 0L
        pendingImportNotes = null
        BiaFormRules.valuesOf(item).forEach { (field, value) -> values[field] = value }
        val date = Calendar.getInstance().apply { timeInMillis = item.measuredAtEpochMillis }
        selectedDateMillis = item.measuredAtEpochMillis
        selectedHour = date.get(Calendar.HOUR_OF_DAY)
        selectedMinute = date.get(Calendar.MINUTE)
        setConditions(item.fasting, item.justWokeUp, item.afterBathroom, item.noRecentWorkout)
        formErrorText.visibility = View.GONE
        bindMeasurementRows()
        renderDateTime()
        setSaveLabel(editing = true)
        baseline = currentSnapshot()
        openingExistingForEdit = true
        findViewById<SelectableSegmentView>(R.id.biaSegment).getChildAt(0)?.performClick()
        openingExistingForEdit = false
    }

    private fun setSaveLabel(editing: Boolean) {
        val label = if (editing) "Salva modifiche" else "Salva"
        findViewById<TextView>(R.id.saveButton).apply {
            text = label
            contentDescription = label
        }
    }

    private fun confirmDelete(item: BiaMeasurementEntity) {
        val dateText = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALIAN).format(Date(item.measuredAtEpochMillis))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bia_k4_delete_title)
            .setMessage(getString(R.string.bia_k4_delete_message, dateText, BiaHistoryText.mainValues(item)))
            .setNegativeButton(R.string.bia_k4_cancel, null)
            .setPositiveButton(R.string.bia_k4_history_delete) { _, _ -> viewModel.delete(item) }
            .show()
    }

    private fun resetForm() {
        editingMeasurement = null
        restoredEditId = 0L
        pendingImportNotes = null
        setSaveLabel(editing = false)
        values.keys.forEach { values[it] = null }
        bindMeasurementRows()
        selectedDateMillis = System.currentTimeMillis()
        val now = Calendar.getInstance()
        selectedHour = now.get(Calendar.HOUR_OF_DAY)
        selectedMinute = now.get(Calendar.MINUTE)
        setConditions(fasting = false, justWoken = false, afterBathroom = false, noWorkout = false)
        formErrorText.visibility = View.GONE
        dateInputLayout.error = null
        renderDateTime()
        baseline = currentSnapshot()
    }

    private fun renderDateTime() {
        val formattedDate = SimpleDateFormat("dd/MM/yyyy", Locale.ITALIAN).format(Date(selectedDateMillis))
        dateInput.setText(formattedDate)
        dateInput.contentDescription = getString(R.string.bia_k4_date_label, formattedDate)
        timeInput.setText(String.format(Locale.ITALIAN, "%02d:%02d", selectedHour, selectedMinute))
    }

    private fun composeMeasurementMillis(): Long {
        val base = Calendar.getInstance().apply { timeInMillis = selectedDateMillis }
        val calendar = Calendar.getInstance().apply {
            set(Calendar.YEAR, base.get(Calendar.YEAR))
            set(Calendar.MONTH, base.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, base.get(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, selectedHour)
            set(Calendar.MINUTE, selectedMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    private fun formatValue(value: Float, unit: String): String = if (unit.isBlank()) {
        BiaHistoryText.number(value)
    } else {
        "${BiaHistoryText.number(value)} $unit"
    }

    private fun formatSigned(value: Float): String = String.format(Locale.ITALIAN, "%+.1f", value)

    companion object {
        const val EXTRA_OPEN_HISTORY = "open_bia_history"
        const val EXTRA_ANALYZE_LATEST = "analyze_latest_bia"
        private const val HISTORY_LOAD_TIMEOUT_MS = 5_000L
        const val EXTRA_EDIT_ID = "edit_bia_measurement_id"
        const val EXTRA_AI_JOB_KEY = "bia_ai_job_key"
        private const val CONDITION_COUNT = 4
        private const val STATE_VALUES = "bia_state_values"
        private const val STATE_DATE = "bia_state_date"
        private const val STATE_HOUR = "bia_state_hour"
        private const val STATE_MINUTE = "bia_state_minute"
        private const val STATE_HAS_BASELINE = "bia_state_has_baseline"
        private const val STATE_BASE_VALUES = "bia_state_base_values"
        private const val STATE_BASE_AT = "bia_state_base_at"
        private const val STATE_BASE_CONDITIONS = "bia_state_base_conditions"
        private const val STATE_EDIT_ID = "bia_state_edit_id"
        private const val STATE_NOTES = "bia_state_notes"
        private const val STATE_IMPORT_PAYLOAD = "bia_state_import_payload"
        private const val STATE_IMPORT_JOB = "bia_state_import_job"
        private const val STATE_IMPORT_IMAGE = "bia_state_import_image"
        private const val STATE_ANALYSIS_JOB = "bia_state_analysis_job"
        private const val STATE_TAB = "bia_state_tab"
    }
}
