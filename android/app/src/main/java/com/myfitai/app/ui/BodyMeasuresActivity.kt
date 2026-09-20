package com.myfitai.app.ui

import android.content.Intent
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.domain.body.BodyWeightHistory
import com.myfitai.app.domain.body.BodyProportionEngine
import com.myfitai.app.domain.ai.AiJobType
import com.myfitai.app.domain.body.BodyProportionsAiJobHandler
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.body.BodyMeasurementsViewModel
import com.myfitai.app.ui.widgets.BodyMeasurementTrendView
import com.myfitai.app.ui.widgets.MeasurementRowView
import com.myfitai.app.ui.widgets.SelectableSegmentView
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class BodyMeasuresActivity : BaseShellActivity() {

    private enum class Metric(
        val label: String,
        val value: (BodyMeasurementEntity) -> Float?,
        val unit: String = "cm",
    ) {
        WEIGHT("Peso corporeo", { it.weightKg }, "kg"),
        HIPS("Fianchi", { it.hipsCm }),
        WAIST("Vita", { it.waistCm }),
        CHEST("Torace", { it.chestCm }),
        ABDOMEN("Addome", { it.abdomenCm }),
        SHOULDERS("Spalle", { it.shouldersCm }),
        GLUTES("Glutei", { it.glutesCm }),
        ARM_LEFT("Braccio sx", { it.armLeftCm }),
        ARM_RIGHT("Braccio dx", { it.armRightCm }),
        THIGH_LEFT("Coscia sx", { it.thighLeftCm }),
        THIGH_RIGHT("Coscia dx", { it.thighRightCm }),
        CALF_LEFT("Polpaccio sx", { it.calfLeftCm }),
        CALF_RIGHT("Polpaccio dx", { it.calfRightCm }),
    }

    private val data by lazy { AppDataContainer.get(this) }
    private val viewModel: BodyMeasurementsViewModel by viewModels {
        BodyMeasurementsViewModel.Factory(data.bodyMeasurementRepository, data.activeProfileStore)
    }

    private var measurements: List<BodyMeasurementEntity> = emptyList()
    private var biaHistory: List<BiaMeasurementEntity> = emptyList()
    private var selectedMetric: Metric = Metric.WEIGHT
    private var selectedRangeIndex = 2
    private var profileHeightCm: Float? = null
    private var latestProportionReport: BodyProportionEngine.Report? = null
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ITALIAN)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_body_measures)
        bindBottom(BottomNavBinder.Tab.MORE)
        bindBack()

        findViewById<View>(R.id.saveButton).setOnClickListener { go(NewBodyMeasurementActivity::class.java) }
        bindTabs()
        bindMetricSelector()
        bindRangeSelector()
        ensureProportionCard()
        loadProfileHeight()
        observeData()
        if (intent.getBooleanExtra(EXTRA_OPEN_HISTORY, false)) {
            findViewById<SelectableSegmentView>(R.id.measureSegment).getChildAt(2)?.performClick()
        }
    }

    private fun loadProfileHeight() {
        lifecycleScope.launch {
            val id = data.activeProfileStore.currentIdOrNull() ?: return@launch
            profileHeightCm = data.userProfileRepository.get(id)?.heightCm
            renderProportions()
        }
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
                        if (values.isNotEmpty() && values.none { measurement -> selectedMetric.value(measurement) != null }) {
                            selectedMetric = Metric.entries.firstOrNull { metric -> values.any { metric.value(it) != null } } ?: selectedMetric
                            findViewById<AutoCompleteTextView>(R.id.trendMetricInput).setText(selectedMetric.label, false)
                        }
                        renderCurrent()
                        renderTrend()
                        renderHistory()
                        renderProportions()
                    }
                }
                launch {
                    data.activeProfileStore.activeProfileId
                        .flatMapLatest(data.biaRepository::all)
                        .collect { readings ->
                            biaHistory = readings
                            renderCurrent()
                            renderTrend()
                            renderHistory()
                        }
                }
                launch {
                    viewModel.deleted.collect {
                        Toast.makeText(this@BodyMeasuresActivity, "Misurazione eliminata dallo storico", Toast.LENGTH_SHORT).show()
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
        findViewById<TextView>(R.id.measureDate).text = latest?.let { entityDate(it).format(dateFormatter) } ?: "Nessuna misura ancora registrata"

        // A weight-only entry must not hide the most recent available circumferences.
        // Each row is independently sourced from its last recorded non-null value.
        val rows = listOf(
            Triple(R.id.rowWeight, "Peso corporeo", measurements.firstNotNullOfOrNull { BodyWeightHistory.weightFor(it, biaHistory)?.kg }),
            Triple(R.id.rowChest, "Torace", measurements.firstNotNullOfOrNull { it.chestCm }),
            Triple(R.id.rowWaist, "Vita", measurements.firstNotNullOfOrNull { it.waistCm }),
            Triple(R.id.rowAbdomen, "Addome", measurements.firstNotNullOfOrNull { it.abdomenCm }),
            Triple(R.id.rowShoulders, "Spalle", measurements.firstNotNullOfOrNull { it.shouldersCm }),
            Triple(R.id.rowHips, "Fianchi", measurements.firstNotNullOfOrNull { it.hipsCm }),
            Triple(R.id.rowGlutes, "Glutei", measurements.firstNotNullOfOrNull { it.glutesCm }),
            Triple(R.id.rowArmLeft, "Braccio sx", measurements.firstNotNullOfOrNull { it.armLeftCm }),
            Triple(R.id.rowArmRight, "Braccio dx", measurements.firstNotNullOfOrNull { it.armRightCm }),
            Triple(R.id.rowThighLeft, "Coscia sx", measurements.firstNotNullOfOrNull { it.thighLeftCm }),
            Triple(R.id.rowThighRight, "Coscia dx", measurements.firstNotNullOfOrNull { it.thighRightCm }),
            Triple(R.id.rowCalfLeft, "Polpaccio sx", measurements.firstNotNullOfOrNull { it.calfLeftCm }),
            Triple(R.id.rowCalfRight, "Polpaccio dx", measurements.firstNotNullOfOrNull { it.calfRightCm }),
        )
        rows.forEach { (id, label, value) ->
            findViewById<MeasurementRowView>(id).apply {
                setLabel(label)
                setValue(value?.let { if (id == R.id.rowWeight) formatKg(it) else formatCm(it) } ?: "—")
            }
        }
    }

    private fun ensureProportionCard() {
        val container = findViewById<LinearLayout>(R.id.measureContent)
        if (container.findViewWithTag<View>(PROPORTION_CARD_TAG) != null) return

        val card = MaterialCardView(this).apply {
            tag = PROPORTION_CARD_TAG
            radius = resources.getDimension(R.dimen.radius_medium)
            cardElevation = 0f
            setCardBackgroundColor(getColor(R.color.white))
            strokeColor = getColor(R.color.divider)
            strokeWidth = dp(1)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        content.addView(TextView(this).apply {
            text = "Proporzioni corporee"
            setTextColor(getColor(R.color.text_primary))
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            id = View.generateViewId().also { proportionStatusId = it }
            setTextColor(getColor(R.color.text_primary))
            textSize = 15f
            setPadding(0, dp(10), 0, 0)
        })
        content.addView(TextView(this).apply {
            id = View.generateViewId().also { proportionDetailsId = it }
            setTextColor(getColor(R.color.text_secondary))
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
        })
        content.addView(TextView(this).apply {
            id = View.generateViewId().also { proportionNoteId = it }
            setTextColor(getColor(R.color.text_muted))
            textSize = 11f
            setPadding(0, dp(8), 0, 0)
        })
        content.addView(MaterialButton(this).apply {
            id = View.generateViewId().also { proportionAiButtonId = it }
            text = "Interpreta con IA"
            isAllCaps = false
            setOnClickListener { analyzeProportionsWithAi(this) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { topMargin = dp(12) }
        })
        card.addView(content)
        container.addView(card)
    }

    private fun renderProportions() {
        if (proportionStatusId == View.NO_ID) return
        val report = BodyProportionEngine.analyze(measurements.firstOrNull(), profileHeightCm)
        latestProportionReport = report

        findViewById<TextView>(proportionStatusId).text = when (report.status) {
            BodyProportionEngine.BalanceStatus.BALANCED -> "Equilibrio destra/sinistra: buono"
            BodyProportionEngine.BalanceStatus.MILD_IMBALANCE -> "Lieve differenza destra/sinistra"
            BodyProportionEngine.BalanceStatus.NOTICEABLE_IMBALANCE -> "Differenza destra/sinistra da monitorare"
            BodyProportionEngine.BalanceStatus.INSUFFICIENT_DATA -> "Dati insufficienti per valutare l'equilibrio"
        }

        val details = buildList {
            report.asymmetries.forEach { a ->
                add("${a.label}: ${formatPercent(a.percent)}${a.largerSide?.let { " · lato $it maggiore" } ?: ""}")
            }
            report.ratios.forEach { r -> add("${r.label}: ${String.format(Locale.ITALIAN, "%.2f", r.value)}") }
        }
        findViewById<TextView>(proportionDetailsId).text = if (details.isEmpty()) {
            "Inserisci misure bilaterali e circonferenze per ottenere rapporti più completi."
        } else details.joinToString("\n")
        findViewById<TextView>(proportionNoteId).text = report.note
        findViewById<MaterialButton>(proportionAiButtonId).isEnabled = report.availableMeasurements > 0
    }

    private fun analyzeProportionsWithAi(button: MaterialButton) {
        val report = latestProportionReport ?: return
        if (report.availableMeasurements <= 0) return
        confirmAiRequest("L'interpretazione IA delle proporzioni corporee") {
            button.isEnabled = false
            button.text = "Analisi in corso…"
            val profileId = data.activeProfileStore.currentIdOrNull() ?: return@confirmAiRequest
            val reportJson = org.json.JSONObject().put("status", report.status.name).put("maxAsymmetry", report.maxAsymmetryPercent ?: org.json.JSONObject.NULL).put("availableMeasurements", report.availableMeasurements).put("note", report.note).put("ratios", org.json.JSONArray().apply { report.ratios.forEach { put(org.json.JSONObject().put("key", it.key).put("label", it.label).put("value", it.value).put("description", it.description)) } }).put("asymmetries", org.json.JSONArray().apply { report.asymmetries.forEach { put(org.json.JSONObject().put("key", it.key).put("label", it.label).put("percent", it.percent).put("largerSide", it.largerSide ?: org.json.JSONObject.NULL)) } }).toString()
            val jobKey = "${System.currentTimeMillis()}"
            data.aiJobScheduler.enqueue(AiJobType.BODY_PROPORTIONS, profileId, jobKey, params = androidx.work.Data.Builder().putString(BodyProportionsAiJobHandler.KEY_REPORT, reportJson).build())
            lifecycleScope.launch { data.aiJobScheduler.observe(AiJobType.BODY_PROPORTIONS, profileId, jobKey).collect { info -> if (info?.state == androidx.work.WorkInfo.State.SUCCEEDED) { val p = org.json.JSONObject(info.outputData.getString(BodyProportionsAiJobHandler.KEY_PAYLOAD).orEmpty()); MaterialAlertDialogBuilder(this@BodyMeasuresActivity).setTitle("Analisi proporzioni").setMessage(p.getString("summary")).setPositiveButton("Chiudi", null).show(); button.isEnabled = true; button.text = "Interpreta con IA" } } }
        }
    }

    private fun renderTrend() {
        findViewById<TextView>(R.id.trendMetricLabel).text = selectedMetric.label

        val allMetricPoints = measurements
            .asReversed()
            .mapNotNull { measurement ->
                val value = if (selectedMetric == Metric.WEIGHT) BodyWeightHistory.weightFor(measurement, biaHistory)?.kg
                    else selectedMetric.value(measurement)
                value?.let { measurement to it }
            }

        val current = allMetricPoints.lastOrNull()
        findViewById<TextView>(R.id.trendCurrentValue).text = current?.second?.let { if (selectedMetric.unit == "kg") formatKg(it) else formatCm(it) } ?: "—"

        val previousDelta = if (allMetricPoints.size >= 2) {
            allMetricPoints.last().second - allMetricPoints[allMetricPoints.lastIndex - 1].second
        } else null
        findViewById<TextView>(R.id.trendDeltaPrevious).text = previousDelta?.let {
            "${formatSigned(it)} ${selectedMetric.unit} vs precedente disponibile"
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
            "${formatSigned(it)} ${selectedMetric.unit} nel periodo"
        } ?: "Dati insufficienti nel periodo"

        val chartPoints = periodPoints.map { (measurement, value) ->
            BodyMeasurementTrendView.Point(entityDate(measurement).format(DateTimeFormatter.ofPattern("dd/MM", Locale.ITALIAN)), value)
        }
        val allSeries = Metric.entries.mapNotNull { metric ->
            val points = measurements
                .asReversed()
                .mapNotNull { measurement ->
                    val value = if (metric == Metric.WEIGHT) {
                        BodyWeightHistory.weightFor(measurement, biaHistory)?.kg
                    } else {
                        metric.value(measurement)
                    }
                    value?.let { measurement to it }
                }
                .filter { (measurement, _) -> !entityDate(measurement).isBefore(fromDate) }
                .map { (measurement, value) ->
                    BodyMeasurementTrendView.Point(entityDate(measurement).format(DateTimeFormatter.ofPattern("dd/MM", Locale.ITALIAN)), value)
                }
            points.takeIf { it.isNotEmpty() }?.let { BodyMeasurementTrendView.Series(metric.label, it) }
        }
        findViewById<BodyMeasurementTrendView>(R.id.bodyMeasurementTrendChart).apply {
            setPoints(chartPoints)
            setNormalizedSeries(allSeries)
        }
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
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = getDrawable(R.drawable.bg_card)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) }
                setOnClickListener { openEdit(measurement) }
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

            val waistDelta = measurement.waistCm?.let { current ->
                previousAvailableValue(index) { it.waistCm }?.let { current - it }
            }
            if (waistDelta != null) {
                card.addView(TextView(this).apply {
                    text = "Vita ${formatSigned(waistDelta)} cm rispetto al valore precedente disponibile"
                    setTextColor(getColor(if (waistDelta <= 0f) R.color.semantic_positive else R.color.text_secondary))
                    textSize = 12f
                    setPadding(0, dp(6), 0, 0)
                })
            }

            card.addView(TextView(this).apply {
                text = "Tocca per modificare · Tieni premuto per eliminare"
                setTextColor(getColor(R.color.text_muted))
                textSize = 10f
                setPadding(0, dp(6), 0, 0)
            })
            container.addView(card)
        }
    }

    private fun previousAvailableValue(
        currentIndex: Int,
        selector: (BodyMeasurementEntity) -> Float?,
    ): Float? = measurements.drop(currentIndex + 1).firstNotNullOfOrNull(selector)

    private fun historyValues(value: BodyMeasurementEntity): String {
        val items = listOfNotNull(
            BodyWeightHistory.weightFor(value, biaHistory)?.let {
                if (it.fromBia) "Peso BIA (stessa data) ${formatKg(it.kg)}"
                else "Peso corporeo ${formatKg(it.kg)}"
            },
            value.hipsCm?.let { "Fianchi ${formatCm(it)}" },
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

    private fun openEdit(measurement: BodyMeasurementEntity) {
        startActivity(Intent(this, NewBodyMeasurementActivity::class.java).apply {
            putExtra(NewBodyMeasurementActivity.EXTRA_EDIT_ID, measurement.id)
        })
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

    private fun formatKg(value: Float): String = String.format(Locale.ITALIAN, "%.1f kg", value)

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

    private fun formatPercent(value: Float): String = String.format(Locale.ITALIAN, "%.1f%%", value)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_OPEN_HISTORY = "open_body_history"
        private const val PROPORTION_CARD_TAG = "body_proportion_card"
        private var proportionStatusId: Int = View.NO_ID
        private var proportionDetailsId: Int = View.NO_ID
        private var proportionNoteId: Int = View.NO_ID
        private var proportionAiButtonId: Int = View.NO_ID
    }
}
