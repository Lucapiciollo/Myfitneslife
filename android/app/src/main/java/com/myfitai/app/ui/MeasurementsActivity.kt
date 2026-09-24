package com.myfitai.app.ui

import android.content.Intent
import android.os.Bundle
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.card.MaterialCardView
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.widgets.MeasurementActionCardView
import com.myfitai.app.ui.widgets.StatusRowView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class MeasurementsActivity : BaseShellActivity() {
    private val data by lazy { AppDataContainer.get(this) }
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ITALIAN)
    private var biaHistory: List<BiaMeasurementEntity> = emptyList()
    private var latestBody: BodyMeasurementEntity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_measurements)
        bindBottom(BottomNavBinder.Tab.MORE)
        bindBack()
        normalizeMeasurementCards()

        findViewById<MeasurementActionCardView>(R.id.biaCard).apply {
            setTitle("BIA")
            setHelp("Spiega dati BIA e dati insufficienti") {
            MaterialAlertDialogBuilder(this@MeasurementsActivity)
                .setTitle("Dati BIA e dati insufficienti")
                .setMessage("La BIA può contribuire al calcolo di metabolismo, target e trend corporei quando sono disponibili valori coerenti, come peso, grasso corporeo o massa muscolare.\n\nLa dicitura \"Dati insufficienti\" compare quando mancano i valori necessari oppure quando esiste una sola rilevazione: una singola misura descrive solo lo stato attuale e non permette di calcolare una variazione affidabile nel tempo. Registra altre rilevazioni in date diverse per ottenere un confronto.")
                .setPositiveButton("Chiudi", null)
                .show()
            }
            setAddAction("Nuova rilevazione BIA") {
            startActivity(Intent(this@MeasurementsActivity, BiaActivity::class.java))
            }
            setHistoryAction("Storico rilevazioni BIA") {
            startActivity(Intent(this@MeasurementsActivity, BiaActivity::class.java).putExtra(BiaActivity.EXTRA_OPEN_HISTORY, true))
            }
        }
        findViewById<MeasurementActionCardView>(R.id.bodyCard).apply {
            setTitle("Misure")
            setHelp("Spiega misure corporee") {
            showHelpCard(
                "Misure corporee",
                "Registra circonferenze e peso nella stessa rilevazione. Usa lo storico per confrontare i valori nel tempo: la singola misura descrive lo stato del giorno, mentre il trend richiede più date confrontabili.",
            )
            }
            setAddAction("Nuova misura corporea") {
            startActivity(Intent(this@MeasurementsActivity, BodyMeasuresActivity::class.java))
            }
            setHistoryAction("Storico misure corporee") {
            startActivity(Intent(this@MeasurementsActivity, BodyMeasuresActivity::class.java).putExtra(BodyMeasuresActivity.EXTRA_OPEN_HISTORY, true))
            }
        }
        findViewById<android.view.View>(R.id.allHistoryButton).setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    data.activeProfileStore.activeProfileId
                        .flatMapLatest(data.biaRepository::all)
                        .collect {
                            biaHistory = it
                            renderBia(it.firstOrNull())
                            renderBody(latestBody)
                            renderDietImpact()
                        }
                }
                launch {
                    data.activeProfileStore.activeProfileId
                        .flatMapLatest(data.bodyMeasurementRepository::latest)
                        .collect {
                            latestBody = it
                            renderBody(it)
                            renderDietImpact()
                        }
                }
            }
        }
    }

    private fun renderBia(value: BiaMeasurementEntity?) {
        findViewById<MeasurementActionCardView>(R.id.biaCard).setLatest(value?.let {
            "Ultima rilevazione: ${formatDate(it.measuredAtEpochMillis)}"
        } ?: "Nessuna rilevazione BIA disponibile")
    }

    private fun normalizeMeasurementCards() {
        findViewById<MaterialCardView>(R.id.dietImpactCard).apply {
            setCardBackgroundColor(getColor(R.color.white))
            strokeColor = getColor(R.color.divider)
            strokeWidth = dp(1)
            cardElevation = 0f
        }
        listOf(R.id.biaCard, R.id.bodyCard).forEach { id ->
            findViewById<View>(id).apply {
                elevation = 0f
                setBackgroundColor(getColor(R.color.white))
            }
        }
    }

    private fun renderBody(value: BodyMeasurementEntity?) {
        findViewById<MeasurementActionCardView>(R.id.bodyCard).setLatest(value?.let {
            "Ultima rilevazione: ${formatDate(it.measuredAtEpochMillis)}"
        } ?: "Nessuna misura corporea disponibile")
    }

    private suspend fun renderDietImpact() {
        val profileId = data.activeProfileStore.currentIdOrNull()
        if (profileId == null) {
            findViewById<View>(R.id.dietImpactCard).visibility = View.GONE
            return
        }
        val bia = data.biaRepository.latest(profileId).first()
        val body = data.bodyMeasurementRepository.latest(profileId).first()

        if (bia == null && body == null) {
            findViewById<View>(R.id.dietImpactCard).visibility = View.GONE
            return
        }

        val rows = findViewById<LinearLayout>(R.id.dietImpactRows)
        rows.removeAllViews()
        findViewById<View>(R.id.dietImpactCard).visibility = View.VISIBLE
        listOf(
            "Peso" to (bia?.weightKg != null),
            "Grasso corporeo" to (bia?.bodyFatPercent != null),
            "Massa muscolare" to (bia?.muscleMassKg != null),
            "Vita" to (body?.waistCm != null),
            "Addome" to (body?.abdomenCm != null),
        ).forEachIndexed { index, (label, available) ->
            if (index > 0) rows.addView(View(this).apply {
                setBackgroundColor(getColor(R.color.divider))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            })
            rows.addView(impactRow(label, available))
        }

        val today = LocalDate.now()
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val plan = data.mealPlanRepository.getPlanForWeek(profileId, monday.toEpochDay())
        if (plan == null) {
            findViewById<TextView>(R.id.dietTargetReasonText).text =
                "Nessun piano corrente da confrontare. I trend corporei verranno valutati alla prossima generazione."
            return
        }

        val versions = data.mealPlanRepository.versions(profileId, plan.id).first().sortedByDescending { it.versionNumber }
        val current = versions.firstOrNull()
        val previous = versions.drop(1).firstOrNull()
        if (current == null) {
            findViewById<TextView>(R.id.dietTargetReasonText).text = "Target adattivo non ancora disponibile."
            return
        }

        val delta = if (current.targetKcal != null && previous?.targetKcal != null) current.targetKcal - previous.targetKcal else null
        val adaptive = parseAdaptiveReason(current.reason)
        findViewById<TextView>(R.id.dietTargetReasonText).text = buildString {
            append("Target attuale: ${current.targetKcal?.let { "$it kcal" } ?: "—"}")
            delta?.let { append(" · variazione ${if (it >= 0) "+" else ""}$it kcal") }
            appendLine()
            append("Decisione: ${adaptive.decisionLabel}")
            if (adaptive.windowDays != null) append(" · finestra ${adaptive.windowDays} giorni")
            appendLine()
            append("Motivo: ${adaptive.reasonLabel}")
            appendLine("\nIl motore usa trend sufficientemente lunghi, non una singola misurazione.")
            append("Acqua, grasso viscerale, muscolo scheletrico e altre circonferenze restano indicatori di contesto/monitoraggio.")
        }
    }

    private fun impactRow(label: String, available: Boolean): StatusRowView = StatusRowView(this).apply {
        setLabel(label)
        setState(if (available) "Utilizzato" else "Non disponibile")
        setStatus(if (available) StatusRowView.Status.POSITIVE else StatusRowView.Status.NEUTRAL)
        contentDescription = if (available) "$label utilizzato" else "$label non disponibile"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class AdaptiveReason(val decisionLabel: String, val reasonLabel: String, val windowDays: Int?)

    private fun parseAdaptiveReason(reason: String?): AdaptiveReason {
        val parts = reason.orEmpty().split(':')
        val decision = parts.getOrNull(1)
        val code = parts.getOrNull(2)
        val days = parts.getOrNull(3)?.removeSuffix("D")?.toIntOrNull()
        val decisionLabel = when (decision) {
            "INCREASE_DEFICIT" -> "deficit leggermente aumentato"
            "REDUCE_DEFICIT" -> "deficit ridotto"
            "KEEP" -> "target mantenuto"
            "INSUFFICIENT_DATA" -> "dati insufficienti"
            "NOT_APPLICABLE" -> "adattamento non applicabile"
            else -> "target corrente"
        }
        val reasonLabel = when (code) {
            "SUSTAINED_STALL" -> "stallo sostenuto su peso e circonferenze"
            "MUSCLE_TREND_DOWN" -> "trend della massa muscolare in calo"
            "WEIGHT_LOSS_TOO_FAST" -> "calo di peso troppo rapido"
            "FAVORABLE_PROGRESS" -> "progressi corporei favorevoli"
            "NO_SAFE_CHANGE" -> "nessuna variazione sufficientemente supportata"
            "INSUFFICIENT_TREND" -> "trend troppo breve o con pochi segnali"
            "GOAL_NOT_ADAPTIVE" -> "obiettivo non soggetto ad adattamento automatico"
            "MISSING_BASE_TARGET" -> "target di base incompleto"
            else -> code?.lowercase()?.replace('_', ' ') ?: "nessuna motivazione adattiva disponibile"
        }
        return AdaptiveReason(decisionLabel, reasonLabel, days)
    }

    private fun formatDate(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(dateFormatter)

    private fun formatNumber(value: Float): String = if (value % 1f == 0f) {
        value.toInt().toString()
    } else {
        String.format(Locale.ITALIAN, "%.1f", value)
    }
}
