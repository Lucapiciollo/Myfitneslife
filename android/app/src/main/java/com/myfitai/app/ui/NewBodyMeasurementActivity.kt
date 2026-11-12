package com.myfitai.app.ui

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewParent
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.card.MaterialCardView
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.domain.body.BodyMeasurementFormState
import com.myfitai.app.domain.body.BodyMeasurementRanges
import com.myfitai.app.domain.body.BodyMeasurementRanges.Check
import com.myfitai.app.domain.body.BodyMeasurementRanges.Field
import com.myfitai.app.domain.body.BodyWeightHistory
import kotlinx.coroutines.flow.first
import com.myfitai.app.ui.body.BodyMeasurementsViewModel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class NewBodyMeasurementActivity : BaseShellActivity() {

    private val data by lazy { AppDataContainer.get(this) }
    private val viewModel: BodyMeasurementsViewModel by viewModels {
        BodyMeasurementsViewModel.Factory(data.bodyMeasurementRepository, data.activeProfileStore, data.nutritionPathTrigger)
    }

    private val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ITALIAN)
    private var selectedDate: LocalDate = LocalDate.now()
    private var editingMeasurement: BodyMeasurementEntity? = null
    private val requestedEditId: Long by lazy { intent.getLongExtra(EXTRA_EDIT_ID, 0L) }

    /** Stato di partenza per l'avviso di uscita: nullo finche' la misura da modificare non e' stata caricata. */
    private var baselineValues: List<String>? = null
    private var baselineDate: LocalDate = selectedDate

    private val exitGuard = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = requestExit()
    }

    private class FieldSpec(val inputId: Int, val field: Field, @StringRes val label: Int)

    /** Ordine di salvataggio e di focus sugli errori; un solo elenco lega campo, intervallo ed etichetta. */
    private val specs = listOf(
        FieldSpec(R.id.bodyWeightInput, Field.WEIGHT, R.string.measure_field_weight),
        FieldSpec(R.id.chestInput, Field.CHEST, R.string.measure_field_chest),
        FieldSpec(R.id.waistInput, Field.WAIST, R.string.measure_field_waist),
        FieldSpec(R.id.abdomenInput, Field.ABDOMEN, R.string.measure_field_abdomen),
        FieldSpec(R.id.shouldersInput, Field.SHOULDERS, R.string.measure_field_shoulders),
        FieldSpec(R.id.glutesInput, Field.GLUTES, R.string.measure_field_glutes),
        FieldSpec(R.id.hipsInput, Field.HIPS, R.string.measure_field_hips),
        FieldSpec(R.id.armLeftInput, Field.ARM_LEFT, R.string.measure_field_arm_left),
        FieldSpec(R.id.armRightInput, Field.ARM_RIGHT, R.string.measure_field_arm_right),
        FieldSpec(R.id.thighLeftInput, Field.THIGH_LEFT, R.string.measure_field_thigh_left),
        FieldSpec(R.id.thighRightInput, Field.THIGH_RIGHT, R.string.measure_field_thigh_right),
        FieldSpec(R.id.calfLeftInput, Field.CALF_LEFT, R.string.measure_field_calf_left),
        FieldSpec(R.id.calfRightInput, Field.CALF_RIGHT, R.string.measure_field_calf_right),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_body_measurement)
        onBackPressedDispatcher.addCallback(this, exitGuard)
        bindBack()
        findViewById<View>(R.id.backButton)?.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        normalizeFormCards()
        findViewById<TextView>(R.id.title).text = if (requestedEditId > 0L) "Modifica misurazione" else "Nuova misurazione"

        // Dopo una ricreazione (rotazione, cambio tema) la data scelta e la base per l'avviso di uscita tornano quelle di prima.
        val restoredEditLoaded = savedInstanceState?.getBoolean(STATE_BASELINE_READY, false) == true
        savedInstanceState?.getLong(STATE_SELECTED_DAY, Long.MIN_VALUE)
            ?.takeIf { it != Long.MIN_VALUE }
            ?.let { selectedDate = LocalDate.ofEpochDay(it) }
        if (savedInstanceState != null) {
            savedInstanceState.getLong(STATE_BASELINE_DAY, Long.MIN_VALUE)
                .takeIf { it != Long.MIN_VALUE }
                ?.let { baselineDate = LocalDate.ofEpochDay(it) }
            baselineValues = savedInstanceState.getStringArrayList(STATE_BASELINE_VALUES)
        }
        if (requestedEditId <= 0L && baselineValues == null) {
            baselineDate = selectedDate
            baselineValues = specs.map { "" }
        }

        bindDatePicker()
        bindInlineErrorReset()
        bindSave()
        observeEvents()
        if (requestedEditId > 0L) loadForEdit(requestedEditId, keepRestoredInput = restoredEditLoaded)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_SELECTED_DAY, selectedDate.toEpochDay())
        outState.putLong(STATE_BASELINE_DAY, baselineDate.toEpochDay())
        baselineValues?.let {
            outState.putStringArrayList(STATE_BASELINE_VALUES, ArrayList(it))
            outState.putBoolean(STATE_BASELINE_READY, true)
        }
    }

    private fun loadForEdit(id: Long, keepRestoredInput: Boolean) {
        val saveButton = findViewById<android.view.View>(R.id.saveMeasurementButton)
        saveButton.isEnabled = false
        lifecycleScope.launch {
            val profileId = data.activeProfileStore.currentIdOrNull()
            val existing = profileId?.let { profile ->
                data.bodyMeasurementRepository.all(profile).first().firstOrNull { it.id == id }
            }
            if (existing == null) {
                Toast.makeText(this@NewBodyMeasurementActivity, "Misurazione non disponibile per il profilo attivo", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            editingMeasurement = existing
            if (!keepRestoredInput) {
                // Prima apertura: si mostrano i valori salvati. Dopo una ricreazione si tengono invece quelli digitati dall'utente.
                selectedDate = Instant.ofEpochMilli(existing.measuredAtEpochMillis)
                    .atZone(ZoneOffset.UTC).toLocalDate()
                findViewById<TextInputEditText>(R.id.dateInput).setText(selectedDate.format(formatter))
                val values = mapOf(
                    R.id.bodyWeightInput to existing.weightKg,
                    R.id.chestInput to existing.chestCm,
                    R.id.waistInput to existing.waistCm,
                    R.id.abdomenInput to existing.abdomenCm,
                    R.id.shouldersInput to existing.shouldersCm,
                    R.id.hipsInput to existing.hipsCm,
                    R.id.glutesInput to existing.glutesCm,
                    R.id.armLeftInput to existing.armLeftCm,
                    R.id.armRightInput to existing.armRightCm,
                    R.id.thighLeftInput to existing.thighLeftCm,
                    R.id.thighRightInput to existing.thighRightCm,
                    R.id.calfLeftInput to existing.calfLeftCm,
                    R.id.calfRightInput to existing.calfRightCm,
                )
                specs.forEach { spec ->
                    findViewById<TextInputEditText>(spec.inputId)
                        .setText(values[spec.inputId]?.let { String.format(Locale.ITALIAN, "%.1f", it) }.orEmpty())
                }
                baselineDate = selectedDate
                baselineValues = currentValues()
            }
            val linkedWeight = BodyWeightHistory.weightFor(
                existing,
                data.biaRepository.all(existing.profileId).first(),
            )
            findViewById<TextView>(R.id.sameDateBiaWeightHint).apply {
                visibility = if (linkedWeight?.fromBia == true) View.VISIBLE else View.GONE
                text = linkedWeight?.takeIf { it.fromBia }?.let {
                    "Peso BIA della stessa data: ${String.format(Locale.ITALIAN, "%.1f", it.kg)} kg. " +
                        "È visibile nello storico; inserisci un peso manuale solo se vuoi registrarlo separatamente."
                }.orEmpty()
            }
            findViewById<android.widget.TextView>(R.id.saveMeasurementButton).apply {
                text = "Salva modifiche"
                contentDescription = "Salva modifiche"
            }
            saveButton.isEnabled = true
        }
    }

    private fun normalizeFormCards() {
        val root = findViewById<View>(android.R.id.content)
        fun walk(view: View) {
            if (view is MaterialCardView) {
                view.setCardBackgroundColor(getColor(R.color.white))
                view.strokeColor = getColor(R.color.divider)
                view.strokeWidth = resources.getDimensionPixelSize(R.dimen.border_width_default)
                view.cardElevation = resources.getDimension(R.dimen.elevation_none)
            }
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) walk(view.getChildAt(index))
            }
        }
        walk(root)
    }

    private fun bindDatePicker() {
        val dateLayout = findViewById<TextInputLayout>(R.id.dateLayout)
        val dateInput = findViewById<TextInputEditText>(R.id.dateInput)
        dateInput.setText(selectedDate.format(formatter))

        val openDatePicker = {
            // Le date future non sono selezionabili: il fine giornata di oggi (UTC, come la selezione) e' l'ultimo giorno valido.
            val lastValidDay = LocalDate.now().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1L
            val constraints = CalendarConstraints.Builder()
                .setEnd(lastValidDay)
                .setValidator(DateValidatorPointBackward.before(lastValidDay + 1L))
                .build()
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTheme(R.style.ThemeOverlay_MyFitAI_MaterialCalendar)
                .setTitleText("Data misurazione")
                .setCalendarConstraints(constraints)
                .setSelection(selectedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                .build()
            picker.addOnPositiveButtonClickListener { selection ->
                selectedDate = Instant.ofEpochMilli(selection).atZone(ZoneOffset.UTC).toLocalDate()
                dateInput.setText(selectedDate.format(formatter))
            }
            picker.show(supportFragmentManager, "body_measurement_date_picker")
        }

        dateInput.setOnClickListener { openDatePicker() }
        dateLayout.setEndIconOnClickListener { openDatePicker() }
        dateInput.doAfterTextChanged { dateLayout.error = null }
    }

    /** L'errore di un campo sparisce appena l'utente lo modifica. */
    private fun bindInlineErrorReset() {
        specs.forEach { spec ->
            val input = findViewById<TextInputEditText>(spec.inputId)
            input.doAfterTextChanged { layoutOf(input)?.error = null }
        }
    }

    private fun bindSave() {
        findViewById<android.view.View>(R.id.saveMeasurementButton).setOnClickListener { trySave() }
    }

    /** Valida con errori sotto ogni campo; restituisce false (e porta il focus sul primo errore) se c'e' qualcosa da correggere. */
    private fun trySave(): Boolean {
        val values = validatedValues() ?: return false
        val original = editingMeasurement
        val measuredAt = if (original != null &&
            Instant.ofEpochMilli(original.measuredAtEpochMillis).atZone(ZoneOffset.UTC).toLocalDate() == selectedDate
        ) original.measuredAtEpochMillis
        else selectedDate.atTime(LocalTime.NOON).toInstant(ZoneOffset.UTC).toEpochMilli()
        viewModel.save(
            measuredAtEpochMillis = measuredAt,
            chestCm = values[R.id.chestInput],
            waistCm = values[R.id.waistInput],
            abdomenCm = values[R.id.abdomenInput],
            shouldersCm = values[R.id.shouldersInput],
            glutesCm = values[R.id.glutesInput],
            hipsCm = values[R.id.hipsInput],
            weightKg = values[R.id.bodyWeightInput],
            existingMeasurementId = original?.id,
            armLeftCm = values[R.id.armLeftInput],
            armRightCm = values[R.id.armRightInput],
            thighLeftCm = values[R.id.thighLeftInput],
            thighRightCm = values[R.id.thighRightInput],
            calfLeftCm = values[R.id.calfLeftInput],
            calfRightCm = values[R.id.calfRightInput],
        )
        return true
    }

    private fun validatedValues(): Map<Int, Float?>? {
        var firstInvalid: View? = null
        val dateLayout = findViewById<TextInputLayout>(R.id.dateLayout)
        dateLayout.error = null
        if (BodyMeasurementRanges.isFutureDate(selectedDate, LocalDate.now())) {
            dateLayout.error = getString(R.string.measure_error_future_date)
            firstInvalid = dateLayout
        }
        val parsed = LinkedHashMap<Int, Float?>()
        specs.forEach { spec ->
            val input = findViewById<TextInputEditText>(spec.inputId)
            val layout = layoutOf(input)
            val message: String? = when (val check = BodyMeasurementRanges.check(spec.field, input.text?.toString())) {
                Check.Empty -> { parsed[spec.inputId] = null; null }
                is Check.Valid -> { parsed[spec.inputId] = check.value; null }
                Check.NotANumber -> getString(R.string.measure_error_not_a_number)
                is Check.OutOfRange -> getString(
                    R.string.measure_error_out_of_range,
                    getString(spec.label),
                    BodyMeasurementRanges.formatBound(check.min),
                    BodyMeasurementRanges.formatBound(check.max),
                    spec.field.unit,
                )
            }
            layout?.error = message
            if (message != null && firstInvalid == null) firstInvalid = input
        }
        if (firstInvalid == null && parsed.values.all { it == null }) {
            val weight = findViewById<TextInputEditText>(R.id.bodyWeightInput)
            layoutOf(weight)?.error = getString(R.string.measure_error_none)
            firstInvalid = weight
        }
        firstInvalid?.let { target ->
            if (target is TextInputEditText) target.requestFocus()
            else target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height))
            return null
        }
        return parsed
    }

    private fun layoutOf(input: View): TextInputLayout? {
        var parent: ViewParent? = input.parent
        while (parent != null && parent !is TextInputLayout) parent = parent.parent
        return parent as? TextInputLayout
    }

    private fun currentValues(): List<String> =
        specs.map { findViewById<TextInputEditText>(it.inputId).text?.toString().orEmpty() }

    private fun isDirty(): Boolean {
        val base = baselineValues ?: return false
        return BodyMeasurementFormState.isDirty(base, currentValues(), baselineDate, selectedDate)
    }

    private fun requestExit() {
        if (!isDirty()) {
            leaveForm()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.measure_discard_title)
            .setMessage(R.string.measure_discard_message)
            .setNeutralButton(R.string.measure_discard_keep, null)
            .setPositiveButton(R.string.measure_discard_save) { _, _ -> trySave() }
            .setNegativeButton(R.string.measure_discard_exit) { _, _ -> leaveForm() }
            .show()
    }

    private fun leaveForm() {
        exitGuard.isEnabled = false
        onBackPressedDispatcher.onBackPressed()
        exitGuard.isEnabled = true
    }

    private fun observeEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.saved.collect {
                        Toast.makeText(this@NewBodyMeasurementActivity, if (editingMeasurement != null) "Misurazione aggiornata" else "Misurazione salvata nello storico", Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        exitGuard.isEnabled = false
                        finish()
                    }
                }
                launch {
                    viewModel.error.collect { message ->
                        Toast.makeText(this@NewBodyMeasurementActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_EDIT_ID = "edit_body_measurement_id"
        private const val STATE_SELECTED_DAY = "new_measure_selected_day"
        private const val STATE_BASELINE_DAY = "new_measure_baseline_day"
        private const val STATE_BASELINE_VALUES = "new_measure_baseline_values"
        private const val STATE_BASELINE_READY = "new_measure_baseline_ready"
    }
}
