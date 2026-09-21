package com.myfitai.app.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.data.profile.NutritionPlanSchedulePreferences

class NutritionPlanSettingsActivity : BaseShellActivity() {
    private val data by lazy { AppDataContainer.get(this) }
    private val profileId get() = data.activeProfileStore.currentIdOrNull()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nutrition_plan_settings)
        bindBack()
        val id = profileId ?: return
        val switch = findViewById<MaterialSwitch>(R.id.scheduleSwitch)
        switch.setOnCheckedChangeListener { _, enabled -> data.nutritionPlanSchedulePreferences.setEnabled(id, enabled); refresh() }
        findViewById<View>(R.id.mealCountButton).setOnClickListener { chooseMealCount(id) }
        findViewById<View>(R.id.scheduleButton).setOnClickListener { chooseFrequency(id) }
        findViewById<View>(R.id.nutritionPathButton).setOnClickListener {
            startActivity(android.content.Intent(this, NutritionPathActivity::class.java))
        }
        refresh()
    }
    private fun refresh() {
        val id = profileId ?: return
        val config = data.nutritionPlanSchedulePreferences.get(id)
        findViewById<TextView>(R.id.mealCountValue).text = "${data.mealCountPreferences.get(id)} pasti al giorno"
        findViewById<MaterialSwitch>(R.id.scheduleSwitch).isChecked = config.enabled
        findViewById<TextView>(R.id.scheduleValue).text = if (config.enabled) "${config.frequency.name.lowercase()} · ${config.timeMinutes / 60}:${"%02d".format(config.timeMinutes % 60)}" else "Disattivata"
    }
    private fun chooseMealCount(id: Long) {
        val values = intArrayOf(4, 5, 6)
        MaterialAlertDialogBuilder(this).setTitle("Pasti al giorno").setSingleChoiceItems(values.map { "$it pasti" }.toTypedArray(), values.indexOf(data.mealCountPreferences.get(id))) { dialog, which -> data.mealCountPreferences.set(id, values[which]); refresh(); dialog.dismiss() }.setNegativeButton("Annulla", null).show()
    }
    private fun chooseFrequency(id: Long) {
        val values = NutritionPlanSchedulePreferences.Frequency.entries
        MaterialAlertDialogBuilder(this).setTitle("Frequenza piano").setSingleChoiceItems(values.map { it.name.lowercase() }.toTypedArray(), values.indexOf(data.nutritionPlanSchedulePreferences.get(id).frequency)) { dialog, which -> data.nutritionPlanSchedulePreferences.setFrequency(id, values[which]); dialog.dismiss(); chooseTime(id) }.setNegativeButton("Annulla", null).show()
    }
    private fun chooseTime(id: Long) {
        val current = data.nutritionPlanSchedulePreferences.get(id)
        MaterialTimePicker.Builder().setTimeFormat(TimeFormat.CLOCK_24H).setHour(current.timeMinutes / 60).setMinute(current.timeMinutes % 60).setTitleText("Ora generazione").build().also { picker -> picker.addOnPositiveButtonClickListener { data.nutritionPlanSchedulePreferences.setTimeMinutes(id, picker.hour * 60 + picker.minute); data.nutritionPlanSchedulePreferences.setEnabled(id, true); data.nutritionPlanScheduler.reschedule(id); refresh(); Toast.makeText(this, "Configurazione piano aggiornata", Toast.LENGTH_SHORT).show() }; picker.show(supportFragmentManager, "plan_time") }
    }
}
