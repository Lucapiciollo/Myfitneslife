package com.myfitai.app.domain.body

import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiaAnalysisPolicyTest {
    private fun reading(id: Long = 7L, weight: Float? = 90f, fat: Float? = 20f) = BiaMeasurementEntity(
        id = id, profileId = 1L, measuredAtEpochMillis = 1_700_000_000_000L, weightKg = weight, bodyFatPercent = fat,
        visceralFatLevel = null, muscleMassKg = 66f, skeletalMuscleKg = null, bodyWaterPercent = null, bmrKcal = null,
        fasting = true, justWokeUp = true, afterBathroom = true, noRecentWorkout = true,
    )

    private fun report(vararg all: BiaMeasurementEntity) = BiaAnalysisReport.build(all.toList())

    private val result = BiaAnalysisService.Result(
        interpretation = BiaAnalysisContract.Interpretation(
            classification = "INSUFFICIENT_DATA", verdict = "v", whatIsHappening = "w", fatLoss = "?", objective = "?",
            caloriesAndMacros = "?", nextCheck = "n", reliability = "?", reliabilityReason = "r", comparisons = emptyList(),
            historical = emptyList(), scenarios = emptyList(), positives = emptyList(), monitor = emptyList(),
            actions = emptyList(), safetyNote = "", question = null, agentValidation = "ok",
        ),
        provider = "GEMINI",
        model = "test",
    )

    @Test
    fun automaticJob_skipsWhenLatestReadingWasAlreadyAnalyzed() {
        val latest = reading()
        val stored = BiaAnalysisAiJobHandler.payloadJson(result, latest, measurementCount = 1)
        val decision = BiaAnalysisPolicy.decide(automatic = true, report = report(latest), storedPayload = stored)
        assertEquals(BiaAnalysisPolicy.Decision.Skip(BiaAnalysisPolicy.SKIP_ALREADY_ANALYZED), decision)
    }

    @Test
    fun automaticJob_runsWhenNothingWasAnalyzedYet() {
        val decision = BiaAnalysisPolicy.decide(automatic = true, report = report(reading()), storedPayload = null)
        assertEquals(BiaAnalysisPolicy.Decision.Run, decision)
    }

    @Test
    fun singleReading_isAllowedToRun() {
        assertEquals(BiaAnalysisPolicy.Decision.Run, BiaAnalysisPolicy.decide(true, report(reading()), null))
    }

    @Test
    fun noValidReading_isNoDataBeforeAnyPaidCall() {
        assertEquals(BiaAnalysisPolicy.Decision.NoData, BiaAnalysisPolicy.decide(true, null, null))
        assertEquals(BiaAnalysisPolicy.Decision.NoData, BiaAnalysisPolicy.decide(false, null, null))
    }

    @Test
    fun manualJob_isNeverBlockedByAnExistingAnalysis() {
        val latest = reading()
        val stored = BiaAnalysisAiJobHandler.payloadJson(result, latest, measurementCount = 1)
        assertEquals(BiaAnalysisPolicy.Decision.Run, BiaAnalysisPolicy.decide(false, report(latest), stored))
    }

    @Test
    fun editedReading_makesTheStoredAnalysisObsoleteAndReRunnable() {
        val original = reading(weight = 90f)
        val stored = BiaAnalysisAiJobHandler.payloadJson(result, original, measurementCount = 1)
        val edited = original.copy(weightKg = 88f)
        assertFalse(BiaAnalysisPolicy.isObsolete(stored, original))
        assertTrue(BiaAnalysisPolicy.isObsolete(stored, edited))
        assertEquals(BiaAnalysisPolicy.Decision.Run, BiaAnalysisPolicy.decide(true, report(edited), stored))
    }

    @Test
    fun legacyPayloadWithoutFingerprint_isNotMarkedObsolete() {
        assertFalse(BiaAnalysisPolicy.isObsolete("""{"verdict":"x"}""", reading()))
        assertFalse(BiaAnalysisPolicy.isObsolete("not json", reading()))
    }

    @Test
    fun payloadKeepsContractVersionAndTheMeasurementItRefersTo() {
        val latest = reading(id = 42L)
        val json = org.json.JSONObject(BiaAnalysisAiJobHandler.payloadJson(result, latest, measurementCount = 3))
        assertEquals(BiaAnalysisPolicy.CONTRACT_VERSION, json.getString("contractVersion"))
        assertEquals(42L, json.getLong("biaMeasurementId"))
        assertEquals(latest.measuredAtEpochMillis, json.getLong("measuredAtEpochMillis"))
        assertEquals(3, json.getInt("measurementCount"))
        assertNotEquals("", json.getString("dataFingerprint"))
    }

    @Test
    fun autoJobKey_isDeterministicSoTwoLaunchesQueueOneJob() {
        val due = 1_790_000_000_000L
        assertEquals(BiaProgressCoachScheduler.jobKey(due), BiaProgressCoachScheduler.jobKey(due))
        assertEquals(BiaProgressCoachScheduler.jobKey(null), BiaProgressCoachScheduler.jobKey(null))
        assertEquals("auto-0", BiaProgressCoachScheduler.jobKey(null))
        assertNotEquals(BiaProgressCoachScheduler.jobKey(due), BiaProgressCoachScheduler.jobKey(due + 1))
    }

    @Test
    fun delay_dependsOnlyOnDueAndNow_neverOnTheKey() {
        assertEquals(0L, BiaProgressCoachScheduler.delayMillis(null, 5_000L))
        assertEquals(0L, BiaProgressCoachScheduler.delayMillis(1_000L, 5_000L))
        assertEquals(4_000L, BiaProgressCoachScheduler.delayMillis(9_000L, 5_000L))
    }
}
