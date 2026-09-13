package com.myfitai.app.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.widget.Toast
import com.google.android.material.textfield.TextInputEditText
import com.myfitai.app.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class NewBodyMeasurementActivity : BaseShellActivity() {

    private val calendar = Calendar.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_body_measurement)
        bindBack()

        val dateInput = findViewById<TextInputEditText>(R.id.dateInput)
        val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.ITALIAN)
        dateInput.setText(formatter.format(calendar.time))
        dateInput.setOnClickListener {
            DatePickerDialog(
                this,
                { _, year, month, day ->
                    calendar.set(year, month, day)
                    dateInput.setText(formatter.format(calendar.time))
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH),
            ).show()
        }

        findViewById<android.view.View>(R.id.saveMeasurementButton).setOnClickListener {
            // Persistenza reale prevista allo step 7/10 (Room). La UI è già completa e validabile.
            Toast.makeText(this, "Misurazione pronta per il salvataggio", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
