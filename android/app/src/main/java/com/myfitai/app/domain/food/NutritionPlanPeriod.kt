package com.myfitai.app.domain.food

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Shared date rules for manual generation, automatic jobs and exports. */
object NutritionPlanPeriod {
    const val MIN_WEEKS = 1
    const val MAX_WEEKS = 4

    fun monday(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun endDate(monday: LocalDate, periodWeeks: Int): LocalDate {
        require(periodWeeks in MIN_WEEKS..MAX_WEEKS) { "INVALID_PERIOD_WEEKS" }
        return monday.plusWeeks(periodWeeks.toLong()).minusDays(1)
    }

    fun generationStart(monday: LocalDate, today: LocalDate): LocalDate =
        if (today.isAfter(monday)) today else monday

    fun isEntirelyPast(monday: LocalDate, periodWeeks: Int, today: LocalDate): Boolean =
        endDate(monday, periodWeeks).isBefore(today)

    fun dates(monday: LocalDate, periodWeeks: Int, firstDate: LocalDate = monday): List<LocalDate> {
        val end = endDate(monday, periodWeeks)
        require(!firstDate.isBefore(monday) && !firstDate.isAfter(end)) { "GENERATION_START_INVALID" }
        return generateSequence(firstDate) { current ->
            current.plusDays(1).takeUnless { it.isAfter(end) }
        }.toList()
    }
}
