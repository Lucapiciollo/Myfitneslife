package com.myfitai.app.domain.body

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiaHistoryImportContractTest {
    @Test fun importsCompleteAndPartialMeasurementsWithoutInventingMissingValues() {
        val rows = BiaHistoryImportContract.parse(
            """{"schema":"myfitai_bia_history_v1","biaMeasurements":[
                {"date":"2026-01-10","weightKg":89.0,"visceralFatLevel":7,"boneMassKg":4.6},
                {"date":"2026-02-10","bodyFatPercent":20.7,"visceralFatLevel":null}
            ]}""", 12L,
        )
        assertEquals(2, rows.size)
        assertEquals(12L, rows[0].profileId)
        assertEquals(7f, rows[0].visceralFatLevel!!, 0.01f)
        assertEquals(4.6f, rows[0].boneMassKg!!, 0.01f)
        assertNull(rows[1].weightKg)
        assertNull(rows[1].visceralFatLevel)
        assertEquals("2026-01-10", BiaHistoryImportContract.dayKey(rows[0].measuredAtEpochMillis))
    }

    @Test fun rejectsRepeatedDaysInsteadOfDuplicatingReadings() {
        val result = runCatching {
            BiaHistoryImportContract.parse(
                """{"schema":"myfitai_bia_history_v1","biaMeasurements":[
                    {"date":"2026-01-10","weightKg":89.0},
                    {"date":"2026-01-10","weightKg":90.0}
                ]}""", 1L,
            )
        }
        assertTrue(result.isFailure)
    }

    @Test fun rejectsNonNumericMetricsAndOutOfRangePercentages() {
        for (metric in listOf("\"weightKg\":\"89\"", "\"bodyFatPercent\":110")) {
            val result = runCatching {
                BiaHistoryImportContract.parse(
                    """{"schema":"myfitai_bia_history_v1","biaMeasurements":[{"date":"2026-01-10",""" + metric + "}]}",
                    1L,
                )
            }
            assertTrue(result.isFailure)
        }
    }

    @Test fun rejectsInvalidCalendarDatesAndEmptyReadings() {
        for (row in listOf("""{"date":"2026-02-30","weightKg":89}""", """{"date":"2026-02-10","weightKg":null}""")) {
            assertTrue(runCatching {
                BiaHistoryImportContract.parse(
                    """{"schema":"myfitai_bia_history_v1","biaMeasurements":[""" + row + "]}",
                    1L,
                )
            }.isFailure)
        }
    }
}
