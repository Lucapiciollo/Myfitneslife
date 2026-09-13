package com.myfitai.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.history.HistoryViewModel
import com.myfitai.app.ui.widgets.HistoryRowView
import com.myfitai.app.ui.widgets.SelectableSegmentView
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class HistoryActivity : BaseShellActivity() {

    private val data by lazy { AppDataContainer.get(this) }
    private val viewModel: HistoryViewModel by viewModels {
        HistoryViewModel.Factory(
            activeProfileStore = data.activeProfileStore,
            bodyMeasurements = data.bodyMeasurementRepository,
            bia = data.biaRepository,
            plans = data.mealPlanRepository,
            cheats = data.cheatEntryRepository,
        )
    }

    private var selectedCategory = 2
    private var latestState = HistoryViewModel.State()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        bindBottom(BottomNavBinder.Tab.PROGRESS)
        bindBack()

        findViewById<SelectableSegmentView>(R.id.categorySegment).apply {
            setSegments(listOf("Misure", "BIA", "Piani", "Sgarri"), selectedIndex = selectedCategory)
            setOnSegmentSelectedListener { index ->
                selectedCategory = index
                render(latestState)
            }
        }
        findViewById<View>(R.id.exportButton).setOnClickListener { go(ExportActivity::class.java) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    latestState = state
                    render(state)
                }
            }
        }
    }

    private fun render(state: HistoryViewModel.State) {
        val container = findViewById<LinearLayout>(R.id.historyRowsContainer)
        container.removeAllViews()

        when (selectedCategory) {
            0 -> state.measurements.forEachIndexed { index, row ->
                addRow(container, index,
                    icon = R.drawable.ic_setting_person,
                    title = "Misure ${formatInstantDate(row.measuredAtEpochMillis)}",
                    subtitle = measurementSubtitle(row),
                    onClick = { go(BodyMeasuresActivity::class.java) },
                )
            }
            1 -> state.bia.forEachIndexed { index, row ->
                addRow(container, index,
                    icon = R.drawable.ic_nav_progress,
                    title = "BIA ${formatInstantDate(row.measuredAtEpochMillis)}",
                    subtitle = biaSubtitle(row),
                    onClick = { go(BiaActivity::class.java) },
                )
            }
            2 -> state.plans.forEachIndexed { index, snapshot ->
                val start = LocalDate.ofEpochDay(snapshot.weekStartEpochDay)
                val end = start.plusDays(6)
                addRow(container, index,
                    icon = R.drawable.ic_calendar,
                    title = "${formatWeekDate(start)} – ${formatWeekDate(end)}",
                    subtitle = "Piano v${snapshot.version.versionNumber} · ${snapshot.version.source}",
                    onClick = {
                        startActivity(
                            Intent(this, FoodPlanActivity::class.java)
                                .putExtra(FoodPlanActivity.EXTRA_WEEK_START_EPOCH_DAY, snapshot.weekStartEpochDay)
                        )
                    },
                )
            }
            else -> state.cheats.forEachIndexed { index, row ->
                addRow(container, index,
                    icon = R.drawable.ic_setting_food,
                    title = "Sgarro ${formatInstantDateTime(row.occurredAtEpochMillis)}",
                    subtitle = cheatSubtitle(row),
                    onClick = null,
                )
            }
        }

        val empty = findViewById<TextView>(R.id.emptyHistoryText)
        val isEmpty = container.childCount == 0
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        container.visibility = if (isEmpty) View.GONE else View.VISIBLE
        if (state.profileId == null) empty.text = "Nessun profilo attivo." else if (isEmpty) empty.text = "Nessun dato disponibile per questa categoria."
    }

    private fun addRow(
        container: LinearLayout,
        index: Int,
        icon: Int,
        title: String,
        subtitle: String,
        onClick: (() -> Unit)?,
    ) {
        if (index > 0) {
            container.addView(View(this).apply { setBackgroundColor(getColor(R.color.divider)) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
        }
        container.addView(HistoryRowView(this).apply {
            setIcon(icon)
            setTitle(title)
            setSubtitle(subtitle)
            if (onClick != null) setOnClickListener { onClick() } else {
                isClickable = false
                isFocusable = false
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun measurementSubtitle(row: com.myfitai.app.data.local.entity.BodyMeasurementEntity): String {
        val values = listOfNotNull(
            row.waistCm?.let { "Vita ${formatNumber(it)} cm" },
            row.chestCm?.let { "Torace ${formatNumber(it)} cm" },
            row.abdomenCm?.let { "Addome ${formatNumber(it)} cm" },
        )
        return values.take(2).joinToString(" · ").ifBlank { "Rilevazione registrata" }
    }

    private fun biaSubtitle(row: com.myfitai.app.data.local.entity.BiaMeasurementEntity): String {
        val values = listOfNotNull(
            row.weightKg?.let { "${formatNumber(it)} kg" },
            row.bodyFatPercent?.let { "Grasso ${formatNumber(it)}%" },
            row.muscleMassKg?.let { "Muscolo ${formatNumber(it)} kg" },
        )
        return values.take(2).joinToString(" · ").ifBlank { "Rilevazione registrata" }
    }

    private fun cheatSubtitle(row: com.myfitai.app.data.local.entity.CheatEntryEntity): String {
        val estimate = row.estimatedKcal?.let { " · stima $it kcal" }.orEmpty()
        return row.description.trim().ifBlank { "Sgarro registrato" } + estimate
    }

    private fun formatInstantDate(value: Long): String = Instant.ofEpochMilli(value)
        .atZone(ZoneId.systemDefault()).toLocalDate()
        .format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ITALIAN))

    private fun formatInstantDateTime(value: Long): String = Instant.ofEpochMilli(value)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", Locale.ITALIAN))

    private fun formatWeekDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ITALIAN))
    private fun formatNumber(value: Float): String = if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.ITALIAN, "%.1f", value)
}
