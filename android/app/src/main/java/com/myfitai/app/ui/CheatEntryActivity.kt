package com.myfitai.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.ChipGroup
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.myfitai.app.R
import com.myfitai.app.ai.AiImageInput
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.food.CheatAdjustmentService
import com.myfitai.app.domain.food.LabelImageProcessor
import com.myfitai.app.domain.food.LabelImageTempStore
import com.myfitai.app.ui.food.CheatEntryViewModel
import com.myfitai.app.ui.widgets.SelectableSegmentView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class CheatEntryActivity : BaseShellActivity() {

    private val data by lazy { AppDataContainer.get(this) }
    private val viewModel: CheatEntryViewModel by viewModels {
        CheatEntryViewModel.Factory(data.cheatAdjustmentService, data.notificationScheduler, data.aiJobScheduler, data.activeProfileStore, data.aiImageJobStore)
    }

    private var selectedDate: LocalDate = LocalDate.now()
    private var selectedTime: LocalTime = LocalTime.now().withSecond(0).withNano(0)
    private var labelImage: AiImageInput? = null
    private var pendingCameraFile: File? = null
    private var labelProcessing = false
    private val labelTempStore by lazy { LabelImageTempStore(this) }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) prepareLabelFromUri(uri)
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = pendingCameraFile
        pendingCameraFile = null
        if (success && file != null) prepareLabelFromFile(file) else labelTempStore.delete(file)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cheat_entry)
        bindBack()

        findViewById<SelectableSegmentView>(R.id.modeSegment)
            .setSegments(listOf("Rapido", "Dettagliato"), selectedIndex = 0)

        val quantityInput = findViewById<AutoCompleteTextView>(R.id.quantityInput)
        quantityInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, listOf("Piccolo", "Medio", "Grande")))
        quantityInput.setText("Medio", false)

        bindLabelPhoto()
        renderDateTime()
        bindPickers()
        findViewById<View>(R.id.analyzeButton).setOnClickListener { analyze() }
        findViewById<View>(R.id.reevaluateButton).setOnClickListener { analyze() }
        findViewById<View>(R.id.confirmButton).setOnClickListener { confirm() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::renderState)
            }
        }
    }

    private fun bindLabelPhoto() {
        findViewById<View>(R.id.addLabelPhotoButton).setOnClickListener {
            if (!labelProcessing) showLabelSourceDialog()
        }
        findViewById<View>(R.id.removeLabelPhotoButton).setOnClickListener {
            labelImage = null
            viewModel.invalidateUnderstanding()
            renderLabelState()
        }
        renderLabelState()
    }

    private fun showLabelSourceDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Foto etichetta nutrizionale")
            .setItems(arrayOf("Scatta foto", "Scegli dalla galleria")) { _, which ->
                when (which) {
                    0 -> launchLabelCamera()
                    1 -> galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }
            .show()
    }

    private fun launchLabelCamera() {
        val file = labelTempStore.create()
        pendingCameraFile = file
        cameraLauncher.launch(FileProvider.getUriForFile(this, "$packageName.fileprovider", file))
    }

    private fun prepareLabelFromUri(uri: Uri) {
        setLabelProcessing(true)
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { LabelImageProcessor.fromUri(this@CheatEntryActivity, uri) } }
                .onSuccess {
                    labelImage = it
                    viewModel.invalidateUnderstanding()
                    setLabelProcessing(false)
                }
                .onFailure {
                    labelImage = null
                    viewModel.invalidateUnderstanding()
                    setLabelProcessing(false, "Impossibile leggere la foto. Riprova con l'etichetta ben visibile.")
                }
        }
    }

    private fun prepareLabelFromFile(file: File) {
        setLabelProcessing(true)
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { LabelImageProcessor.fromFile(file) } }
                .onSuccess {
                    labelImage = it
                    viewModel.invalidateUnderstanding()
                    setLabelProcessing(false)
                }
                .onFailure {
                    labelImage = null
                    viewModel.invalidateUnderstanding()
                    setLabelProcessing(false, "Impossibile leggere la foto. Riprova con l'etichetta ben visibile.")
                }
            // Il file della fotocamera non sopravvive alla preparazione del payload in memoria.
            withContext(Dispatchers.IO) { runCatching { labelTempStore.delete(file) } }
        }
    }

    private fun setLabelProcessing(processing: Boolean, error: String? = null) {
        labelProcessing = processing
        findViewById<View>(R.id.addLabelPhotoButton).isEnabled = !processing
        if (processing) {
            findViewById<View>(R.id.labelPhotoStatusRow).visibility = View.VISIBLE
            findViewById<TextView>(R.id.labelPhotoStatus).apply {
                text = "Preparazione etichetta…"
                setTextColor(getColor(R.color.text_secondary))
            }
        } else if (error != null) {
            findViewById<View>(R.id.labelPhotoStatusRow).visibility = View.VISIBLE
            findViewById<TextView>(R.id.labelPhotoStatus).apply {
                text = error
                setTextColor(getColor(R.color.text_secondary))
            }
        } else renderLabelState()
    }

    private fun renderLabelState() {
        val attached = labelImage != null
        findViewById<View>(R.id.labelPhotoStatusRow).visibility = if (attached) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.labelPhotoStatus).apply {
            text = "Etichetta pronta ✓ · solo memoria temporanea"
            setTextColor(getColor(R.color.accent_green))
        }
        findViewById<View>(R.id.removeLabelPhotoButton).visibility = if (attached) View.VISIBLE else View.GONE
    }

    private fun bindPickers() {
        findViewById<View>(R.id.dateField).setOnClickListener {
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Data sgarro")
                .setSelection(selectedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                .build()
            picker.addOnPositiveButtonClickListener { selection ->
                selectedDate = Instant.ofEpochMilli(selection).atZone(ZoneOffset.UTC).toLocalDate()
                viewModel.invalidateUnderstanding()
                renderDateTime()
            }
            picker.show(supportFragmentManager, "cheat_date_picker")
        }

        findViewById<View>(R.id.timeField).setOnClickListener {
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(selectedTime.hour)
                .setMinute(selectedTime.minute)
                .setTitleText("Ora sgarro")
                .build()
            picker.addOnPositiveButtonClickListener {
                selectedTime = LocalTime.of(picker.hour, picker.minute)
                viewModel.invalidateUnderstanding()
                renderDateTime()
            }
            picker.show(supportFragmentManager, "cheat_time_picker")
        }
    }

    private fun analyze() {
        val input = buildInput() ?: return
        confirmAiRequest("La valutazione dello sgarro") {
            viewModel.analyze(input)
        }
    }

    private fun confirm() {
        val input = buildInput() ?: return
        confirmAiRequest("La conferma dello sgarro e l'adattamento dei pasti futuri") {
            viewModel.confirm(input.copy(labelImage = null))
        }
    }

    private fun buildInput(): CheatAdjustmentService.Input? {
        if (labelProcessing) {
            showStatus("Attendi il completamento della foto dell'etichetta.")
            return null
        }
        val description = findViewById<EditText>(R.id.descriptionInput).text?.toString()?.trim().orEmpty()
        if (description.isBlank()) {
            findViewById<EditText>(R.id.descriptionInput).error = "Descrivi cosa hai mangiato"
            return null
        }
        val category = selectedCategory()
        val fullDescription = if (category == null || description.startsWith(category, ignoreCase = true)) description else "$category — $description"
        val occurredAt = selectedDate.atTime(selectedTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (occurredAt > System.currentTimeMillis() + 60_000L) {
            showStatus("La data dello sgarro non può essere nel futuro.")
            return null
        }

        val baseNotes = findViewById<EditText>(R.id.notesInput).text?.toString()?.trim().orEmpty()
        val clarification = findViewById<EditText>(R.id.clarificationInput).text?.toString()?.trim().orEmpty()
        val notes = listOfNotNull(
            baseNotes.takeIf { it.isNotBlank() },
            clarification.takeIf { it.isNotBlank() }?.let { "Chiarimento utente dopo la prima lettura IA: $it" },
        ).joinToString("\n").takeIf { it.isNotBlank() }

        return CheatAdjustmentService.Input(
            description = fullDescription,
            quantityText = findViewById<AutoCompleteTextView>(R.id.quantityInput).text?.toString(),
            notes = notes,
            occurredAtEpochMillis = occurredAt,
            labelImage = labelImage,
        )
    }

    private fun selectedCategory(): String? {
        val checkedId = findViewById<ChipGroup>(R.id.foodChipGroup).checkedChipId
        return checkedId.takeIf { it != View.NO_ID }
            ?.let { findViewById<com.google.android.material.chip.Chip>(it).text?.toString()?.trim() }
            ?.takeIf { it.isNotBlank() }
    }

    private fun renderDateTime() {
        findViewById<TextView>(R.id.dateValue).text = selectedDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ITALIAN))
        findViewById<TextView>(R.id.timeValue).text = selectedTime.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ITALIAN))
    }

    private fun renderState(state: CheatEntryViewModel.State) {
        val hasUnderstanding = state.understanding != null
        findViewById<View>(R.id.analyzeButton).isEnabled = !state.running && !labelProcessing
        findViewById<View>(R.id.reevaluateButton).isEnabled = !state.running && !labelProcessing
        findViewById<View>(R.id.confirmButton).isEnabled = !state.running && !labelProcessing
        findViewById<View>(R.id.addLabelPhotoButton).isEnabled = !state.running && !labelProcessing
        findViewById<ProgressBar>(R.id.progress).visibility = if (state.running) View.VISIBLE else View.GONE
        findViewById<View>(R.id.aiUnderstandingCard).visibility = if (hasUnderstanding) View.VISIBLE else View.GONE
        findViewById<View>(R.id.analyzeButton).visibility = if (hasUnderstanding) View.GONE else View.VISIBLE

        state.understanding?.let { understanding ->
            findViewById<TextView>(R.id.aiUnderstoodFood).text = understanding.understoodFood
            findViewById<TextView>(R.id.aiEstimate).text = understanding.estimateSummary
            findViewById<TextView>(R.id.aiEstimateNotes).text = buildString {
                append(understanding.estimate.notes.ifBlank { "Nessuna nota aggiuntiva." })
                append("\nValutato con ${understanding.provider} · ${understanding.model}")
            }
        }

        findViewById<TextView>(R.id.statusText).apply {
            visibility = if (state.running || state.error != null) View.VISIBLE else View.GONE
            text = when {
                state.running && hasUnderstanding -> "Conferma dello sgarro e verifica dei pasti futuri…"
                state.running && labelImage != null -> "L'IA sta leggendo descrizione ed etichetta per dirti cosa ha capito…"
                state.running -> "L'IA sta interpretando ciò che hai mangiato…"
                state.error != null -> state.error
                else -> ""
            }
        }

        val result = state.result ?: return
        labelImage = null
        viewModel.consumeResult()
        startActivity(
            Intent(this, AdjustedPlanActivity::class.java)
                .putExtra(AdjustedPlanActivity.EXTRA_DESCRIPTION, findViewById<EditText>(R.id.descriptionInput).text?.toString().orEmpty())
                .putExtra(AdjustedPlanActivity.EXTRA_ESTIMATE, result.estimateSummary)
                .putExtra(AdjustedPlanActivity.EXTRA_ADAPTED, result.adapted)
                .putExtra(AdjustedPlanActivity.EXTRA_SUMMARY, result.adaptationSummary)
                .putStringArrayListExtra(AdjustedPlanActivity.EXTRA_MODIFIED_MEALS, ArrayList(result.modifiedMeals))
        )
        finish()
    }

    private fun showStatus(message: String) {
        findViewById<TextView>(R.id.statusText).apply {
            visibility = View.VISIBLE
            text = message
        }
    }

    override fun onDestroy() {
        pendingCameraFile?.let { runCatching { labelTempStore.delete(it) } }
        pendingCameraFile = null
        labelImage = null
        super.onDestroy()
    }
}
