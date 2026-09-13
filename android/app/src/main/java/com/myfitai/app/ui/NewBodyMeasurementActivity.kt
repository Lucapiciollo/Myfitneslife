package com.myfitai.app.ui

import android.os.Bundle
import android.widget.Toast
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.myfitai.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NewBodyMeasurementActivity : BaseShellActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_body_measurement)
        bindBack()

        val dateLayout = findViewById<TextInputLayout>(R.id.dateLayout)
        val dateInput = findViewById<TextInputEditText>(R.id.dateInput)
        val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.ITALIAN)
        dateInput.setText(formatter.format(Date()))

        val openDatePicker = {
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Data misurazione")
                .setSelection(MaterialDatePicker.todayInUtcMilliseconds())
                .build()
            picker.addOnPositiveButtonClickListener { selection ->
                dateInput.setText(formatter.format(Date(selection)))
            }
            picker.show(supportFragmentManager, "body_measurement_date_picker")
        }

        dateInput.setOnClickListener { openDatePicker() }
        dateLayout.setEndIconOnClickListener { openDatePicker() }

        findViewById<android.view.View>(R.id.saveMeasurementButton).setOnClickListener {
            // Persistenza reale prevista allo step 7/10 (Room). Qui chiudiamo il flusso UI.
            Toast.makeText(this, "Misurazione pronta per il salvataggio", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        }
    }
}
