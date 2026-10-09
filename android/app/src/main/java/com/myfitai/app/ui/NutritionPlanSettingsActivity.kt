package com.myfitai.app.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.data.profile.NutritionPlanSchedulePreferences
import com.myfitai.app.domain.calculation.DailyActivityCheckInEngine.Intensity
import com.myfitai.app.domain.calculation.TrainingProgram
import java.time.DayOfWeek
import java.util.Locale

class NutritionPlanSettingsActivity : BaseShellActivity() {
    private val data by lazy { AppDataContainer.get(this) }
    private val profileId get() = data.activeProfileStore.currentIdOrNull()
    private var originalTrainingProgram: TrainingProgram? = null
    private var planUpdateWasPending = false
    private var calorieProgramChanged = false
    private val trainingDayChips by lazy {
        linkedMapOf(
            R.id.trainingDayMonday to DayOfWeek.MONDAY,
            R.id.trainingDayTuesday to DayOfWeek.TUESDAY,
            R.id.trainingDayWednesday to DayOfWeek.WEDNESDAY,
            R.id.trainingDayThursday to DayOfWeek.THURSDAY,
            R.id.trainingDayFriday to DayOfWeek.FRIDAY,
            R.id.trainingDaySaturday to DayOfWeek.SATURDAY,
            R.id.trainingDaySunday to DayOfWeek.SUNDAY,
        )
    }
    /** Set while chips are updated from storage, so programmatic changes are not saved back. */
    private var renderingTraining = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nutrition_plan_settings)
        bindBack()
        val id = profileId ?: return
        originalTrainingProgram = data.trainingProgramPreferences.get(id)
        planUpdateWasPending = data.nutritionPlanUpdatePreferences.isPending(id)
        val switch = findViewById<MaterialSwitch>(R.id.scheduleSwitch)
        switch.setOnCheckedChangeListener { _, enabled -> data.nutritionPlanSchedulePreferences.setEnabled(id, enabled); refresh() }
        findViewById<View>(R.id.mealCountButton).setOnClickListener { chooseMealCount(id) }
        findViewById<View>(R.id.scheduleButton).setOnClickListener { chooseFrequency(id) }
        findViewById<View>(R.id.nutritionPathButton).setOnClickListener {
            if (aiProviderConfigured) startActivity(android.content.Intent(this, NutritionPathActivity::class.java))
        }
        bindTrainingProgram(id)
        refresh()
    }
    private fun refresh() {
        val id = profileId ?: return
        val config = data.nutritionPlanSchedulePreferences.get(id)
        findViewById<TextView>(R.id.mealCountValue).text = "${data.mealCountPreferences.get(id)} pasti al giorno"
        findViewById<MaterialSwitch>(R.id.scheduleSwitch).isChecked = config.enabled
        findViewById<TextView>(R.id.scheduleValue).text = if (config.enabled) "${config.frequency.name.lowercase()} · ${config.timeMinutes / 60}:${"%02d".format(config.timeMinutes % 60)}" else "Disattivata"
        val switch = findViewById<MaterialSwitch>(R.id.scheduleSwitch)
        switch.isEnabled = aiProviderConfigured
        findViewById<View>(R.id.scheduleButton).isEnabled = aiProviderConfigured
        setAiActionEnabled(findViewById(R.id.nutritionPathButton))
        renderTrainingProgram(id)
    }

    private fun bindTrainingProgram(id: Long) {
        trainingDayChips.keys.forEach { chipId ->
            findViewById<Chip>(chipId).setOnCheckedChangeListener { _, _ -> if (!renderingTraining) saveTrainingDays(id) }
        }
        findViewById<View>(R.id.trainingSessionButton).setOnClickListener { chooseTrainingDuration(id) }
        findViewById<View>(R.id.trainingTimeButton).setOnClickListener { chooseTrainingTime(id) }
    }

    private fun renderTrainingProgram(id: Long) {
        val program = data.trainingProgramPreferences.get(id)
        renderingTraining = true
        trainingDayChips.forEach { (chipId, day) -> findViewById<Chip>(chipId).isChecked = day in program.days }
        renderingTraining = false
        findViewById<TextView>(R.id.trainingProgramValue).text = program.summary()
        findViewById<TextView>(R.id.trainingProgramNotice).text = if (
            planUpdateWasPending || calorieProgramChanged ||
            originalTrainingProgram?.changesDailyCaloriesComparedTo(program) == true
        ) {
            "Il programma modifica le calorie giornaliere. Rigenera il piano alimentare per applicare i nuovi target."
        } else {
            "Salvato nel profilo. Alla prossima generazione il piano usa calorie e menu diversi nei giorni di allenamento."
        }
        findViewById<MaterialButton>(R.id.trainingTimeButton).text = program.startMinutes
            ?.let { "Orario abituale: %02d:%02d".format(it / 60, it % 60) }
            ?: "Orario abituale (facoltativo)"
    }

    private fun saveTrainingDays(id: Long) {
        val days = trainingDayChips.filter { (chipId, _) -> findViewById<Chip>(chipId).isChecked }.values.toSet()
        saveTrainingProgram(id, data.trainingProgramPreferences.get(id).copy(days = days))
        renderTrainingProgram(id)
    }

    private fun chooseTrainingDuration(id: Long) {
        val current = data.trainingProgramPreferences.get(id)
        val options = TrainingProgram.DURATION_OPTIONS
        MaterialAlertDialogBuilder(this)
            .setTitle("Durata abituale")
            .setSingleChoiceItems(options.map { "$it min" }.toTypedArray(), options.indexOf(current.durationMinutes)) { dialog, which ->
                saveTrainingProgram(id, data.trainingProgramPreferences.get(id).copy(durationMinutes = options[which]))
                dialog.dismiss()
                chooseTrainingIntensity(id)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun chooseTrainingIntensity(id: Long) {
        val current = data.trainingProgramPreferences.get(id)
        val values = Intensity.entries
        val labels = values.map { TrainingProgram.intensityLabel(it).replaceFirstChar { c -> c.titlecase(Locale.ITALIAN) } }
        MaterialAlertDialogBuilder(this)
            .setTitle("Intensità abituale")
            .setSingleChoiceItems(labels.toTypedArray(), values.indexOf(current.intensity)) { dialog, which ->
                saveTrainingProgram(id, data.trainingProgramPreferences.get(id).copy(intensity = values[which]))
                dialog.dismiss()
                renderTrainingProgram(id)
                Toast.makeText(this, "Programma allenamenti aggiornato", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun chooseTrainingTime(id: Long) {
        val current = data.trainingProgramPreferences.get(id)
        if (current.startMinutes == null) {
            showTrainingTimePicker(id, DEFAULT_PICKER_MINUTES)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Orario abituale %02d:%02d".format(current.startMinutes / 60, current.startMinutes % 60))
            .setItems(arrayOf("Cambia orario", "Rimuovi orario")) { _, which ->
                if (which == 0) {
                    showTrainingTimePicker(id, current.startMinutes)
                } else {
                    // Time affects meal timing only, not daily calorie targets; don't ask to regenerate.
                    data.trainingProgramPreferences.set(id, data.trainingProgramPreferences.get(id).copy(startMinutes = null))
                    renderTrainingProgram(id)
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun showTrainingTimePicker(id: Long, initialMinutes: Int) {
        MaterialTimePicker.Builder()
            .setTheme(R.style.ThemeOverlay_MyFitAI_MaterialTimePicker)
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(initialMinutes / 60)
            .setMinute(initialMinutes % 60)
            .setTitleText("Orario abituale dell'allenamento")
            .build()
            .also { picker ->
                picker.addOnPositiveButtonClickListener {
                    // Time affects meal timing only, not daily calorie targets; don't ask to regenerate.
                    data.trainingProgramPreferences.set(id, data.trainingProgramPreferences.get(id).copy(startMinutes = picker.hour * 60 + picker.minute))
                    renderTrainingProgram(id)
                }
                picker.show(supportFragmentManager, "training_time")
            }
    }

    private fun chooseMealCount(id: Long) {
        val values = intArrayOf(4, 5, 6)
        MaterialAlertDialogBuilder(this).setTitle("Pasti al giorno").setSingleChoiceItems(values.map { "$it pasti" }.toTypedArray(), values.indexOf(data.mealCountPreferences.get(id))) { dialog, which -> data.mealCountPreferences.set(id, values[which]); refresh(); dialog.dismiss() }.setNegativeButton("Annulla", null).show()
    }

    private fun saveTrainingProgram(id: Long, updated: TrainingProgram) {
        val before = data.trainingProgramPreferences.get(id)
        val priorProgram = originalTrainingProgram ?: before
        data.trainingProgramPreferences.set(id, updated)
        calorieProgramChanged = priorProgram.changesDailyCaloriesComparedTo(updated)
        lifecycleScope.launch {
            if (data.mealPlanRepository.getPlanForWeek(
                    id,
                    java.time.LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay(),
                ) != null
            ) {
                data.nutritionPlanUpdatePreferences.setPending(id, planUpdateWasPending || calorieProgramChanged)
            }
        }
    }

    private fun chooseFrequency(id: Long) {
        val values = NutritionPlanSchedulePreferences.Frequency.entries
        val labels = arrayOf("Ogni giorno", "Ogni settimana", "Ogni 2 settimane", "Ogni mese")
        MaterialAlertDialogBuilder(this).setTitle("Frequenza piano").setSingleChoiceItems(labels, values.indexOf(data.nutritionPlanSchedulePreferences.get(id).frequency)) { dialog, which -> data.nutritionPlanSchedulePreferences.setFrequency(id, values[which]); dialog.dismiss(); chooseTime(id) }.setNegativeButton("Annulla", null).show()
    }
    private fun chooseTime(id: Long) {
        val current = data.nutritionPlanSchedulePreferences.get(id)
        MaterialTimePicker.Builder().setTheme(R.style.ThemeOverlay_MyFitAI_MaterialTimePicker).setTimeFormat(TimeFormat.CLOCK_24H).setHour(current.timeMinutes / 60).setMinute(current.timeMinutes % 60).setTitleText("Ora generazione").build().also { picker -> picker.addOnPositiveButtonClickListener { data.nutritionPlanSchedulePreferences.setTimeMinutes(id, picker.hour * 60 + picker.minute); data.nutritionPlanSchedulePreferences.setEnabled(id, true); data.nutritionPlanScheduler.reschedule(id); refresh(); Toast.makeText(this, "Configurazione piano aggiornata", Toast.LENGTH_SHORT).show() }; picker.show(supportFragmentManager, "plan_time") }
    }

    private companion object {
        const val DEFAULT_PICKER_MINUTES = 18 * 60
    }
}
