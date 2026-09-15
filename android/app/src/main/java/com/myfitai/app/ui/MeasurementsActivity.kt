package com.myfitai.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.navigation.BottomNavBinder
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_measurements)
        bindBottom(BottomNavBinder.Tab.MORE)
        bindBack()

        findViewById<android.view.View>(R.id.newBiaButton).setOnClickListener { startActivity(Intent(this, BiaActivity::class.java)) }
        findViewById<android.view.View>(R.id.biaHistoryButton).setOnClickListener {
            startActivity(Intent(this, BiaActivity::class.java).putExtra(BiaActivity.EXTRA_OPEN_HISTORY, true))
        }
        findViewById<android.view.View>(R.id.newBodyButton).setOnClickListener { startActivity(Intent(this, BodyMeasuresActivity::class.java)) }
        findViewById<android.view.View>(R.id.bodyHistoryButton).setOnClickListener {
            startActivity(Intent(this, BodyMeasuresActivity::class.java).putExtra(BodyMeasuresActivity.EXTRA_OPEN_HISTORY, true))
        }
        findViewById<android.view.View>(R.id.allHistoryButton).setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    data.activeProfileStore.activeProfileId
                        .flatMapLatest(data.biaRepository::latest)
                        .collect {
                            renderBia(it)
                            renderDietImpact()
                        }
                }
                launch {
                    data.activeProfileStore.activeProfileId
                        .flatMapLatest(data.bodyMeasurementRepository::latest)
                        .collect {
                            renderBody(it)
                            renderDietImpact()
                        }
                }
            }
        }
    }

    private fun renderBia(value: BiaMeasurementEntity?) {
        findViewById<TextView>(R.id.biaLatestText).text = value?.let {
            val date = formatDate(it.measuredAtEpochMillis)
            val details = listOfNotNull(
                it.weightKg?.let { weight -> "Peso ${formatNumber(weight)} kg" },
                it.bodyFatPercent?.let { fat -> "Grasso ${formatNumber(fat)}%" },
                it.muscleMassKg?.let { muscle -> "Massa muscolare ${formatNumber(muscle)} kg" },
            ).joinToString(" · ")
            "Ultima rilevazione: $date\n${details.ifBlank { "Valori parziali" }}"
        } ?: "Nessuna rilevazione BIA disponibile"
    }

    private fun renderBody(value: BodyMeasurementEntity?) {
        findViewById<TextView>(R.id.bodyLatestText).text = value?.let {
            val details = listOfNotNull(
                it.waistCm?.let { waist -> "Vita ${formatNumber(waist)} cm" },
                it.abdomenCm?.let { abdomen -> "Addome ${formatNumber(abdomen)} cm" },
                it.chestCm?.let { chest -> "Torace ${formatNumber(chest)} cm" },
            ).joinToString(" · ")
            "Ultima rilevazione: ${formatDate(it.measuredAtEpochMillis)}\n${details.ifBlank { "Valori parziali" }}"
        } ?: "Nessuna misura corporea disponibile"
    }

    private suspend fun renderDietImpact() {
        val profileId = data.activeProfileStore.currentIdOrNull() ?: return
        val bia = data.biaRepository.latest(profileId).first()
        val body = data.bodyMeasurementRepository.latest(profileId).first()

        fun used(available: Boolean) = if (available) "✓ utilizzato" else "— non disponibile"
        findViewById<TextView>(R.id.dietImpactText).text = buildString {
            appendLine("Dati che possono influenzare i target:")
            appendLine("Peso: ${used(bia?.weightKg != null)}")
            appendLine("Grasso corporeo: ${used(bia?.bodyFatPercent != null)}")
            appendLine("Massa muscolare: ${used(bia?.muscleMassKg != null)}")
            appendLine("Vita: ${used(body?.waistCm != null)}")
            append("Addome: ${used(body?.abdomenCm != null)}")
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
