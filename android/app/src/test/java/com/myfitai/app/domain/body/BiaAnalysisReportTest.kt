package com.myfitai.app.domain.body

import androidx.work.Data
import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.domain.ai.AiUserContext
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiaAnalysisReportTest {
    private val day = 86_400_000L

    private fun reading(
        id: Long,
        dayIndex: Int,
        weight: Float? = 90f,
        fat: Float? = 20f,
        muscle: Float? = 66f,
        fasting: Boolean = true,
    ) = BiaMeasurementEntity(
        id = id,
        profileId = 1L,
        measuredAtEpochMillis = 1_700_000_000_000L + dayIndex * day,
        weightKg = weight,
        bodyFatPercent = fat,
        visceralFatLevel = null,
        muscleMassKg = muscle,
        skeletalMuscleKg = null,
        bodyWaterPercent = null,
        bmrKcal = null,
        fasting = fasting,
        justWokeUp = false,
        afterBathroom = true,
        noRecentWorkout = false,
    )

    @Test
    fun emptyOrValuelessHistoryHasNothingToAnalyse() {
        assertNull(BiaAnalysisReport.build(emptyList()))
        assertNull(BiaAnalysisReport.build(listOf(reading(1, 0, weight = null, fat = null, muscle = null))))
    }

    @Test
    fun defaultsToNewestReadingAndComputesDeltaFromMostRecentEarlierValue() {
        val report = assertNotNull(
            BiaAnalysisReport.build(
                listOf(
                    reading(3, 20, weight = 88f, fat = null),
                    reading(1, 0, weight = 90f, fat = 21f),
                    reading(2, 10, weight = 89f, fat = null),
                ),
            ),
        )

        assertEquals(3L, report.latest.id)
        assertEquals(3, report.measurementCount)
        assertEquals(listOf(1L, 2L, 3L), report.history.map { it.id })
        assertEquals(-1f, report.previousDelta.getValue("weightKg"), 0.001f)
        // Body fat is missing in the newest reading: no delta is invented.
        assertNull(report.previousDelta["bodyFatPercent"])
        assertEquals("Condizioni: a digiuno · dopo bagno", report.conditions)
    }

    @Test
    fun selectedOlderReadingNeverSeesLaterOnes() {
        val all = (1..5).map { reading(it.toLong(), it * 10, weight = 90f - it) }

        val report = assertNotNull(BiaAnalysisReport.build(all, selectedId = 3L))

        assertEquals(3L, report.latest.id)
        assertEquals(listOf(1L, 2L, 3L), report.history.map { it.id })
        assertEquals(5, report.measurementCount)
    }

    @Test
    fun latestReadingDeltaIsComputedAgainstPreviousWhileOlderIdsAreNotAnAnalysisEntry() {
        val all = listOf(
            reading(1, 0, weight = 90f),
            reading(2, 10, weight = 89f),
            reading(3, 20, weight = 88f),
        )

        val latest = BiaAnalysisReport.build(all)!!

        assertEquals(3L, latest.latest.id)
        assertEquals(-1f, latest.previousDelta.getValue("weightKg"), 0.001f)
        assertEquals(listOf(1L, 2L, 3L), latest.history.map { it.id })
        // The former per-history-row action is no longer an analysis target selector.
        assertEquals(3L, BiaAnalysisReport.build(all)?.latest?.id)
    }

    @Test
    fun latestReadingWithoutEarlierValuesHasNoFabricatedComparison() {
        val report = BiaAnalysisReport.build(listOf(reading(1, 0), reading(2, 10, weight = null, fat = null, muscle = null)))

        assertNull("a value-less reading must not be analysed or presented as an AI comparison", report)
    }

    @Test
    fun historySentToTheModelIsCappedToTheNewestRows() {
        val all = (1..60).map { reading(it.toLong(), it) }

        val report = assertNotNull(BiaAnalysisReport.build(all))

        assertEquals(BiaAnalysisReport.MAX_HISTORY_ROWS, report.history.size)
        assertEquals(60L, report.history.last().id)
        assertEquals(25L, report.history.first().id)
        assertEquals(60, report.measurementCount)
    }

    @Test
    fun jobParametersDoNotGrowWithTheHistory() {
        val tiny = BiaAnalysisAiJobHandler.params(7L)
        // WorkManager rejects Data above 10 KB: an identifier must stay far below it regardless of history size.
        val withScheduler = Data.Builder()
            .putAll(tiny)
            .putString("ai_job_type", "BIA_ANALYSIS")
            .putLong("profile_id", 1L)
            .putString("job_key", "1790000000000")
            .build()

        assertEquals(7L, withScheduler.getLong(BiaAnalysisAiJobHandler.KEY_MEASUREMENT_ID, -1L))
        assertTrue(Data.Builder().putLong("a", 1L).build().keyValueMap.size == 1)
    }

    @Test
    fun promptHistoryOmitsMissingValuesInsteadOfSendingZero() {
        val text = BiaAnalysisReport.historyForPrompt(listOf(reading(1, 0, weight = 90f, fat = null, muscle = null)))

        assertTrue(text.contains("weightKg=90.0"))
        assertTrue(!text.contains("bodyFatPercent"))
        assertTrue(!text.contains("muscleMassKg"))
    }

    // ---- T2: contesto del BIA Coach, rumore BIA, SAFETY_FLAG -------------------------------------------

    private val today = LocalDate.of(2026, 11, 12)

    private fun makeProfile(
        birth: LocalDate = LocalDate.of(1964, 3, 10),
        sex: String? = "Femmina",
        goal: String? = "Ricomposizione",
    ) = UserProfileEntity(
        id = 1L,
        name = "Test",
        birthDateEpochDay = birth.toEpochDay(),
        heightCm = 165f,
        currentWeightKg = 68f,
        goal = goal,
        activityLevel = "Sedentario",
        wakeTimeMinutes = 420,
        sleepTimeMinutes = 1380,
        dietaryPreferencesJson = null,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        biologicalSex = sex,
    )

    private fun body(dayIndex: Int, weight: Float? = 68f) = BodyMeasurementEntity(
        profileId = 1L,
        measuredAtEpochMillis = 1_700_000_000_000L + dayIndex * day,
        chestCm = null, waistCm = 80f, abdomenCm = null, shouldersCm = null, glutesCm = null,
        armLeftCm = null, armRightCm = null, thighLeftCm = null, thighRightCm = null, calfLeftCm = null, calfRightCm = null,
        weightKg = weight,
    )

    private fun context(
        all: List<BiaMeasurementEntity>,
        profile: UserProfileEntity = makeProfile(),
        bodyHistory: List<BodyMeasurementEntity> = emptyList(),
        plan: List<BiaAnalysisReport.PlanDay> = emptyList(),
        calculation: LocalCalculationEngine.Result? = null,
    ) = BiaAnalysisReport.coachContext(
        profile = profile,
        today = today,
        report = BiaAnalysisReport.build(all)!!,
        bodyHistory = bodyHistory,
        planDays = plan,
        calculation = calculation,
    )

    private fun line(prompt: String, tag: String) = prompt.lines().first { it.startsWith("$tag:") }

    @Test
    fun coachContextUsesAgeInYearsAndIsoDatesWithoutEpoch() {
        val prompt = context(
            all = listOf(reading(1, 0), reading(2, 10)),
            bodyHistory = listOf(body(5)),
            plan = listOf(BiaAnalysisReport.PlanDay(LocalDate.of(2023, 11, 14).toEpochDay(), 1800, 120, 180, 55)),
        ).prompt

        // Female, born 1964-03-10, on 2026-11-12: 62 years, as in AiUserContext.
        assertTrue(prompt, prompt.contains("U=Femmina|62|165.0|68.0|Ricomposizione|Sedentario|420|1380"))
        assertTrue(!prompt.contains("birthDate"))
        assertTrue(prompt.contains("DATA_ANALISI:2026-11-12"))
        // Workouts are no longer part of the coach input; the measurement condition stays.
        assertTrue(prompt, !prompt.contains("ALLENAMENTI"))
        assertTrue(prompt, prompt.contains("noWorkout="))
        assertTrue(prompt.contains("date=2023-11-14,kcal=1800"))
        // No epoch millis or epoch days anywhere (8+ consecutive digits, or 5-digit epoch days after "date=").
        assertTrue(prompt, !Regex("\\d{8,}").containsMatchIn(prompt))
        assertTrue(prompt, !Regex("date=\\d{5}").containsMatchIn(prompt))
        Regex("date=([^,;]+)").findAll(prompt).forEach { match ->
            assertTrue(match.groupValues[1], Regex("\\d{4}-\\d{2}-\\d{2}").matches(match.groupValues[1]))
        }
    }

    @Test
    fun coachContextDropsAlwaysUnknownSectionsAndStatesThePlanIsNotConsumption() {
        val prompt = context(listOf(reading(1, 0), reading(2, 10))).prompt

        listOf("PRESTAZIONI_E_FORZA", "ATTIVITA_E_PASSI", "INTEGRAZIONE", "SONNO_E_RECUPERO").forEach {
            assertTrue(it, !prompt.contains(it))
        }
        assertTrue(prompt.contains("NOTA_PIANO:il piano alimentare e pianificato, NON e consumo registrato."))
        assertTrue(prompt.contains("PIANO_PIANIFICATO:"))
    }

    @Test
    fun coachContextCapsEveryWindowAndNeverLooksPastTheAnalysedReading() {
        val bia = (1..40).map { reading(it.toLong(), it) }
        val plan = (0 until 60).map { BiaAnalysisReport.PlanDay(LocalDate.of(2023, 10, 1).toEpochDay() + it, 1800, 120, 180, 55) }

        val prompt = context(
            all = bia,
            bodyHistory = (1..60).map { body(it) } + body(200),
            plan = plan,
        ).prompt

        assertEquals(BiaAnalysisReport.MAX_HISTORY_ROWS, line(prompt, "MISURE_CORPOREE").removePrefix("MISURE_CORPOREE:").split(";").size)
        assertEquals(BiaAnalysisReport.MAX_HISTORY_ROWS, line(prompt, "PIANO_PIANIFICATO").removePrefix("PIANO_PIANIFICATO:").split(";").size)
        assertEquals(BiaAnalysisReport.MAX_HISTORY_ROWS, line(prompt, "STORICO_PESO").removePrefix("STORICO_PESO:").split(";").size)
        // The reading at day 200 is after the latest BIA (day 40): it is not sent.
        val lastDay = BiaHistoryImportContract.dayKey(1_700_000_000_000L + 200 * day)
        assertTrue(!prompt.contains(lastDay))
    }

    @Test
    fun historyRowsCarryTheirOwnMeasurementConditionsAndComparabilityIsReported() {
        val prompt = context(
            listOf(reading(1, 0, fasting = false), reading(2, 10, fasting = true)),
        ).prompt

        val history = line(prompt, "STORICO_BIA")
        assertTrue(history, history.contains("fasting=N,wokeUp=N,afterBathroom=Y,noWorkout=N"))
        assertTrue(history, history.contains("fasting=Y,wokeUp=N,afterBathroom=Y,noWorkout=N"))
        val comparability = line(prompt, "COMPARABILITA_ULTIMA_PRECEDENTE")
        assertTrue(comparability, comparability.contains("score=") && comparability.contains("Condizione diversa: digiuno"))
        assertTrue(BiaAnalysisReport.build(listOf(reading(1, 0, fasting = false), reading(2, 10)))!!.degradedComparability)
    }

    @Test
    fun deltasBelowTheNoiseThresholdAreNotReliableAndCarryPointsAndInterval() {
        // Same conditions, weight -0.4 kg between the last two, -0.5 kg over 20 days.
        val prompt = context(
            listOf(
                reading(1, 0, weight = 90f), reading(2, 10, weight = 89.9f), reading(3, 20, weight = 89.5f),
            ),
        ).prompt

        val delta = line(prompt, "DELTA_PRECEDENTE_VS_RUMORE")
        assertTrue(delta, delta.contains("weightKg delta=-0.4 soglia=0.8 esito=NESSUNA_VARIAZIONE_AFFIDABILE"))
        val trend = line(prompt, "TREND_BIA")
        assertTrue(trend, trend.contains("weightKg n=3 giorni=20 delta=-0.5 soglia=0.8 esito=NESSUNA_VARIAZIONE_AFFIDABILE"))
        assertTrue(trend, trend.contains("bodyFatPercent n=3 giorni=20 delta=0.0 soglia=1.0"))
    }

    @Test
    fun deltasAboveNoiseAreReliableAndThresholdsDoubleWhenConditionsDiffer() {
        val same = context(listOf(reading(1, 0, weight = 90f), reading(2, 10, weight = 88.9f))).prompt
        assertTrue(line(same, "DELTA_PRECEDENTE_VS_RUMORE").contains("weightKg delta=-1.1 soglia=0.8 esito=VARIAZIONE_OLTRE_RUMORE"))

        // Different fasting condition: threshold 1.6, so -1.1 kg is no longer a reliable change.
        val different = context(listOf(reading(1, 0, weight = 90f, fasting = false), reading(2, 10, weight = 88.9f))).prompt
        assertTrue(line(different, "DELTA_PRECEDENTE_VS_RUMORE").contains("weightKg delta=-1.1 soglia=1.6 esito=NESSUNA_VARIAZIONE_AFFIDABILE"))
        assertTrue(line(different, "RUMORE_BIA").contains("applicato al delta precedente=SI"))
    }

    @Test
    fun noiseThresholdsAreTheSingleDeclaredSource() {
        assertEquals(0.8, BiaNoiseThresholds.forMetric("weightKg")!!, 0.0)
        assertEquals(1.0, BiaNoiseThresholds.forMetric("bodyFatPercent")!!, 0.0)
        assertEquals(1.0, BiaNoiseThresholds.forMetric("leanMassKg")!!, 0.0)
        assertEquals(1.0, BiaNoiseThresholds.forMetric("bodyWaterKg")!!, 0.0)
        assertEquals(1.6, BiaNoiseThresholds.forMetric("weightKg", degraded = true)!!, 1e-9)
        assertNull(BiaNoiseThresholds.forMetric("visceralFatLevel"))
        assertTrue(!BiaNoiseThresholds.isReliableChange("weightKg", 0.8))
        assertTrue(BiaNoiseThresholds.isReliableChange("weightKg", -0.81))
        assertTrue(!BiaNoiseThresholds.isReliableChange("visceralFatLevel", 5.0))
    }

    @Test
    fun safetyFlagIsActiveForLowBmiWithWeightLossGoalOnly() {
        val lossGoal = LocalCalculationEngine.Goal.WEIGHT_LOSS
        val flagged = AiUserContext.safetyFlag(35, 17.9, lossGoal, emptyList(), null, "Femmina")
        assertTrue(flagged.active)
        assertEquals(listOf("LOW_BMI_WEIGHT_LOSS"), flagged.reasons)
        assertTrue(flagged.line().startsWith("SAFETY_FLAG:ACTIVE(LOW_BMI_WEIGHT_LOSS"))
        assertTrue(!AiUserContext.safetyFlag(35, 17.9, LocalCalculationEngine.Goal.MAINTENANCE, emptyList(), null, "Femmina").active)
        assertEquals("SAFETY_FLAG:NONE", AiUserContext.safetyFlag(35, 22.0, lossGoal, emptyList(), null, "Femmina").line())
    }

    @Test
    fun safetyFlagDetectsRapidLossOverAtLeastTwoWeeks() {
        val dayMs = 86_400_000L
        val goal = LocalCalculationEngine.Goal.WEIGHT_LOSS
        // 80 -> 78.0 kg in 14 days = 1.25 % per week.
        assertTrue(AiUserContext.safetyFlag(40, 24.0, goal, listOf(0L to 80f, 14 * dayMs to 78f), null, "Maschio").reasons.contains("RAPID_WEIGHT_LOSS"))
        // 80 -> 78.5 kg in 14 days = 0.94 % per week.
        assertTrue(!AiUserContext.safetyFlag(40, 24.0, goal, listOf(0L to 80f, 14 * dayMs to 78.5f), null, "Maschio").active)
        // A large drop over a single week is not enough evidence (less than two weeks).
        assertTrue(!AiUserContext.safetyFlag(40, 24.0, goal, listOf(0L to 80f, 7 * dayMs to 77f), null, "Maschio").active)
    }

    @Test
    fun safetyFlagCoversAgeAndExtremeTargetsButNotAnAgeOf62() {
        val goal = LocalCalculationEngine.Goal.WEIGHT_LOSS
        assertTrue(AiUserContext.safetyFlag(17, 22.0, goal, emptyList(), null, "Maschio").reasons.contains("AGE_UNDER_18"))
        assertTrue(AiUserContext.safetyFlag(75, 22.0, LocalCalculationEngine.Goal.MAINTENANCE, emptyList(), null, "Maschio").reasons.contains("AGE_75_PLUS"))
        assertTrue(AiUserContext.safetyFlag(40, 22.0, goal, emptyList(), 1100.0, "Femmina").reasons.contains("EXTREME_LOW_TARGET"))
        assertTrue(!AiUserContext.safetyFlag(40, 22.0, goal, emptyList(), 1300.0, "Femmina").active)
        assertTrue(AiUserContext.safetyFlag(40, 22.0, goal, emptyList(), 1300.0, "Maschio").reasons.contains("EXTREME_LOW_TARGET"))
        // Age 62 alone is not a flag.
        assertTrue(!AiUserContext.safetyFlag(62, 24.0, goal, emptyList(), 1700.0, "Femmina").active)
    }

    @Test
    fun coachContextCarriesTheSafetyFlagForAProfileOutsideTheSafeRange() {
        val calculation = LocalCalculationEngine.Result(
            bmi = 17.9, bmrKcal = 1300.0, tdeeKcal = 1560.0, targetKcal = 1326.0,
            proteinG = 120.0, fatG = 50.0, carbsG = 100.0, waistHeightRatio = null, bmrMethod = "MIFFLIN_ST_JEOR",
        )
        val flagged = context(listOf(reading(1, 0), reading(2, 10)), profile = makeProfile(goal = "Dimagrimento"), calculation = calculation)
        assertTrue(flagged.safetyFlag.active)
        assertTrue(flagged.prompt.contains("SAFETY_FLAG:ACTIVE(LOW_BMI_WEIGHT_LOSS"))

        val ordinary = context(listOf(reading(1, 0), reading(2, 10)), profile = makeProfile(goal = "Ricomposizione"))
        assertTrue(!ordinary.safetyFlag.active)
        assertTrue(ordinary.prompt.contains("SAFETY_FLAG:NONE"))
    }

    private fun <T : Any> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }
}
