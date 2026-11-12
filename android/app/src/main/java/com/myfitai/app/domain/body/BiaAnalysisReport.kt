package com.myfitai.app.domain.body

import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.local.entity.WorkoutEntity
import com.myfitai.app.domain.ai.AiUserContext
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import com.myfitai.app.domain.calculation.OperationalNutritionMetrics
import com.myfitai.app.domain.calculation.ProfileCalculationMapper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Deterministic input of the BIA Progress Coach, derived from Room.
 *
 * The report is built where the job runs and never travels through WorkManager `Data`
 * (hard limit: 10 KB). Sending the whole history as a job parameter made the app crash
 * as soon as the history exceeded roughly 17 readings.
 */
object BiaAnalysisReport {
    /** Newest readings sent to the model; older ones add tokens without improving the trend. */
    const val MAX_HISTORY_ROWS = 36

    private val DELTA_KEYS = setOf(
        "weightKg", "bodyFatPercent", "muscleMassKg", "skeletalMuscleKg", "bodyWaterPercent",
        "fatMassKg", "leanMassKg", "bodyWaterKg", "subcutaneousFatPercent", "boneMassKg",
        "proteinPercent", "proteinKg", "bmi",
    )

    data class Report(
        /** Every saved reading of the profile, not only the ones sent as history. */
        val measurementCount: Int,
        val latest: BiaMeasurementEntity,
        val current: Map<String, Float>,
        /** Difference from the most recent earlier reading that has the same metric. */
        val previousDelta: Map<String, Float>,
        /** Chronological, ending with [latest]; later readings are never included. */
        val history: List<BiaMeasurementEntity>,
        val conditions: String,
        /** Reading immediately before [latest] in chronological order, null for the first one. */
        val previous: BiaMeasurementEntity? = null,
        /** Comparability of [latest] with [previous] from [BiaMeasurementQualityEngine]. */
        val quality: BiaMeasurementQualityEngine.Result? = null,
    ) {
        /** Different conditions or low comparability: the noise thresholds are doubled. */
        val degradedComparability: Boolean
            get() = quality?.let { q -> q.level == BiaMeasurementQualityEngine.Level.LOW || q.reasons.any { it.startsWith("Condizione diversa") } } ?: false
    }

    /**
     * @param all every reading of the profile, in any order.
     * @param selectedId reading to analyse; the most recent one when null or unknown.
     * @return null when there is no reading with at least one measured value.
     */
    fun build(all: List<BiaMeasurementEntity>, selectedId: Long? = null): Report? {
        val chronological = all.sortedWith(compareBy({ it.measuredAtEpochMillis }, { it.id }))
        val latest = selectedId?.let { id -> chronological.firstOrNull { it.id == id } }
            ?: chronological.lastOrNull()
            ?: return null
        val current = measurementValues(latest)
        if (current.isEmpty()) return null

        val untilLatest = chronological.subList(0, chronological.indexOfFirst { it.id == latest.id } + 1)
        val earlierNewestFirst = untilLatest.dropLast(1).asReversed().map(::measurementValues)
        val delta = linkedMapOf<String, Float>()
        current.forEach { (key, value) ->
            if (key !in DELTA_KEYS) return@forEach
            earlierNewestFirst.firstNotNullOfOrNull { it[key] }?.let { delta[key] = value - it }
        }
        return Report(
            measurementCount = all.size,
            latest = latest,
            current = current,
            previousDelta = delta,
            history = untilLatest.takeLast(MAX_HISTORY_ROWS),
            conditions = conditionLine(latest) ?: "?",
            previous = untilLatest.dropLast(1).lastOrNull(),
            quality = untilLatest.dropLast(1).lastOrNull()?.let { BiaMeasurementQualityEngine.evaluate(latest, it) },
        )
    }

