package com.myfitai.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.GridLayout
import android.view.ViewGroup
import android.content.res.ColorStateList
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
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.myfitai.app.R
import com.myfitai.app.ai.AiImageInput
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.food.CheatAdjustmentService
import com.myfitai.app.domain.food.CheatPhotoProcessor
import com.myfitai.app.domain.food.CheatPhotoTempStore
import com.myfitai.app.ui.food.CheatEntryViewModel
import com.myfitai.app.ui.motion.UiMotion
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
    private var foodImage: AiImageInput? = null
    private var pendingCameraFile: File? = null
    private var photoProcessing = false
    private var detailedMode = false
    private val photoTempStore by lazy { CheatPhotoTempStore(this) }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) preparePhotoFromUri(uri)
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = pendingCameraFile
        pendingCameraFile = null
        if (success && file != null) preparePhotoFromFile(file) else photoTempStore.delete(file)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.takeIf { it.hasExtra(EXTRA_OCCURRED_DATE_EPOCH_DAY) }
            ?.getLongExtra(EXTRA_OCCURRED_DATE_EPOCH_DAY, LocalDate.now().toEpochDay())
            ?.let { selectedDate = LocalDate.ofEpochDay(it) }
        setContentView(R.layout.activity_cheat_entry)
        adaptLandscapeContent()
        bindBack()
        normalizeCheatSurfaces()

        findViewById<SelectableSegmentView>(R.id.modeSegment).apply {
            setSegments(listOf("Rapido", "Dettagliato"), selectedIndex = 0)
            setOnSegmentSelectedListener { index ->
                renderMode(detailed = index == 1)
                // Quantity, label photo and notes only count in the detailed mode: a previous reading is no longer valid.
                viewModel.invalidateUnderstanding()
            }
        }
        renderMode(detailed = false)

        val quantityInput = findViewById<AutoCompleteTextView>(R.id.quantityInput)
        quantityInput.setMyFitAiDropdownItems(listOf("Piccolo", "Medio", "Grande"))
        quantityInput.setText("Medio", false)

        bindCheatPhoto()
        renderDateTime()
        bindPickers()
        gateAiClick(findViewById(R.id.analyzeButton)) { analyze() }
        gateAiClick(findViewById(R.id.reevaluateButton)) { analyze() }
        gateAiClick(findViewById(R.id.confirmButton)) { confirm() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::renderState)
            }
        }
    }

    private fun normalizeCheatSurfaces() {
        fun visit(view: View) {
            when (view) {
                is MaterialCardView -> {
                    view.setCardBackgroundColor(getColor(R.color.white))
                    view.strokeColor = getColor(R.color.divider)
                    view.strokeWidth = resources.getDimensionPixelSize(R.dimen.border_width_default)
                    view.cardElevation = resources.getDimension(R.dimen.elevation_none)
                }
                is TextInputLayout -> {
                    view.boxBackgroundColor = getColor(R.color.white)
                    view.boxStrokeColor = getColor(R.color.myfitai_input_stroke)
                    view.hintTextColor = ColorStateList.valueOf(getColor(R.color.myfitai_input_hint))
                }
            }
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(findViewById(android.R.id.content))
    }

    private fun adaptLandscapeContent() {
        if (resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE) return
        val scroll = findFirstScrollView(findViewById(android.R.id.content)) ?: return
        val root = scroll.getChildAt(0) as? ViewGroup ?: return
        if (root.findViewWithTag<View>(LANDSCAPE_GRID_TAG) != null) return
        val children = (0 until root.childCount).map { root.getChildAt(it) }
        val grid = GridLayout(this).apply {
            tag = LANDSCAPE_GRID_TAG
            columnCount = 2
            useDefaultMargins = false
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.removeAllViews()
        root.addView(grid)
        children.forEach { child ->
            val halfWidth = child.id == R.id.entryCard ||
                child.id == R.id.labelPhotoCard ||
                child.id == R.id.whenCard ||
                child.id == R.id.quantityCard ||
                child.id == R.id.notesCard
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, if (halfWidth) 1 else 2, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
                setMargins(
                    resources.getDimensionPixelSize(R.dimen.space_6),
                    resources.getDimensionPixelSize(R.dimen.space_6),
                    resources.getDimensionPixelSize(R.dimen.space_6),
                    resources.getDimensionPixelSize(R.dimen.space_6),
                )
            }
            grid.addView(child, params)
        }
    }

    private fun renderMode(detailed: Boolean) {
        detailedMode = detailed
        UiMotion.reveal(findViewById(R.id.labelPhotoCard), detailed)
        UiMotion.reveal(findViewById(R.id.quantityCard), detailed)
        UiMotion.reveal(findViewById(R.id.notesCard), detailed)
        findViewById<View>(R.id.modeSegment).contentDescription =
            if (detailed) "Modalità dettagliata selezionata" else "Modalità rapida selezionata"
    }

    private fun findFirstScrollView(view: View): android.widget.ScrollView? {
        if (view is android.widget.ScrollView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findFirstScrollView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun bindCheatPhoto() {
        gateAiClick(findViewById(R.id.addLabelPhotoButton)) { if (!photoProcessing) showPhotoSourceDialog() }
        findViewById<View>(R.id.removeLabelPhotoButton).setOnClickListener {
            foodImage = null
            viewModel.invalidateUnderstanding()
            renderPhotoState()
        }
        renderPhotoState()
    }

    private fun showPhotoSourceDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Aggiungi foto allo sgarro")
            .setItems(arrayOf("Scatta foto", "Scegli dalla galleria")) { _, which ->
                when (which) {
                    0 -> launchPhotoCamera()
                    1 -> galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }
            .show()
    }

    private fun launchPhotoCamera() {
        val file = photoTempStore.create()
        pendingCameraFile = file
        cameraLauncher.launch(FileProvider.getUriForFile(this, "$packageName.fileprovider", file))
    }

    private fun preparePhotoFromUri(uri: Uri) {
        setPhotoProcessing(true)
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { CheatPhotoProcessor.fromUri(this@CheatEntryActivity, uri) } }
                .onSuccess {
                    foodImage = it
                    viewModel.invalidateUnderstanding()
                    setPhotoProcessing(false)
                }
                .onFailure {
                    foodImage = null
                    viewModel.invalidateUnderstanding()
                    setPhotoProcessing(false, "Impossibile leggere la foto. Riprova con un'immagine nitida e ben illuminata.")
                }
        }
    }

    private fun preparePhotoFromFile(file: File) {
        setPhotoProcessing(true)
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { CheatPhotoProcessor.fromFile(file) } }
                .onSuccess {
                    foodImage = it
                    viewModel.invalidateUnderstanding()
                    setPhotoProcessing(false)
                }
                .onFailure {
                    foodImage = null
                    viewModel.invalidateUnderstanding()
                    setPhotoProcessing(false, "Impossibile leggere la foto. Riprova con un'immagine nitida e ben illuminata.")
                }
            // Il file della fotocamera non sopravvive alla preparazione del payload in memoria.
            withContext(Dispatchers.IO) { runCatching { photoTempStore.delete(file) } }
        }
    }

    private fun setPhotoProcessing(processing: Boolean, error: String? = null) {
        photoProcessing = processing
        findViewById<View>(R.id.addLabelPhotoButton).isEnabled = !processing
        if (processing) {
            UiMotion.reveal(findViewById(R.id.labelPhotoStatusRow), true, animateChange = false)
            findViewById<TextView>(R.id.labelPhotoStatus).apply {
                text = "Preparazione foto…"
                setTextColor(getColor(R.color.text_secondary))
            }
        } else if (error != null) {
            UiMotion.reveal(findViewById(R.id.labelPhotoStatusRow), true, animateChange = false)
            findViewById<TextView>(R.id.labelPhotoStatus).apply {
                text = error
                setTextColor(getColor(R.color.text_secondary))
            }
        } else renderPhotoState()
    }

    private fun renderPhotoState() {
        val attached = foodImage != null
        UiMotion.reveal(findViewById(R.id.labelPhotoStatusRow), attached)
        findViewById<TextView>(R.id.labelPhotoStatus).apply {
            text = "Foto pronta ✓ · solo memoria temporanea"
            setTextColor(getColor(R.color.accent_green))
        }
        UiMotion.reveal(findViewById(R.id.removeLabelPhotoButton), attached)
    }

    private fun bindPickers() {
        findViewById<View>(R.id.dateField).setOnClickListener {
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTheme(R.style.ThemeOverlay_MyFitAI_MaterialCalendar)
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
                .setTheme(R.style.ThemeOverlay_MyFitAI_MaterialTimePicker)
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
        confirmAiRequest("La conferma dello sgarro e la distribuzione del surplus nella settimana") {
            viewModel.confirm(input.copy(foodImage = null))
        }
    }

    private fun buildInput(): CheatAdjustmentService.Input? {
        if (photoProcessing) {
            showStatus("Attendi il completamento della foto dell'etichetta.")
            return null
        }
        val description = findViewById<TextInputEditText>(R.id.descriptionInput).text?.toString()?.trim().orEmpty()
        if (description.isBlank()) {
            findViewById<TextInputEditText>(R.id.descriptionInput).error = "Descrivi cosa hai mangiato"
            return null
        }
        val category = selectedCategory()
        val fullDescription = if (category == null || description.startsWith(category, ignoreCase = true)) description else "$category — $description"
        val occurredAt = selectedDate.atTime(selectedTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (occurredAt > System.currentTimeMillis() + 60_000L) {
            showStatus("La data dello sgarro non può essere nel futuro.")
            return null
        }

        val notes = composeCheatNotes(
            detailed = detailedMode,
            notes = findViewById<TextInputEditText>(R.id.notesInput).text?.toString().orEmpty(),
            clarification = findViewById<TextInputEditText>(R.id.clarificationInput).text?.toString().orEmpty(),
        )

        return CheatAdjustmentService.Input(
            description = fullDescription,
            quantityText = composeCheatQuantity(detailedMode, findViewById<AutoCompleteTextView>(R.id.quantityInput).text?.toString()),
            notes = notes,
            occurredAtEpochMillis = occurredAt,
            foodImage = foodImage.takeIf { detailedMode },
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
        setAiActionEnabled(findViewById(R.id.analyzeButton), !state.running && !photoProcessing)
        setAiActionEnabled(findViewById(R.id.reevaluateButton), !state.running && !photoProcessing)
        setAiActionEnabled(findViewById(R.id.confirmButton), !state.running && !photoProcessing)
        setAiActionEnabled(findViewById(R.id.addLabelPhotoButton), !state.running && !photoProcessing)
        findViewById<View>(R.id.removeLabelPhotoButton).isEnabled = !state.running && !photoProcessing
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
                state.running && detailedMode && foodImage != null -> "L'IA sta valutando descrizione e foto per dirti cosa ha capito…"
                state.running -> "L'IA sta interpretando ciò che hai mangiato…"
                state.error != null -> state.error
                else -> ""
            }
        }

        val result = state.result ?: return
        foodImage = null
        viewModel.consumeResult()
        startActivity(
            Intent(this, AdjustedPlanActivity::class.java)
                .putExtra(AdjustedPlanActivity.EXTRA_DESCRIPTION, findViewById<TextInputEditText>(R.id.descriptionInput).text?.toString().orEmpty())
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

    companion object {
        private const val LANDSCAPE_GRID_TAG = "cheat_landscape_grid"
        const val EXTRA_OCCURRED_DATE_EPOCH_DAY = "cheat_occurred_date_epoch_day"
    }

    override fun onDestroy() {
        pendingCameraFile?.let { runCatching { photoTempStore.delete(it) } }
        pendingCameraFile = null
        foodImage = null
        super.onDestroy()
    }

}

/**
 * Builds the notes sent with a cheat entry. The free-text notes field only exists in the detailed mode,
 * so text typed there must be ignored when the quick mode is selected (the field is hidden).
 * The AI clarification belongs to the understanding step and is independent from the mode.
 */
internal fun composeCheatNotes(detailed: Boolean, notes: String, clarification: String): String? =
    listOfNotNull(
        notes.trim().takeIf { detailed && it.isNotBlank() },
        clarification.trim().takeIf { it.isNotBlank() }?.let { "Chiarimento utente dopo la prima lettura IA: $it" },
    ).joinToString("\n").takeIf { it.isNotBlank() }

/**
 * Quantity is a detailed-mode field: when the quick mode is selected the (hidden) value must not be sent,
 * so the AI estimates the portion from the description alone.
 */
internal fun composeCheatQuantity(detailed: Boolean, quantity: String?): String? =
    quantity?.trim()?.takeIf { detailed && it.isNotBlank() }
