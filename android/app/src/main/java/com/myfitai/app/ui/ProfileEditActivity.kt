package com.myfitai.app.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.ui.profile.ProfileEditViewModel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class ProfileEditActivity : BaseShellActivity() {

    private val data by lazy { AppDataContainer.get(this) }
    private val viewModel: ProfileEditViewModel by viewModels {
        ProfileEditViewModel.Factory(data.userProfileRepository, data.activeProfileStore)
    }

    private lateinit var nameInput: TextInputEditText
    private lateinit var birthDateInput: TextInputEditText
    private lateinit var heightInput: TextInputEditText
    private lateinit var weightInput: TextInputEditText
    private lateinit var goalInput: AutoCompleteTextView
    private lateinit var activityInput: AutoCompleteTextView
    private lateinit var wakeTimeInput: TextInputEditText
    private lateinit var sleepTimeInput: TextInputEditText
    private lateinit var preferencesInput: TextInputEditText
    private lateinit var saveButton: MaterialButton

    private var birthDateEpochDay: Long? = null
    private var wakeTimeMinutes: Int? = null
    private var sleepTimeMinutes: Int? = null
    private var initialRenderedProfileId: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_edit)
        bindBack()
        bindViews()
        bindDropdowns()
        bindPickers()
        bindSave()
        observeState()
    }

    private fun bindViews() {
        nameInput = findViewById(R.id.nameInput)
        birthDateInput = findViewById(R.id.birthDateInput)
        heightInput = findViewById(R.id.heightInput)
        weightInput = findViewById(R.id.weightInput)
        goalInput = findViewById(R.id.goalInput)
        activityInput = findViewById(R.id.activityInput)
        wakeTimeInput = findViewById(R.id.wakeTimeInput)
        sleepTimeInput = findViewById(R.id.sleepTimeInput)
        preferencesInput = findViewById(R.id.preferencesInput)
        saveButton = findViewById(R.id.saveProfileButton)
    }

    private fun bindDropdowns() {
        goalInput.setAdapter(
            ArrayAdapter.createFromResource(
                this,
                R.array.profile_goals,
                android.R.layout.simple_dropdown_item_1line,
            )
        )
        activityInput.setAdapter(
            ArrayAdapter.createFromResource(
                this,
                R.array.profile_activity_levels,
                android.R.layout.simple_dropdown_item_1line,
            )
        )
    }

    private fun bindPickers() {
        birthDateInput.setOnClickListener {
            val builder = MaterialDatePicker.Builder.datePicker().setTitleText("Data di nascita")
            birthDateEpochDay?.let { epochDay ->
                builder.setSelection(LocalDate.ofEpochDay(epochDay).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            }
            val picker = builder.build()
            picker.addOnPositiveButtonClickListener { millis ->
                val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                birthDateEpochDay = date.toEpochDay()
                birthDateInput.setText(date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ITALIAN)))
            }
            picker.show(supportFragmentManager, "profile_birth_date")
        }

        wakeTimeInput.setOnClickListener { showTimePicker("Ora di sveglia", wakeTimeMinutes) { value ->
            wakeTimeMinutes = value
            wakeTimeInput.setText(formatMinutes(value))
        } }

        sleepTimeInput.setOnClickListener { showTimePicker("Ora di sonno", sleepTimeMinutes) { value ->
            sleepTimeMinutes = value
            sleepTimeInput.setText(formatMinutes(value))
        } }
    }

    private fun showTimePicker(title: String, current: Int?, onSelected: (Int) -> Unit) {
        val hour = current?.div(60) ?: 7
        val minute = current?.rem(60) ?: 0
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(hour)
            .setMinute(minute)
            .setTitleText(title)
            .build()
        picker.addOnPositiveButtonClickListener { onSelected(picker.hour * 60 + picker.minute) }
        picker.show(supportFragmentManager, "profile_time_picker")
    }

    private fun bindSave() {
        saveButton.setOnClickListener {
            clearErrors()
            val name = nameInput.text?.toString()?.trim().orEmpty()
            val height = parseFloat(heightInput.text?.toString())
            val weight = parseFloat(weightInput.text?.toString())

            var valid = true
            if (name.isBlank()) {
                findViewById<TextInputLayout>(R.id.nameLayout).error = "Inserisci un nome"
                valid = false
            }
            if (height != null && height <= 0f) {
                findViewById<TextInputLayout>(R.id.heightLayout).error = "Altezza non valida"
                valid = false
            }
            if (weight != null && weight <= 0f) {
                findViewById<TextInputLayout>(R.id.weightLayout).error = "Peso non valido"
                valid = false
            }
            if (!valid) return@setOnClickListener

            val preferencesJson = preferencesInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                JSONObject().put("notes", it).toString()
            }

            viewModel.save(
                name = name,
                birthDateEpochDay = birthDateEpochDay,
                heightCm = height,
                currentWeightKg = weight,
                goal = goalInput.text?.toString(),
                activityLevel = activityInput.text?.toString(),
                wakeTimeMinutes = wakeTimeMinutes,
                sleepTimeMinutes = sleepTimeMinutes,
                dietaryPreferencesJson = preferencesJson,
            )
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.profile.collect { profile ->
                        if (profile != null && initialRenderedProfileId != profile.id) {
                            render(profile)
                            initialRenderedProfileId = profile.id
                        }
                    }
                }
                launch {
                    viewModel.saving.collect { saving ->
                        saveButton.isEnabled = !saving
                        saveButton.text = if (saving) "Salvataggio…" else "Salva profilo"
                    }
                }
                launch {
                    viewModel.saved.collect {
                        Toast.makeText(this@ProfileEditActivity, "Profilo salvato", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
                launch {
                    viewModel.error.collect { message ->
                        Toast.makeText(this@ProfileEditActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun render(profile: UserProfileEntity) {
        findViewById<TextView>(R.id.title).text = "Modifica ${profile.name}"
        nameInput.setText(profile.name)
        birthDateEpochDay = profile.birthDateEpochDay
        birthDateInput.setText(profile.birthDateEpochDay?.let {
            LocalDate.ofEpochDay(it).format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ITALIAN))
        }.orEmpty())
        heightInput.setText(profile.heightCm?.let(::formatNumber).orEmpty())
        weightInput.setText(profile.currentWeightKg?.let(::formatNumber).orEmpty())
        goalInput.setText(profile.goal.orEmpty(), false)
        activityInput.setText(profile.activityLevel.orEmpty(), false)
        wakeTimeMinutes = profile.wakeTimeMinutes
        sleepTimeMinutes = profile.sleepTimeMinutes
        wakeTimeInput.setText(profile.wakeTimeMinutes?.let(::formatMinutes).orEmpty())
        sleepTimeInput.setText(profile.sleepTimeMinutes?.let(::formatMinutes).orEmpty())
        preferencesInput.setText(readPreferenceNotes(profile.dietaryPreferencesJson))
    }

    private fun readPreferenceNotes(json: String?): String {
        if (json.isNullOrBlank()) return ""
        return runCatching { JSONObject(json).optString("notes") }.getOrDefault(json)
    }

    private fun clearErrors() {
        findViewById<TextInputLayout>(R.id.nameLayout).error = null
        findViewById<TextInputLayout>(R.id.heightLayout).error = null
        findViewById<TextInputLayout>(R.id.weightLayout).error = null
    }

    private fun parseFloat(value: String?): Float? = value
        ?.trim()
        ?.replace(',', '.')
        ?.takeIf { it.isNotEmpty() }
        ?.toFloatOrNull()

    private fun formatMinutes(total: Int): String = String.format(Locale.ITALIAN, "%02d:%02d", total / 60, total % 60)

    private fun formatNumber(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.ITALIAN, "%.1f", value)
}
