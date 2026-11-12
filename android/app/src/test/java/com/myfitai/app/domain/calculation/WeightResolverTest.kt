package com.myfitai.app.domain.calculation

import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.data.local.entity.UserProfileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeightResolverTest {
    private fun bia(id: Long, at: Long, weight: Float?, profileId: Long = 1) = BiaMeasurementEntity(
        id = id, profileId = profileId, measuredAtEpochMillis = at, weightKg = weight,
        bodyFatPercent = 20f, visceralFatLevel = null, muscleMassKg = 40f, skeletalMuscleKg = null,
        bodyWaterPercent = null, bmrKcal = null, fasting = true, justWokeUp = true, afterBathroom = true, noRecentWorkout = true,
    )

    private fun body(id: Long, at: Long, weight: Float?, profileId: Long = 1) = BodyMeasurementEntity(
        id = id, profileId = profileId, measuredAtEpochMillis = at, chestCm = null, waistCm = 90f, abdomenCm = null,
        shouldersCm = null, glutesCm = null, armLeftCm = null, armRightCm = null, thighLeftCm = null,
        thighRightCm = null, calfLeftCm = null, calfRightCm = null, weightKg = weight,
    )

    private fun profile(weight: Float?) = UserProfileEntity(
        name = "Test", birthDateEpochDay = null, heightCm = 180f, currentWeightKg = weight, goal = null,
        activityLevel = null, wakeTimeMinutes = null, sleepTimeMinutes = null, dietaryPreferencesJson = null,
        createdAtEpochMillis = 1, updatedAtEpochMillis = 1,
    )

    @Test
    fun latestBiaWinsOverOlderMeasurement() {
        val reading = WeightResolver.resolve(listOf(bia(1, 2_000, 88f)), listOf(body(1, 1_000, 90f)), 95f)
        assertEquals(WeightReading(88f, 2_000, WeightSource.BIA), reading)
    }

    @Test
    fun latestMeasurementWinsOverOlderBia() {
        val reading = WeightResolver.resolve(listOf(bia(1, 1_000, 88f)), listOf(body(1, 2_000, 87f)), 95f)
        assertEquals(WeightReading(87f, 2_000, WeightSource.MISURA), reading)
    }

    @Test
    fun newestRecordWithoutWeightDoesNotHideOlderWeight() {
        val reading = WeightResolver.resolve(
            listOf(bia(1, 1_000, 88f), bia(2, 5_000, null)),
            listOf(body(1, 6_000, null)),
            95f,
        )
        assertEquals(WeightReading(88f, 1_000, WeightSource.BIA), reading)
    }

    @Test
    fun profileOnlyHasNoInventedDate() {
        val reading = WeightResolver.resolve(emptyList(), listOf(body(1, 1_000, null)), 95f)
        assertEquals(95f, reading.valueKg)
        assertNull(reading.measuredAtEpochMillis)
        assertEquals(WeightSource.PROFILO, reading.source)
    }

    @Test
    fun noWeightAnywhereIsNessuno() {
        assertEquals(WeightReading(null, null, WeightSource.NESSUNO), WeightResolver.resolve(emptyList(), emptyList(), null))
    }

    @Test
    fun sameTimestampPrefersHigherIdThenMeasurement() {
        val higherBiaId = WeightResolver.resolve(listOf(bia(9, 1_000, 80f)), listOf(body(3, 1_000, 81f)), null)
        assertEquals(WeightSource.BIA, higherBiaId.source)
        assertEquals(80f, higherBiaId.valueKg)

        val sameId = WeightResolver.resolve(listOf(bia(3, 1_000, 80f)), listOf(body(3, 1_000, 81f)), null)
        assertEquals(WeightSource.MISURA, sameId.source)

        val sameTable = WeightResolver.resolve(listOf(bia(1, 1_000, 80f), bia(2, 1_000, 79f)), emptyList(), null)
        assertEquals(79f, sameTable.valueKg)
    }

    @Test
    fun invalidValuesAreIgnored() {
        val reading = WeightResolver.resolve(
            listOf(bia(1, 1_000, 88f), bia(2, 4_000, Float.NaN), bia(3, 5_000, 0f)),
            listOf(body(1, 6_000, -3f), body(2, 7_000, Float.POSITIVE_INFINITY)),
            95f,
        )
        assertEquals(WeightReading(88f, 1_000, WeightSource.BIA), reading)
        assertEquals(WeightSource.NESSUNO, WeightResolver.resolve(emptyList(), emptyList(), 0f).source)
        assertEquals(WeightSource.NESSUNO, WeightResolver.resolve(emptyList(), emptyList(), Float.NaN).source)
    }

    @Test
    fun otherProfilesRowsAndProfileWeightAreNotTouched() {
        val mine = profile(95f)
        val reading = WeightResolver.resolve(
            listOf(bia(1, 9_000, 70f, profileId = 2)),
            listOf(body(1, 9_000, 71f, profileId = 2)),
            mine.currentWeightKg,
            profileId = 1,
        )
        assertEquals(WeightReading(95f, null, WeightSource.PROFILO), reading)
        assertEquals(95f, mine.currentWeightKg)
    }

    @Test
    fun observationsKeepTrendOrderAndMatchResolvedValue() {
        val bia = listOf(bia(1, 3_000, 88f), bia(2, 1_000, 90f))
        val body = listOf(body(1, 2_000, 89f))
        val observations = WeightResolver.observations(bia, body)
        assertEquals(listOf(90f, 89f, 88f), observations.map { it.valueKg })
        assertEquals(observations.last().valueKg, WeightResolver.resolve(bia, body, 95f).valueKg)
    }
}