    /** Keys are stable provider-facing names; null metrics are omitted, never sent as zero. */
    fun measurementValues(item: BiaMeasurementEntity): Map<String, Float> = linkedMapOf<String, Float>().apply {
        item.weightKg?.let { put("weightKg", it) }
        item.bodyFatPercent?.let { put("bodyFatPercent", it) }
        item.visceralFatLevel?.let { put("visceralFatLevel", it) }
        item.muscleMassKg?.let { put("muscleMassKg", it) }
        item.skeletalMuscleKg?.let { put("skeletalMuscleKg", it) }
        item.bodyWaterPercent?.let { put("bodyWaterPercent", it) }
        item.bmrKcal?.let { put("bmrKcal", it) }
        item.fatMassKg?.let { put("fatMassKg", it) }
        item.leanMassKg?.let { put("leanMassKg", it) }
        item.bodyWaterKg?.let { put("bodyWaterKg", it) }
        item.subcutaneousFatPercent?.let { put("subcutaneousFatPercent", it) }
        item.boneMassKg?.let { put("boneMassKg", it) }
        item.proteinPercent?.let { put("proteinPercent", it) }
        item.proteinKg?.let { put("proteinKg", it) }
        item.bodyAgeYears?.let { put("bodyAgeYears", it.toFloat()) }
        item.bmi?.let { put("bmi", it) }
    }

    fun conditionLine(item: BiaMeasurementEntity): String? {
        val conditions = listOfNotNull(
            "a digiuno".takeIf { item.fasting },
            "appena sveglio".takeIf { item.justWokeUp },
            "dopo bagno".takeIf { item.afterBathroom },
            "nessun allenamento recente".takeIf { item.noRecentWorkout },
        )
        return conditions.takeIf { it.isNotEmpty() }?.joinToString(" · ", prefix = "Condizioni: ")
    }

    fun valuesForPrompt(values: Map<String, Float>): String =
        values.entries.joinToString(",") { (key, value) -> "$key=$value" }.ifBlank { "?" }

    /**
     * Measurement conditions of one reading. The database stores plain booleans: `N` means "not
     * declared or not true" and cannot be told apart from an explicit "no".
     */
    fun conditionsForRow(item: BiaMeasurementEntity): String =
        "fasting=${yn(item.fasting)},wokeUp=${yn(item.justWokeUp)},afterBathroom=${yn(item.afterBathroom)},noWorkout=${yn(item.noRecentWorkout)}"

    private fun yn(value: Boolean) = if (value) "Y" else "N"

    fun historyForPrompt(history: List<BiaMeasurementEntity>): String =
        history.joinToString(";") { row ->
            "date=${BiaHistoryImportContract.dayKey(row.measuredAtEpochMillis)},${conditionsForRow(row)}," + valuesForPrompt(measurementValues(row))
        }.ifBlank { "?" }

    // ---- Context of the BIA Progress Coach -------------------------------------------------------------

    /** Planned day of the meal plan: it is a plan, never consumption. */
    data class PlanDay(val dateEpochDay: Long, val kcal: Number?, val proteinG: Number?, val carbsG: Number?, val fatG: Number?)

    data class CoachContext(
        val prompt: String,
        val safetyFlag: AiUserContext.SafetyFlag,
        /** Scenarios calculated by the app and sent as `SCENARI_APP`; the model only comments them. */
        val scenarios: List<BiaScenarioCalculator.Scenario> = emptyList(),
        /** Calories and macros written by the app ([caloriesSection]); the model only adds a qualitative comment. */
        val caloriesText: String = "",
    )

    /**
     * Target of the day shown by the Home, from the same [OperationalNutritionMetrics] calculation. [dayType] is
     * `RIPOSO` or `ALLENAMENTO`; every value may be missing.
     */
    data class OperationalTarget(
        val dayType: String,
        val tdeeKcal: Double?,
        val targetKcal: Double?,
        val proteinG: Double?,
        val carbsG: Double?,
        val fatG: Double?,
    ) {
        companion object {
            fun from(metrics: OperationalNutritionMetrics.Result) = OperationalTarget(
                dayType = if (metrics.activityStatus == "PLANNED_WORKOUT") "ALLENAMENTO" else "RIPOSO",
                tdeeKcal = metrics.operationalTdeeKcal,
                targetKcal = metrics.operationalTargetKcal,
                proteinG = metrics.operationalProteinG,
                carbsG = metrics.operationalCarbsG,
                fatG = metrics.operationalFatG,
            )
        }
    }

    private const val DAY_MILLIS = 86_400_000L

    private const val INPUT_LEGEND = "U=sex|ageYears|heightCm|weightKg|goal|activity|wakeMinutes|sleepMinutes. ? means unavailable."

