package com.myfitai.app.domain.body

import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyWeightHistoryTest {
    private val rome = ZoneId.of("Europe/Rome")

    private fun body(date: String, manualWeight: Float? = null) = BodyMeasurementEntity(
        profileId = 1,
        measuredAtEpochMillis = LocalDate.parse(date).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
        weightKg = manualWeight,
        chestCm = null,
        waistCm = 90f,
        abdomenCm = null,
        shouldersCm = null,
        glutesCm = null,
        armLeftCm = null,
        armRightCm = null,
        thighLeftCm = null,
        thighRightCm = null,
        calfLeftCm = null,
        calfRightCm = null,
    )

    private fun bia(date: String, weight: Float?, hour: Int = 8) = BiaMeasurementEntity(
        profileId = 1,
        measuredAtEpochMillis = LocalDate.parse(date).atTime(hour, 0)
            .atZone(rome).toInstant().toEpochMilli(),
        weightKg = weight,
        bodyFatPercent = null,
        visceralFatLevel = null,
        muscleMassKg = null,
        skeletalMuscleKg = null,
        bodyWaterPercent = null,
        bmrKcal = null,
        fasting = false,
        justWokeUp = false,
        afterBathroom = false,
        noRecentWorkout = false,
    )

    @Test fun linksOnlyMatchingHistoricalDate() {
        val result = BodyWeightHistory.weightFor(
            body("2026-09-18"),
            listOf(bia("2026-09-20", 79f), bia("2026-09-18", 81f)),
            rome,
        )
        assertEquals(81f, result!!.kg, 0.001f)
        assertTrue(result.fromBia)
    }

    @Test fun neverCopiesTodaysWeightOntoOldMeasurement() {
        assertNull(BodyWeightHistory.weightFor(body("2026-09-18"), listOf(bia("2026-09-20", 79f)), rome))
    }

    @Test fun preservesExplicitManualWeightOverLinkedBia() {
        val result = BodyWeightHistory.weightFor(body("2026-09-18", 82f), listOf(bia("2026-09-18", 81f)), rome)
        assertEquals(82f, result!!.kg, 0.001f)
        assertFalse(result.fromBia)
    }

    @Test fun selectsLatestValidReadingWithinSameDate() {
        val result = BodyWeightHistory.weightFor(
            body("2026-09-18"),
            listOf(bia("2026-09-18", 80f, 7), bia("2026-09-18", 0f, 20), bia("2026-09-18", 81f, 19)),
            rome,
        )
        assertEquals(81f, result!!.kg, 0.001f)
    }
}
