package com.myfitai.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.ChipGroup
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.food.CheatAdjustmentService
import com.myfitai.app.ui.food.CheatEntryViewModel
import com.myfitai.app.ui.widgets.SelectableSegmentView
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class CheatEntryActivity : BaseShellActivity() {

    private val data by lazy { AppDataContainer.get(this) }
    private val viewModel: CheatEntryViewModel by viewModels {
        CheatEntryViewModel.Factory(data.cheatAdjustmentService, data.notificationScheduler)
    }

    private var selectedDate: LocalDate = LocalDate.now()
    private var selectedTime: LocalTime = LocalTime.now().withSecond(0).withNano(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cheat_entry)
        bindBack()

        findViewById<SelectableSegmentView>(R.id.modeSegment)
            .setSegments(listOf("Rapido", "Dettagliato"), selectedIndex = 0)

        val quantityInput = findViewById<AutoCompleteTextView>(R.id.quantityInput)
        val quantities = listOf("Piccolo", "Medio", "Grande")
        quantityInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, quantities))
        quantityInput.setText("Medio", false)

        renderDateTime()
        bindPickers()
        findViewById<View>(R.id.confirmButton).setOnClickListener { submit() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::renderState)
            }
        }
    }

    private fun bindPickers() {
        findViewById<View>(R.id.dateField).setOnClickListener {
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Data sgarro")
                .setSelection(selectedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                .build()
            picker.addOnPositiveButtonClickListener { selection ->
                selectedDate = Instant.ofEpochMilli(selection).atZone(ZoneOffset.UTC).toLocalDate()
                renderDateTime()
            }
            picker.show(supportFragmentManager, "cheat_date_picker")
        }

        findViewById<View>(R.id.timeField).setOnClickListener {
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(selectedTime.hour)
                .setMinute(selectedTime.minute)
                .setTitleText("Ora sgarro")
                .build()
            picker.addOnPositiveButtonClickListener {
                selectedTime = LocalTime.of(picker.hour, picker.minute)
                renderDateTime()
            }
            picker.show(supportFragmentManager, "cheat_time_picker")
        }
    }

    private fun submit() {
        val description = findViewById<EditText>(R.id.descriptionInput).text?.toString()?.trim().orEmpty()
        if (description.isBlank()) {
            findViewById<EditText>(R.id.descriptionInput).error = "Descrivi cosa hai mangiato"
            return
        }
        val category = selectedCategory()
        val fullDescription = if (category == null || description.startsWith(category, ignoreCase = true)) description else "$category — $description"
        val occurredAt = selectedDate.atTime(selectedTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (occurredAt > System.currentTimeMillis() + 60_000L) {
            findViewById<TextView>(R.id.statusText).apply { visibility = View.VISIBLE; text = "La data dello sgarro non può essere nel futuro." }
            return
        }

        viewModel.submit(
            CheatAdjustmentService.Input(
                description = fullDescription,
                quantityText = findViewById<AutoCompleteTextView>(R.id.quantityInput).text?.toString(),
                notes = findViewById<EditText>(R.id.notesInput).text?.toString(),
                occurredAtEpochMillis = occurredAt,
            )
        )
    }

    private fun selectedCategory(): String? {
        val group = findViewById<ChipGroup>(R.id.foodChipGroup)
        val checkedId = group.checkedChipId
        return checkedId.takeIf { it != View.NO_ID }
            ?.let { findViewById<com.google.android.material.chip.Chip>(it).text?.toString()?.trim() }
            ?.takeIf { it.isNotBlank() }
    }

    private fun renderDateTime() {
        findViewById<TextView>(R.id.dateValue).text = selectedDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ITALIAN))
        findViewById<TextView>(R.id.timeValue).text = selectedTime.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ITALIAN))
    }

    private fun renderState(state: CheatEntryViewModel.State) {
        findViewById<View>(R.id.confirmButton).isEnabled = !state.running
        findViewById<ProgressBar>(R.id.progress).visibility = if (state.running) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.statusText).apply {
            visibility = if (state.running || state.error != null) View.VISIBLE else View.GONE
            text = when { state.running -> "Stima dello sgarro e verifica dei pasti futuri in corso…"; state.error != null -> state.error; else -> "" }
        }

        val result = state.result ?: return
        viewModel.consumeResult()
        startActivity(
            Intent(this, AdjustedPlanActivity::class.java)
                .putExtra(AdjustedPlanActivity.EXTRA_DESCRIPTION, findViewById<EditText>(R.id.descriptionInput).text?.toString().orEmpty())
                .putExtra(AdjustedPlanActivity.EXTRA_ESTIMATE, result.estimateSummary)
                .putExtra(AdjustedPlanActivity.EXTRA_ADAPTED, result.adapted)
                .putExtra(AdjustedPlanActivity.EXTRA_SUMMARY, result.adaptationSummary)
                .putStringArrayListExtra(AdjustedPlanActivity.EXTRA_MODIFIED_MEALS, ArrayList(result.modifiedMeals))
        )
        finish()
    }
}