    /**
     * Builds the whole coach input in the same format as [AiUserContext]: age in years, ISO `yyyy-MM-dd`
     * dates everywhere, no epoch values. Every window (BIA, body measurements, workouts, plan days,
     * weights) is capped to [MAX_HISTORY_ROWS] rows and never contains data later than the analysed reading.
     *
     * The two calorie targets are sent with explicit labels (`TARGET_ABITUALE_KCAL` from [calculation],
     * `TARGET_OPERATIVO_OGGI_KCAL` from [operational]); the fat scenarios and the fat-loss estimate are calculated
     * here by [BiaScenarioCalculator] and the model only comments them.
     */
    fun coachContext(
        profile: UserProfileEntity,
        today: LocalDate,
        report: Report,
        bodyHistory: List<BodyMeasurementEntity>,
        workoutHistory: List<WorkoutEntity>,
        planDays: List<PlanDay>,
        calculation: LocalCalculationEngine.Result?,
        zone: ZoneId = ZoneId.systemDefault(),
        operational: OperationalTarget? = null,
    ): CoachContext {
        val cutoff = report.latest.measuredAtEpochMillis + DAY_MILLIS
        val cutoffEpochDay = Instant.ofEpochMilli(report.latest.measuredAtEpochMillis).atZone(zone).toLocalDate().toEpochDay()
        val body = bodyHistory.filter { it.measuredAtEpochMillis <= cutoff }.sortedBy { it.measuredAtEpochMillis }.takeLast(MAX_HISTORY_ROWS)
        val workouts = workoutHistory.filter { it.startedAtEpochMillis <= cutoff }.sortedBy { it.startedAtEpochMillis }.takeLast(MAX_HISTORY_ROWS)
        val plan = planDays.filter { it.dateEpochDay <= cutoffEpochDay }.sortedBy { it.dateEpochDay }.takeLast(MAX_HISTORY_ROWS)
        val weights = (
            report.history.mapNotNull { row -> row.weightKg?.let { row.measuredAtEpochMillis to it } } +
                body.mapNotNull { row -> row.weightKg?.let { row.measuredAtEpochMillis to it } }
            ).sortedBy { it.first }.takeLast(MAX_HISTORY_ROWS)

        val safety = AiUserContext.safetyFlag(
            ageYears = AiUserContext.ageYears(profile, today),
            bmi = calculation?.bmi,
            goal = ProfileCalculationMapper.goal(profile.goal),
            weightObservations = weights,
            targetKcal = calculation?.targetKcal,
            biologicalSex = profile.biologicalSex,
        )
        val scenarios = BiaScenarioCalculator.scenarios(composition(report.latest), profile.biologicalSex)
        val prompt = buildString {
            appendLine("DATA_ANALISI:${today}")
            appendLine("LEGENDA:$INPUT_LEGEND Date ISO yyyy-MM-dd. Condizioni misura per riga: Y=dichiarata, N=non dichiarata o assente (il dato non distingue).")
            appendLine("PROFILO_UTENTE:")
            appendLine("U=${AiUserContext.profileLine(profile, today)}")
            appendLine("BMI_BMR:bmi=${calculation?.bmi?.let { fmt(it) } ?: "?"},bmrKcal=${calculation?.bmrKcal?.let { whole(it) } ?: "?"}")
            appendLine(habitualTargetLine(calculation))
            appendLine(operationalTargetLine(operational))
            appendLine("REGOLA_TARGET:sono DUE target diversi. Citane uno solo con la sua etichetta (target abituale / target operativo di oggi, giorno di riposo o di allenamento) e mai la parola target da sola. Il tdee e il consumo stimato, NON un target.")
            appendLine("REGOLA_CIFRE_CALORIE:le cifre di kcal e grammi di calorie e macro le scrive l'app. Nei record O e K NON scrivere cifre di kcal o grammi: commento qualitativo.")
            profile.dietaryPreferencesJson?.takeIf { it.isNotBlank() }?.let { appendLine("PREFERENZE_ALIMENTARI:${it.replace('\n', ' ')}") }
            appendLine("OBIETTIVO:${profile.goal ?: "?"}")
            appendLine(safety.line())
            appendLine("BIA_CORRENTE:date=${BiaHistoryImportContract.dayKey(report.latest.measuredAtEpochMillis)},${conditionsForRow(report.latest)},${valuesForPrompt(report.current)}")
            appendLine("STORICO_BIA:${historyForPrompt(report.history)}")
            appendLine("DELTA_PRECEDENTE:${valuesForPrompt(report.previousDelta)}")
            appendLine("CONDIZIONI_MISURAZIONE:${report.conditions}")
            appendLine(comparabilityLine(report))
            appendLine(noiseLegendLine(report))
            appendLine(previousDeltaVsNoiseLine(report))
            appendLine(trendVsNoiseLine(report))
            appendLine("NOTE_UTENTE:${report.latest.notes?.takeIf { it.isNotBlank() }?.replace('\n', ' ') ?: "?"}")
            appendLine("MISURE_CORPOREE:${body.joinToString(";") { "date=${BiaHistoryImportContract.dayKey(it.measuredAtEpochMillis)},weight=${it.weightKg ?: "?"},waist=${it.waistCm ?: "?"},abdomen=${it.abdomenCm ?: "?"},hips=${it.hipsCm ?: "?"},chest=${it.chestCm ?: "?"},arms=${it.armLeftCm ?: "?"}/${it.armRightCm ?: "?"},thighs=${it.thighLeftCm ?: "?"}/${it.thighRightCm ?: "?"}" }.ifBlank { "?" }}")
            appendLine("STORICO_PESO:${weights.joinToString(";") { "${BiaHistoryImportContract.dayKey(it.first)}=${it.second}" }.ifBlank { "?" }}")
            appendLine("ALLENAMENTI:${workouts.joinToString(";") { "date=${BiaHistoryImportContract.dayKey(it.startedAtEpochMillis)},type=${it.type},title=${it.title.replace('|', '/')},duration=${it.durationMinutes ?: "?"},rest=${it.isRestDay}" }.ifBlank { "?" }}")
            appendLine("NOTA_PIANO:il piano alimentare e pianificato, NON e consumo registrato.")
            appendLine("PIANO_PIANIFICATO:${plan.joinToString(";") { "date=${LocalDate.ofEpochDay(it.dateEpochDay)},kcal=${it.kcal ?: "?"},p=${it.proteinG ?: "?"},c=${it.carbsG ?: "?"},f=${it.fatG ?: "?"}" }.ifBlank { "?" }}")
            appendLine(fatLossEstimateLine(report))
            appendLine(scenariosLine(scenarios))
        }
        return CoachContext(prompt, safety, scenarios, caloriesSection(calculation, operational))
    }

