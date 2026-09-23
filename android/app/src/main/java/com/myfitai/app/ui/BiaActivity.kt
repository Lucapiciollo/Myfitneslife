package com.myfitai.app.ui

import android.app.AlertDialog
import android.graphics.Typeface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.domain.body.AiImageProcessor
import com.myfitai.app.domain.body.AiImageTempStore
import com.myfitai.app.domain.body.BiaImportContract
import com.myfitai.app.domain.ai.AiJobType
import com.myfitai.app.domain.body.BiaAnalysisAiJobHandler
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.bia.BiaViewModel
import com.myfitai.app.ui.widgets.MeasurementRowView
import com.myfitai.app.ui.widgets.SelectableSegmentView
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BiaActivity : BaseShellActivity() {

    private val data by lazy { AppDataContainer.get(this) }
    private val viewModel: BiaViewModel by viewModels {
        BiaViewModel.Factory(data.biaRepository, data.activeProfileStore, data.nutritionPathTrigger)
    }

    private lateinit var dateInput: TextInputEditText
    private lateinit var dateInputLayout: TextInputLayout
    private lateinit var timeInput: TextInputEditText
    private lateinit var newMeasurementContainer: View
    private lateinit var historyContainer: View
    private lateinit var historyList: LinearLayout
    private lateinit var historySummary: TextView

    private val values = linkedMapOf<String, Float?>(
        KEY_WEIGHT to null,
        KEY_BODY_FAT to null,
        KEY_VISCERAL_FAT to null,
        KEY_MUSCLE_MASS to null,
        KEY_SKELETAL_MUSCLE to null,
        KEY_BODY_WATER to null,
        KEY_BMR to null,
        KEY_FAT_MASS to null,
        KEY_LEAN_MASS to null,
        KEY_BODY_WATER_KG to null,
        KEY_SUBCUTANEOUS_FAT to null,
        KEY_BONE_MASS to null,
        KEY_PROTEIN_PERCENT to null,
        KEY_PROTEIN_KG to null,
        KEY_BODY_AGE to null,
        KEY_BMI to null,
    )

    private var selectedDateMillis: Long = System.currentTimeMillis()
    private var selectedHour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    private var selectedMinute: Int = Calendar.getInstance().get(Calendar.MINUTE)
    private var pendingImportFile: File? = null
    private var editingMeasurement: BiaMeasurementEntity? = null
    private var pendingEditId: Long = 0L
    private var openingExistingForEdit = false
    private val imageTempStore by lazy { AiImageTempStore(this) }

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
        bindBottom(BottomNavBinder.Tab.MORE)
        bindBack()
        bindViews()
        normalizeBiaCards()
        bindSegments()
        findViewById<View>(R.id.addBiaFromHistoryButton).setOnClickListener {
            resetForm()
            findViewById<SelectableSegmentView>(R.id.biaSegment).getChildAt(0)?.performClick()
        }
        bindDateTime()
        bindHelpCards()
        bindMeasurementRows()
        bindSave()
        bindPhotoImport()
        findViewById<View>(R.id.analyzeBiaButton).setOnClickListener { analyzeBiaWithAi(it) }
        pendingEditId = intent.getLongExtra(EXTRA_EDIT_ID, 0L)
        observeState()
        renderDateTime()
        if (pendingEditId > 0L) loadPendingEdit()
        if (intent.getBooleanExtra(EXTRA_OPEN_HISTORY, false) && pendingEditId == 0L) {
            findViewById<SelectableSegmentView>(R.id.biaSegment).getChildAt(1)?.performClick()
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
    }

    private fun normalizeBiaCards() {
        listOf(R.id.biaDateCard, R.id.biaConditionsCard, R.id.biaResultsCard).forEach { id ->
            findViewById<MaterialCardView>(id).apply {
                setCardBackgroundColor(getColor(R.color.white))
                strokeColor = getColor(R.color.divider)
                strokeWidth = dp(1)
                cardElevation = 0f
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
            if (index == 0 && editingMeasurement != null && !openingExistingForEdit) resetForm()
            newMeasurementContainer.visibility = if (index == 0) View.VISIBLE else View.GONE
            historyContainer.visibility = if (index == 1) View.VISIBLE else View.GONE
            if (index == 1) renderHistory(viewModel.history.value)
        }
    }

    private fun bindDateTime() {
        dateInput.setOnClickListener {
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Data misurazione")
                .setSelection(selectedDateMillis)
                .build()
            picker.addOnPositiveButtonClickListener { selection ->
                selectedDateMillis = selection
                renderDateTime()
            }
            picker.show(supportFragmentManager, "bia_date_picker")
        }
        dateInputLayout.setEndIconOnClickListener { dateInput.performClick() }

        timeInput.setOnClickListener {
            val picker = MaterialTimePicker.Builder()
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
        bindRow(R.id.rowWeight, KEY_WEIGHT, "Peso", "kg")
        bindRow(R.id.rowBodyFat, KEY_BODY_FAT, "Grasso corporeo", "%")
        bindRow(R.id.rowVisceralFat, KEY_VISCERAL_FAT, "Grasso viscerale", "")
        bindRow(R.id.rowMuscleMass, KEY_MUSCLE_MASS, "Massa muscolare", "kg")
        bindRow(R.id.rowSkeletalMuscle, KEY_SKELETAL_MUSCLE, "Muscolo scheletrico", "kg")
        bindRow(R.id.rowBodyWater, KEY_BODY_WATER, "Acqua corporea", "%")
        bindRow(R.id.rowBmr, KEY_BMR, "BMR", "kcal")
        bindRow(R.id.rowFatMass, KEY_FAT_MASS, "Massa grassa", "kg")
        bindRow(R.id.rowLeanMass, KEY_LEAN_MASS, "Massa magra", "kg")
        bindRow(R.id.rowBodyWaterKg, KEY_BODY_WATER_KG, "Acqua corporea", "kg")
        bindRow(R.id.rowSubcutaneousFat, KEY_SUBCUTANEOUS_FAT, "Grasso sottocutaneo", "%")
        bindRow(R.id.rowBoneMass, KEY_BONE_MASS, "Massa ossea", "kg")
        bindRow(R.id.rowProteinPercent, KEY_PROTEIN_PERCENT, "Proteine", "%")
        bindRow(R.id.rowProteinKg, KEY_PROTEIN_KG, "Proteine", "kg")
        bindRow(R.id.rowBodyAge, KEY_BODY_AGE, "Età corporea", "anni")
        bindRow(R.id.rowBmi, KEY_BMI, "BMI", "")
    }

    private fun bindRow(viewId: Int, key: String, label: String, unit: String) {
        findViewById<MeasurementRowView>(viewId).apply {
            showIcon()
            setLabel(label)
            setValue(values[key]?.let { formatValue(it, unit) } ?: "—")
            isClickable = true
            isFocusable = true
            setOnClickListener { showValueDialog(key, label, unit, this) }
        }
    }

    private fun showValueDialog(key: String, label: String, unit: String, row: MeasurementRowView) {
        val content = layoutInflater.inflate(R.layout.dialog_standard_value_input, null, false)
        val inputLayout = content.findViewById<TextInputLayout>(R.id.valueInputLayout)
        val input = content.findViewById<TextInputEditText>(R.id.valueInput)

        inputLayout.hint = if (unit.isBlank()) label else "$label ($unit)"
        values[key]?.let { input.setText(formatNumber(it)) }
        input.setSelectAllOnFocus(true)

        MaterialAlertDialogBuilder(this)
            .setTitle(label)
            .setView(content)
            .setNegativeButton("Annulla", null)
            .setNeutralButton("Svuota") { _, _ ->
                values[key] = null
                row.setValue("—")
            }
            .setPositiveButton("Conferma") { _, _ ->
                val value = parseFloat(input.text?.toString())
                values[key] = value
                row.setValue(value?.let { formatValue(it, unit) } ?: "—")
            }
            .show()
    }

    private fun bindSave() {
        findViewById<View>(R.id.saveButton).setOnClickListener {
            val original = editingMeasurement
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
            if (original == null) {
                viewModel.save(
                    measuredAtEpochMillis = measuredAt,
                    weightKg = values[KEY_WEIGHT],
                    bodyFatPercent = values[KEY_BODY_FAT],
                    visceralFatLevel = values[KEY_VISCERAL_FAT],
                    muscleMassKg = values[KEY_MUSCLE_MASS],
                    skeletalMuscleKg = values[KEY_SKELETAL_MUSCLE],
                    bodyWaterPercent = values[KEY_BODY_WATER],
                    bmrKcal = values[KEY_BMR],
                    fatMassKg = values[KEY_FAT_MASS],
                    leanMassKg = values[KEY_LEAN_MASS],
                    bodyWaterKg = values[KEY_BODY_WATER_KG],
                    subcutaneousFatPercent = values[KEY_SUBCUTANEOUS_FAT],
                    boneMassKg = values[KEY_BONE_MASS],
                    proteinPercent = values[KEY_PROTEIN_PERCENT],
                    proteinKg = values[KEY_PROTEIN_KG],
                    bodyAgeYears = values[KEY_BODY_AGE]?.toInt(),
                    bmi = values[KEY_BMI],
                    fasting = findViewById<MaterialCheckBox>(R.id.checkFasting).isChecked,
                    justWokeUp = findViewById<MaterialCheckBox>(R.id.checkJustWoken).isChecked,
                    afterBathroom = findViewById<MaterialCheckBox>(R.id.checkAfterShower).isChecked,
                    noRecentWorkout = findViewById<MaterialCheckBox>(R.id.checkNoWorkout).isChecked,
                )
            } else {
                viewModel.update(
                    original = original,
                    measuredAtEpochMillis = measuredAt,
                    weightKg = values[KEY_WEIGHT],
                    bodyFatPercent = values[KEY_BODY_FAT],
                    visceralFatLevel = values[KEY_VISCERAL_FAT],
                    muscleMassKg = values[KEY_MUSCLE_MASS],
                    skeletalMuscleKg = values[KEY_SKELETAL_MUSCLE],
                    bodyWaterPercent = values[KEY_BODY_WATER],
                    bmrKcal = values[KEY_BMR],
                    fatMassKg = values[KEY_FAT_MASS],
                    leanMassKg = values[KEY_LEAN_MASS],
                    bodyWaterKg = values[KEY_BODY_WATER_KG],
                    subcutaneousFatPercent = values[KEY_SUBCUTANEOUS_FAT],
                    boneMassKg = values[KEY_BONE_MASS],
                    proteinPercent = values[KEY_PROTEIN_PERCENT],
                    proteinKg = values[KEY_PROTEIN_KG],
                    bodyAgeYears = values[KEY_BODY_AGE]?.toInt(),
                    bmi = values[KEY_BMI],
                    fasting = findViewById<MaterialCheckBox>(R.id.checkFasting).isChecked,
                    justWokeUp = findViewById<MaterialCheckBox>(R.id.checkJustWoken).isChecked,
                    afterBathroom = findViewById<MaterialCheckBox>(R.id.checkAfterShower).isChecked,
                    noRecentWorkout = findViewById<MaterialCheckBox>(R.id.checkNoWorkout).isChecked,
                )
            }
        }
    }

    private fun bindPhotoImport() {
        findViewById<View>(R.id.importPhotoButton).setOnClickListener {
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
    }

    private fun processImportUri(uri: Uri) {
        showImportStatus("Preparazione della foto…")
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { AiImageProcessor.fromUri(this@BiaActivity, uri) } }
                .onSuccess { importImage(it) }
                .onFailure { showImportStatus("Impossibile leggere la foto") }
        }
    }

    private fun processImportFile(file: File) {
        showImportStatus("Preparazione della foto…")
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { AiImageProcessor.fromFile(file) } }
                .onSuccess { importImage(it) }
                .onFailure { showImportStatus("Impossibile leggere la foto") }
                .also { withContext(Dispatchers.IO) { imageTempStore.delete(file) } }
        }
    }

    private fun importImage(image: com.myfitai.app.ai.AiImageInput) {
        confirmAiRequest("La lettura IA dei valori BIA dalla foto") {
            showImportStatus("Lettura BIA in corso…")
            lifecycleScope.launch {
                val profileId = data.activeProfileStore.currentIdOrNull()
                if (profileId == null) {
                    Toast.makeText(this@BiaActivity, "Nessun profilo attivo", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val key = System.currentTimeMillis().toString()
                data.aiJobScheduler.enqueue(com.myfitai.app.domain.ai.AiJobType.BIA_IMPORT, profileId, key, params = androidx.work.Data.Builder().putString(com.myfitai.app.domain.ai.AiJobWorker.KEY_IMAGE_PATH, data.aiImageJobStore.write(image)).build())
                data.aiJobScheduler.observe(com.myfitai.app.domain.ai.AiJobType.BIA_IMPORT, profileId, key).collect { info ->
                    when (info?.state) {
                        androidx.work.WorkInfo.State.SUCCEEDED -> {
                            showImportStatus("Importazione completata: controlla i valori prima di salvarli.")
                            val p = org.json.JSONObject(info.outputData.getString(com.myfitai.app.domain.body.BiaImportAiJobHandler.KEY_PAYLOAD).orEmpty())
                            showImportPreview(
                                BiaImportContract.Preview(
                                    isBiaDocument = true,
                                    rejectionReason = "",
                                    measuredAtEpochMillis = p.optLong("measuredAtEpochMillis").takeIf { it > 0 },
                                    weightKg = p.floatOrNull("weightKg"),
                                    bodyFatPercent = p.floatOrNull("bodyFatPercent"),
                                    visceralFatLevel = p.floatOrNull("visceralFatLevel"),
                                    muscleMassKg = p.floatOrNull("muscleMassKg"),
                                    skeletalMuscleKg = p.floatOrNull("skeletalMuscleKg"),
                                    bodyWaterPercent = p.floatOrNull("bodyWaterPercent"),
                                    bmrKcal = p.floatOrNull("bmrKcal"),
                                    confidence = p.getString("confidence"),
                                    notes = p.getString("notes"),
                                    fatMassKg = p.floatOrNull("fatMassKg"),
                                    leanMassKg = p.floatOrNull("leanMassKg"),
                                    bodyWaterKg = p.floatOrNull("bodyWaterKg"),
                                    subcutaneousFatPercent = p.floatOrNull("subcutaneousFatPercent"),
                                    boneMassKg = p.floatOrNull("boneMassKg"),
                                    proteinPercent = p.floatOrNull("proteinPercent"),
                                    proteinKg = p.floatOrNull("proteinKg"),
                                    bodyAgeYears = p.floatOrNull("bodyAgeYears"),
                                    bmi = p.floatOrNull("bmi"),
                                ),
                                p.getString("provider"),
                                p.getString("model"),
                            )
                        }
                        androidx.work.WorkInfo.State.FAILED,
                        androidx.work.WorkInfo.State.CANCELLED -> {
                            val message = info.outputData.getString("error") ?: "Impossibile leggere i valori BIA dalla foto"
                            showImportStatus(message)
                        }
                        androidx.work.WorkInfo.State.ENQUEUED,
                        androidx.work.WorkInfo.State.RUNNING,
                        androidx.work.WorkInfo.State.BLOCKED -> showImportStatus("Lettura BIA in corso…")
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun showImportStatus(message: String) {
        findViewById<TextView>(R.id.importStatusText)?.apply {
            text = message
            visibility = View.VISIBLE
        }
    }

    private fun showImportPreview(preview: BiaImportContract.Preview, provider: String, model: String) {
        val content = layoutInflater.inflate(R.layout.dialog_bia_import_preview, null, false)
        val scroll = content.findViewById<androidx.core.widget.NestedScrollView>(R.id.biaImportScroll)
        val meta = content.findViewById<TextView>(R.id.biaImportMeta)
        val date = content.findViewById<TextView>(R.id.biaImportDate)

        val inputs = mapOf(
            KEY_WEIGHT to content.findViewById<TextInputEditText>(R.id.biaImportWeight),
            KEY_BODY_FAT to content.findViewById<TextInputEditText>(R.id.biaImportBodyFat),
            KEY_VISCERAL_FAT to content.findViewById<TextInputEditText>(R.id.biaImportVisceralFat),
            KEY_MUSCLE_MASS to content.findViewById<TextInputEditText>(R.id.biaImportMuscleMass),
            KEY_SKELETAL_MUSCLE to content.findViewById<TextInputEditText>(R.id.biaImportSkeletalMuscle),
            KEY_BODY_WATER to content.findViewById<TextInputEditText>(R.id.biaImportBodyWater),
            KEY_BMR to content.findViewById<TextInputEditText>(R.id.biaImportBmr),
            KEY_FAT_MASS to content.findViewById<TextInputEditText>(R.id.biaImportFatMass),
            KEY_LEAN_MASS to content.findViewById<TextInputEditText>(R.id.biaImportLeanMass),
            KEY_BODY_WATER_KG to content.findViewById<TextInputEditText>(R.id.biaImportBodyWaterKg),
            KEY_SUBCUTANEOUS_FAT to content.findViewById<TextInputEditText>(R.id.biaImportSubcutaneousFat),
            KEY_BONE_MASS to content.findViewById<TextInputEditText>(R.id.biaImportBoneMass),
            KEY_PROTEIN_PERCENT to content.findViewById<TextInputEditText>(R.id.biaImportProteinPercent),
            KEY_PROTEIN_KG to content.findViewById<TextInputEditText>(R.id.biaImportProteinKg),
            KEY_BODY_AGE to content.findViewById<TextInputEditText>(R.id.biaImportBodyAge),
            KEY_BMI to content.findViewById<TextInputEditText>(R.id.biaImportBmi),
        )

        inputs.getValue(KEY_WEIGHT).setText(preview.weightKg?.let(::formatNumber).orEmpty())
        inputs.getValue(KEY_BODY_FAT).setText(preview.bodyFatPercent?.let(::formatNumber).orEmpty())
        inputs.getValue(KEY_VISCERAL_FAT).setText(preview.visceralFatLevel?.let(::formatNumber).orEmpty())
        inputs.getValue(KEY_MUSCLE_MASS).setText(preview.muscleMassKg?.let(::formatNumber).orEmpty())
        inputs.getValue(KEY_SKELETAL_MUSCLE).setText(preview.skeletalMuscleKg?.let(::formatNumber).orEmpty())
        inputs.getValue(KEY_BODY_WATER).setText(preview.bodyWaterPercent?.let(::formatNumber).orEmpty())
        inputs.getValue(KEY_BMR).setText(preview.bmrKcal?.let(::formatNumber).orEmpty())
        inputs.getValue(KEY_FAT_MASS).setText(preview.fatMassKg?.let(::formatNumber).orEmpty())
        inputs.getValue(KEY_LEAN_MASS).setText(preview.leanMassKg?.let(::formatNumber).orEmpty())
        inputs.getValue(KEY_BODY_WATER_KG).setText(preview.bodyWaterKg?.let(::formatNumber).orEmpty())
        inputs.getValue(KEY_SUBCUTANEOUS_FAT).setText(preview.subcutaneousFatPercent?.let(::formatNumber).orEmpty())
        inputs.getValue(KEY_BONE_MASS).setText(preview.boneMassKg?.let(::formatNumber).orEmpty())
        inputs.getValue(KEY_PROTEIN_PERCENT).setText(preview.proteinPercent?.let(::formatNumber).orEmpty())
        inputs.getValue(KEY_PROTEIN_KG).setText(preview.proteinKg?.let(::formatNumber).orEmpty())
        inputs.getValue(KEY_BODY_AGE).setText(preview.bodyAgeYears?.let(::formatNumber).orEmpty())
        inputs.getValue(KEY_BMI).setText(preview.bmi?.let(::formatNumber).orEmpty())

        meta.text = buildString {
            append("Provider ").append(provider).append(" · ").append(model)
            append("\nConfidenza: ").append(preview.confidence)
            preview.notes.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
        }
        date.text = preview.measuredAtEpochMillis?.let { timestamp ->
            "Data rilevata: ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALIAN).format(Date(timestamp))}"
        } ?: "Data rilevata: non disponibile"

        val scrollHeight = minOf(
            (resources.displayMetrics.density * 300f).toInt(),
            (resources.displayMetrics.heightPixels * 0.46f).toInt(),
        )
        scroll.layoutParams = (scroll.layoutParams
            ?: android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, scrollHeight)).apply {
            height = scrollHeight
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Controlla importazione BIA")
            .setView(content)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Usa valori") { _, _ ->
                values[KEY_WEIGHT] = parseFloat(inputs.getValue(KEY_WEIGHT).text?.toString())
                values[KEY_BODY_FAT] = parseFloat(inputs.getValue(KEY_BODY_FAT).text?.toString())
                values[KEY_VISCERAL_FAT] = parseFloat(inputs.getValue(KEY_VISCERAL_FAT).text?.toString())
                values[KEY_MUSCLE_MASS] = parseFloat(inputs.getValue(KEY_MUSCLE_MASS).text?.toString())
                values[KEY_SKELETAL_MUSCLE] = parseFloat(inputs.getValue(KEY_SKELETAL_MUSCLE).text?.toString())
                values[KEY_BODY_WATER] = parseFloat(inputs.getValue(KEY_BODY_WATER).text?.toString())
                values[KEY_BMR] = parseFloat(inputs.getValue(KEY_BMR).text?.toString())
                values[KEY_FAT_MASS] = parseFloat(inputs.getValue(KEY_FAT_MASS).text?.toString())
                values[KEY_LEAN_MASS] = parseFloat(inputs.getValue(KEY_LEAN_MASS).text?.toString())
                values[KEY_BODY_WATER_KG] = parseFloat(inputs.getValue(KEY_BODY_WATER_KG).text?.toString())
                values[KEY_SUBCUTANEOUS_FAT] = parseFloat(inputs.getValue(KEY_SUBCUTANEOUS_FAT).text?.toString())
                values[KEY_BONE_MASS] = parseFloat(inputs.getValue(KEY_BONE_MASS).text?.toString())
                values[KEY_PROTEIN_PERCENT] = parseFloat(inputs.getValue(KEY_PROTEIN_PERCENT).text?.toString())
                values[KEY_PROTEIN_KG] = parseFloat(inputs.getValue(KEY_PROTEIN_KG).text?.toString())
                values[KEY_BODY_AGE] = parseFloat(inputs.getValue(KEY_BODY_AGE).text?.toString())
                values[KEY_BMI] = parseFloat(inputs.getValue(KEY_BMI).text?.toString())
                preview.measuredAtEpochMillis?.let { timestamp ->
                    Calendar.getInstance().apply { timeInMillis = timestamp }.also { detected ->
                        selectedDateMillis = detected.timeInMillis
                        selectedHour = detected.get(Calendar.HOUR_OF_DAY)
                        selectedMinute = detected.get(Calendar.MINUTE)
                    }
                }
                bindMeasurementRows()
                renderDateTime()
                Toast.makeText(
                    this@BiaActivity,
                    if (preview.measuredAtEpochMillis != null) {
                        "Valori e data rilevata caricati. Controllali prima di salvare."
                    } else {
                        "Valori caricati. La data non è stata rilevata: controllala prima di salvare."
                    },
                    Toast.LENGTH_LONG,
                ).show()
            }
            .create()

        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(getColor(R.color.text_secondary))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(getColor(R.color.accent_green_dark))
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.92f).toInt(),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            )
        }
        dialog.show()
    }

    private fun org.json.JSONObject.floatOrNull(key: String): Float? =
        if (has(key) && !isNull(key)) optDouble(key).toFloat().takeIf { it.isFinite() } else null

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
                        if (historyContainer.visibility == View.VISIBLE) renderHistory(history)
                    }
                }
                launch {
                    viewModel.saved.collect { recommendationJobKey ->
                        Toast.makeText(this@BiaActivity, "Misurazione BIA aggiunta allo storico", Toast.LENGTH_SHORT).show()
                        resetForm()
                        // Only a saved BIA can trigger a visible goal recommendation. Never
                        // silently replace the existing goal: NutritionPathActivity asks the user.
                        startActivity(Intent(this@BiaActivity, NutritionPathActivity::class.java).apply {
                            recommendationJobKey?.let { putExtra(NutritionPathActivity.EXTRA_JOB_KEY, it) }
                        })
                    }
                }
                launch {
                    viewModel.updated.collect {
                        Toast.makeText(this@BiaActivity, "Misurazione BIA aggiornata", Toast.LENGTH_SHORT).show()
                        editingMeasurement = null
                        resetForm()
                        findViewById<SelectableSegmentView>(R.id.biaSegment).getChildAt(1)?.performClick()
                    }
                }
                launch {
                    viewModel.deleted.collect {
                        Toast.makeText(this@BiaActivity, "Misurazione BIA eliminata dallo storico", Toast.LENGTH_SHORT).show()
                    }
                }
                launch {
                    viewModel.error.collect { message ->
                        Toast.makeText(this@BiaActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun renderHistory(history: List<BiaMeasurementEntity>) {
        historyList.removeAllViews()
        findViewById<View>(R.id.analyzeBiaButton).isEnabled = history.any { hasAnalysisValue(it) }
        if (history.isEmpty()) {
            historySummary.text = "Nessuna misurazione BIA salvata per questo profilo."
            historySummary.visibility = View.GONE
            findViewById<View>(R.id.historyEmptyText).visibility = View.VISIBLE
            findViewById<View>(R.id.analyzeBiaButton).visibility = View.GONE
            return
        }
        historySummary.visibility = View.VISIBLE
        findViewById<View>(R.id.historyEmptyText).visibility = View.GONE
        findViewById<View>(R.id.analyzeBiaButton).visibility = View.VISIBLE

        val latest = history.first()
        historySummary.text = buildSummary(history, latest)

        history.forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_card)
                elevation = 0f
                setPadding(dp(16), dp(12), dp(16), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) }
            }

            card.addView(TextView(this).apply {
                text = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALIAN).format(Date(item.measuredAtEpochMillis))
                setTextColor(getColor(R.color.text_primary))
                setTypeface(typeface, Typeface.BOLD)
                textSize = 15f
            })
            card.addView(TextView(this).apply {
                text = buildMeasurementLine(item)
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(0, dp(5), 0, 0)
            })
            buildConditionLine(item)?.let { conditions ->
                card.addView(TextView(this).apply {
                    text = conditions
                    setTextColor(getColor(R.color.text_muted))
                    textSize = 12f
                    setPadding(0, dp(6), 0, 0)
                })
            }
            card.addView(TextView(this).apply {
                text = "Tocca per modificare · Tieni premuto per eliminare"
                setTextColor(getColor(R.color.text_muted))
                textSize = 10f
                setPadding(0, dp(6), 0, 0)
            })
            card.setOnClickListener { editReading(item) }
            card.contentDescription = "Modifica rilevazione BIA"
            card.setOnLongClickListener {
                confirmDelete(item)
                true
            }
            historyList.addView(card)
        }
    }

    private fun analyzeBiaWithAi(button: View) {
        val history = viewModel.history.value
        val latest = history.firstOrNull() ?: return
        val current = linkedMapOf<String, Float>().apply {
            latest.weightKg?.let { put("weightKg", it) }
            latest.bodyFatPercent?.let { put("bodyFatPercent", it) }
            latest.visceralFatLevel?.let { put("visceralFatLevel", it) }
            latest.muscleMassKg?.let { put("muscleMassKg", it) }
            latest.skeletalMuscleKg?.let { put("skeletalMuscleKg", it) }
            latest.bodyWaterPercent?.let { put("bodyWaterPercent", it) }
            latest.bmrKcal?.let { put("bmrKcal", it) }
            latest.fatMassKg?.let { put("fatMassKg", it) }
            latest.leanMassKg?.let { put("leanMassKg", it) }
            latest.bodyWaterKg?.let { put("bodyWaterKg", it) }
            latest.subcutaneousFatPercent?.let { put("subcutaneousFatPercent", it) }
            latest.boneMassKg?.let { put("boneMassKg", it) }
            latest.proteinPercent?.let { put("proteinPercent", it) }
            latest.proteinKg?.let { put("proteinKg", it) }
            latest.bodyAgeYears?.let { put("bodyAgeYears", it.toFloat()) }
            latest.bmi?.let { put("bmi", it) }
        }
        if (current.isEmpty()) return

        val previousDelta = linkedMapOf<String, Float>().apply {
            fun addDelta(key: String, currentValue: Float?, selector: (BiaMeasurementEntity) -> Float?) {
                val previous = history.drop(1).firstNotNullOfOrNull(selector)
                if (currentValue != null && previous != null) put(key, currentValue - previous)
            }
            addDelta("weightKg", latest.weightKg) { it.weightKg }
            addDelta("bodyFatPercent", latest.bodyFatPercent) { it.bodyFatPercent }
            addDelta("muscleMassKg", latest.muscleMassKg) { it.muscleMassKg }
            addDelta("skeletalMuscleKg", latest.skeletalMuscleKg) { it.skeletalMuscleKg }
            addDelta("bodyWaterPercent", latest.bodyWaterPercent) { it.bodyWaterPercent }
            addDelta("fatMassKg", latest.fatMassKg) { it.fatMassKg }
            addDelta("leanMassKg", latest.leanMassKg) { it.leanMassKg }
            addDelta("bodyWaterKg", latest.bodyWaterKg) { it.bodyWaterKg }
            addDelta("subcutaneousFatPercent", latest.subcutaneousFatPercent) { it.subcutaneousFatPercent }
            addDelta("boneMassKg", latest.boneMassKg) { it.boneMassKg }
            addDelta("proteinPercent", latest.proteinPercent) { it.proteinPercent }
            addDelta("proteinKg", latest.proteinKg) { it.proteinKg }
            addDelta("bmi", latest.bmi) { it.bmi }
        }

        val report = org.json.JSONObject()
            .put("measurementCount", history.size)
            .put("current", jsonValues(current))
            .put("previousDelta", jsonValues(previousDelta))
            .toString()
        confirmAiRequest("L'analisi IA dello stato muscolare e dei valori BIA") {
            button.isEnabled = false
            val profileId = data.activeProfileStore.currentIdOrNull() ?: return@confirmAiRequest
            val jobKey = System.currentTimeMillis().toString()
            data.aiJobScheduler.enqueue(
                AiJobType.BIA_ANALYSIS,
                profileId,
                jobKey,
                params = androidx.work.Data.Builder().putString(BiaAnalysisAiJobHandler.KEY_REPORT, report).build(),
            )
            lifecycleScope.launch {
                data.aiJobScheduler.observe(AiJobType.BIA_ANALYSIS, profileId, jobKey).collect { info ->
                    when (info?.state) {
                        androidx.work.WorkInfo.State.SUCCEEDED -> {
                            val payload = org.json.JSONObject(info.outputData.getString(BiaAnalysisAiJobHandler.KEY_PAYLOAD).orEmpty())
                            val doingWell = jsonLines(payload, "doingWell")
                            val improve = jsonLines(payload, "improve")
                            val message = buildString {
                                appendLine(payload.optString("summary"))
                                appendLine()
                                appendLine("Stato della muscolatura")
                                appendLine(payload.optString("muscleStatus"))
                                if (doingWell.isNotEmpty()) {
                                    appendLine()
                                    appendLine("Cosa va bene")
                                    doingWell.forEach { appendLine("• $it") }
                                }
                                if (improve.isNotEmpty()) {
                                    appendLine()
                                    appendLine("Dove migliorare")
                                    improve.forEach { appendLine("• $it") }
                                }
                            }
                            MaterialAlertDialogBuilder(this@BiaActivity)
                                .setTitle("Analisi sportiva BIA")
                                .setMessage(message.trim())
                                .setPositiveButton("Chiudi", null)
                                .show()
                            button.isEnabled = true
                        }
                        androidx.work.WorkInfo.State.FAILED, androidx.work.WorkInfo.State.CANCELLED -> {
                            button.isEnabled = true
                            Toast.makeText(this@BiaActivity, "Analisi BIA non riuscita. Riprova.", Toast.LENGTH_LONG).show()
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun jsonValues(values: Map<String, Float>): org.json.JSONArray = org.json.JSONArray().apply {
        values.forEach { (key, value) -> put(org.json.JSONObject().put("key", key).put("value", value)) }
    }

    private fun jsonLines(payload: org.json.JSONObject, key: String): List<String> {
        val values = payload.optJSONArray(key) ?: return emptyList()
        return buildList { for (index in 0 until values.length()) add(values.optString(index)) }
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

    private fun buildMeasurementLine(item: BiaMeasurementEntity): String = listOfNotNull(
        item.weightKg?.let { "Peso ${formatValue(it, "kg")}" },
        item.bodyFatPercent?.let { "Grasso ${formatValue(it, "%")}" },
        item.visceralFatLevel?.let { "Viscerale ${formatValue(it, "")}" },
        item.muscleMassKg?.let { "Massa muscolare ${formatValue(it, "kg")}" },
        item.skeletalMuscleKg?.let { "Scheletrico ${formatValue(it, "kg")}" },
        item.bodyWaterPercent?.let { "Acqua ${formatValue(it, "%")}" },
        item.bmrKcal?.let { "BMR ${formatValue(it, "kcal")}" },
        item.fatMassKg?.let { "Grasso ${formatValue(it, "kg")}" },
        item.leanMassKg?.let { "Massa magra ${formatValue(it, "kg")}" },
        item.bodyWaterKg?.let { "Acqua ${formatValue(it, "kg")}" },
        item.subcutaneousFatPercent?.let { "Sottocutaneo ${formatValue(it, "%")}" },
        item.boneMassKg?.let { "Ossa ${formatValue(it, "kg")}" },
        item.proteinPercent?.let { "Proteine ${formatValue(it, "%")}" },
        item.proteinKg?.let { "Proteine ${formatValue(it, "kg")}" },
        item.bodyAgeYears?.let { "Età corporea " + it },
        item.bmi?.let { "BMI " + formatNumber(it) },
    ).joinToString(" · ").ifBlank { "Valori parziali" }

    private fun buildConditionLine(item: BiaMeasurementEntity): String? {
        val conditions = listOfNotNull(
            "a digiuno".takeIf { item.fasting },
            "appena sveglio".takeIf { item.justWokeUp },
            "dopo bagno".takeIf { item.afterBathroom },
            "nessun allenamento recente".takeIf { item.noRecentWorkout },
        )
        return conditions.takeIf { it.isNotEmpty() }?.joinToString(" · ", prefix = "Condizioni: ")
    }

    private fun editReading(item: BiaMeasurementEntity) {
        editingMeasurement = item
        values[KEY_WEIGHT] = item.weightKg
        values[KEY_BODY_FAT] = item.bodyFatPercent
        values[KEY_VISCERAL_FAT] = item.visceralFatLevel
        values[KEY_MUSCLE_MASS] = item.muscleMassKg
        values[KEY_SKELETAL_MUSCLE] = item.skeletalMuscleKg
        values[KEY_BODY_WATER] = item.bodyWaterPercent
        values[KEY_BMR] = item.bmrKcal
        values[KEY_FAT_MASS] = item.fatMassKg
        values[KEY_LEAN_MASS] = item.leanMassKg
        values[KEY_BODY_WATER_KG] = item.bodyWaterKg
        values[KEY_SUBCUTANEOUS_FAT] = item.subcutaneousFatPercent
        values[KEY_BONE_MASS] = item.boneMassKg
        values[KEY_PROTEIN_PERCENT] = item.proteinPercent
        values[KEY_PROTEIN_KG] = item.proteinKg
        values[KEY_BODY_AGE] = item.bodyAgeYears?.toFloat()
        values[KEY_BMI] = item.bmi
        val date = Calendar.getInstance().apply { timeInMillis = item.measuredAtEpochMillis }
        selectedDateMillis = item.measuredAtEpochMillis
        selectedHour = date.get(Calendar.HOUR_OF_DAY)
        selectedMinute = date.get(Calendar.MINUTE)
        findViewById<MaterialCheckBox>(R.id.checkFasting).isChecked = item.fasting
        findViewById<MaterialCheckBox>(R.id.checkJustWoken).isChecked = item.justWokeUp
        findViewById<MaterialCheckBox>(R.id.checkAfterShower).isChecked = item.afterBathroom
        findViewById<MaterialCheckBox>(R.id.checkNoWorkout).isChecked = item.noRecentWorkout
        bindMeasurementRows()
        renderDateTime()
        findViewById<android.widget.TextView>(R.id.saveButton).apply {
            text = "Salva modifiche"
            contentDescription = "Salva modifiche"
        }
        openingExistingForEdit = true
        findViewById<SelectableSegmentView>(R.id.biaSegment).getChildAt(0)?.performClick()
        openingExistingForEdit = false
    }

    private fun confirmDelete(item: BiaMeasurementEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Elimina misurazione")
            .setMessage("Vuoi eliminare questa misurazione BIA dallo storico? L'operazione non è reversibile.")
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Elimina") { _, _ -> viewModel.delete(item) }
            .show()
    }

    private fun resetForm() {
        editingMeasurement = null
        findViewById<android.widget.TextView>(R.id.saveButton).apply {
            text = "Salva"
            contentDescription = "Salva"
        }
        values.keys.forEach { values[it] = null }
        bindMeasurementRows()
        selectedDateMillis = System.currentTimeMillis()
        val now = Calendar.getInstance()
        selectedHour = now.get(Calendar.HOUR_OF_DAY)
        selectedMinute = now.get(Calendar.MINUTE)
        findViewById<MaterialCheckBox>(R.id.checkFasting).isChecked = false
        findViewById<MaterialCheckBox>(R.id.checkJustWoken).isChecked = false
        findViewById<MaterialCheckBox>(R.id.checkAfterShower).isChecked = false
        findViewById<MaterialCheckBox>(R.id.checkNoWorkout).isChecked = false
        renderDateTime()
    }

    private fun renderDateTime() {
        val formattedDate = SimpleDateFormat("dd/MM/yyyy", Locale.ITALIAN).format(Date(selectedDateMillis))
        dateInput.setText(formattedDate)
        dateInput.contentDescription = "Data rilevazione $formattedDate"
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

    private fun parseFloat(raw: String?): Float? = raw
        ?.trim()
        ?.replace(',', '.')
        ?.takeIf { it.isNotEmpty() }
        ?.toFloatOrNull()

    private fun formatValue(value: Float, unit: String): String = if (unit.isBlank()) {
        formatNumber(value)
    } else {
        "${formatNumber(value)} $unit"
    }

    private fun formatNumber(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.ITALIAN, "%.1f", value)

    private fun formatSigned(value: Float): String = String.format(Locale.ITALIAN, "%+.1f", value)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_OPEN_HISTORY = "open_bia_history"
        const val EXTRA_EDIT_ID = "edit_bia_measurement_id"
        const val EXTRA_AI_JOB_KEY = "bia_ai_job_key"
        private const val KEY_WEIGHT = "weight"
        private const val KEY_BODY_FAT = "bodyFat"
        private const val KEY_VISCERAL_FAT = "visceralFat"
        private const val KEY_MUSCLE_MASS = "muscleMass"
        private const val KEY_SKELETAL_MUSCLE = "skeletalMuscle"
        private const val KEY_BODY_WATER = "bodyWater"
        private const val KEY_BMR = "bmr"
        private const val KEY_FAT_MASS = "fatMass"
        private const val KEY_LEAN_MASS = "leanMass"
        private const val KEY_BODY_WATER_KG = "bodyWaterKg"
        private const val KEY_SUBCUTANEOUS_FAT = "subcutaneousFat"
        private const val KEY_BONE_MASS = "boneMass"
        private const val KEY_PROTEIN_PERCENT = "proteinPercent"
        private const val KEY_PROTEIN_KG = "proteinKg"
        private const val KEY_BODY_AGE = "bodyAge"
        private const val KEY_BMI = "bmi"
    }
}
