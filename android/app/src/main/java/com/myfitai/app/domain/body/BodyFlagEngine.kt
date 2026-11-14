package com.myfitai.app.domain.body

import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import com.myfitai.app.domain.calculation.WeightResolver
import com.myfitai.app.domain.calculation.WeightSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Motore deterministico di segnalazioni corporee. Puro (nessun Android, nessuna IA, nessun I/O): stessi input =>
 * stessi flag, ordinati per gravita. Il codice decide, un'IA potra solo spiegare.
 *
 * Principi:
 * - uso SOLO input realmente disponibili: se manca un dato la regola non produce flag e il motivo resta in
 *   [BodyFlagEvaluation.skipped];
 * - le misure BIA rumorose o con condizioni diverse (qualita [BiaMeasurementQualityEngine]) interrompono la serie
 *   usata, e le variazioni si confrontano con [BiaNoiseThresholds];
 * - perimetro: adulti (eta >= [BodyFlagThresholds.adultMinAgeYears]); minorenni o eta ignota = nessuna regola;
 * - linguaggio: segnale da approfondire con un medico, mai diagnosi; nessun testo di UI qui.
 */
object BodyFlagEngine {
    private const val DAY_MS = 86_400_000L

    fun evaluate(
        profile: BodyFlagProfile,
        bia: List<BiaMeasurementEntity>,
        measures: List<BodyMeasurementEntity>,
        today: LocalDate,
        thresholds: BodyFlagThresholds = BodyFlagThresholds.DEFAULT,
        biaZone: ZoneId = ZoneId.systemDefault(),
    ): List<BodyFlag> = evaluateDetailed(profile, bia, measures, today, thresholds, biaZone).flags

    fun evaluateDetailed(
        profile: BodyFlagProfile,
        bia: List<BiaMeasurementEntity>,
        measures: List<BodyMeasurementEntity>,
        today: LocalDate,
        thresholds: BodyFlagThresholds = BodyFlagThresholds.DEFAULT,
        biaZone: ZoneId = ZoneId.systemDefault(),
    ): BodyFlagEvaluation = Run(profile, bia, measures, today, thresholds, biaZone).execute()

    private data class Obs(val epoch: Long, val kg: Double, val source: WeightSource, val date: LocalDate)

    private class Chain(val points: List<BiaMeasurementEntity>, val worstLevel: BiaMeasurementQualityEngine.Level)

