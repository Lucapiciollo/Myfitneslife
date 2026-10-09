package com.myfitai.app.domain.body

import androidx.work.Data
import com.myfitai.app.data.local.entity.BiaMeasurementEntity
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
        fasting = true,
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

    private fun <T : Any> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }
}