    private fun whole(value: Double): String = Math.round(value).toString()

    /**
     * Calories and macros of the "Obiettivo e calorie" section, written by the app from the same figures sent to the
     * model: today's target with the day type, the habitual target and the two TDEE named as estimated consumption
     * (never as targets). Missing values are left out; empty when nothing is available.
     */
    fun caloriesSection(calculation: LocalCalculationEngine.Result?, operational: OperationalTarget?): String {
        fun macros(protein: Double?, carbs: Double?, fat: Double?) = listOfNotNull(
            protein?.let { "proteine ${whole(it)} g" },
            carbs?.let { "carboidrati ${whole(it)} g" },
            fat?.let { "grassi ${whole(it)} g" },
        )
        val lines = mutableListOf<String>()
        operational?.let { today ->
            val dayType = if (today.dayType == "ALLENAMENTO") "giorno di allenamento" else "giorno di riposo"
            val parts = listOfNotNull(today.targetKcal?.let { "target ${whole(it)} kcal" }) + macros(today.proteinG, today.carbsG, today.fatG)
            if (parts.isNotEmpty()) lines += "Oggi ($dayType): " + parts.joinToString(", ")
            today.tdeeKcal?.let { lines += "Consumo operativo stimato oggi (TDEE): ${whole(it)} kcal" }
        }
        calculation?.let { habitual ->
            val parts = listOfNotNull(habitual.targetKcal?.let { "${whole(it)} kcal" }) + macros(habitual.proteinG, habitual.carbsG, habitual.fatG)
            if (parts.isNotEmpty()) lines += "Target abituale: " + parts.joinToString(", ")
            habitual.tdeeKcal?.let { lines += "Consumo abituale stimato (TDEE): ${whole(it)} kcal" }
        }
        return lines.joinToString("\n")
    }

