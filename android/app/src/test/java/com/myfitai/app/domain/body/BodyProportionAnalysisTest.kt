package com.myfitai.app.domain.body

import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyProportionAnalysisTest {
    private fun measurement(id: Long, date: Long, waist: Float?, armLeft: Float?, armRight: Float?) = BodyMeasurementEntity(
        id = id,
        profileId = 7,
        measuredAtEpochMillis = date,
        chestCm = 100f,
        waistCm = waist,
        abdomenCm = 90f,
        shouldersCm = 115f,
        glutesCm = 105f,
        armLeftCm = armLeft,
        armRightCm = armRight,
        thighLeftCm = 60f,
        thighRightCm = 60f,
        calfLeftCm = 38f,
        calfRightCm = 38f,
    )

    @Test
    fun latestMeasurementGetsOnlyComparablePreviousValues() {
        val previous = measurement(1, 1_000, waist = 90f, armLeft = 34f, armRight = 35f)
        val latest = measurement(2, 2_000, waist = 88f, armLeft = null, armRight = 36f)

        val report = BodyProportionEngine.analyze(latest, 180f, previous)

        assertEquals(listOf("chest", "waist", "abdomen", "shoulders", "glutes", "armRight", "thighLeft", "thighRight", "calfLeft", "calfRight"), report.comparison.map { it.key })
        val waist = report.comparison.first { it.key == "waist" }
        assertEquals(90f, waist.previous, 0.001f)
        assertEquals(88f, waist.current, 0.001f)
        assertEquals(-2f, waist.difference, 0.001f)
    }

    @Test
    fun noPreviousMeasurementHasNoComparison() {
        val report = BodyProportionEngine.analyze(measurement(1, 1_000, 90f, 34f, 35f), 180f)

        assertTrue(report.comparison.isEmpty())
        assertEquals(null, report.previousDate)
    }

    @Test
    fun dateOrderDoesNotDependOnCallerOrdering() {
        val older = measurement(1, 1_000, 90f, 34f, 35f)
        val newer = measurement(2, 2_000, 88f, 35f, 36f)
        val all = listOf(newer, older).sortedByDescending { it.measuredAtEpochMillis }
        val report = BodyProportionEngine.analyze(all.first(), 180f, all.drop(1).first())

        assertEquals("1970-01-01", report.currentDate)
        assertEquals(-2f, report.comparison.first { it.key == "waist" }.difference, 0.001f)
    }

    @Test
    fun ratiosAreDeterministicAndReportOnlyUsesTheTwoPassedReadings() {
        val older = measurement(1, 1_000, 90f, 34f, 35f)
        val newest = measurement(2, 2_000, 88f, 35f, 36f)
        val report = BodyProportionEngine.analyze(newest, 180f, older)
        val standalone = BodyProportionEngine.analyze(newest, 180f)

        assertEquals(standalone.ratios, report.ratios)
        assertEquals(older.measuredAtEpochMillis.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString() }, report.previousDate)
        assertEquals(newest.measuredAtEpochMillis.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString() }, report.currentDate)
    }
}
