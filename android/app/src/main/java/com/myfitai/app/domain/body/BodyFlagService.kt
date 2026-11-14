package com.myfitai.app.domain.body

import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.repository.BiaRepository
import com.myfitai.app.data.repository.BodyMeasurementRepository
import com.myfitai.app.data.repository.UserProfileRepository
import com.myfitai.app.domain.ai.AiUserContext
import com.myfitai.app.domain.calculation.ProfileCalculationMapper
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId

/**
 * Un solo suggerimento mostrato quando non ci sono segnalazioni e mancano dati utili. Sono codici, non testi:
 * le stringhe vivono nella UI. Ordine di dichiarazione = priorita.
 */
enum class BodyFlagHint { NOT_ADULT, ADD_BIRTH_DATE, ADD_HEIGHT, MEASURE_WAIST, ADD_BIA }

/**
 * Risultato per la UI e per i consumatori futuri (BIA Coach): flag gia decisi dal motore, altezza usata (serve a
 * spiegare il rapporto vita/altezza), eventuale suggerimento sui dati mancanti e se le tendenze BIA sono spente
 * perche le misure non hanno le stesse condizioni dichiarate.
 */
data class BodyFlagReport(
    val flags: List<BodyFlag>,
    val heightCm: Double?,
    val hint: BodyFlagHint?,
    val biaTrendNeedsSameConditions: Boolean,
    val thresholdsVersion: String,
    /** Regole non valutate e motivo interno (mai testo per l'utente): serve al BIA Coach per dire "nessun flag: motivo". */
    val skipped: List<BodyFlagSkip> = emptyList(),
)

/**
 * Carica profilo, BIA e misure corporee e chiama [BodyFlagEngine]. Nessuna dipendenza UI e nessuna IA: lo stesso
 * servizio e riusabile dal BIA Coach. [evaluate] e pura (data di oggi e fuso iniettabili) e serve alla UI reattiva
 * che osserva gia i flussi; [load] e il punto d'ingresso "dato un profileId".
 */
class BodyFlagService(
    private val profiles: UserProfileRepository,
    private val bia: BiaRepository,
    private val bodyMeasurements: BodyMeasurementRepository,
) {
    /** @return null se il profilo non esiste. */
    suspend fun load(
        profileId: Long,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        thresholds: BodyFlagThresholds = BodyFlagThresholds.DEFAULT,
    ): BodyFlagReport? {
        val profile = profiles.get(profileId) ?: return null
        val biaRows = bia.all(profileId).first()
        val bodyRows = bodyMeasurements.all(profileId).first()
        return evaluate(profile, biaRows, bodyRows, today, zone, thresholds)
    }

    companion object {
        fun evaluate(
            profile: UserProfileEntity,
            bia: List<BiaMeasurementEntity>,
            measures: List<BodyMeasurementEntity>,
            today: LocalDate,
            zone: ZoneId = ZoneId.systemDefault(),
            thresholds: BodyFlagThresholds = BodyFlagThresholds.DEFAULT,
        ): BodyFlagReport {
            val flagProfile = BodyFlagProfile(
                sex = ProfileCalculationMapper.sex(profile.biologicalSex),
                ageYears = AiUserContext.ageYears(profile, today),
                // Float di Room -> Double senza rumore binario (164f resta 164.0, 172.3f resta 172.3).
                heightCm = profile.heightCm?.toString()?.toDouble(),
                goal = ProfileCalculationMapper.goal(profile.goal),
            )
            val evaluation = BodyFlagEngine.evaluateDetailed(flagProfile, bia, measures, today, thresholds, zone)
            val needsSameConditions = trendNeedsSameConditions(evaluation, bia, today, zone)
            return BodyFlagReport(
                flags = evaluation.flags,
                heightCm = flagProfile.heightCm?.takeIf { it in thresholds.heightMinCm..thresholds.heightMaxCm },
                hint = hint(evaluation, needsSameConditions),
                biaTrendNeedsSameConditions = needsSameConditions,
                thresholdsVersion = evaluation.thresholdsVersion,
                skipped = evaluation.skipped,
            )
        }

        /**
         * Le tendenze BIA mancano perche la serie si e interrotta su una coppia degradata (condizioni non dichiarate o
         * diverse, orari lontani): ultime due BIA non confrontabili E regola saltata per poche misure coerenti.
         */
        private fun trendNeedsSameConditions(
            evaluation: BodyFlagEvaluation,
            bia: List<BiaMeasurementEntity>,
            today: LocalDate,
            zone: ZoneId,
        ): Boolean {
            val interrupted = evaluation.skipped.any { it.reason == BodyFlagSkipReason.BIA_NOT_ENOUGH_COHERENT_MEASUREMENTS }
            if (!interrupted) return false
            val lastTwo = bia
                .filter { !java.time.Instant.ofEpochMilli(it.measuredAtEpochMillis).atZone(zone).toLocalDate().isAfter(today) }
                .sortedWith(compareBy({ it.measuredAtEpochMillis }, { it.id }))
                .takeLast(2)
            if (lastTwo.size < 2) return false
            return BiaMeasurementQualityEngine.isDegraded(BiaMeasurementQualityEngine.evaluate(lastTwo[1], lastTwo[0], zone))
        }

        private fun hint(evaluation: BodyFlagEvaluation, needsSameConditions: Boolean): BodyFlagHint? {
            fun reason(rule: BodyFlagRule) = evaluation.skipped.firstOrNull { it.rule == rule }?.reason
            val whtr = reason(BodyFlagRule.WAIST_HEIGHT_RATIO)
            return when {
                whtr == BodyFlagSkipReason.NOT_ADULT -> BodyFlagHint.NOT_ADULT
                whtr == BodyFlagSkipReason.AGE_MISSING -> BodyFlagHint.ADD_BIRTH_DATE
                whtr == BodyFlagSkipReason.HEIGHT_MISSING -> BodyFlagHint.ADD_HEIGHT
                whtr == BodyFlagSkipReason.WAIST_MISSING || whtr == BodyFlagSkipReason.WAIST_TOO_OLD -> BodyFlagHint.MEASURE_WAIST
                needsSameConditions -> null
                reason(BodyFlagRule.BODY_FAT_TREND) in BIA_TREND_MISSING -> BodyFlagHint.ADD_BIA
                else -> null
            }
        }

        private val BIA_TREND_MISSING = setOf(
            BodyFlagSkipReason.BIA_MISSING,
            BodyFlagSkipReason.BIA_TOO_OLD,
            BodyFlagSkipReason.BIA_NOT_ENOUGH_COHERENT_MEASUREMENTS,
            BodyFlagSkipReason.OBSERVATION_WINDOW_TOO_SHORT,
        )
    }
}
