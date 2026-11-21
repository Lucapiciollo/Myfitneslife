package com.myfitai.app.domain.food

import com.myfitai.app.data.repository.PlanVersionDraft
import java.time.LocalDate

/**
 * Pure split of one generated date-range draft into one draft per touched calendar week (Monday key).
 * Each week draft keeps the generation metadata and carries only the range days of that week: days of the same
 * week outside the range are never invented, copied or carried over.
 */
object PlanRangePartitioner {
    /** Draft to append as a new version of the plan stored on [weekStartEpochDay]. */
    data class WeekDraft(val weekStartEpochDay: Long, val draft: PlanVersionDraft)

    /** Generated days do not cover the range exactly. Lists hold epoch days, sorted ascending. */
    class CoverageMismatch(
        val missingEpochDays: List<Long>,
        val extraEpochDays: List<Long>,
        val duplicatedEpochDays: List<Long>,
    ) : IllegalArgumentException(
        "PLAN_RANGE_COVERAGE_MISMATCH:MISSING=${missingEpochDays.size}:" +
            "EXTRA=${extraEpochDays.size}:DUPLICATED=${duplicatedEpochDays.size}",
    )

    /** Monday epoch days of every calendar week touched by [range], ascending. */
    fun weekStarts(range: DateRange): List<Long> =
        NutritionPlanPeriod.dates(range).map { NutritionPlanPeriod.monday(it).toEpochDay() }.distinct()

    /**
     * @throws IllegalArgumentException `DATE_RANGE_TOO_LONG` above [NutritionPlanPeriod.MAX_RANGE_DAYS]
     * @throws CoverageMismatch when a range date is missing, a day falls outside the range or a date repeats
     */
    fun partition(range: DateRange, draft: PlanVersionDraft): List<WeekDraft> {
        NutritionPlanPeriod.requireGenerationLength(range)
        val expected = NutritionPlanPeriod.dates(range).map { it.toEpochDay() }
        val expectedSet = expected.toSet()
        val generated = draft.days.map { it.dateEpochDay }
        val generatedSet = generated.toSet()
        val duplicated = generated.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
        val missing = expected.filterNot { it in generatedSet }
        val extra = generatedSet.filterNot { it in expectedSet }.sorted()
        if (missing.isNotEmpty() || extra.isNotEmpty() || duplicated.isNotEmpty()) {
            throw CoverageMismatch(missing, extra, duplicated)
        }
        return draft.days
            .sortedBy { it.dateEpochDay }
            .groupBy { NutritionPlanPeriod.monday(LocalDate.ofEpochDay(it.dateEpochDay)).toEpochDay() }
            .toSortedMap()
            .map { (weekStart, days) -> WeekDraft(weekStart, draft.copy(days = days)) }
    }
}
