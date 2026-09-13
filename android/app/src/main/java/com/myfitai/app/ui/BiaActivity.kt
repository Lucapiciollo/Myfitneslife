package com.myfitai.app.ui

import android.os.Bundle
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.myfitai.app.R
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.widgets.MeasurementRowView
import com.myfitai.app.ui.widgets.SelectableSegmentView
import java.text.SimpleDateFormat
import java.util.Locale

class BiaActivity : BaseShellActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bia)
        bindBottom(BottomNavBinder.Tab.PROGRESS)
        bindBack()
        findViewById<android.view.View>(R.id.saveButton).setOnClickListener { go(BodyMeasuresActivity::class.java) }

        findViewById<SelectableSegmentView>(R.id.biaSegment).setSegments(listOf("Nuova misurazione", "Storico"), selectedIndex = 0)
        findViewById<SelectableSegmentView>(R.id.biaSegment).setOnSegmentSelectedListener { index ->
            if (index == 1) go(HistoryActivity::class.java)
        }

        val dateInput = findViewById<TextInputEditText>(R.id.dateInput)
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ITALIAN)
        dateInput.setOnClickListener {
            val picker = MaterialDatePicker.Builder.datePicker().setTitleText("Data misurazione").build()
            picker.addOnPositiveButtonClickListener { selection -> dateInput.setText(dateFormat.format(java.util.Date(selection))) }
            picker.show(supportFragmentManager, "date_picker")
        }

        val timeInput = findViewById<TextInputEditText>(R.id.timeInput)
        timeInput.setOnClickListener {
            val picker = MaterialTimePicker.Builder().setTimeFormat(TimeFormat.CLOCK_24H).setHour(7).setMinute(15).setTitleText("Ora misurazione").build()
            picker.addOnPositiveButtonClickListener { timeInput.setText(String.format(Locale.ITALIAN, "%02d:%02d", picker.hour, picker.minute)) }
            picker.show(supportFragmentManager, "time_picker")
        }

        val results = listOf(
            R.id.rowWeight to ("Peso" to "78,4 kg"),
            R.id.rowBodyFat to ("Grasso corporeo" to "14,2 %"),
            R.id.rowVisceralFat to ("Grasso viscerale" to "6"),
            R.id.rowMuscleMass to ("Massa muscolare" to "66,8 kg"),
            R.id.rowSkeletalMuscle to ("Muscolo scheletrico" to "38,2 kg"),
            R.id.rowBodyWater to ("Acqua corporea" to "58,1 %"),
            R.id.rowBmr to ("BMR" to "1.820 kcal"),
        )
        results.forEach { (id, pair) ->
            findViewById<MeasurementRowView>(id).apply {
                showIcon()
                setLabel(pair.first)
                setValue(pair.second)
            }
        }
    }
}