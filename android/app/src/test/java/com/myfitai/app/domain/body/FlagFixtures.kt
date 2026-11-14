package com.myfitai.app.domain.body

import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Costruttori di dati sintetici per i test del motore di segnalazioni. Date relative a [TODAY], tutto in UTC. */
internal object FlagFixtures {
    val TODAY: LocalDate = LocalDate.of(2026, 11, 14)
    val ZONE: ZoneId = ZoneOffset.UTC

    val MALE = LocalCalculationEngine.BiologicalSex.MALE
    val FEMALE = LocalCalculationEngine.BiologicalSex.FEMALE

    fun profile(
        sex: LocalCalculationEngine.BiologicalSex? = MALE,
        age: Int? = 40,
        height: Double? = 180.0,
        goal: LocalCalculationEngine.Goal? = LocalCalculationEngine.Goal.MAINTENANCE,
    ) = BodyFlagProfile(sex, age, height, goal)

    private fun epoch(daysAgo: Int, hour: Int, minute: Int = 30): Long =
        TODAY.minusDays(daysAgo.toLong()).atTime(hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    /** BIA con tutte le condizioni dichiarate (qualita ALTA tra misure alla stessa ora) salvo [conditions] = false. */
    fun bia(
        daysAgo: Int,
        weight: Float? = null,
        fat: Float? = null,
        visceral: Float? = null,
        skeletal: Float? = null,
        lean: Float? = null,
        conditions: Boolean = true,
        hour: Int = 7,
    ) = BiaMeasurementEntity(
        id = daysAgo.toLong() + 1,
        profileId = 1,
        measuredAtEpochMillis = epoch(daysAgo, hour),
        weightKg = weight,
        bodyFatPercent = fat,
        visceralFatLevel = visceral,
        muscleMassKg = null,
        skeletalMuscleKg = skeletal,
        bodyWaterPercent = null,
        bmrKcal = null,
        fasting = conditions,
        justWokeUp = conditions,
        afterBathroom = conditions,
        noRecentWorkout = conditions,
        leanMassKg = lean,
    )

    fun body(daysAgo: Int, waist: Float? = null, weight: Float? = null) = BodyMeasurementEntity(
        id = daysAgo.toLong() + 1,
        profileId = 1,
        measuredAtEpochMillis = TODAY.minusDays(daysAgo.toLong()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        chestCm = null,
        waistCm = waist,
        abdomenCm = null,
        shouldersCm = null,
        glutesCm = null,
        armLeftCm = null,
        armRightCm = null,
        thighLeftCm = null,
        thighRightCm = null,
        calfLeftCm = null,
        calfRightCm = null,
        weightKg = weight,
    )

    fun eval(
        profile: BodyFlagProfile,
        bia: List<BiaMeasurementEntity> = emptyList(),
        body: List<BodyMeasurementEntity> = emptyList(),
        thresholds: BodyFlagThresholds = BodyFlagThresholds.DEFAULT,
    ): BodyFlagEvaluation = BodyFlagEngine.evaluateDetailed(profile, bia, body, TODAY, thresholds, ZONE)

    fun codes(evaluation: BodyFlagEvaluation): List<BodyFlagCode> = evaluation.flags.map { it.code }

    fun skipReason(evaluation: BodyFlagEvaluation, rule: BodyFlagRule): BodyFlagSkipReason? =
        evaluation.skipped.firstOrNull { it.rule == rule }?.reason
}
