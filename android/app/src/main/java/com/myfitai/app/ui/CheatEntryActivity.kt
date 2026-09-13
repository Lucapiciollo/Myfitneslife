package com.myfitai.app.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.myfitai.app.R
import com.myfitai.app.ui.widgets.SelectableSegmentView
import java.text.SimpleDateFormat
import java.util.Locale

class CheatEntryActivity : BaseShellActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cheat_entry)
        bindBack()

        findViewById<SelectableSegmentView>(R.id.modeSegment).setSegments(listOf("Rapido", "Dettagliato"), selectedIndex = 0)

        val quantityInput = findViewById<AutoCompleteTextView>(R.id.quantityInput)
        val quantities = listOf("Piccolo (circa 150 kcal)", "Medio (circa 300 kcal)", "Grande (circa 500 kcal)")
        quantityInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, quantities))

        val dateValue = findViewById<android.widget.TextView>(R.id.dateValue)
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ITALIAN)
        findViewById<android.view.View>(R.id.dateField).setOnClickListener {
            val picker = MaterialDatePicker.Builder.datePicker().setTitleText("Data sgarro").build()
            picker.addOnPositiveButtonClickListener { selection -> dateValue.text = dateFormat.format(java.util.Date(selection)) }
            picker.show(supportFragmentManager, "cheat_date_picker")
        }

        val timeValue = findViewById<android.widget.TextView>(R.id.timeValue)
        findViewById<android.view.View>(R.id.timeField).setOnClickListener {
            val picker = MaterialTimePicker.Builder().setTimeFormat(TimeFormat.CLOCK_24H).setHour(15).setMinute(30).setTitleText("Ora sgarro").build()
            picker.addOnPositiveButtonClickListener { timeValue.text = String.format(Locale.ITALIAN, "%02d:%02d", picker.hour, picker.minute) }
            picker.show(supportFragmentManager, "cheat_time_picker")
        }

        findViewById<android.view.View>(R.id.confirmButton).setOnClickListener { go(AdjustedPlanActivity::class.java) }
    }
}
