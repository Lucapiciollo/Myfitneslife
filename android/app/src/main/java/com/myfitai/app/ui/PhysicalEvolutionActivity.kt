package com.myfitai.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.ImageButton
import android.widget.FrameLayout
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.progress.ProgressAnalysisCompactContract
import com.myfitai.app.domain.progress.ProgressAnalysisRequirements
import com.myfitai.app.domain.progress.ProgressAnalysisService
import com.myfitai.app.domain.ai.AiJobType
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.progress.PhysicalEvolutionState
import com.myfitai.app.ui.progress.PhysicalEvolutionViewModel
import com.myfitai.app.ui.progress.ProgressMetricState
import com.myfitai.app.ui.widgets.SelectableSegmentView
import com.myfitai.app.ui.widgets.TimeRangeSelectorView
import com.myfitai.app.ui.widgets.WeightTrendChartView
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

class PhysicalEvolutionActivity : BaseShellActivity() {
    private val data by lazy { AppDataContainer.get(this) }
    private val viewModel: PhysicalEvolutionViewModel by viewModels {
        PhysicalEvolutionViewModel.Factory(data.biaRepository, data.bodyMeasurementRepository, data.activeProfileStore)
    }
    private var metricIndex = 0
    private var rangeIndex = 1
    private var latestState = PhysicalEvolutionState()
    private var analysisDetailsExpanded = false
    private lateinit var analysisLastText: TextView
    private lateinit var analysisNextText: TextView
    private lateinit var analysisJobMessageText: TextView
    private lateinit var analysisResultText: TextView
    private lateinit var analysisDetailsButton: MaterialButton
    private lateinit var analysisDetailsContainer: LinearLayout
    private lateinit var analysisProgress: ProgressBar
    private lateinit var analysisButton: MaterialButton
    private lateinit var analysisRequirementsText: TextView
    private lateinit var analysisHeader: View
    private lateinit var analysisCard: View
    private lateinit var reviewHeader: View
    private lateinit var reviewCard: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_physical_evolution)
        bindBack()
        bindBottom(BottomNavBinder.Tab.PROGRESS)
        bindProgressAnalysisCard()
        findViewById<ImageButton>(R.id.otherIndicatorsHelpButton).setOnClickListener {
            showHelp(
                "Altri indicatori",
                "Qui trovi valori aggiuntivi letti dalle rilevazioni BIA del profilo attivo. " +
                    "L'acqua corporea, il grasso e la massa muscolare mostrano lo storico registrato: " +
                "non sono una diagnosi e possono variare anche per motivi temporanei.",
            )
        }
        findViewById<ImageButton>(R.id.progressMetricHelpButton).setOnClickListener {
            showHelp(
                "Progresso",
                "Il valore, la variazione e il grafico mostrano l'andamento della metrica selezionata " +
                    "usando le rilevazioni registrate nel profilo attivo. Se i dati sono insufficienti, " +
                    "l'app evita di stimare una tendenza non affidabile.",
            )
        }
        bindWeeklyReviewCard()

        findViewById<WeightTrendChartView>(R.id.evolutionChart).showYAxisLabels()
        findViewById<SelectableSegmentView>(R.id.metricSegment).apply {
            setSegments(listOf("Peso", "Grasso", "Massa muscolare"), 0)
            setOnSegmentSelectedListener { metricIndex = it; render(latestState) }
        }
        findViewById<TimeRangeSelectorView>(R.id.timeRangeSelector).apply {
            setRanges(listOf("1M", "3M", "6M", "1Y"), 1)
            setOnRangeSelectedListener { rangeIndex = it; render(latestState) }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { latestState = it; render(it) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                data.activeProfileStore.activeProfileId.collectLatest { profileId ->
                    if (profileId <= 0L) {
                        renderProgressAnalysisStatus()
                    } else {
                        data.aiJobScheduler
                            .observeActive(AiJobType.PROGRESS_ANALYSIS, profileId)
                            .collectLatest(::renderProgressAnalysisJob)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::analysisLastText.isInitialized) {
            data.activeProfileStore.currentIdOrNull()?.let(data.progressAnalysisScheduler::ensureScheduled)
            renderProgressAnalysisStatus()
        }
    }

    private fun bindProgressAnalysisCard() {
        val root = findViewById<LinearLayout>(R.id.dynamicProgressCards)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val title = TextView(this@PhysicalEvolutionActivity).apply {
                text = "Analisi progressi IA"
                setTextAppearance(R.style.Text_MyFitAI_SettingsLabel)
            }
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageButton(this@PhysicalEvolutionActivity).apply {
                setImageResource(R.drawable.ic_help_outline)
                background = null
                contentDescription = "Spiega analisi progressi IA"
                setOnClickListener {
                    showHelp(
                        "Analisi progressi IA",
                        "L'analisi IA interpreta i dati registrati nel tempo, come peso, grasso corporeo, " +
                            "massa muscolare, misure, allenamenti e sgarri. " +
                            "I valori mostrati nella pagina restano disponibili anche senza analisi IA: " +
                            "la scritta 'Ultima esecuzione: mai' significa solo che questa interpretazione non è ancora stata eseguita.",
                    )
                }
            }, LinearLayout.LayoutParams(dimen(R.dimen.icon_button_size), dimen(R.dimen.icon_button_size)))
        }
        analysisHeader = header

        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(getColor(R.color.white))
            radius = resources.getDimension(R.dimen.radius_card)
            strokeWidth = dimen(R.dimen.space_1)
            setStrokeColor(getColor(R.color.divider))
             cardElevation = 0f
        }
        analysisCard = card
        val cardContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val horizontalPadding = dimen(R.dimen.card_content_padding)
            val verticalPadding = dimen(R.dimen.space_14)
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
        }
        card.addView(cardContent)
        cardContent.addView(header)
        analysisLastText = bodyText()
        analysisNextText = bodyText()
        analysisJobMessageText = bodyText().apply {
            visibility = View.GONE
            setTextColor(getColor(R.color.text_secondary))
        }
        analysisResultText = bodyText().apply { visibility = View.GONE }
        analysisDetailsButton = MaterialButton(this).apply {
            text = "Mostra dettagli analisi"
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener {
                analysisDetailsExpanded = !analysisDetailsExpanded
                renderDetailsVisibility()
            }
        }
        analysisDetailsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        analysisProgress = ProgressBar(this).apply { visibility = View.GONE }
        analysisButton = MaterialButton(this).apply {
            text = "Esegui analisi ora"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { confirmManualProgressAnalysis() }
        }
        analysisRequirementsText = bodyText().apply {
            visibility = View.GONE
            setTextAppearance(R.style.Text_MyFitAI_Micro)
            setTextColor(getColor(R.color.text_muted))
        }
        cardContent.addView(analysisLastText, marginTopParams(R.dimen.space_8))
        cardContent.addView(analysisNextText, marginTopParams(R.dimen.space_4))
        cardContent.addView(analysisJobMessageText, marginTopParams(R.dimen.space_8))
        cardContent.addView(analysisResultText, marginTopParams(R.dimen.space_10))
        cardContent.addView(analysisDetailsButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dimen(R.dimen.control_compact_min_height)).apply { topMargin = dimen(R.dimen.space_8) })
        cardContent.addView(analysisDetailsContainer, marginTopParams(R.dimen.space_6))
        cardContent.addView(analysisProgress, LinearLayout.LayoutParams(dimen(R.dimen.progress_indicator_size), dimen(R.dimen.progress_indicator_size)).apply { topMargin = dimen(R.dimen.space_10) })
        cardContent.addView(analysisButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dimen(R.dimen.button_primary_min_height)).apply { topMargin = dimen(R.dimen.space_12) })
        cardContent.addView(analysisRequirementsText, marginTopParams(R.dimen.space_6))
        root.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dimen(R.dimen.space_8)
        })
        renderProgressAnalysisStatus()
    }

    private fun bindWeeklyReviewCard() {
        val root = findViewById<LinearLayout>(R.id.dynamicProgressCards)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(TextView(this@PhysicalEvolutionActivity).apply {
                text = "Review settimanale"
                 setTextAppearance(R.style.Text_MyFitAI_SettingsLabel)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageButton(this@PhysicalEvolutionActivity).apply {
                setImageResource(R.drawable.ic_help_outline)
                background = null
                contentDescription = "Spiega review settimanale"
                setOnClickListener {
                    showHelp(
                        "Review settimanale",
                        "Raccoglie alimentazione, allenamenti e variazioni corporee degli ultimi sette giorni. " +
                            "La sintesi IA viene generata solo sui dati registrati e non sostituisce una valutazione professionale.",
                    )
                }
            }, LinearLayout.LayoutParams(dimen(R.dimen.icon_button_size), dimen(R.dimen.icon_button_size)))
        }
        reviewHeader = header

        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(getColor(R.color.white))
            radius = resources.getDimension(R.dimen.radius_card)
            strokeWidth = dimen(R.dimen.space_1)
            setStrokeColor(getColor(R.color.divider))
             cardElevation = 0f
            isClickable = true
            isFocusable = true
            setOnClickListener { startActivity(Intent(this@PhysicalEvolutionActivity, WeeklyReviewActivity::class.java)) }
        }
        reviewCard = card
        val cardContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val horizontalPadding = dimen(R.dimen.card_content_padding)
            val verticalPadding = dimen(R.dimen.space_14)
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
        }
        card.addView(cardContent)
        cardContent.addView(header)
        cardContent.addView(TextView(this).apply {
            text = "Gli ultimi 7 giorni, in un unico punto"
            setTextAppearance(R.style.Text_MyFitAI_SettingsLabel)
        })
        cardContent.addView(TextView(this).apply {
            text = "Controlla alimentazione, allenamenti, variazioni corporee e sintesi IA della settimana."
            setTextAppearance(R.style.Text_MyFitAI_Body)
        }, marginTopParams(R.dimen.space_6))
        cardContent.addView(MaterialButton(this).apply {
            text = "Apri review"
            isAllCaps = false
            setOnClickListener { startActivity(Intent(this@PhysicalEvolutionActivity, WeeklyReviewActivity::class.java)) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dimen(R.dimen.button_primary_min_height)).apply {
            topMargin = dimen(R.dimen.space_12)
        })
        root.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dimen(R.dimen.space_8)
        })
        reviewHeader.visibility = View.GONE
        reviewCard.visibility = View.GONE
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                data.activeProfileStore.activeProfileId.collectLatest { profileId ->
                    val hasReview = profileId > 0L && data.weeklyReviewRepository.all(profileId).first().isNotEmpty()
                    reviewHeader.visibility = if (hasReview) View.VISIBLE else View.GONE
                    reviewCard.visibility = if (hasReview) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun confirmManualProgressAnalysis() {
        confirmAiRequest("L'analisi dei progressi corporei") {
            executeProgressAnalysis()
        }
    }

    private fun executeProgressAnalysis() {
        if (analysisProgress.visibility == View.VISIBLE) return
        val profileId = data.activeProfileStore.currentIdOrNull()
        if (profileId == null) {
            analysisResultText.visibility = View.VISIBLE
            analysisResultText.text = "Seleziona un profilo prima di avviare l'analisi IA."
            analysisProgress.visibility = View.GONE
            setAiActionEnabled(analysisButton, false)
            return
        }
        setAiActionEnabled(analysisButton, false)
        analysisButton.text = "Analisi in corso…"
        analysisProgress.visibility = View.VISIBLE
        showAnalysisJobMessage("Analisi dei trend corporei e dello storico registrato…")
        analysisDetailsButton.visibility = View.GONE
        analysisDetailsContainer.visibility = View.GONE
        analysisDetailsExpanded = false
        val jobKey = "manual-${System.currentTimeMillis()}"
        val enqueueError = runCatching {
            data.aiJobScheduler.enqueue(AiJobType.PROGRESS_ANALYSIS, profileId, jobKey)
        }.exceptionOrNull()
        if (enqueueError != null) {
            showAnalysisJobMessage("Impossibile avviare l'analisi IA: ${enqueueError.message ?: "errore locale"}")
            analysisProgress.visibility = View.GONE
            setAiActionEnabled(analysisButton)
            analysisButton.text = "Esegui analisi ora"
            return
        }
    }

    private fun renderProgressAnalysisJob(info: androidx.work.WorkInfo?) {
        when (info?.state) {
            androidx.work.WorkInfo.State.BLOCKED,
            androidx.work.WorkInfo.State.RUNNING -> {
                analysisProgress.visibility = View.VISIBLE
                showAnalysisJobMessage("Analisi dei trend corporei e dello storico registrato…")
                analysisDetailsButton.visibility = View.GONE
                analysisDetailsContainer.visibility = View.GONE
                setAiActionEnabled(analysisButton, false)
                analysisButton.text = "Analisi in corso…"
            }
            androidx.work.WorkInfo.State.ENQUEUED -> {
                val profileId = data.activeProfileStore.currentIdOrNull()
                val isManual = profileId != null && info.tags.contains(
                    "ai-${AiJobType.PROGRESS_ANALYSIS.name.lowercase()}-profile-$profileId-manual",
                )
                val nextDue = profileId?.let(data.progressAnalysisPreferences::nextDueEpochMillis)
                val automaticIsDue = nextDue != null && nextDue <= System.currentTimeMillis()
                if (!isManual && !automaticIsDue) {
                    analysisProgress.visibility = View.GONE
                    hideAnalysisJobMessage()
                    analysisButton.text = "Esegui analisi ora"
                    renderProgressAnalysisStatus()
                    return
                }
                analysisProgress.visibility = View.VISIBLE
                showAnalysisJobMessage(
                    if (!isManual) "Analisi automatica in attesa: verrà eseguita quando il dispositivo sarà connesso."
                    else "Analisi in coda: partirà quando il dispositivo sarà connesso."
                )
                setAiActionEnabled(analysisButton, false)
                analysisButton.text = "Analisi in coda…"
            }
            androidx.work.WorkInfo.State.FAILED -> {
                analysisProgress.visibility = View.GONE
                setAiActionEnabled(analysisButton)
                analysisButton.text = "Esegui analisi ora"
                val error = info.outputData.getString(com.myfitai.app.domain.ai.AiJobWorker.KEY_ERROR)
                val profileId = data.activeProfileStore.currentIdOrNull()
                val previousSummary = profileId?.let(data.progressAnalysisPreferences::lastSummary)
                renderProgressAnalysisStatus()
                if (!error.isNullOrBlank()) {
                    showAnalysisJobMessage("Ultima richiesta non riuscita: $error")
                    if (!previousSummary.isNullOrBlank()) {
                        analysisResultText.visibility = View.VISIBLE
                    }
                }
            }
            androidx.work.WorkInfo.State.SUCCEEDED,
            androidx.work.WorkInfo.State.CANCELLED,
            null -> {
                analysisProgress.visibility = View.GONE
                analysisButton.text = "Esegui analisi ora"
                hideAnalysisJobMessage()
                renderProgressAnalysisStatus()
            }
        }
    }

    private fun showAnalysisJobMessage(message: String) {
        analysisJobMessageText.text = message
        analysisJobMessageText.visibility = View.VISIBLE
    }

    private fun hideAnalysisJobMessage() {
        analysisJobMessageText.visibility = View.GONE
    }

    private fun renderProgressAnalysisStatus(keepTransientResult: Boolean = false) {
        refreshProgressAnalysisRequirements()
        val profileId = data.activeProfileStore.currentIdOrNull()
        if (profileId == null) {
            analysisHeader.visibility = View.GONE
            analysisCard.visibility = View.GONE
            analysisLastText.text = "Ultima esecuzione: profilo non disponibile"
            analysisNextText.text = "Esecuzione automatica: non pianificata"
            analysisProgress.visibility = View.GONE
            hideAnalysisJobMessage()
            setAiActionEnabled(analysisButton, false)
            analysisDetailsButton.visibility = View.GONE
            analysisDetailsContainer.visibility = View.GONE
            return
        }
        val prefs = data.progressAnalysisPreferences
        val last = prefs.lastSuccessEpochMillis(profileId)
        val hasAnalysis = !prefs.lastSummary(profileId).isNullOrBlank()
        analysisHeader.visibility = View.VISIBLE
        analysisCard.visibility = View.VISIBLE
        analysisLastText.text = if (last == null) {
            "Ultima esecuzione: mai"
        } else {
            "Ultima esecuzione: ${formatDateTime(last)}"
        }
        val next = prefs.nextDueEpochMillis(profileId)
        analysisNextText.text = if (next == null) {
            "Automatica: si attiva dopo la prima analisi · frequenza ${prefs.intervalWeeks} sett."
        } else {
            val remaining = prefs.remainingMillis(profileId, System.currentTimeMillis()) ?: 0L
            "Prossima automatica: ${formatDateTime(next)} · manca ${formatRemaining(remaining)}"
        }
        analysisResultText.setTextColor(getColor(R.color.text_secondary))
        if (!keepTransientResult) {
            val summary = prefs.lastSummary(profileId)
            val classificationValue = prefs.lastClassification(profileId)
            val classification = classificationValue?.let(::classificationLabel)
            val confidence = prefs.lastConfidence(profileId)?.let(::confidenceLabel)
            if (summary != null) {
                analysisResultText.visibility = View.VISIBLE
                val result = SpannableStringBuilder()
                classification?.let {
                    val start = result.length
                    result.append(it)
                    result.setSpan(ForegroundColorSpan(classificationColor(classificationValue)), start, result.length, 0)
                }
                confidence?.let {
                    if (result.isNotEmpty()) result.append(" · ")
                    result.append("confidenza $it")
                }
                result.append("\n").append(summary)
                analysisResultText.text = result
                renderAnalysisDetails(prefs.lastPatterns(profileId))
            } else {
                analysisResultText.visibility = View.VISIBLE
                analysisResultText.setTextColor(getColor(R.color.text_secondary))
                analysisResultText.text = "Nessuna analisi IA disponibile. Tocca 'Esegui analisi ora' per ricevere una sintesi dei tuoi progressi."
                analysisDetailsButton.visibility = View.GONE
                analysisDetailsContainer.visibility = View.GONE
            }
        }
    }

    private fun refreshProgressAnalysisRequirements() {
        lifecycleScope.launch {
            val profileId = data.activeProfileStore.currentIdOrNull()
            val missing = if (profileId == null) {
                listOf("un profilo attivo")
            } else {
                val snapshot = data.profileCalculationService.profileSnapshot(profileId, java.time.LocalDate.now())
                ProgressAnalysisRequirements.missing(snapshot)
            }
            setAiActionEnabled(analysisButton, missing.isEmpty() && analysisProgress.visibility != View.VISIBLE)
            if (missing.isEmpty()) {
                analysisRequirementsText.visibility = View.GONE
            } else {
                analysisRequirementsText.visibility = View.VISIBLE
                analysisRequirementsText.text = ProgressAnalysisRequirements.message(missing)
            }
        }
    }

    private fun showHelp(title: String, message: String) {
        showHelpCard(title, message)
    }

    private fun renderAnalysisDetails(patterns: List<ProgressAnalysisCompactContract.Pattern>) {
        analysisDetailsContainer.removeAllViews()
        if (patterns.isEmpty()) {
            analysisDetailsButton.visibility = View.GONE
            analysisDetailsContainer.visibility = View.GONE
            return
        }
        analysisDetailsButton.visibility = View.VISIBLE
        patterns.forEach { pattern ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.TOP
                val verticalPadding = dimen(R.dimen.space_5)
                setPadding(0, verticalPadding, 0, verticalPadding)
            }
            val indicator = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dimen(R.dimen.progress_pattern_indicator_width), dimen(R.dimen.progress_pattern_indicator_width)).apply {
                    marginEnd = dimen(R.dimen.space_4)
                }
                val color = when (pattern.direction) {
                    ProgressAnalysisCompactContract.Direction.FAVORABLE -> getColor(R.color.semantic_positive)
                    ProgressAnalysisCompactContract.Direction.UNFAVORABLE -> getColor(R.color.semantic_error)
                    ProgressAnalysisCompactContract.Direction.UNCERTAIN -> getColor(R.color.semantic_warning)
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(color)
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            indicator.addView(TextView(this).apply {
                text = ""
                setTextAppearance(R.style.Text_MyFitAI_BodyEmphasis)
                layoutParams = FrameLayout.LayoutParams(dimen(R.dimen.progress_pattern_indicator_dot_size), dimen(R.dimen.progress_pattern_indicator_dot_size), android.view.Gravity.CENTER)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            item.addView(indicator)
            item.addView(TextView(this).apply {
                text = "${patternLabel(pattern.code)} · ${directionLabel(pattern.direction)} · confidenza ${confidenceLabel(pattern.confidence)}"
                setTextAppearance(R.style.Text_MyFitAI_Body)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            item.contentDescription = "${directionLabel(pattern.direction)}: ${patternLabel(pattern.code)}, confidenza ${confidenceLabel(pattern.confidence)}"
            analysisDetailsContainer.addView(item)
        }
        renderDetailsVisibility()
    }

    private fun renderDetailsVisibility() {
        analysisDetailsContainer.visibility = if (analysisDetailsExpanded && analysisDetailsContainer.childCount > 0) View.VISIBLE else View.GONE
        analysisDetailsButton.text = if (analysisDetailsExpanded) "Nascondi dettagli analisi" else "Mostra dettagli analisi"
    }

    private fun patternLabel(code: ProgressAnalysisCompactContract.PatternCode): String = when (code) {
        ProgressAnalysisCompactContract.PatternCode.WEIGHT -> "Peso"
        ProgressAnalysisCompactContract.PatternCode.BODY_FAT -> "Grasso corporeo"
        ProgressAnalysisCompactContract.PatternCode.MUSCLE -> "Massa muscolare"
        ProgressAnalysisCompactContract.PatternCode.WAIST -> "Circonferenza vita"
        ProgressAnalysisCompactContract.PatternCode.ABDOMEN -> "Circonferenza addome"
        ProgressAnalysisCompactContract.PatternCode.LIMBS -> "Circonferenze arti"
        ProgressAnalysisCompactContract.PatternCode.TRAINING -> "Allenamento"
        ProgressAnalysisCompactContract.PatternCode.DEVIATIONS -> "Rispetto del piano e deviazioni"
        ProgressAnalysisCompactContract.PatternCode.BODY_COHERENCE -> "Coerenza complessiva dei dati corporei"
    }

    private fun directionLabel(direction: ProgressAnalysisCompactContract.Direction): String = when (direction) {
        ProgressAnalysisCompactContract.Direction.FAVORABLE -> "trend favorevole"
        ProgressAnalysisCompactContract.Direction.UNFAVORABLE -> "da monitorare"
        ProgressAnalysisCompactContract.Direction.UNCERTAIN -> "trend non conclusivo"
    }

    private fun render(state: PhysicalEvolutionState) {
        val selected = when (metricIndex) { 1 -> state.bodyFat; 2 -> state.muscle; else -> state.weight }
        val filtered = viewModel.filtered(selected, rangeIndex)
        val unit = if (metricIndex == 1) "%" else "kg"
        findViewById<TextView>(R.id.metricLabel).text = listOf("Peso", "Grasso corporeo", "Massa muscolare")[metricIndex]
        findViewById<TextView>(R.id.metricValue).text = filtered.value?.let { "${fmt(it)} $unit" } ?: "—"
        val rangeLabel = listOf("1M", "3M", "6M", "1Y")[rangeIndex]
        findViewById<TextView>(R.id.metricDelta).text = filtered.delta?.let { "${signed(it)} $unit negli ultimi $rangeLabel" } ?: "Dati insufficienti"
        findViewById<WeightTrendChartView>(R.id.evolutionChart).setData(filtered.series.map { it.value })
        renderDateLabels(filtered)
        renderSecondary(R.id.otherIndicatorFatValue, R.id.otherIndicatorFatDelta, state.bodyFat, "%")
        renderSecondary(R.id.otherIndicatorMuscleValue, R.id.otherIndicatorMuscleDelta, state.muscle, "kg")
        renderSecondary(R.id.otherIndicatorWaterValue, R.id.otherIndicatorWaterDelta, state.bodyWater, "%")
        findViewById<View>(R.id.visualComparisonSection).visibility = View.GONE
        findViewById<TextView>(R.id.progressSummaryTitle).text = if (filtered.series.size >= 2) "Andamento basato sulle misurazioni disponibili" else "Affidabilità dell’andamento"
        findViewById<TextView>(R.id.progressSummaryText).text = if (filtered.series.size >= 2) "I valori mostrati derivano dallo storico BIA reale del profilo attivo." else "Aggiungi almeno due misurazioni confrontabili per visualizzare un andamento affidabile."
        findViewById<View>(R.id.otherIndicatorsCard).visibility = if (state.bodyFat.value == null && state.muscle.value == null && state.bodyWater.value == null) View.GONE else View.VISIBLE
    }

    private fun renderSecondary(valueId: Int, deltaId: Int, metric: ProgressMetricState, unit: String) {
        findViewById<TextView>(valueId).text = metric.value?.let { "${fmt(it)} $unit" } ?: "—"
        findViewById<TextView>(deltaId).text = metric.delta?.let { signed(it) } ?: "—"
    }

    private fun renderDateLabels(metric: ProgressMetricState) {
        val ids = intArrayOf(R.id.dateLabel1, R.id.dateLabel2, R.id.dateLabel3, R.id.dateLabel4, R.id.dateLabel5)
        val points = metric.series
        ids.forEachIndexed { index, id ->
            val point = if (points.isEmpty()) null else points[((points.lastIndex * index) / 4).coerceIn(0, points.lastIndex)]
            findViewById<TextView>(id).text = point?.let {
                Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM"))
            } ?: "—"
        }
    }

    private fun bodyText() = TextView(this).apply {
        setTextAppearance(R.style.Text_MyFitAI_Body)
    }

    private fun marginTopParams(dimenRes: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dimen(dimenRes) }

    private fun classificationLabel(value: ProgressAnalysisCompactContract.Classification): String = when (value) {
        ProgressAnalysisCompactContract.Classification.POSITIVE_RECOMPOSITION -> "Ricomposizione positiva"
        ProgressAnalysisCompactContract.Classification.STABLE -> "Andamento stabile"
        ProgressAnalysisCompactContract.Classification.WEIGHT_LOSS -> "Dimagrimento in corso"
        ProgressAnalysisCompactContract.Classification.WEIGHT_LOSS_WITH_MUSCLE_RISK -> "Dimagrimento con segnale muscolare da monitorare"
        ProgressAnalysisCompactContract.Classification.NEGATIVE_TREND -> "Trend sfavorevole"
        ProgressAnalysisCompactContract.Classification.INSUFFICIENT_DATA -> "Dati insufficienti"
    }

    private fun classificationLabel(value: String): String = runCatching {
        classificationLabel(ProgressAnalysisCompactContract.Classification.valueOf(value))
    }.getOrDefault(value)

    private fun classificationColor(value: String?): Int = when (runCatching {
        value?.let { ProgressAnalysisCompactContract.Classification.valueOf(it) }
    }.getOrNull()) {
        ProgressAnalysisCompactContract.Classification.POSITIVE_RECOMPOSITION,
        ProgressAnalysisCompactContract.Classification.STABLE,
        ProgressAnalysisCompactContract.Classification.WEIGHT_LOSS -> getColor(R.color.semantic_positive)
        ProgressAnalysisCompactContract.Classification.WEIGHT_LOSS_WITH_MUSCLE_RISK,
        ProgressAnalysisCompactContract.Classification.NEGATIVE_TREND -> getColor(R.color.semantic_error)
        else -> getColor(R.color.text_secondary)
    }

    private fun confidenceLabel(value: ProgressAnalysisCompactContract.Confidence): String = when (value) {
        ProgressAnalysisCompactContract.Confidence.LOW -> "bassa"
        ProgressAnalysisCompactContract.Confidence.MEDIUM -> "media"
        ProgressAnalysisCompactContract.Confidence.HIGH -> "alta"
    }

    private fun confidenceLabel(value: String): String = runCatching {
        confidenceLabel(ProgressAnalysisCompactContract.Confidence.valueOf(value))
    }.getOrDefault(value.lowercase(Locale.ITALIAN))

    private fun formatDateTime(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ITALIAN))

    private fun formatRemaining(millis: Long): String {
        if (millis <= 0L) return "appena possibile"
        val totalHours = TimeUnit.MILLISECONDS.toHours(millis)
        val totalDays = TimeUnit.MILLISECONDS.toDays(millis)
        val weeks = totalDays / 7
        val days = totalDays % 7
        return when {
            weeks > 0 && days > 0 -> "$weeks ${if (weeks == 1L) "settimana" else "settimane"} e $days ${if (days == 1L) "giorno" else "giorni"}"
            weeks > 0 -> "$weeks ${if (weeks == 1L) "settimana" else "settimane"}"
            totalDays > 0 -> "$totalDays ${if (totalDays == 1L) "giorno" else "giorni"}"
            else -> "${totalHours.coerceAtLeast(1)} ${if (totalHours <= 1) "ora" else "ore"}"
        }
    }

    private fun dimen(dimenRes: Int): Int = resources.getDimensionPixelSize(dimenRes)
    private fun fmt(v: Float) = String.format(Locale.ITALIAN, "%.1f", v)
    private fun signed(v: Float) = String.format(Locale.ITALIAN, "%+.1f", v)
}
