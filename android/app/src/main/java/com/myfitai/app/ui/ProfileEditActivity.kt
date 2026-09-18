package com.myfitai.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
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
import com.myfitai.app.domain.food.DietaryProfile
import com.myfitai.app.ui.profile.ProfileEditViewModel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class ProfileEditActivity : BaseShellActivity() {

    private val isBootstrap by lazy { intent.getBooleanExtra(EXTRA_BOOTSTRAP, false) }
    private val isCreate by lazy { isBootstrap || intent.getBooleanExtra(EXTRA_CREATE, false) }
    private val data by lazy { AppDataContainer.get(this) }
    private val viewModel: ProfileEditViewModel by viewModels {
        ProfileEditViewModel.Factory(data.userProfileRepository, data.activeProfileStore, allowCreate = isCreate)
    }

    private lateinit var nameInput: TextInputEditText
    private lateinit var birthDateInput: TextInputEditText
    private lateinit var sexInput: AutoCompleteTextView
    private lateinit var heightInput: TextInputEditText
    private lateinit var weightInput: TextInputEditText
    private lateinit var goalInput: AutoCompleteTextView
    private lateinit var activityInput: AutoCompleteTextView
    private lateinit var wakeTimeInput: TextInputEditText
    private lateinit var sleepTimeInput: TextInputEditText
    private lateinit var preferredFoodsInput: TextInputEditText
    private lateinit var dislikedFoodsInput: TextInputEditText
    private lateinit var excludedFoodsInput: TextInputEditText
    private lateinit var intolerancesInput: TextInputEditText
    private lateinit var allergiesInput: TextInputEditText
    private lateinit var dietStyleInput: AutoCompleteTextView
    private lateinit var preferencesInput: TextInputEditText
    private lateinit var saveButton: MaterialButton

    private var birthDateEpochDay: Long? = null
    private var wakeTimeMinutes: Int? = null
    private var sleepTimeMinutes: Int? = null
    private var initialRenderedProfileId: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_edit)
        when {
            isBootstrap -> {
                findViewById<View>(R.id.backButton).visibility = View.INVISIBLE
                findViewById<TextView>(R.id.title).text = "Crea il tuo profilo"
                findViewById<View>(R.id.bootstrapHint).visibility = View.VISIBLE
            }
            isCreate -> {
                bindBack()
                findViewById<TextView>(R.id.title).text = "Nuovo profilo"
                findViewById<View>(R.id.bootstrapHint).visibility = View.VISIBLE
            }
            else -> bindBack()
        }
        bindViews()
        bindDropdowns()
        bindPickers()
        bindSave()
        observeState()
    }

    private fun bindViews() {
        nameInput = findViewById(R.id.nameInput)
        birthDateInput = findViewById(R.id.birthDateInput)
        sexInput = findViewById(R.id.sexInput)
        heightInput = findViewById(R.id.heightInput)
        weightInput = findViewById(R.id.weightInput)
        goalInput = findViewById(R.id.goalInput)
        activityInput = findViewById(R.id.activityInput)
        wakeTimeInput = findViewById(R.id.wakeTimeInput)
        sleepTimeInput = findViewById(R.id.sleepTimeInput)
        preferredFoodsInput = findViewById(R.id.preferredFoodsInput)
        dislikedFoodsInput = findViewById(R.id.dislikedFoodsInput)
        excludedFoodsInput = findViewById(R.id.excludedFoodsInput)
        intolerancesInput = findViewById(R.id.intolerancesInput)
        allergiesInput = findViewById(R.id.allergiesInput)
        dietStyleInput = findViewById(R.id.dietStyleInput)
        preferencesInput = findViewById(R.id.preferencesInput)
        saveButton = findViewById(R.id.saveProfileButton)
    }

    private fun bindDropdowns() {
        sexInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, listOf("Maschio", "Femmina")))
        goalInput.setAdapter(ArrayAdapter.createFromResource(this, R.array.profile_goals, android.R.layout.simple_dropdown_item_1line))
        activityInput.setAdapter(ArrayAdapter.createFromResource(this, R.array.profile_activity_levels, android.R.layout.simple_dropdown_item_1line))
        dietStyleInput.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                listOf("Nessuno", "Onnivoro", "Vegetariano", "Vegano", "Pescetariano"),
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
        wakeTimeInput.setOnClickListener { showTimePicker("Ora di sveglia", wakeTimeMinutes) { value -> wakeTimeMinutes = value; wakeTimeInput.setText(formatMinutes(value)) } }
        sleepTimeInput.setOnClickListener { showTimePicker("Ora di sonno", sleepTimeMinutes) { value -> sleepTimeMinutes = value; sleepTimeInput.setText(formatMinutes(value)) } }
    }

    private fun showTimePicker(title: String, current: Int?, onSelected: (Int) -> Unit) {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(current?.div(60) ?: 7)
            .setMinute(current?.rem(60) ?: 0)
            .setTitleText(title)
            .build()
        picker.addOnPositiveButtonClickListener { onSelected(picker.hour * 60 + picker.minute) }
        picker.show(supportFragmentManager, "profile_time_picker")
    }

    private fun bindSave() {
        saveButton.setOnClickListener {
            clearErrors()
            val name = nameInput.text?.toString()?.trim().orEmpty()
            val sex = sexInput.text?.toString()?.trim().orEmpty()
            val height = parseFloat(heightInput.text?.toString())
            val weight = parseFloat(weightInput.text?.toString())
            val goal = goalInput.text?.toString()?.trim().orEmpty()
            val activity = activityInput.text?.toString()?.trim().orEmpty()

            var valid = true
            fun fail(layoutId: Int, message: String) {
                findViewById<TextInputLayout>(layoutId).error = message
                valid = false
            }

            if (name.isBlank()) fail(R.id.nameLayout, "Inserisci un nome")
            if (birthDateEpochDay != null && LocalDate.ofEpochDay(birthDateEpochDay!!).isAfter(LocalDate.now())) fail(R.id.birthDateLayout, "Data non valida")
            if (height != null && (height < 80f || height > 250f)) fail(R.id.heightLayout, "Altezza non valida")
            if (weight != null && (weight < 20f || weight > 400f)) fail(R.id.weightLayout, "Peso non valido")

            if (isCreate) {
                if (birthDateEpochDay == null) fail(R.id.birthDateLayout, "Inserisci la data di nascita")
                if (sex !in setOf("Maschio", "Femmina")) fail(R.id.sexLayout, "Seleziona il sesso biologico")
                if (height == null) fail(R.id.heightLayout, "Inserisci l'altezza")
                if (weight == null) fail(R.id.weightLayout, "Inserisci il peso iniziale")
                if (goal.isBlank()) fail(R.id.goalLayout, "Seleziona un obiettivo")
                if (activity.isBlank()) fail(R.id.activityLayout, "Seleziona il livello di attività")
            }
            if (!valid) return@setOnClickListener

            val dietaryProfile = DietaryProfile(
                preferredFoods = DietaryProfile.csv(preferredFoodsInput.text?.toString()),
                dislikedFoods = DietaryProfile.csv(dislikedFoodsInput.text?.toString()),
                excludedFoods = DietaryProfile.csv(excludedFoodsInput.text?.toString()),
                intolerances = DietaryProfile.csv(intolerancesInput.text?.toString()),
                allergies = DietaryProfile.csv(allergiesInput.text?.toString()),
                dietStyle = dietStyleInput.text?.toString()?.trim()?.takeIf {
                    it.isNotBlank() && !it.equals("Nessuno", ignoreCase = true)
                },
                notes = preferencesInput.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
            )
            viewModel.save(
                name = name,
                birthDateEpochDay = birthDateEpochDay,
                biologicalSex = sex.takeIf { it.isNotBlank() },
                heightCm = height,
                currentWeightKg = weight,
                goal = goal,
                activityLevel = activity,
                wakeTimeMinutes = wakeTimeMinutes,
                sleepTimeMinutes = sleepTimeMinutes,
                dietaryPreferencesJson = dietaryProfile.toJson(),
            )
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.profile.collect { profile ->
                        if (!isCreate && profile != null && initialRenderedProfileId != profile.id) {
                            render(profile)
                            initialRenderedProfileId = profile.id
                        }
                    }
                }
                launch {
                    viewModel.saving.collect { saving ->
                        saveButton.isEnabled = !saving
                        saveButton.text = if (saving) "Salvataggio…" else if (isCreate) "Crea profilo e continua" else "Salva profilo"
                    }
                }
                launch {
                    viewModel.saved.collect {
                        data.nutritionPathTrigger.maybeEnqueue(it)
                        Toast.makeText(this@ProfileEditActivity, if (isCreate) "Profilo creato" else "Profilo salvato", Toast.LENGTH_SHORT).show()
                        if (isBootstrap) {
                            startActivity(Intent(this@ProfileEditActivity, HomeActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            })
                            finish()
                        } else if (isCreate) {
                            startActivity(Intent(this@ProfileEditActivity, HomeActivity::class.java).apply {
                                putExtra(com.myfitai.app.navigation.BottomNavBinder.EXTRA_TAB_ROOT, true)
                                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                            })
                            finish()
                        } else finish()
                    }
                }
                launch { viewModel.error.collect { message -> Toast.makeText(this@ProfileEditActivity, message, Toast.LENGTH_SHORT).show() } }
            }
        }
    }

    private fun render(profile: UserProfileEntity) {
        findViewById<TextView>(R.id.title).text = "Modifica ${profile.name}"
        nameInput.setText(profile.name)
        birthDateEpochDay = profile.birthDateEpochDay
        birthDateInput.setText(profile.birthDateEpochDay?.let { LocalDate.ofEpochDay(it).format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ITALIAN)) }.orEmpty())
        sexInput.setText(profile.biologicalSex.orEmpty(), false)
        heightInput.setText(profile.heightCm?.let(::formatNumber).orEmpty())
        weightInput.setText(profile.currentWeightKg?.let(::formatNumber).orEmpty())
        goalInput.setText(profile.goal.orEmpty(), false)
        activityInput.setText(profile.activityLevel.orEmpty(), false)
        wakeTimeMinutes = profile.wakeTimeMinutes
        sleepTimeMinutes = profile.sleepTimeMinutes
        wakeTimeInput.setText(profile.wakeTimeMinutes?.let(::formatMinutes).orEmpty())
        sleepTimeInput.setText(profile.sleepTimeMinutes?.let(::formatMinutes).orEmpty())

        val dietaryProfile = DietaryProfile.parse(profile.dietaryPreferencesJson)
        preferredFoodsInput.setText(dietaryProfile.preferredFoods.joinToString(", "))
        dislikedFoodsInput.setText(dietaryProfile.dislikedFoods.joinToString(", "))
        excludedFoodsInput.setText(dietaryProfile.excludedFoods.joinToString(", "))
        intolerancesInput.setText(dietaryProfile.intolerances.joinToString(", "))
        allergiesInput.setText(dietaryProfile.allergies.joinToString(", "))
        dietStyleInput.setText(dietaryProfile.dietStyle ?: "Nessuno", false)
        preferencesInput.setText(dietaryProfile.notes.orEmpty())
    }

    private fun clearErrors() {
        listOf(R.id.nameLayout, R.id.birthDateLayout, R.id.sexLayout, R.id.heightLayout, R.id.weightLayout, R.id.goalLayout, R.id.activityLayout)
            .forEach { findViewById<TextInputLayout>(it).error = null }
    }

    private fun parseFloat(value: String?): Float? = value?.trim()?.replace(',', '.')?.takeIf { it.isNotEmpty() }?.toFloatOrNull()
    private fun formatMinutes(total: Int): String = String.format(Locale.ITALIAN, "%02d:%02d", total / 60, total % 60)
    private fun formatNumber(value: Float): String = if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.ITALIAN, "%.1f", value)

    companion object {
        const val EXTRA_BOOTSTRAP = "profile_bootstrap"
        const val EXTRA_CREATE = "profile_create"
    }
}
