package com.myfitai.app.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_body_measurement)
        bindBack()
        bindDatePicker()
        bindSave()
        observeEvents()
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
            val values = inputMap().mapValues { (_, input) -> parseFloat(input.text?.toString()) }
            if (values.values.all { it == null }) {
                Toast.makeText(this, "Inserisci almeno una misura", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (values.values.filterNotNull().any { !it.isFinite() || it <= 0f || it > 300f }) {
                Toast.makeText(this, "Controlla i valori: circonferenze in cm e peso in kg devono essere maggiori di 0 e non oltre 300", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val measuredAt = selectedDate.atTime(LocalTime.NOON).toInstant(ZoneOffset.UTC).toEpochMilli()
            viewModel.save(
                measuredAtEpochMillis = measuredAt,
                chestCm = values[R.id.chestInput],
                waistCm = values[R.id.waistInput],
                abdomenCm = values[R.id.abdomenInput],
                shouldersCm = values[R.id.shouldersInput],
                glutesCm = values[R.id.glutesInput],
                hipsCm = values[R.id.hipsInput],
                weightKg = values[R.id.bodyWeightInput],
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
                        Toast.makeText(this@NewBodyMeasurementActivity, "Misurazione salvata nello storico", Toast.LENGTH_SHORT).show()
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

    private fun parseFloat(value: String?): Float? = value
        ?.trim()
        ?.replace(',', '.')
        ?.takeIf { it.isNotEmpty() }
        ?.toFloatOrNull()
}
