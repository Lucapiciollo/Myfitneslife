package com.myfitai.app.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.ui.workout.WorkoutViewModel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class NewWorkoutActivity : BaseShellActivity() {

    private val data by lazy { AppDataContainer.get(this) }
    private val viewModel: WorkoutViewModel by viewModels {
        WorkoutViewModel.Factory(data.workoutRepository, data.activeProfileStore)
    }

    private var selectedDate = LocalDate.now()
    private var selectedTime = LocalTime.now().withSecond(0).withNano(0)
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ITALIAN)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_workout)
        bindBack()
        bindForm()
        observeEvents()
    }

    private fun bindForm() {
        val dateInput = findViewById<TextInputEditText>(R.id.dateInput)
        val timeInput = findViewById<TextInputEditText>(R.id.timeInput)
        val typeInput = findViewById<AutoCompleteTextView>(R.id.typeInput)
        val titleInput = findViewById<TextInputEditText>(R.id.titleInput)
        val durationInput = findViewById<TextInputEditText>(R.id.durationInput)
        val notesInput = findViewById<TextInputEditText>(R.id.notesInput)
        val restSwitch = findViewById<MaterialSwitch>(R.id.restDaySwitch)

        val types = listOf("Pesi", "Cardio", "Mobilità", "Sport", "Altro")
        typeInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, types))
        typeInput.setText(types.first(), false)

        fun renderDateTime() {
            dateInput.setText(selectedDate.format(dateFormatter))
            timeInput.setText(String.format(Locale.ITALIAN, "%02d:%02d", selectedTime.hour, selectedTime.minute))
        }
        renderDateTime()

        dateInput.setOnClickListener {
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Data allenamento")
                .setSelection(selectedDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
                .build()
            picker.addOnPositiveButtonClickListener { millis ->
                selectedDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
                renderDateTime()
            }
            picker.show(supportFragmentManager, "workout_date_picker")
        }

        timeInput.setOnClickListener {
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(selectedTime.hour)
                .setMinute(selectedTime.minute)
                .setTitleText("Ora allenamento")
                .build()
            picker.addOnPositiveButtonClickListener {
                selectedTime = LocalTime.of(picker.hour, picker.minute)
                renderDateTime()
            }
            picker.show(supportFragmentManager, "workout_time_picker")
        }

        restSwitch.setOnCheckedChangeListener { _, checked ->
            typeInput.isEnabled = !checked
            titleInput.isEnabled = !checked
            durationInput.isEnabled = !checked
        }

        findViewById<android.view.View>(R.id.saveWorkoutButton).setOnClickListener {
            val duration = durationInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()
            if (durationInput.text?.toString()?.isNotBlank() == true && duration == null) {
                Toast.makeText(this, "Durata non valida", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val startedAt = selectedDate.atTime(selectedTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            viewModel.save(
                startedAtEpochMillis = startedAt,
                type = typeInput.text?.toString().orEmpty(),
                title = titleInput.text?.toString().orEmpty(),
                durationMinutes = duration,
                isRestDay = restSwitch.isChecked,
                notes = notesInput.text?.toString(),
            )
        }
    }

    private fun observeEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.saved.collect {
                        Toast.makeText(this@NewWorkoutActivity, "Allenamento salvato nello storico", Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        finish()
                    }
                }
                launch {
                    viewModel.error.collect { message ->
                        Toast.makeText(this@NewWorkoutActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