    private class Run(
        private val profile: BodyFlagProfile,
        bia: List<BiaMeasurementEntity>,
        measures: List<BodyMeasurementEntity>,
        private val today: LocalDate,
        private val t: BodyFlagThresholds,
        private val biaZone: ZoneId,
    ) {
        private val flags = mutableListOf<BodyFlag>()
        private val skipped = mutableListOf<BodyFlagSkip>()

        private val biaSorted = bia.filter { !biaDate(it).isAfter(today) }
            .sortedWith(compareBy({ it.measuredAtEpochMillis }, { it.id }))
        private val bodySorted = measures.filter { !bodyDate(it).isAfter(today) }
            .sortedWith(compareBy({ it.measuredAtEpochMillis }, { it.id }))

        private val heightCm: Double? = profile.heightCm
            ?.takeIf { it.isFinite() && it in t.heightMinCm..t.heightMaxCm }

        private val weightObservations: List<Obs> by lazy {
            WeightResolver.observations(biaSorted, bodySorted)
                .filter { it.valueKg in BodyValueRanges.WEIGHT_KG }
                .map {
                    val date = if (it.source == WeightSource.BIA) {
                        Instant.ofEpochMilli(it.measuredAtEpochMillis).atZone(biaZone).toLocalDate()
                    } else {
                        Instant.ofEpochMilli(it.measuredAtEpochMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    Obs(it.measuredAtEpochMillis, it.valueKg.exact(), it.source, date)
                }
        }

        /** BMI dell'ultimo peso misurato (non del profilo) abbastanza recente; usato anche come condizione di altre regole. */
        private val bmiValue: Double? by lazy {
            val h = heightCm ?: return@lazy null
            val w = weightObservations.lastOrNull() ?: return@lazy null
            if (daysBetween(w.date, today) > t.weightMaxAgeDays) return@lazy null
            round1(w.kg / ((h / 100.0) * (h / 100.0)))
        }

        fun execute(): BodyFlagEvaluation {
            val age = profile.ageYears
            val gate = when {
                age == null -> BodyFlagSkipReason.AGE_MISSING
                age < t.adultMinAgeYears -> BodyFlagSkipReason.NOT_ADULT
                else -> null
            }
            if (gate != null) {
                BodyFlagRule.entries.forEach { skipped += BodyFlagSkip(it, gate) }
            } else {
                waistHeightRatio()
                waistCircumference()
                bmi()
                visceralFat()
                skeletalMuscle(age!!)
                rapidWeightLoss()
                leanMassLoss()
                bodyFatTrend()
            }
            val ordered = flags.sortedWith(
                compareByDescending<BodyFlag> { it.severity.rank }
                    .thenByDescending { it.code.priority }
                    .thenByDescending { it.reliability.rank }
                    .thenBy { it.code.ordinal },
            )
            return BodyFlagEvaluation(ordered, skipped.toList(), t.version)
        }

        // ---- Circonferenza vita e WHtR ----

        private class Waist(val cm: Double, val date: LocalDate, val reliability: BodyFlagReliability)

        private fun waistOrSkip(rule: BodyFlagRule): Waist? {
            val field = BodyMeasurementRanges.Field.WAIST
            val row = bodySorted.lastOrNull { r -> r.waistCm?.let { it.isFinite() && it in field.min..field.max } == true }
            if (row == null) return skip(rule, BodyFlagSkipReason.WAIST_MISSING)
            val date = bodyDate(row)
            val age = daysBetween(date, today)
            if (age > t.waistMaxAgeDays) return skip(rule, BodyFlagSkipReason.WAIST_TOO_OLD)
            val reliability = if (age <= t.waistRecentDays) BodyFlagReliability.ALTA else BodyFlagReliability.MEDIA
            return Waist(row.waistCm!!.exact(), date, reliability)
        }

        private fun skipOnly(rule: BodyFlagRule, reason: BodyFlagSkipReason) {
            skipped += BodyFlagSkip(rule, reason)
        }

        private fun <T> skip(rule: BodyFlagRule, reason: BodyFlagSkipReason): T? {
            skipped += BodyFlagSkip(rule, reason)
            return null
        }

        private fun waistHeightRatio() {
            val rule = BodyFlagRule.WAIST_HEIGHT_RATIO
            val height = heightCm ?: return skipOnly(rule, BodyFlagSkipReason.HEIGHT_MISSING)
            val waist = waistOrSkip(rule) ?: return
            val whtr = waist.cm / height
            val code = when {
                whtr >= t.whtrSecondLevel -> BodyFlagCode.WHTR_HIGH
                whtr >= t.whtrFirstLevel -> BodyFlagCode.WHTR_ABOVE_HALF
                else -> return
            }
            flags += BodyFlag(
                code, BodyFlagSeverity.ATTENZIONE, waist.reliability,
                listOf(ev("whtr", whtr, "", waist.date), ev("waistCm", waist.cm, "cm", waist.date)),
                listOf(BodyFlagThresholds.SRC_NICE_NG246_2025),
            )
        }

        private fun waistCircumference() {
            val rule = BodyFlagRule.WAIST_CIRCUMFERENCE
            val (first, second) = when (profile.sex) {
                LocalCalculationEngine.BiologicalSex.MALE -> t.waistMaleFirstCm to t.waistMaleSecondCm
                LocalCalculationEngine.BiologicalSex.FEMALE -> t.waistFemaleFirstCm to t.waistFemaleSecondCm
                null -> return skipOnly(rule, BodyFlagSkipReason.SEX_MISSING)
            }
            val waist = waistOrSkip(rule) ?: return
            val code = when {
                waist.cm >= second -> BodyFlagCode.WAIST_SECOND_LEVEL
                waist.cm >= first -> BodyFlagCode.WAIST_FIRST_LEVEL
                else -> return
            }
            flags += BodyFlag(
                code, BodyFlagSeverity.ATTENZIONE, waist.reliability,
                listOf(ev("waistCm", waist.cm, "cm", waist.date)),
                listOf(BodyFlagThresholds.SRC_WHO_WAIST_2008),
            )
        }

        // ---- BMI (solo informativo) ----

        private fun bmi() {
            val rule = BodyFlagRule.BMI
            if (heightCm == null) return skipOnly(rule, BodyFlagSkipReason.HEIGHT_MISSING)
            val weight = weightObservations.lastOrNull()
                ?: return skipOnly(rule, BodyFlagSkipReason.WEIGHT_MISSING)
            val weightAge = daysBetween(weight.date, today)
            if (weightAge > t.weightMaxAgeDays) return skipOnly(rule, BodyFlagSkipReason.WEIGHT_TOO_OLD)
            val value = bmiValue ?: return
            val code = when {
                value < t.bmiUnder -> BodyFlagCode.BMI_BAND_UNDER
                value >= t.bmiHigh -> BodyFlagCode.BMI_BAND_HIGH
                value >= t.bmiOver -> BodyFlagCode.BMI_BAND_OVER
                else -> return
            }
            val reliability = if (weightAge <= t.weightRecentDays) BodyFlagReliability.ALTA else BodyFlagReliability.MEDIA
            flags += BodyFlag(
                code, BodyFlagSeverity.INFO, reliability,
                listOf(ev("bmi", value, "", weight.date), ev("weightKg", weight.kg, "kg", weight.date)),
                listOf(BodyFlagThresholds.SRC_WHO_BMI_2000),
            )
        }

        // ---- Catena BIA coerente ----

        /**
         * Serie piu recente di misure BIA dove ogni coppia consecutiva ha qualita non degradata
         * ([BiaMeasurementQualityEngine.isDegraded]) dentro la finestra di trend. Ritorna null (con motivo) se
         * i dati non bastano per una regola di trend.
         */
        private fun chainOrSkip(
            rule: BodyFlagRule,
            metric: (BiaMeasurementEntity) -> Float?,
            range: BodyValueRanges.Range,
        ): Chain? {
            val usable = biaSorted.filter { e -> metric(e)?.let { it in range } == true }
            val latest = usable.lastOrNull() ?: return skip(rule, BodyFlagSkipReason.BIA_MISSING)
            if (daysBetween(biaDate(latest), today) > t.biaMaxAgeDays) return skip(rule, BodyFlagSkipReason.BIA_TOO_OLD)
            val windowStart = today.minusDays(t.trendWindowDays)
            val reversed = mutableListOf(latest)
            var worst = BiaMeasurementQualityEngine.Level.HIGH
            var index = usable.lastIndex - 1
            while (index >= 0) {
                val previous = usable[index]
                if (biaDate(previous).isBefore(windowStart)) break
                val quality = BiaMeasurementQualityEngine.evaluate(usable[index + 1], previous, biaZone)
                if (BiaMeasurementQualityEngine.isDegraded(quality)) break
                if (quality.level.ordinal > worst.ordinal) worst = quality.level
                reversed += previous
                index--
            }
            if (reversed.size < t.trendMinMeasurements) {
                return skip(rule, BodyFlagSkipReason.BIA_NOT_ENOUGH_COHERENT_MEASUREMENTS)
            }
            val points = reversed.asReversed().toList()
            if (daysBetween(biaDate(points.first()), biaDate(points.last())) < t.trendMinSpanDays) {
                return skip(rule, BodyFlagSkipReason.OBSERVATION_WINDOW_TOO_SHORT)
            }
            return Chain(points, worst)
        }

        // ---- Grasso viscerale ----

        private fun visceralFat() {
            val rule = BodyFlagRule.VISCERAL_FAT
            val height = heightCm ?: return skipOnly(rule, BodyFlagSkipReason.HEIGHT_MISSING)
            val waist = waistOrSkip(rule) ?: return
            val whtr = waist.cm / height
            val chain = chainOrSkip(rule, { it.visceralFatLevel }, BodyValueRanges.VISCERAL_FAT_LEVEL) ?: return
            if (whtr < t.whtrFirstLevel) return
            val values = chain.points.map { it.visceralFatLevel!!.exact() }
            val delta = round2(values.last() - values.first())
            val rising = delta >= t.visceralMinRiseLevels && values.last() >= values.max()
            if (!rising) return
            val first = biaDate(chain.points.first())
            val last = biaDate(chain.points.last())
            flags += BodyFlag(
                BodyFlagCode.VISCERAL_FAT_RISING, BodyFlagSeverity.INFO, BodyFlagReliability.BASSA,
                listOf(
                    ev("visceralFatLevelFirst", values.first(), "", first),
                    ev("visceralFatLevelLast", values.last(), "", last),
                    ev("whtr", whtr, "", waist.date),
                ),
                listOf(BodyFlagThresholds.SRC_FITDAYS_VISCERAL, BodyFlagThresholds.SRC_NICE_NG246_2025),
            )
        }

        // ---- Muscolo scheletrico ----

        private fun skeletalMuscle(age: Int) {
            val rule = BodyFlagRule.SKELETAL_MUSCLE
            val lowBmi = bmiValue?.let { it < t.bmiUnder } == true
            if (age < t.muscleSecondIndicatorAgeYears && !lowBmi) {
                skipped += BodyFlagSkip(rule, BodyFlagSkipReason.SECOND_INDICATOR_MISSING)
                return
            }
            val chain = chainOrSkip(rule, { it.skeletalMuscleKg }, BodyValueRanges.SKELETAL_MUSCLE_KG) ?: return
            val values = chain.points.map { it.skeletalMuscleKg!!.exact() }
            val delta = round2(values.last() - values.first())
            val noise = BiaNoiseThresholds.forMetric("skeletalMuscleKg") ?: return
            val declining = delta <= -t.muscleDeclineMinKg &&
                BiaNoiseThresholds.isReliableChange("skeletalMuscleKg", delta) &&
                values.last() <= values.dropLast(1).min() + noise
            if (!declining) return
            val evidence = mutableListOf(
                ev("skeletalMuscleKgFirst", values.first(), "kg", biaDate(chain.points.first())),
                ev("skeletalMuscleKgLast", values.last(), "kg", biaDate(chain.points.last())),
                ev("ageYears", age.toDouble(), "anni", today),
            )
            bmiValue?.let { evidence += ev("bmi", it, "", today) }
            flags += BodyFlag(
                BodyFlagCode.SKELETAL_MUSCLE_DECLINE, BodyFlagSeverity.ATTENZIONE, BodyFlagReliability.BASSA,
                evidence,
                listOf(BodyFlagThresholds.SRC_EWGSOP2_2019, BodyFlagThresholds.SRC_INTERNAL_BIA_NOISE),
            )
        }

        // ---- Ritmo di calo del peso ----

        private fun rapidWeightLoss() {
            val rule = BodyFlagRule.RAPID_WEIGHT_LOSS
            val obs = weightObservations
            if (obs.size < 3) return skipOnly(rule, BodyFlagSkipReason.WEIGHT_MISSING)
            val recentFrom = today.minusDays(t.rapidLossRecencyDays)
            var anyTriple = false
            for (c in obs.asReversed()) {
                if (c.date.isBefore(recentFrom)) break
                val a = nearestWeekBefore(obs, c) ?: continue
                val b = nearestWeekBefore(obs, a) ?: continue
                anyTriple = true
                val rateCA = weeklyRate(a, c)
                val rateAB = weeklyRate(b, a)
                if (rateCA > t.rapidLossKgPerWeek && rateAB > t.rapidLossKgPerWeek) {
                    val reliability = if (a.source == b.source && b.source == c.source) BodyFlagReliability.ALTA else BodyFlagReliability.MEDIA
                    flags += BodyFlag(
                        BodyFlagCode.RAPID_WEIGHT_LOSS, BodyFlagSeverity.ATTENZIONE, reliability,
                        listOf(
                            ev("weightKgStart", b.kg, "kg", b.date),
                            ev("weightKgMiddle", a.kg, "kg", a.date),
                            ev("weightKgLatest", c.kg, "kg", c.date),
                            ev("weeklyRateFirstKg", rateAB, "kg/settimana", a.date),
                            ev("weeklyRateSecondKg", rateCA, "kg/settimana", c.date),
                        ),
                        listOf(BodyFlagThresholds.SRC_INTERNAL_RAPID_LOSS),
                    )
                    return
                }
            }
            if (!anyTriple) skipped += BodyFlagSkip(rule, BodyFlagSkipReason.OBSERVATION_WINDOW_TOO_SHORT)
        }

        private fun nearestWeekBefore(obs: List<Obs>, of: Obs): Obs? {
            val target = of.epoch - 7 * DAY_MS
            val tolerance = (t.weekToleranceDays * DAY_MS).toLong()
            return obs.filter { it.epoch < of.epoch && abs(it.epoch - target) <= tolerance }
                .minByOrNull { abs(it.epoch - target) }
        }

        /** Calo settimanale in kg (positivo = peso sceso), normalizzato sui giorni reali tra le due letture. */
        private fun weeklyRate(from: Obs, to: Obs): Double {
            val days = (to.epoch - from.epoch).toDouble() / DAY_MS
            return round2((from.kg - to.kg) / days * 7.0)
        }

        // ---- Calo di massa magra ----

        private fun leanMassLoss() {
            val rule = BodyFlagRule.LEAN_MASS_LOSS
            val chain = chainOrSkip(
                rule,
                { e -> e.leanMassKg?.takeIf { e.weightKg?.let { w -> w in BodyValueRanges.WEIGHT_KG } == true } },
                BodyValueRanges.LEAN_MASS_KG,
            ) ?: return
            val lean = chain.points.map { it.leanMassKg!!.exact() }
            val weight = chain.points.map { it.weightKg!!.exact() }
            val leanDelta = round2(lean.last() - lean.first())
            if (leanDelta > -t.leanLossMinKg || !BiaNoiseThresholds.isReliableChange("leanMassKg", leanDelta)) return
            val weightLoss = round2(weight.first() - weight.last())
            val noise = BiaNoiseThresholds.WEIGHT_KG
            if (weightLoss <= noise) {
                skipped += BodyFlagSkip(rule, BodyFlagSkipReason.WEIGHT_LOSS_BELOW_NOISE)
                return
            }
            val share = -leanDelta / weightLoss
            if (share <= t.leanLossShareOfWeightLoss) return
            val firstDate = biaDate(chain.points.first())
            val lastDate = biaDate(chain.points.last())
            flags += BodyFlag(
                BodyFlagCode.LEAN_MASS_LOSS_SHARE, BodyFlagSeverity.ATTENZIONE, BodyFlagReliability.BASSA,
                listOf(
                    ev("leanMassKgFirst", lean.first(), "kg", firstDate),
                    ev("leanMassKgLast", lean.last(), "kg", lastDate),
                    ev("weightKgFirst", weight.first(), "kg", firstDate),
                    ev("weightKgLast", weight.last(), "kg", lastDate),
                    ev("leanShareOfWeightLossPercent", share * 100.0, "%", lastDate),
                ),
                listOf(BodyFlagThresholds.SRC_INTERNAL_LEAN_LOSS, BodyFlagThresholds.SRC_INTERNAL_BIA_NOISE),
            )
        }

        // ---- % massa grassa: solo trend informativo ----

        private fun bodyFatTrend() {
            val rule = BodyFlagRule.BODY_FAT_TREND
            val chain = chainOrSkip(rule, { it.bodyFatPercent }, BodyValueRanges.BODY_FAT_PERCENT) ?: return
            val values = chain.points.map { it.bodyFatPercent!!.exact() }
            val delta = round2(values.last() - values.first())
            if (!BiaNoiseThresholds.isReliableChange("bodyFatPercent", delta)) return
            val code = if (delta > 0) BodyFlagCode.BODY_FAT_TREND_UP else BodyFlagCode.BODY_FAT_TREND_DOWN
            val reliability = if (chain.worstLevel == BiaMeasurementQualityEngine.Level.HIGH) BodyFlagReliability.MEDIA else BodyFlagReliability.BASSA
            flags += BodyFlag(
                code, BodyFlagSeverity.INFO, reliability,
                listOf(
                    ev("bodyFatPercentFirst", values.first(), "%", biaDate(chain.points.first())),
                    ev("bodyFatPercentLast", values.last(), "%", biaDate(chain.points.last())),
                ),
                listOf(BodyFlagThresholds.SRC_INTERNAL_BIA_NOISE),
            )
        }

        // ---- Utilita ----

        private fun biaDate(e: BiaMeasurementEntity): LocalDate =
            Instant.ofEpochMilli(e.measuredAtEpochMillis).atZone(biaZone).toLocalDate()

        /** Le misure corporee salvano la data come giorno UTC (come [BodyWeightHistory]). */
        private fun bodyDate(e: BodyMeasurementEntity): LocalDate =
            Instant.ofEpochMilli(e.measuredAtEpochMillis).atZone(ZoneOffset.UTC).toLocalDate()

        private fun daysBetween(from: LocalDate, to: LocalDate): Long = ChronoUnit.DAYS.between(from, to)

        private fun ev(key: String, value: Double, unit: String, date: LocalDate) =
            BodyFlagEvidence(key, Math.round(value * 1000.0) / 1000.0, unit, date)
    }
}

/** Float di Room -> Double senza il rumore binario (88,2f resta 88,2). */
private fun Float.exact(): Double = toString().toDouble()

private fun round1(value: Double): Double = Math.round(value * 10.0) / 10.0

private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0
