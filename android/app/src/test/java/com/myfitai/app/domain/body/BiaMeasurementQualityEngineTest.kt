package com.myfitai.app.domain.body

import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class BiaMeasurementQualityEngineTest {
    @Test
    fun sameConditionsAndSimilarHour_isHighQuality() {
        val previous = sample(epoch(2026, 9, 8, 7, 30), true, true, true, true)
        val current = sample(epoch(2026, 9, 15, 8, 0), true, true, true, true)

        val result = BiaMeasurementQualityEngine.evaluate(current, previous, ZoneOffset.UTC)

        assertEquals(BiaMeasurementQualityEngine.Level.HIGH, result.level)
        assertEquals(100, result.score)
    }

    @Test
    fun differentConditionsAndFarHour_isLowQuality() {
        val previous = sample(epoch(2026, 9, 8, 7, 0), true, true, true, true)
        val current = sample(epoch(2026, 9, 15, 20, 0), false, false, false, false)

        val result = BiaMeasurementQualityEngine.evaluate(current, previous, ZoneOffset.UTC)

        assertEquals(BiaMeasurementQualityEngine.Level.LOW, result.level)
        assertEquals(0, result.score)
    }

    @Test
    fun similarHoursAcrossMidnight_areHighQuality() {
        val previous = sample(epoch(2026, 9, 8, 23, 30), true, true, true, true)
        val current = sample(epoch(2026, 9, 15, 0, 0), true, true, true, true)

        val result = BiaMeasurementQualityEngine.evaluate(current, previous, ZoneOffset.UTC)

        assertEquals(BiaMeasurementQualityEngine.Level.HIGH, result.level)
        assertEquals(100, result.score)
    }

    @Test
    fun noPrevious_isInsufficient() {
        val result = BiaMeasurementQualityEngine.evaluate(
            sample(epoch(2026, 9, 15, 8, 0), true, true, true, true),
            null,
            ZoneOffset.UTC,
        )
        assertEquals(BiaMeasurementQualityEngine.Level.INSUFFICIENT, result.level)
    }

    private fun sample(
        time: Long,
        fasting: Boolean,
        justWokeUp: Boolean,
        afterBathroom: Boolean,
        noRecentWorkout: Boolean,
    ) = BiaMeasurementEntity(
        id = 0,
        profileId = 1,
        measuredAtEpochMillis = time,
        weightKg = 80f,
        bodyFatPercent = 15f,
        visceralFatLevel = 5f,
        muscleMassKg = 60f,
        skeletalMuscleKg = 35f,
        bodyWaterPercent = 60f,
        bmrKcal = 1800f,
        fasting = fasting,
        justWokeUp = justWokeUp,
        afterBathroom = afterBathroom,
        noRecentWorkout = noRecentWorkout,
        notes = null,
    )

    private fun epoch(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        LocalDateTime.of(y, m, d, h, min).toInstant(ZoneOffset.UTC).toEpochMilli()
}
