package com.myfitai.app.domain.food

import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class NutritionPlanPeriodTest {
    private val monday = LocalDate.of(2026, 9, 14)
    private val targets = NutritionBusinessValidator.Targets(2400.0, 160.0, 280.0, 70.0)

    /** Giovedi' 29/10/2026 - martedi' 03/11/2026: 6 giorni, a cavallo di settimana e di mese (W = lunedi' 26/10). */
    private val crossRange = DateRange(LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 3))

    @Test
    fun periodDates_areAnchoredToMonday() {
        assertEquals(monday, NutritionPlanPeriod.monday(monday.plusDays(3)))
        assertEquals(monday.plusDays(13), NutritionPlanPeriod.endDate(monday, 2))
        assertEquals(28, NutritionPlanPeriod.dates(monday, 4).size)
    }

    @Test
    fun currentWeek_excludesOnlyPastDays() {
        val today = monday.plusDays(3)
        val dates = NutritionPlanPeriod.dates(monday, 2, NutritionPlanPeriod.generationStart(monday, today))
        assertEquals(today, dates.first())
        assertEquals(monday.plusDays(13), dates.last())
        assertEquals(11, dates.size)
    }

    @Test
    fun completedPeriod_isRejected() {
        assertTrue(NutritionPlanPeriod.isEntirelyPast(monday, 1, monday.plusDays(7)))
        assertFalse(NutritionPlanPeriod.isEntirelyPast(monday, 2, monday.plusDays(7)))
    }

    @Test
    fun dateRange_oneDayIsInclusive() {
        val today = LocalDate.of(2026, 11, 20)
        val range = DateRange(today, today)

        assertEquals(1L, range.dayCount)
        assertEquals(listOf(today), NutritionPlanPeriod.dates(range))
        assertTrue(NutritionPlanPeriod.validateSelectableRange(range, today).isSuccess)
    }

    @Test
    fun dateRange_crossesWeekAndMonthWithoutExtendingEndpoints() {
        val dates = NutritionPlanPeriod.dates(crossRange)

        assertEquals(6, dates.size)
        assertEquals(crossRange.start, dates.first())
        assertEquals(crossRange.endInclusive, dates.last())
        assertEquals(crossRange.dayCount, dates.size.toLong())
        assertEquals(2, NutritionPlanPeriod.calendarWeeksSpanned(crossRange))
    }

    @Test
    fun defaultRange_startsTodayAndEndsOnTheCurrentMonthEnd() {
        val today = LocalDate.of(2026, 11, 20)
        val range = NutritionPlanPeriod.defaultSelectableRange(today)

        assertEquals(today, range.start)
        assertEquals(YearMonth.from(today).atEndOfMonth(), range.endInclusive)
    }

    @Test
    fun defaultRange_leapFebruaryEndsOnThe29th_andMarchFirstIsRejected() {
        val today = LocalDate.of(2028, 2, 10)
        val range = NutritionPlanPeriod.defaultSelectableRange(today)

        assertEquals(LocalDate.of(2028, 2, 29), range.endInclusive)
        assertEquals(20L, range.dayCount)
        assertTrue(NutritionPlanPeriod.validateSelectableRange(range, today).isSuccess)
        assertEquals(
            "DATE_RANGE_END_AFTER_MONTH",
            NutritionPlanPeriod.validateSelectableRange(DateRange(today, LocalDate.of(2028, 3, 1)), today).exceptionOrNull()?.message,
        )
    }

    @Test
    fun defaultRange_inAThirtyOneDayMonthCoversThirtyOneDays() {
        val today = LocalDate.of(2026, 12, 1)
        val range = NutritionPlanPeriod.defaultSelectableRange(today)

        assertEquals(LocalDate.of(2026, 12, 31), range.endInclusive)
        assertEquals(31L, range.dayCount)
        assertEquals(31, NutritionPlanPeriod.dates(range).size)
        assertTrue(NutritionPlanPeriod.validateSelectableRange(range, today).isSuccess)
        assertEquals(range, NutritionPlanPeriod.requireGenerationLength(range))
    }

    @Test
    fun defaultRange_onTheLastDayOfTheMonthIsASingleDay() {
        val today = LocalDate.of(2026, 11, 30)
        val range = NutritionPlanPeriod.defaultSelectableRange(today)

        assertEquals(DateRange(today, today), range)
        assertEquals(listOf(today), NutritionPlanPeriod.dates(range))
        assertTrue(NutritionPlanPeriod.validateSelectableRange(range, today).isSuccess)
    }

    @Test
    fun yearBoundary_mondayDatesAndGateCrossTheYear() {
        assertEquals(LocalDate.of(2026, 12, 28), NutritionPlanPeriod.monday(LocalDate.of(2027, 1, 1)))
        val range = DateRange(LocalDate.of(2026, 12, 30), LocalDate.of(2027, 1, 2))

        assertEquals(
            listOf(LocalDate.of(2026, 12, 30), LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2)),
            NutritionPlanPeriod.dates(range),
        )
        assertEquals(1, NutritionPlanPeriod.calendarWeeksSpanned(range))
        assertNull(rangeGate(responseFor(range), range))
    }

    @Test
    fun dateRange_invertedEndpointsAreRejected() {
        val today = LocalDate.of(2026, 11, 20)

        assertEquals("DATE_RANGE_INVALID", failure { DateRange(today, today.minusDays(1)) })
    }

    @Test
    fun selectableRange_rejectsPastOnlyAndStartBeforeToday() {
        val today = LocalDate.of(2026, 11, 20)
        val pastOnly = DateRange(today.minusDays(4), today.minusDays(1))
        val startsBeforeToday = DateRange(today.minusDays(1), today.plusDays(1))

        assertTrue(NutritionPlanPeriod.isEntirelyPast(pastOnly, today))
        assertEquals("DATE_RANGE_ENTIRELY_PAST", NutritionPlanPeriod.validateSelectableRange(pastOnly, today).exceptionOrNull()?.message)
        assertEquals(
            "DATE_RANGE_START_BEFORE_TODAY",
            NutritionPlanPeriod.validateSelectableRange(startsBeforeToday, today).exceptionOrNull()?.message,
        )
    }

    @Test
    fun selectableRange_rejectsEndAfterCurrentMonthAndAcceptsInclusiveMonthEnd() {
        val today = LocalDate.of(2026, 11, 20)
        val monthEnd = YearMonth.from(today).atEndOfMonth()

        assertTrue(NutritionPlanPeriod.validateSelectableRange(DateRange(today, monthEnd), today).isSuccess)
        assertEquals(
            "DATE_RANGE_END_AFTER_MONTH",
            NutritionPlanPeriod.validateSelectableRange(DateRange(today, monthEnd.plusDays(1)), today).exceptionOrNull()?.message,
        )
    }

    @Test
    fun trimToGenerable_cutsOnlyThePastPart_andTheSelectionRuleStaysStrict() {
        val today = LocalDate.of(2026, 11, 20)
        val monthEnd = LocalDate.of(2026, 11, 30)
        val partlyPast = DateRange(LocalDate.of(2026, 11, 18), monthEnd)
        val future = DateRange(today, LocalDate.of(2026, 11, 25))

        assertEquals(DateRange(today, monthEnd), NutritionPlanPeriod.trimToGenerable(partlyPast, today).getOrThrow())
        assertEquals(future, NutritionPlanPeriod.trimToGenerable(future, today).getOrThrow())
        assertEquals(DateRange(today, today), NutritionPlanPeriod.trimToGenerable(DateRange(today.minusDays(5), today), today).getOrThrow())
        assertEquals(
            "DATE_RANGE_ENTIRELY_PAST",
            NutritionPlanPeriod.trimToGenerable(DateRange(LocalDate.of(2026, 11, 10), today.minusDays(1)), today).exceptionOrNull()?.message,
        )
        assertEquals(
            "DATE_RANGE_START_BEFORE_TODAY",
            NutritionPlanPeriod.validateSelectableRange(partlyPast, today).exceptionOrNull()?.message,
        )
    }

    @Test
    fun legacyDateRange_rejectsInvalidWeeksAndStart() {
        assertEquals("INVALID_PERIOD_WEEKS", failure { NutritionPlanPeriod.legacyDateRange(monday, 0) })
        assertEquals("INVALID_PERIOD_WEEKS", failure { NutritionPlanPeriod.legacyDateRange(monday, 5) })
        assertEquals("GENERATION_START_INVALID", failure { NutritionPlanPeriod.legacyDateRange(monday, 1, monday.minusDays(1)) })
        assertEquals("GENERATION_START_INVALID", failure { NutritionPlanPeriod.legacyDateRange(monday, 1, monday.plusDays(7)) })
        assertEquals(DateRange(monday.plusDays(3), monday.plusDays(13)), NutritionPlanPeriod.legacyDateRange(monday, 2, monday.plusDays(3)))
    }

    @Test
    fun generationLength_acceptsThirtyOneDays_andRejectsThirtyTwoOnEveryRangePath() {
        val month = DateRange(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31))
        val tooLong = DateRange(month.start, month.endInclusive.plusDays(1))

        assertEquals(31, NutritionPlanPeriod.MAX_RANGE_DAYS)
        assertEquals(32L, tooLong.dayCount)
        assertEquals("DATE_RANGE_TOO_LONG", failure { NutritionPlanPeriod.requireGenerationLength(tooLong) })
        // Business gate.
        assertNull(rangeGate(responseFor(month), month))
        assertEquals("DATE_RANGE_TOO_LONG", rangeGate(responseFor(tooLong), tooLong))
        // Recovery engine.
        assertEquals(NutritionPlanPeriod.dates(month), recovery(month).days.map { it.date })
        assertEquals("DATE_RANGE_TOO_LONG", failure { recovery(tooLong) })
        // MFP2 context.
        assertEquals(month, mfp2RangeContext(month).dateRange)
        assertEquals("DATE_RANGE_TOO_LONG", failure { mfp2RangeContext(tooLong) })
        // A trimmed retry range stays within the same limit.
        assertEquals("DATE_RANGE_TOO_LONG", NutritionPlanPeriod.trimToGenerable(tooLong, tooLong.start).exceptionOrNull()?.message)
    }

    @Test
    fun schema_daysMaxItemsUsesTheSharedRangeLimit() {
        val days = JSONObject(NutritionPlanContract.schemaJson).getJSONObject("properties").getJSONObject("days")

        assertEquals(NutritionPlanPeriod.MAX_RANGE_DAYS, days.getInt("maxItems"))
        assertEquals(1, days.getInt("minItems"))
    }

    @Test
    fun rangeGate_acceptsExactlyTheRequestedDates_andRejectsWrongWeekStartMissingExtraAndDuplicateDates() {
        val response = responseFor(crossRange)
        val afterEnd = crossRange.endInclusive.plusDays(1).toEpochDay()

        assertNull(rangeGate(response, crossRange))
        assertEquals("WEEK_START_MISMATCH", rangeGate(response.copy(weekStartEpochDay = crossRange.start.toEpochDay()), crossRange))
        assertEquals("WEEK_MUST_HAVE_6_DAYS", rangeGate(response.copy(days = response.days.dropLast(1)), crossRange))
        assertEquals(
            "WEEK_MUST_HAVE_6_DAYS",
            rangeGate(response.copy(days = response.days + response.days.last().copy(dateEpochDay = afterEnd)), crossRange),
        )
        assertEquals("WEEK_DATES_INVALID", rangeGate(response.copy(days = response.days.dropLast(1) + response.days.first()), crossRange))
        assertEquals(
            "WEEK_DATES_INVALID",
            rangeGate(response.copy(days = response.days.dropLast(1) + response.days.last().copy(dateEpochDay = afterEnd)), crossRange),
        )
    }

    @Test
    fun rangeGate_weekParametersMustDescribeTheSameRequest() {
        val response = responseFor(crossRange)

        assertNull(rangeGate(response, crossRange, firstDate = crossRange.start, periodWeeks = 2))
        assertEquals(
            "DATE_RANGE_WEEK_START_INCOHERENT",
            rangeGate(response, crossRange, weekStart = NutritionPlanPeriod.monday(crossRange.start).minusWeeks(1)),
        )
        assertEquals("DATE_RANGE_FIRST_DATE_INCOHERENT", rangeGate(response, crossRange, firstDate = crossRange.start.plusDays(1)))
        assertEquals("DATE_RANGE_PERIOD_WEEKS_INCOHERENT", rangeGate(response, crossRange, periodWeeks = 1))
    }

    @Test
    fun legacyGate_keepsTheBaseOrderOfChecks() {
        val response = responseFor(NutritionPlanPeriod.dates(monday, 1), monday)
        val wrongWeek = response.copy(weekStartEpochDay = monday.plusWeeks(1).toEpochDay())

        assertEquals("WEEK_START_MISMATCH", legacyGate(wrongWeek, firstDate = monday, periodWeeks = 5))
        assertEquals("WEEK_START_MISMATCH", legacyGate(wrongWeek, firstDate = monday.minusDays(1), periodWeeks = 1))
        assertEquals("INVALID_PERIOD_WEEKS", legacyGate(response, firstDate = monday, periodWeeks = 5))
        assertEquals("GENERATION_START_INVALID", legacyGate(response, firstDate = monday.minusDays(1), periodWeeks = 1))
        assertEquals("GENERATION_START_INVALID", legacyGate(response, firstDate = monday.plusDays(7), periodWeeks = 1))
        assertNull(legacyGate(response, firstDate = monday, periodWeeks = 1))
        // Defaults: one full week from the requested Monday, as before.
        assertTrue(NutritionPlanContract.validateBusiness(response, monday, targets).isSuccess)
    }

    @Test
    fun legacyPastWeek_keepsTheRequestMondayAsWeekStart_inMfp1AndMfp2() {
        val requestMonday = LocalDate.of(2026, 11, 9)
        val today = LocalDate.of(2026, 11, 20)
        val periodWeeks = 2
        val generationStart = NutritionPlanPeriod.generationStart(requestMonday, today)
        val dates = NutritionPlanPeriod.dates(requestMonday, periodWeeks, generationStart)
        assertEquals(listOf(today, today.plusDays(1), today.plusDays(2)), dates)

        // MFP1: W resta il lunedi' della richiesta, non quello del primo giorno generato.
        val mfp1 = responseFor(dates, requestMonday)
        assertNull(legacyGate(mfp1, firstDate = generationStart, periodWeeks = periodWeeks, weekStart = requestMonday))
        assertEquals(
            "WEEK_START_MISMATCH",
            legacyGate(
                mfp1.copy(weekStartEpochDay = NutritionPlanPeriod.monday(generationStart).toEpochDay()),
                firstDate = generationStart, periodWeeks = periodWeeks, weekStart = requestMonday,
            ),
        )

        // MFP2: stesso W anche se il modello ne scrive un altro.
        val context = mfp2Context(requestMonday, generationStart, periodWeeks, dates)
        val accepted = mfp2.evaluate(mfp2Envelope(dates, weekStart = 0L), context, 0)
        assertEquals(requestMonday.toEpochDay(), accepted.response.weekStartEpochDay)
        assertEquals(dates.map(LocalDate::toEpochDay), accepted.response.days.map { it.dateEpochDay })
        assertEquals(DateRange(generationStart, LocalDate.of(2026, 11, 22)), context.dateRange)
    }

    @Test
    fun mfp2_evaluatesAnExplicitNonWeeklyRange() {
        val context = mfp2RangeContext(crossRange)
        val dates = NutritionPlanPeriod.dates(crossRange)

        val accepted = mfp2.evaluate(mfp2Envelope(dates, weekStart = 0L), context, 0)

        assertTrue(accepted.assessment.accepted)
        assertEquals(LocalDate.of(2026, 10, 26).toEpochDay(), accepted.response.weekStartEpochDay)
        assertEquals(dates.map(LocalDate::toEpochDay), accepted.response.days.map { it.dateEpochDay })
        assertEquals(
            "WEEK_MUST_HAVE_6_DAYS",
            failure { mfp2.evaluate(mfp2Envelope(dates.dropLast(1), weekStart = 0L), context, 0) },
        )
    }

    @Test
    fun mfp2Context_copyNeverLeavesAStaleRange() {
        val legacy = mfp2Context(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 20), 2, emptyList())
        assertEquals(
            DateRange(LocalDate.of(2026, 11, 21), LocalDate.of(2026, 11, 22)),
            legacy.copy(generationStart = LocalDate.of(2026, 11, 21)).dateRange,
        )

        val explicit = mfp2RangeContext(crossRange)
        assertEquals(crossRange, explicit.dateRange)
        assertEquals("DATE_RANGE_FIRST_DATE_INCOHERENT", failure { explicit.copy(generationStart = crossRange.start.plusDays(1)) })
        assertEquals("DATE_RANGE_WEEK_START_INCOHERENT", failure { explicit.copy(monday = LocalDate.of(2026, 10, 19)) })
        assertEquals("DATE_RANGE_PERIOD_WEEKS_INCOHERENT", failure { explicit.copy(periodWeeks = 1) })
    }

    @Test
    fun calorieRecovery_usesTheExactRequestedDates() {
        assertEquals(NutritionPlanPeriod.dates(crossRange), recovery(crossRange).days.map { it.date })
    }

    @Test
    fun calorieRecovery_spreadsCheatsAcrossWeekAndMonthInsideTheRange() {
        val base = NutritionBusinessValidator.Targets(2000.0, 180.0, 220.0, 70.0)
        // Sgarro di mercoledi' 28/10: finestra 29/10-04/11, quindi il recupero passa il 1/11 (mese) e il 2/11 (settimana).
        val wednesday = CalorieRecoveryEngine.plan(
            crossRange, crossRange.start, base, 80.0, LocalCalculationEngine.Goal.MAINTENANCE,
            listOf(CalorieRecoveryEngine.Credit(1, LocalDate.of(2026, 10, 28), 1000)),
        )
        assertEquals(NutritionPlanPeriod.dates(crossRange), wednesday.days.map { it.date })
        assertEquals(1000, wednesday.plannedRecoveryKcal)
        assertTrue(wednesday.days.all { it.recoveredKcal in 1..200 })

        // Sgarro di domenica 1/11: solo i giorni successivi del range (nuova settimana) lo recuperano.
        val sunday = LocalDate.of(2026, 11, 1)
        val result = CalorieRecoveryEngine.plan(
            crossRange, crossRange.start, base, 80.0, LocalCalculationEngine.Goal.MAINTENANCE,
            listOf(CalorieRecoveryEngine.Credit(2, sunday, 300)),
        )
        assertTrue(result.days.filter { !it.date.isAfter(sunday) }.all { it.recoveredKcal == 0 })
        assertEquals(300, result.days.filter { it.date.isAfter(sunday) }.sumOf { it.recoveredKcal })
        assertEquals(300, result.plannedBySource[2L])
        assertEquals(0, result.remainingKcal)
    }

    private fun failure(block: () -> Any?): String? = runCatching(block).exceptionOrNull()?.message

    private fun rangeGate(
        response: NutritionPlanContract.Response,
        range: DateRange,
        weekStart: LocalDate = NutritionPlanPeriod.monday(range.start),
        firstDate: LocalDate? = null,
        periodWeeks: Int? = null,
    ): String? = NutritionPlanContract.validateBusiness(
        response = response,
        expectedWeekStart = weekStart,
        targets = targets,
        expectedFirstDate = firstDate,
        expectedPeriodWeeks = periodWeeks,
        expectedDateRange = range,
    ).exceptionOrNull()?.message

    private fun legacyGate(
        response: NutritionPlanContract.Response,
        firstDate: LocalDate,
        periodWeeks: Int,
        weekStart: LocalDate = monday,
    ): String? = NutritionPlanContract.validateBusiness(
        response = response,
        expectedWeekStart = weekStart,
        targets = targets,
        expectedFirstDate = firstDate,
        expectedPeriodWeeks = periodWeeks,
    ).exceptionOrNull()?.message

    private fun recovery(range: DateRange) = CalorieRecoveryEngine.plan(
        dateRange = range,
        today = range.start,
        baseTargets = targets,
        weightKg = 80.0,
        goal = LocalCalculationEngine.Goal.MAINTENANCE,
        credits = emptyList(),
    )

    private val mfp2 by lazy { FoodCatalogPlanBuilder(FoodCatalogPlanFixtures.catalog) }

    private fun mfp2Context(
        monday: LocalDate,
        generationStart: LocalDate,
        periodWeeks: Int,
        dates: List<LocalDate>,
        requestedRange: DateRange? = null,
    ) = FoodCatalogPlanBuilder.Context(
        monday = monday,
        generationStart = generationStart,
        periodWeeks = periodWeeks,
        targets = FoodCatalogPlanFixtures.targets(),
        dailyTargets = dates.associate { it.toEpochDay() to FoodCatalogPlanFixtures.targets() },
        dietaryProfile = DietaryProfile(),
        sportsMode = SportsNutritionClassifier.Mode.NORMAL,
        mealsPerDay = FoodCatalogPlanFixtures.MEALS_PER_DAY,
        maintenanceCeilingKcal = null,
        requestedRange = requestedRange,
    )

    private fun mfp2RangeContext(range: DateRange) = mfp2Context(
        monday = NutritionPlanPeriod.monday(range.start),
        generationStart = range.start,
        periodWeeks = NutritionPlanPeriod.calendarWeeksSpanned(range),
        dates = NutritionPlanPeriod.dates(range),
        requestedRange = range,
    )

    /** Busta MFP2 multi-giorno con i pasti standard del fixture (stessa grammatica di [FoodCatalogPlanFixtures.payload]). */
    private fun mfp2Envelope(dates: List<LocalDate>, weekStart: Long): String = FoodCatalogPlanFixtures.envelope(
        buildList {
            add("MFP2")
            add("W|$weekStart")
            dates.forEach { date ->
                add("D|${date.toEpochDay()}")
                FoodCatalogPlanFixtures.standardMeals.forEach { meal ->
                    add("M|${meal.type}|${meal.title}|${meal.time}|${meal.preparation}")
                    meal.items.forEach { (id, grams) -> add("I|$id|$grams") }
                }
                add("H|Bevi con regolarita' durante la giornata")
            }
            add("V|1|target rispettati")
        }.joinToString("\n"),
    )

    private fun responseFor(range: DateRange): NutritionPlanContract.Response =
        responseFor(NutritionPlanPeriod.dates(range), NutritionPlanPeriod.monday(range.start))

    private fun responseFor(dates: List<LocalDate>, weekStart: LocalDate): NutritionPlanContract.Response {
        val ingredient = NutritionPlanContract.GeneratedIngredient(
            name = "Riso",
            quantity = 1f,
            unit = "g",
            displayDose = "1 g",
            weightState = "",
            nutritionConfidence = "MEDIUM",
            category = "OTHER",
        )
        val meals = List(5) { index ->
            NutritionPlanContract.GeneratedMeal(
                type = "meal_$index",
                title = "Pasto $index",
                timeMinutes = index * 180,
                kcal = 480,
                proteinG = 32f,
                carbsG = 56f,
                fatG = 14f,
                preparation = "Preparazione semplice.",
                ingredients = listOf(ingredient),
            )
        }
        return NutritionPlanContract.Response(
            weekStartEpochDay = weekStart.toEpochDay(),
            days = dates.map { date ->
                NutritionPlanContract.GeneratedDay(
                    dateEpochDay = date.toEpochDay(),
                    totalKcal = targets.kcal.toInt(),
                    proteinG = targets.proteinG.toFloat(),
                    carbsG = targets.carbsG.toFloat(),
                    fatG = targets.fatG.toFloat(),
                    meals = meals,
                )
            },
            agentValidation = NutritionPlanContract.AgentValidation(valid = true, notes = ""),
        )
    }
}