    private fun habitualTargetLine(calculation: LocalCalculationEngine.Result?): String {
        if (calculation == null) return "TARGET_ABITUALE_KCAL:?"
        return "TARGET_ABITUALE_KCAL:tdee=${calculation.tdeeKcal?.let { whole(it) } ?: "?"},target=${calculation.targetKcal?.let { whole(it) } ?: "?"}," +
            "p=${calculation.proteinG?.let { whole(it) } ?: "?"},c=${calculation.carbsG?.let { whole(it) } ?: "?"},f=${calculation.fatG?.let { whole(it) } ?: "?"}" +
            " (target del profilo sul TDEE abituale, NON il target di oggi)"
    }

    private fun operationalTargetLine(target: OperationalTarget?): String {
        if (target == null) return "TARGET_OPERATIVO_OGGI_KCAL:? (non disponibile: non citare un target di oggi)"
        return "TARGET_OPERATIVO_OGGI_KCAL:tipo_giorno=${target.dayType},tdee=${target.tdeeKcal?.let { whole(it) } ?: "?"},target=${target.targetKcal?.let { whole(it) } ?: "?"}," +
            "p=${target.proteinG?.let { whole(it) } ?: "?"},c=${target.carbsG?.let { whole(it) } ?: "?"},f=${target.fatG?.let { whole(it) } ?: "?"}" +
            " (stesso calcolo mostrato nella Home oggi)"
    }

    private fun composition(item: BiaMeasurementEntity) = BiaScenarioCalculator.Composition(
        weightKg = item.weightKg?.toDouble(),
        bodyFatPercent = item.bodyFatPercent?.toDouble(),
        fatMassKg = item.fatMassKg?.toDouble(),
        leanMassKg = item.leanMassKg?.toDouble(),
    )

    private fun scenariosLine(scenarios: List<BiaScenarioCalculator.Scenario>): String {
        if (scenarios.isEmpty()) return "SCENARI_APP:? (dati insufficienti o nessun obiettivo plausibile: non scrivere scenari, nessun record G)"
        return "SCENARI_APP:" + scenarios.joinToString(";") {
            "${it.id} grasso_target=${BiaScenarioCalculator.contextPercent(it.targetBodyFatPercent)}%," +
                "peso_obiettivo=${BiaScenarioCalculator.contextNumber(it.targetWeightKg)}kg," +
                "grasso_da_perdere=${BiaScenarioCalculator.contextNumber(it.fatToLoseKg)}kg"
        } + " (calcolati dall'app mantenendo la massa non grassa: sono ipotesi, non previsioni; commentali con G|id|?|?|?|commento|affidabilita senza scrivere cifre nuove)"
    }

    private fun fatLossEstimateLine(report: Report): String {
        val latest = composition(report.latest)
        val previous = report.previous?.let { prev ->
            fatChangeText("vs_precedente", BiaScenarioCalculator.fatChange(composition(prev), latest, report.degradedComparability))
        }
        val first = report.history.firstOrNull()?.takeIf { it.id != report.latest.id && it.id != report.previous?.id }?.let { start ->
            fatChangeText("vs_inizio_finestra", BiaScenarioCalculator.fatChange(composition(start), latest))
        }
        val parts = listOfNotNull(previous, first)
        return "GRASSO_PERSO_STIMA_APP:" + (parts.joinToString(";").ifBlank { "? (serve una misura precedente con massa grassa o peso e % di grasso)" }) +
            " (stime dell'app soggette al rumore BIA, non fatti misurati: nel record F presentale come stima)"
    }

