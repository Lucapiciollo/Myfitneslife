package com.myfitai.app.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.body.BodyMeasurementsViewModel
import com.myfitai.app.ui.widgets.BodyMeasurementTrendView
import com.myfitai.app.ui.widgets.MeasurementRowView
import com.myfitai.app.ui.widgets.SelectableSegmentView
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class BodyMeasuresActivity : BaseShellActivity() {

    private enum class Metric(
        val label: String,
        val value: (BodyMeasurementEntity) -> Float?,
    ) {
        WAIST("Vita") { it.waistCm },
        CHEST("Torace") { it.chestCm },
        ABDOMEN("Addome") { it.abdomenCm },
        SHOULDERS("Spalle") { it.shouldersCm },
        GLUTES("Glutei") { it.glutesCm },
        ARM_LEFT("Braccio sx") { it.armLeftCm },
        ARM_RIGHT("Braccio dx") { it.armRightCm },
        THIGH_LEFT("Coscia sx") { it.thighLeftCm },
        THIGH_RIGHT("Coscia dx") { it.thighRightCm },
        CALF_LEFT("Polpaccio sx") { it.calfLeftCm },
        CALF_RIGHT("Polpaccio dx") { it.calfRightCm },
    }

    private val data by lazy { AppDataContainer.get(this) }
    private val viewModel: BodyMeasurementsViewModel by viewModels {
        BodyMeasurementsViewModel.Factory(data.bodyMeasurementRepository, data.activeProfileStore)
    }

    private var measurements: List<BodyMeasurementEntity> = emptyList()
    private var selectedMetric: Metric = Metric.WAIST
    private var selectedRangeIndex = 2
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ITALIAN)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_body_measures)
        bindBottom(BottomNavBinder.Tab.PROGRESS)
        bindBack()

        findViewById<View>(R.id.saveButton).setOnClickListener { go(NewBodyMeasurementActivity::class.java) }
        bindTabs()
        bindMetricSelector()
        bindRangeSelector()
        observeData()
    }

    private fun bindTabs() {
        val topSegment = findViewById<SelectableSegmentView>(R.id.measureSegment)
        val measureContent = findViewById<View>(R.id.measureContent)
        val trendContent = findViewById<View>(R.id.trendContent)
        val historyContent = findViewById<View>(R.id.historyContent)

        topSegment.setSegments(listOf("Misura", "Andamento", "Storico"), selectedIndex = 0)
        topSegment.setOnSegmentSelectedListener { index ->
            measureContent.visibility = if (index == 0) View.VISIBLE else View.GONE
            trendContent.visibility = if (index == 1) View.VISIBLE else View.GONE
            historyContent.visibility = if (index == 2) View.VISIBLE else View.GONE
        }
    }

    private fun bindMetricSelector() {
        val input = findViewById<AutoCompleteTextView>(R.id.trendMetricInput)
        val labels = Metric.entries.map { it.label }
        input.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels))
        input.setText(selectedMetric.label, false)
        input.setOnItemClickListener { _, _, position, _ ->
            selectedMetric = Metric.entries[position]
            renderTrend()
        }
    }

    private fun bindRangeSelector() {
        val rangeSegment = findViewById<SelectableSegmentView>(R.id.trendRangeSegment)
        rangeSegment.setSegments(listOf("1M", "3M", "6M", "1Y"), selectedIndex = selectedRangeIndex)
        rangeSegment.setOnSegmentSelectedListener { index ->
            selectedRangeIndex = index
            renderTrend()
        }
    }

    private fun observeData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.measurements.collect { values ->
                        measurements = values
                        renderCurrent()
                        renderTrend()
                        renderHistory()
                    }
                }
                launch {
                    viewModel.deleted.collect {
                        Toast.makeText(this@BodyMeasuresActivity, "Misurazione eliminata", Toast.LENGTH_SHORT).show()
                    }
                }
                launch {
                    viewModel.error.collect { message ->
                        Toast.makeText(this@BodyMeasuresActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun renderCurrent() {
        val latest = measurements.firstOrNull()
        findViewById<TextView>(R.id.measureDate).text = latest?.let { entityDate(it).format(dateFormatter) } ?: "Nessuna misura"

        val rows = listOf(
            Triple(R.id.rowChest, "Torace", latest?.chestCm),
            Triple(R.id.rowWaist, "Vita", latest?.waistCm),
            Triple(R.id.rowAbdomen, "Addome", latest?.abdomenCm),
            Triple(R.id.rowShoulders, "Spalle", latest?.shouldersCm),
            Triple(R.id.rowGlutes, "Glutei", latest?.glutesCm),
            Triple(R.id.rowArmLeft, "Braccio sx", latest?.armLeftCm),
            Triple(R.id.rowArmRight, "Braccio dx", latest?.armRightCm),
            Triple(R.id.rowThighLeft, "Coscia sx", latest?.thighLeftCm),
            Triple(R.id.rowThighRight, "Coscia dx", latest?.thighRightCm),
            Triple(R.id.rowCalfLeft, "Polpaccio sx", latest?.calfLeftCm),
            Triple(R.id.rowCalfRight, "Polpaccio dx", latest?.calfRightCm),
        )
        rows.forEach { (id, label, value) ->
            findViewById<MeasurementRowView>(id).apply {
                setLabel(label)
                setValue(value?.let(::formatCm) ?: "—")
            }
        }
    }

    private fun renderTrend() {
        findViewById<TextView>(R.id.trendMetricLabel).text = selectedMetric.label

        val allMetricPoints = measurements
            .asReversed()
            .mapNotNull { measurement -> selectedMetric.value(measurement)?.let { measurement to it } }

        val current = allMetricPoints.lastOrNull()
        findViewById<TextView>(R.id.trendCurrentValue).text = current?.second?.let(::formatCm) ?: "—"

        val previousDelta = if (allMetricPoints.size >= 2) {
            allMetricPoints.last().second - allMetricPoints[allMetricPoints.lastIndex - 1].second
        } else null
        findViewById<TextView>(R.id.trendDeltaPrevious).text = previousDelta?.let {
            "${formatSigned(it)} cm vs precedente"
        } ?: "Dati insufficienti"

        val anchorDate = measurements.firstOrNull()?.let(::entityDate) ?: LocalDate.now()
        val fromDate = when (selectedRangeIndex) {
            0 -> anchorDate.minusMonths(1)
            1 -> anchorDate.minusMonths(3)
            2 -> anchorDate.minusMonths(6)
            else -> anchorDate.minusYears(1)
        }

        val periodPoints = allMetricPoints.filter { (measurement, _) -> !entityDate(measurement).isBefore(fromDate) }
        val periodDelta = if (periodPoints.size >= 2) periodPoints.last().second - periodPoints.first().second else null
        findViewById<TextView>(R.id.trendDeltaPeriod).text = periodDelta?.let {
            "${formatSigned(it)} cm nel periodo"
        } ?: "Dati insufficienti nel periodo"

        val chartPoints = periodPoints.map { (measurement, value) ->
            BodyMeasurementTrendView.Point(entityDate(measurement).format(DateTimeFormatter.ofPattern("dd/MM", Locale.ITALIAN)), value)
        }
        findViewById<BodyMeasurementTrendView>(R.id.bodyMeasurementTrendChart).setPoints(chartPoints)
    }

    private fun renderHistory() {
        val container = findViewById<LinearLayout>(R.id.historyList)
        container.removeAllViews()

        if (measurements.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "Nessuna misurazione salvata per questo profilo."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 14f
                setPadding(0, dp(12), 0, dp(12))
            })
            return
        }

        measurements.forEachIndexed { index, measurement ->
            val older = measurements.getOrNull(index + 1)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = getDrawable(R.drawable.bg_card)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) }
                setOnLongClickListener {
                    confirmDelete(measurement)
                    true
                }
            }

            card.addView(TextView(this).apply {
                text = entityDate(measurement).format(dateFormatter)
                setTextColor(getColor(R.color.text_primary))
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })

            card.addView(TextView(this).apply {
                text = historyValues(measurement)
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(0, dp(5), 0, 0)
            })

            val waistDelta = if (measurement.waistCm != null && older?.waistCm != null) measurement.waistCm - older.waistCm else null
            if (waistDelta != null) {
                card.addView(TextView(this).apply {
                    text = "Vita ${formatSigned(waistDelta)} cm rispetto alla rilevazione precedente"
                    setTextColor(getColor(if (waistDelta <= 0f) R.color.semantic_positive else R.color.text_secondary))
                    textSize = 12f
                    setPadding(0, dp(6), 0, 0)
                })
            }

            card.addView(TextView(this).apply {
                text = "Tieni premuto per eliminare"
                setTextColor(getColor(R.color.text_muted))
                textSize = 10f
                setPadding(0, dp(6), 0, 0)
            })
            container.addView(card)
        }
    }

    private fun historyValues(value: BodyMeasurementEntity): String {
        val items = listOfNotNull(
            value.chestCm?.let { "Torace ${formatCm(it)}" },
            value.waistCm?.let { "Vita ${formatCm(it)}" },
            value.abdomenCm?.let { "Addome ${formatCm(it)}" },
            value.shouldersCm?.let { "Spalle ${formatCm(it)}" },
            value.glutesCm?.let { "Glutei ${formatCm(it)}" },
            value.armLeftCm?.let { "Braccio sx ${formatCm(it)}" },
            value.armRightCm?.let { "Braccio dx ${formatCm(it)}" },
            value.thighLeftCm?.let { "Coscia sx ${formatCm(it)}" },
            value.thighRightCm?.let { "Coscia dx ${formatCm(it)}" },
            value.calfLeftCm?.let { "Polpaccio sx ${formatCm(it)}" },
            value.calfRightCm?.let { "Polpaccio dx ${formatCm(it)}" },
        )
        return items.ifEmpty { listOf("Rilevazione senza valori") }.joinToString(" · ")
    }

    private fun confirmDelete(measurement: BodyMeasurementEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Elimina misurazione?")
            .setMessage("La rilevazione del ${entityDate(measurement).format(dateFormatter)} verrà rimossa dallo storico. Questa operazione non è reversibile.")
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Elimina") { _, _ -> viewModel.delete(measurement) }
            .show()
    }

    private fun entityDate(value: BodyMeasurementEntity): LocalDate =
        Instant.ofEpochMilli(value.measuredAtEpochMillis).atZone(ZoneOffset.UTC).toLocalDate()

    private fun formatCm(value: Float): String = if (value % 1f == 0f) {
        "${value.toInt()} cm"
    } else {
        String.format(Locale.ITALIAN, "%.1f cm", value)
    }

    private fun formatSigned(value: Float): String = String.format(
        Locale.ITALIAN,
        if (value > 0f) "+%.1f" else "%.1f",
        value,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
