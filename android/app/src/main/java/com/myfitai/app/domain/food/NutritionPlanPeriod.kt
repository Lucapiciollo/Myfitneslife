package com.myfitai.app.domain.food

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * An inclusive calendar interval. Start and end are actual requested dates, not week boundaries.
 * The generated-plan length limit ([NutritionPlanPeriod.MAX_RANGE_DAYS]) is enforced on the generation path
 * ([NutritionPlanPeriod.requireGenerationLength]), so the type stays usable for longer periods such as exports.
 */
data class DateRange(val start: LocalDate, val endInclusive: LocalDate) {
    init {
        require(!endInclusive.isBefore(start)) { "DATE_RANGE_INVALID" }
    }

    val dayCount: Long
        get() = ChronoUnit.DAYS.between(start, endInclusive) + 1
}

/** Shared date rules for manual generation, automatic jobs and exports. */
object NutritionPlanPeriod {
    const val MIN_WEEKS = 1
    const val MAX_WEEKS = 4

    /** Longest generated plan (one calendar month). Single source for validator, recovery, MFP2 and JSON schema. */
    const val MAX_RANGE_DAYS = 31

    fun monday(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** MVP calendar default: today through the final day of today's month, both inclusive. */
    fun defaultSelectableRange(today: LocalDate): DateRange =
        DateRange(today, YearMonth.from(today).atEndOfMonth())

    /** Applies the MVP calendar bounds without changing or extending either selected endpoint. */
    fun validateSelectableRange(range: DateRange, today: LocalDate): Result<DateRange> = runCatching {
        require(!isEntirelyPast(range, today)) { "DATE_RANGE_ENTIRELY_PAST" }
        require(!range.start.isBefore(today)) { "DATE_RANGE_START_BEFORE_TODAY" }
        val monthEnd = YearMonth.from(today).atEndOfMonth()
        require(!range.endInclusive.isAfter(monthEnd)) { "DATE_RANGE_END_AFTER_MONTH" }
        range
    }

    /** Generation-path length guard shared by the business gate, the recovery engine and the MFP2 builder. */
    fun requireGenerationLength(range: DateRange): DateRange {
        require(range.dayCount <= MAX_RANGE_DAYS) { "DATE_RANGE_TOO_LONG" }
        return range
    }

    /**
     * Generation/retry guard: a selection that became partly past while waiting is trimmed to today..end (as the
     * legacy generationStart does); an entirely past selection is rejected. The end date never moves. The UI selection
     * rule stays strict ([validateSelectableRange]).
     */
    fun trimToGenerable(range: DateRange, today: LocalDate): Result<DateRange> = runCatching {
        require(!isEntirelyPast(range, today)) { "DATE_RANGE_ENTIRELY_PAST" }
        requireGenerationLength(if (range.start.isBefore(today)) DateRange(today, range.endInclusive) else range)
    }

    fun isEntirelyPast(range: DateRange, today: LocalDate): Boolean = range.endInclusive.isBefore(today)

    /** Calendar weeks (Monday-Sunday) touched by [range], counted from the Monday of its first date. */
    fun calendarWeeksSpanned(range: DateRange): Int =
        (ChronoUnit.DAYS.between(monday(range.start), range.endInclusive) / 7 + 1).toInt()

    /**
     * Range path: week-based parameters may accompany an explicit range only when they describe the same request
     * (Monday of the first date, the first date itself, the calendar weeks spanned). Null = not passed.
     */
    fun requireCoherentLegacyParams(range: DateRange, weekStart: LocalDate, firstDate: LocalDate?, periodWeeks: Int?) {
        require(weekStart == monday(range.start)) { "DATE_RANGE_WEEK_START_INCOHERENT" }
        require(firstDate == null || firstDate == range.start) { "DATE_RANGE_FIRST_DATE_INCOHERENT" }
        require(periodWeeks == null || periodWeeks == calendarWeeksSpanned(range)) { "DATE_RANGE_PERIOD_WEEKS_INCOHERENT" }
    }

    /** Explicit adapter for existing week-based callers. [firstDate] remains inclusive. */
    fun legacyDateRange(monday: LocalDate, periodWeeks: Int, firstDate: LocalDate = monday): DateRange {
        val end = endDate(monday, periodWeeks)
        require(!firstDate.isBefore(monday) && !firstDate.isAfter(end)) { "GENERATION_START_INVALID" }
        return DateRange(firstDate, end)
    }

    fun endDate(monday: LocalDate, periodWeeks: Int): LocalDate {
        require(periodWeeks in MIN_WEEKS..MAX_WEEKS) { "INVALID_PERIOD_WEEKS" }
        return monday.plusWeeks(periodWeeks.toLong()).minusDays(1)
    }

    fun generationStart(monday: LocalDate, today: LocalDate): LocalDate =
        if (today.isAfter(monday)) today else monday

    fun isEntirelyPast(monday: LocalDate, periodWeeks: Int, today: LocalDate): Boolean =
        endDate(monday, periodWeeks).isBefore(today)

    fun dates(monday: LocalDate, periodWeeks: Int, firstDate: LocalDate = monday): List<LocalDate> =
        dates(legacyDateRange(monday, periodWeeks, firstDate))

    /** Enumerates exactly the selected inclusive dates; it never rounds to a Monday or Sunday. */
    fun dates(range: DateRange): List<LocalDate> = generateSequence(range.start) { current ->
        if (current == range.endInclusive) null else current.plusDays(1)
    }.toList()
}
