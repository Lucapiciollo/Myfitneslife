package com.myfitai.app.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.card.MaterialCardView
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_body_measurement)
        bindBack()
        normalizeFormCards()
        findViewById<TextView>(R.id.title).text = if (requestedEditId > 0L) "Modifica misurazione" else "Nuova misurazione"
        bindDatePicker()
        bindSave()
        observeEvents()
        if (requestedEditId > 0L) loadForEdit(requestedEditId)
    }

    private fun loadForEdit(id: Long) {
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
            inputMap().forEach { (key, input) ->
                input.setText(values[key]?.let { String.format(Locale.ITALIAN, "%.1f", it) }.orEmpty())
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
                view.strokeWidth = resources.getDimensionPixelSize(R.dimen.space_1)
                view.cardElevation = 0f
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
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Data misurazione")
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
    }

    private fun bindSave() {
        findViewById<android.view.View>(R.id.saveMeasurementButton).setOnClickListener {
            val inputs = inputMap()
            if (inputs.values.any { input ->
                    val raw = input.text?.toString().orEmpty().trim()
                    raw.isNotEmpty() && parseFloat(raw) == null
                }) {
                Toast.makeText(this, "Controlla i numeri inseriti: usa valori come 86,5", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val values = inputs.mapValues { (_, input) -> parseFloat(input.text?.toString()) }
            if (values.values.all { it == null }) {
                Toast.makeText(this, "Inserisci almeno una misura", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (values.values.filterNotNull().any { !it.isFinite() || it <= 0f || it > 300f }) {
                Toast.makeText(this, "Controlla i valori: circonferenze in cm e peso in kg devono essere maggiori di 0 e non oltre 300", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

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
        }
    }

    private fun observeEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.saved.collect {
                        Toast.makeText(this@NewBodyMeasurementActivity, if (editingMeasurement != null) "Misurazione aggiornata" else "Misurazione salvata nello storico", Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
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

    private fun inputMap(): Map<Int, TextInputEditText> = listOf(
        R.id.bodyWeightInput,
        R.id.chestInput,
        R.id.waistInput,
        R.id.abdomenInput,
        R.id.shouldersInput,
        R.id.glutesInput,
        R.id.hipsInput,
        R.id.armLeftInput,
        R.id.armRightInput,
        R.id.thighLeftInput,
        R.id.thighRightInput,
        R.id.calfLeftInput,
        R.id.calfRightInput,
    ).associateWith(::findViewById)

    companion object {
        const val EXTRA_EDIT_ID = "edit_body_measurement_id"
    }

    private fun parseFloat(value: String?): Float? = value
        ?.trim()
        ?.replace(',', '.')
        ?.takeIf { it.isNotEmpty() }
        ?.toFloatOrNull()
}