    private fun fatChangeText(label: String, change: BiaScenarioCalculator.FatChange): String {
        val status = when (change.status) {
            BiaScenarioCalculator.FatStatus.FAT_DOWN -> "STIMA_GRASSO_IN_CALO"
            BiaScenarioCalculator.FatStatus.FAT_UP -> "STIMA_GRASSO_IN_AUMENTO"
            BiaScenarioCalculator.FatStatus.WITHIN_NOISE -> "ENTRO_RUMORE_NESSUNA_STIMA"
            BiaScenarioCalculator.FatStatus.NOT_AVAILABLE -> "NON_DISPONIBILE"
        }
        val fields = listOfNotNull(
            change.fatMassDeltaKg?.let { "massa_grassa_delta=${BiaScenarioCalculator.contextNumber(it)}kg" },
            change.weightDeltaKg?.let { "peso_delta=${BiaScenarioCalculator.contextNumber(it)}kg" },
            change.bodyFatDeltaPoints?.let { "grasso_punti_delta=${BiaScenarioCalculator.contextNumber(it)}" },
            change.estimatedFatLostKg?.let { "grasso_perso_stimato=${BiaScenarioCalculator.contextNumber(it)}kg" },
        )
        return "$label $status ${fields.joinToString(",")}".trim()
    }

    private fun fmt(value: Double): String = String.format(Locale.US, "%.1f", value)

    private fun comparabilityLine(report: Report): String {
        val previous = report.previous
        val quality = report.quality
        if (previous == null || quality == null) return "COMPARABILITA_ULTIMA_PRECEDENTE:? (nessuna misura precedente)"
        val days = (report.latest.measuredAtEpochMillis - previous.measuredAtEpochMillis) / DAY_MILLIS
        return "COMPARABILITA_ULTIMA_PRECEDENTE:score=${quality.score}/100,livello=${quality.level},giorni_tra_misure=$days," +
            "dettagli=${quality.reasons.joinToString(" / ")}"
    }

    private fun noiseLegendLine(report: Report): String =
        "RUMORE_BIA:soglie assunte (ipotesi conservative, non misurate) weightKg=${fmt(BiaNoiseThresholds.WEIGHT_KG)}," +
            "bodyFatPercent=${fmt(BiaNoiseThresholds.BODY_FAT_POINTS)} punti,massa magra/muscolare=${fmt(BiaNoiseThresholds.LEAN_MASS_KG)} kg," +
            "bodyWaterKg=${fmt(BiaNoiseThresholds.BODY_WATER_KG)}; raddoppiate se condizioni diverse o comparabilita bassa " +
            "(applicato al delta precedente=${if (report.degradedComparability) "SI" else "NO"}). Variazione entro soglia = nessuna variazione affidabile."

    private const val RELIABLE = "VARIAZIONE_OLTRE_RUMORE"
    private const val NOT_RELIABLE = "NESSUNA_VARIAZIONE_AFFIDABILE"

    private fun previousDeltaVsNoiseLine(report: Report): String {
        val entries = BiaNoiseThresholds.COVERED_KEYS.mapNotNull { key ->
            val delta = report.previousDelta[key]?.toDouble() ?: return@mapNotNull null
            val threshold = BiaNoiseThresholds.forMetric(key, report.degradedComparability) ?: return@mapNotNull null
            "$key delta=${fmt(delta)} soglia=${fmt(threshold)} esito=${if (BiaNoiseThresholds.isReliableChange(key, delta, report.degradedComparability)) RELIABLE else NOT_RELIABLE}"
        }
        return "DELTA_PRECEDENTE_VS_RUMORE:${entries.joinToString(";").ifBlank { "?" }}"
    }

    /** First-to-last change over the history window, with the number of points and the interval in days. */
    private fun trendVsNoiseLine(report: Report): String {
        val entries = BiaNoiseThresholds.COVERED_KEYS.mapNotNull { key ->
            val points = report.history.mapNotNull { row -> measurementValues(row)[key]?.let { row.measuredAtEpochMillis to it.toDouble() } }
            if (points.isEmpty()) return@mapNotNull null
            if (points.size < 2) return@mapNotNull "$key n=1 (servono almeno 2 punti) esito=$NOT_RELIABLE"
            val delta = points.last().second - points.first().second
            val days = (points.last().first - points.first().first) / DAY_MILLIS
            val threshold = BiaNoiseThresholds.forMetric(key) ?: return@mapNotNull null
            "$key n=${points.size} giorni=$days delta=${fmt(delta)} soglia=${fmt(threshold)} esito=${if (BiaNoiseThresholds.isReliableChange(key, delta)) RELIABLE else NOT_RELIABLE}"
        }
        return "TREND_BIA:${entries.joinToString(";").ifBlank { "?" }}"
    }
}
