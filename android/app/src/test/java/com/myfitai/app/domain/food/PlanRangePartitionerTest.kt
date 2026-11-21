package com.myfitai.app.domain.food

import com.myfitai.app.data.repository.DayDraft
import com.myfitai.app.data.repository.PlanVersionDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

class PlanRangePartitionerTest {

    @Test
    fun oneDay_isOneWeekWithOneDay() {
        val wednesday = LocalDate.of(2026, 11, 18)
        val weeks = PlanRangePartitioner.partition(DateRange(wednesday, wednesday), draft(listOf(wednesday)))

        assertEquals(listOf(LocalDate.of(2026, 11, 16).toEpochDay()), weeks.map { it.weekStartEpochDay })
        assertEquals(listOf(wednesday.toEpochDay()), weeks.single().draft.days.map { it.dateEpochDay })
    }

    @Test
    fun weekAndMonthCrossing_splitsOnMonday() {
        // Thu 29/10/2026 - Tue 03/11/2026: week of Mon 26/10 gets Thu-Sun, week of Mon 02/11 gets Mon-Tue.
        val range = DateRange(LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 3))
        val weeks = PlanRangePartitioner.partition(range, draft(NutritionPlanPeriod.dates(range)))

        assertWeeks(
            weeks,
            LocalDate.of(2026, 10, 26) to dates(LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 1)),
            LocalDate.of(2026, 11, 2) to dates(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3)),
        )
    }

    @Test
    fun yearCrossing_keepsTheMondayOfThePreviousYear() {
        // Wed 30/12/2026 - Tue 05/01/2027: the first week starts on Mon 28/12/2026 and spans the new year.
        val range = DateRange(LocalDate.of(2026, 12, 30), LocalDate.of(2027, 1, 5))
        val weeks = PlanRangePartitioner.partition(range, draft(NutritionPlanPeriod.dates(range)))

        assertWeeks(
            weeks,
            LocalDate.of(2026, 12, 28) to dates(LocalDate.of(2026, 12, 30), LocalDate.of(2027, 1, 3)),
            LocalDate.of(2027, 1, 4) to dates(LocalDate.of(2027, 1, 4), LocalDate.of(2027, 1, 5)),
        )
    }

    @Test
    fun thirtyOneDays_onFiveMondays() {
        val range = DateRange(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31))
        val weeks = PlanRangePartitioner.partition(range, draft(NutritionPlanPeriod.dates(range)))

        assertEquals(31L, range.dayCount)
        assertEquals(
            listOf(LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 7), LocalDate.of(2026, 12, 14), LocalDate.of(2026, 12, 21), LocalDate.of(2026, 12, 28))
                .map { it.toEpochDay() },
            weeks.map { it.weekStartEpochDay },
        )
        assertEquals(listOf(6, 7, 7, 7, 4), weeks.map { it.draft.days.size })
        assertExactCover(range, weeks)
    }

    @Test
    fun thirtyOneDays_onSixMondays() {
        // Starts on Sunday 01/11/2026 and ends on Tuesday 01/12/2026: six calendar weeks touched.
        val range = DateRange(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1))
        val weeks = PlanRangePartitioner.partition(range, draft(NutritionPlanPeriod.dates(range)))

        assertEquals(31L, range.dayCount)
        assertEquals(PlanRangePartitioner.weekStarts(range), weeks.map { it.weekStartEpochDay })
        assertEquals(LocalDate.of(2026, 10, 26).toEpochDay(), weeks.first().weekStartEpochDay)
        assertEquals(LocalDate.of(2026, 11, 30).toEpochDay(), weeks.last().weekStartEpochDay)
        assertEquals(listOf(1, 7, 7, 7, 7, 2), weeks.map { it.draft.days.size })
        assertExactCover(range, weeks)
    }

    @Test
    fun weekDrafts_keepMetadataAndSortDays() {
        val range = DateRange(LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 3))
        val source = draft(NutritionPlanPeriod.dates(range).reversed())
        val weeks = PlanRangePartitioner.partition(range, source)

        weeks.forEach { week ->
            assertEquals(source.copy(days = week.draft.days), week.draft)
            assertEquals(week.draft.days.sortedBy { it.dateEpochDay }, week.draft.days)
        }
    }

    @Test
    fun missingDate_isRejected() {
        val range = DateRange(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 22))
        val missing = LocalDate.of(2026, 11, 19)
        val error = coverageError(range, NutritionPlanPeriod.dates(range) - missing)

        assertEquals(listOf(missing.toEpochDay()), error.missingEpochDays)
        assertTrue(error.extraEpochDays.isEmpty() && error.duplicatedEpochDays.isEmpty())
        assertEquals("PLAN_RANGE_COVERAGE_MISMATCH:MISSING=1:EXTRA=0:DUPLICATED=0", error.message)
    }

    @Test
    fun dateOutsideRange_isRejectedEvenInTheSameWeek() {
        // Sunday 22/11 belongs to the touched week but not to the range Mon 16 - Sat 21.
        val range = DateRange(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 21))
        val extra = LocalDate.of(2026, 11, 22)
        val error = coverageError(range, NutritionPlanPeriod.dates(range) + extra)

        assertEquals(listOf(extra.toEpochDay()), error.extraEpochDays)
        assertTrue(error.missingEpochDays.isEmpty() && error.duplicatedEpochDays.isEmpty())
    }

    @Test
    fun duplicatedDate_isRejected() {
        val range = DateRange(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 18))
        val duplicated = LocalDate.of(2026, 11, 17)
        val error = coverageError(range, NutritionPlanPeriod.dates(range) + duplicated)

        assertEquals(listOf(duplicated.toEpochDay()), error.duplicatedEpochDays)
        assertTrue(error.missingEpochDays.isEmpty() && error.extraEpochDays.isEmpty())
    }

    @Test
    fun emptyDraft_reportsEveryDateAsMissing() {
        val range = DateRange(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 18))
        val error = coverageError(range, emptyList())

        assertEquals(NutritionPlanPeriod.dates(range).map { it.toEpochDay() }, error.missingEpochDays)
    }

    @Test
    fun rangeLongerThanThirtyOneDays_isRejected() {
        val range = DateRange(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 2))
        val error = runCatching { PlanRangePartitioner.partition(range, draft(NutritionPlanPeriod.dates(range))) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException && error !is PlanRangePartitioner.CoverageMismatch)
        assertEquals("DATE_RANGE_TOO_LONG", error?.message)
    }

    private fun coverageError(range: DateRange, days: List<LocalDate>): PlanRangePartitioner.CoverageMismatch {
        try {
            PlanRangePartitioner.partition(range, draft(days))
        } catch (error: PlanRangePartitioner.CoverageMismatch) {
            return error
        }
        fail("Expected PLAN_RANGE_COVERAGE_MISMATCH")
        throw AssertionError()
    }

    private fun assertWeeks(weeks: List<PlanRangePartitioner.WeekDraft>, vararg expected: Pair<LocalDate, List<LocalDate>>) {
        assertEquals(expected.map { it.first.toEpochDay() }, weeks.map { it.weekStartEpochDay })
        assertEquals(
            expected.map { pair -> pair.second.map { it.toEpochDay() } },
            weeks.map { week -> week.draft.days.map { it.dateEpochDay } },
        )
    }

    private fun assertExactCover(range: DateRange, weeks: List<PlanRangePartitioner.WeekDraft>) {
        assertEquals(NutritionPlanPeriod.dates(range).map { it.toEpochDay() }, weeks.flatMap { week -> week.draft.days.map { it.dateEpochDay } })
        weeks.forEach { week ->
            assertTrue(week.draft.days.all { it.dateEpochDay in week.weekStartEpochDay..(week.weekStartEpochDay + 6) })
        }
    }

    private fun dates(from: LocalDate, to: LocalDate) = NutritionPlanPeriod.dates(DateRange(from, to))

    private fun draft(days: List<LocalDate>) = PlanVersionDraft(
        source = "TEST",
        reason = "RANGE_FIXTURE",
        targetKcal = 2000,
        targetProteinG = 120f,
        targetCarbsG = 250f,
        targetFatG = 60f,
        appValidationJson = "{}",
        inputsFingerprint = "fp",
        catalogVersion = "1",
        days = days.map { date ->
            DayDraft(
                dateEpochDay = date.toEpochDay(),
                totalKcal = 2000,
                proteinG = 120f,
                carbsG = 250f,
                fatG = 60f,
                meals = emptyList(),
            )
        },
    )
}
