package com.myfitai.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.repository.MealPlanRepository.PlanVersionBaseline
import com.myfitai.app.domain.food.DateRange
import com.myfitai.app.domain.food.NutritionPlanPeriod
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

/** Range save/read on an isolated file-backed Room database with synthetic data only. */
@RunWith(AndroidJUnit4::class)
class PlanRangeRepositoryTest {
    private lateinit var db: MyFitAiDatabase
    private lateinit var plans: MealPlanRepository
    private lateinit var profile: UserProfileEntity
    private lateinit var testDbName: String
    private val now = 1_790_000_000_000L

    private val w1 = LocalDate.of(2026, 10, 26)
    private val w2 = LocalDate.of(2026, 11, 2)
    private val w3 = LocalDate.of(2026, 11, 9)
    private val w4 = LocalDate.of(2026, 11, 16)

    /** Thu 29/10 - Tue 10/11/2026: Thu-Sun of W1, all of W2, Mon-Tue of W3. */
    private val range = DateRange(LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 10))

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDbName = "plan-range-test-${UUID.randomUUID()}.db"
        db = Room.databaseBuilder(context, MyFitAiDatabase::class.java, testDbName).allowMainThreadQueries().build()
        plans = MealPlanRepository(db)
        val profileId = UserProfileRepository(db).create(
            UserProfileEntity(
                name = "Profilo range test",
                birthDateEpochDay = 7_000L,
                heightCm = 170f,
                currentWeightKg = 70f,
                goal = "Mantenimento",
                activityLevel = "Moderato",
                wakeTimeMinutes = 420,
                sleepTimeMinutes = 1_380,
                dietaryPreferencesJson = null,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            ),
        )
        profile = db.userProfileDao().get(profileId)!!
    }

    @After
    fun tearDown() {
        try {
            if (::db.isInitialized) db.close()
        } finally {
            if (::testDbName.isInitialized) {
                val context = ApplicationProvider.getApplicationContext<Context>()
                listOf(testDbName, "$testDbName-wal", "$testDbName-shm").forEach { fileName ->
                    val file = context.getDatabasePath(fileName)
                    check(!file.exists() || file.delete()) { "Could not delete test database file: ${file.name}" }
                }
            }
        }
    }

    @Test
    fun commitOverThreeWeeks_appendsOnlyRangeDaysAndKeepsHistory() = runBlocking {
        val old1 = seedWeek(w1, weekDates(w1), "Vecchio W1")
        val old2 = seedWeek(w2, weekDates(w2), "Vecchio W2")
        val old2b = plans.appendVersion(profile.id, old2.planId, now + 1, draft(weekDates(w2), "Vecchio W2 bis"))
        val old3 = seedWeek(w3, weekDates(w3), "Vecchio W3")

        val baseline = plans.loadRangeBaseline(profile.id, range)
        assertEquals(
            mapOf(
                w1.toEpochDay() to PlanVersionBaseline(old1.planId, old1.versionId),
                w2.toEpochDay() to PlanVersionBaseline(old2.planId, old2b),
                w3.toEpochDay() to PlanVersionBaseline(old3.planId, old3.versionId),
            ),
            baseline.weeks,
        )

        val commits = commit(range, baseline)

        assertEquals(listOf(w1, w2, w3).map { it.toEpochDay() }, commits.map { it.weekStartEpochDay })
        assertEquals(listOf(old1.planId, old2.planId, old3.planId), commits.map { it.planId })
        assertEquals("no extra week rows", 3, plans.plans(profile.id).first().size)
        assertLatestRangeVersion(w1, commits[0].versionId, 2, dates(LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 1)))
        assertLatestRangeVersion(w2, commits[1].versionId, 3, weekDates(w2))
        assertLatestRangeVersion(w3, commits[2].versionId, 2, dates(w3, w3.plusDays(1)))
        // History: every previous version is still stored, unchanged.
        assertVersionDays(old1.versionId, weekDates(w1), "Vecchio W1")
        assertVersionDays(old2.versionId, weekDates(w2), "Vecchio W2")
        assertVersionDays(old2b, weekDates(w2), "Vecchio W2 bis")
        assertVersionDays(old3.versionId, weekDates(w3), "Vecchio W3")
        assertEquals(listOf(2, 3, 2), listOf(old1, old2, old3).map { plans.versions(profile.id, it.planId).first().size })
    }

    @Test
    fun commit_createsTheWeekRowWhenMissing() = runBlocking {
        // Sat 14/11 - Mon 16/11/2026: no plan exists for W3 or W4.
        val shortRange = DateRange(LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 16))
        val baseline = plans.loadRangeBaseline(profile.id, shortRange)
        assertEquals(
            mapOf(w3.toEpochDay() to PlanVersionBaseline(null, null), w4.toEpochDay() to PlanVersionBaseline(null, null)),
            baseline.weeks,
        )

        val commits = commit(shortRange, baseline)

        assertEquals(2, plans.plans(profile.id).first().size)
        assertLatestRangeVersion(w3, commits[0].versionId, 1, dates(LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 15)))
        assertLatestRangeVersion(w4, commits[1].versionId, 1, listOf(w4))
    }

    @Test
    fun staleIntermediateWeek_writesNothing() = runBlocking {
        val old1 = seedWeek(w1, weekDates(w1), "Vecchio W1")
        val old2 = seedWeek(w2, weekDates(w2), "Vecchio W2")
        val old3 = seedWeek(w3, weekDates(w3), "Vecchio W3")
        val baseline = plans.loadRangeBaseline(profile.id, range)
        // A concurrent adaptation appends to the intermediate week after the baseline was captured.
        val concurrent = plans.appendVersion(
            profile.id, old2.planId, now + 5,
            draft(weekDates(w2), "Adattamento").copy(source = "CHEAT_ADJUSTMENT"),
        )
        val before = versionCountsByWeek()
        var domainGuardRan = false

        val error = runCatching { commit(range, baseline, validateBeforeAppend = { domainGuardRan = true }) }.exceptionOrNull()

        assertTrue("Expected PlanSnapshotChanged, got $error", error is MealPlanRepository.PlanSnapshotChanged)
        assertEquals("PLAN_SNAPSHOT_STALE", error?.message)
        assertFalse("the final domain guard runs only after every baseline matched", domainGuardRan)
        assertEquals(before, versionCountsByWeek())
        assertEquals(old1.versionId, latestVersionId(w1))
        assertEquals(concurrent, latestVersionId(w2))
        assertEquals(old3.versionId, latestVersionId(w3))
    }

    @Test
    fun weekCreatedConcurrently_isStaleAndWritesNothing() = runBlocking {
        val old1 = seedWeek(w1, weekDates(w1), "Vecchio W1")
        val old2 = seedWeek(w2, weekDates(w2), "Vecchio W2")
        val baseline = plans.loadRangeBaseline(profile.id, range)
        assertEquals(PlanVersionBaseline(null, null), baseline.weeks[w3.toEpochDay()])
        val concurrent = seedWeek(w3, listOf(w3), "Concorrente W3")
        val before = versionCountsByWeek()

        val error = runCatching { commit(range, baseline) }.exceptionOrNull()

        assertTrue("Expected PlanSnapshotChanged, got $error", error is MealPlanRepository.PlanSnapshotChanged)
        assertEquals(before, versionCountsByWeek())
        assertEquals(old1.versionId, latestVersionId(w1))
        assertEquals(old2.versionId, latestVersionId(w2))
        assertEquals(concurrent.versionId, latestVersionId(w3))
    }

    @Test
    fun failedWriteInTheLastWeek_rollsBackEveryWeek() = runBlocking {
        val old1 = seedWeek(w1, weekDates(w1), "Vecchio W1")
        val old2 = seedWeek(w2, weekDates(w2), "Vecchio W2")
        val baseline = plans.loadRangeBaseline(profile.id, range)
        val before = versionCountsByWeek()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_range_last_day BEFORE INSERT ON meal_plan_days " +
                "WHEN NEW.dateEpochDay = ${range.endInclusive.toEpochDay()} " +
                "BEGIN SELECT RAISE(ABORT, 'fixture range failure'); END",
        )

        val error = runCatching { commit(range, baseline) }.exceptionOrNull()

        assertTrue("the failing insert must surface", error != null)
        assertEquals(before, versionCountsByWeek())
        assertEquals(old1.versionId, latestVersionId(w1))
        assertEquals(old2.versionId, latestVersionId(w2))
        assertNull("the W3 row created inside the transaction is rolled back", plans.getPlanForWeek(profile.id, w3.toEpochDay()))
    }

    @Test
    fun staleProfile_writesNothing() = runBlocking {
        val baseline = plans.loadRangeBaseline(profile.id, range)
        db.userProfileDao().update(profile.copy(currentWeightKg = 71f, updatedAtEpochMillis = now + 1))

        val error = runCatching { commit(range, baseline) }.exceptionOrNull()

        assertTrue("Expected SafetySnapshotChanged, got $error", error is MealPlanRepository.SafetySnapshotChanged)
        assertTrue(plans.plans(profile.id).first().isEmpty())
    }

    @Test
    fun readRange_reportsHolesAndResolvesLegacyMultiWeekPlan() = runBlocking {
        // Legacy period plan stored once on W1 and covering W1+W2; W3 has no plan; W4 is planned Mon-Wed only.
        val legacy = seedWeek(w1, dates(w1, w1.plusDays(13)), "Periodo legacy")
        val partial = seedWeek(w4, dates(w4, w4.plusDays(2)), "Parziale W4")
        val readRange = DateRange(LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 20))

        val result = plans.loadRangeDays(profile.id, readRange)

        val legacyDates = dates(LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 8))
        val partialDates = dates(w4, w4.plusDays(2))
        assertEquals((legacyDates + partialDates).map { it.toEpochDay() }, result.days.map { it.dateEpochDay })
        assertEquals(
            (weekDates(w3) + dates(LocalDate.of(2026, 11, 19), LocalDate.of(2026, 11, 20))).map { it.toEpochDay() },
            result.uncoveredEpochDays,
        )
        assertCoversRangeOnce(readRange, result)
        result.days.forEach { assertEquals(it.dateEpochDay, it.day.dateEpochDay) }
        result.days.take(legacyDates.size).forEach { day ->
            assertEquals(legacy.planId, day.planId)
            assertEquals("legacy days keep the week they are stored on", w1.toEpochDay(), day.planWeekStartEpochDay)
            assertEquals(legacy.versionId, day.versionId)
            assertEquals("Periodo legacy", day.day.meals.single().title)
        }
        result.days.drop(legacyDates.size).forEach { day ->
            assertEquals(partial.planId, day.planId)
            assertEquals(partial.versionId, day.versionId)
            assertEquals("Parziale W4", day.day.meals.single().title)
        }
    }

    @Test
    fun rangeCommitOnLegacyCoveredWeek_leavesOutOfRangeDaysUnplanned() = runBlocking {
        val legacyDates = dates(w1, w1.plusDays(13))
        val legacy = seedWeek(w1, legacyDates, "Periodo legacy")
        // Wed 04/11 - Thu 05/11/2026: inside W2, which so far was covered by the legacy plan stored on W1.
        val commitRange = DateRange(LocalDate.of(2026, 11, 4), LocalDate.of(2026, 11, 5))
        val baseline = plans.loadRangeBaseline(profile.id, commitRange)
        assertEquals(mapOf(w2.toEpochDay() to PlanVersionBaseline(null, null)), baseline.weeks)
        assertEquals(
            mapOf(w2.toEpochDay() to PlanVersionBaseline(legacy.planId, legacy.versionId)),
            baseline.effectiveWeeks,
        )

        val commit = commit(commitRange, baseline).single()
        val result = plans.loadRangeDays(profile.id, DateRange(w1, w2.plusDays(6)))

        assertEquals(
            (weekDates(w1) + NutritionPlanPeriod.dates(commitRange)).map { it.toEpochDay() },
            result.days.map { it.dateEpochDay },
        )
        assertEquals(
            listOf(w2, w2.plusDays(1), w2.plusDays(4), w2.plusDays(5), w2.plusDays(6)).map { it.toEpochDay() },
            result.uncoveredEpochDays,
        )
        assertCoversRangeOnce(DateRange(w1, w2.plusDays(6)), result)
        result.days.filter { it.dateEpochDay >= w2.toEpochDay() }.forEach { day ->
            assertEquals(commit.planId, day.planId)
            assertEquals(w2.toEpochDay(), day.planWeekStartEpochDay)
            assertEquals(commit.versionId, day.versionId)
        }
        // The legacy plan itself is untouched.
        assertEquals(1, plans.versions(profile.id, legacy.planId).first().size)
        assertVersionDays(legacy.versionId, legacyDates, "Periodo legacy")
    }

    @Test
    fun legacySelectedVersionChangedAfterBaseline_isStaleAndWritesNothing() = runBlocking {
        val legacy = seedWeek(w1, dates(w1, w1.plusDays(13)), "Periodo legacy")
        val commitRange = DateRange(LocalDate.of(2026, 11, 4), LocalDate.of(2026, 11, 5))
        val baseline = plans.loadRangeBaseline(profile.id, commitRange)
        assertEquals(PlanVersionBaseline(null, null), baseline.weeks[w2.toEpochDay()])
        assertEquals(PlanVersionBaseline(legacy.planId, legacy.versionId), baseline.effectiveWeeks[w2.toEpochDay()])

        // A concurrent writer appends to the source actually displayed for W2, not to an exact W2 row.
        val concurrent = plans.appendVersion(
            profile.id,
            legacy.planId,
            now + 5,
            draft(dates(w1, w1.plusDays(13)), "Periodo legacy concorrente"),
        )
        val beforeCommit = versionCountsByWeek()

        val error = runCatching { commit(commitRange, baseline) }.exceptionOrNull()

        assertTrue("Expected PlanSnapshotChanged, got $error", error is MealPlanRepository.PlanSnapshotChanged)
        assertEquals("PLAN_SNAPSHOT_STALE", error?.message)
        assertEquals(beforeCommit, versionCountsByWeek())
        assertEquals("the legacy plan remains the only plan row", 1, plans.plans(profile.id).first().size)
        assertNull("stale commit must not create an exact W2 row", plans.getPlanForWeek(profile.id, w2.toEpochDay()))
        assertEquals(concurrent, plans.versions(profile.id, legacy.planId).first().first().id)
    }

    private suspend fun commit(
        target: DateRange,
        baseline: MealPlanRepository.RangePlanBaseline,
        validateBeforeAppend: suspend () -> Unit = {},
    ) = plans.commitRangeVersions(
        profileId = profile.id,
        range = target,
        createdAtEpochMillis = now + 10,
        draft = draft(NutritionPlanPeriod.dates(target), RANGE_TITLE),
        expectedProfileSnapshot = profile,
        expectedBaseline = baseline,
        revalidateBeforeWrite = {},
        validateBeforeAppend = validateBeforeAppend,
    )

    private suspend fun assertLatestRangeVersion(week: LocalDate, versionId: Long, versionNumber: Int, expected: List<LocalDate>) {
        val latest = plans.loadLatestSnapshot(profile.id, week.toEpochDay())!!
        assertEquals(versionId, latest.version.id)
        assertEquals(versionNumber, latest.version.versionNumber)
        assertEquals(expected.map { it.toEpochDay() }, latest.version.days.map { it.dateEpochDay })
        assertTrue(latest.version.days.all { day -> day.meals.single().title == RANGE_TITLE })
    }

    private suspend fun assertVersionDays(versionId: Long, expected: List<LocalDate>, title: String) {
        val dao = db.mealPlanDao()
        val days = dao.getDays(profile.id, versionId)
        assertEquals(expected.map { it.toEpochDay() }, days.map { it.dateEpochDay })
        days.forEach { day -> assertEquals(listOf(title), dao.getMeals(profile.id, day.id).map { it.title }) }
    }

    private fun assertCoversRangeOnce(target: DateRange, result: MealPlanRepository.RangePlanDays) {
        val covered = result.days.map { it.dateEpochDay }
        assertEquals("no duplicated day", covered.size, covered.toSet().size)
        assertTrue("covered and uncovered are disjoint", covered.none { it in result.uncoveredEpochDays })
        assertEquals(
            NutritionPlanPeriod.dates(target).map { it.toEpochDay() },
            (covered + result.uncoveredEpochDays).sorted(),
        )
    }

    private suspend fun latestVersionId(week: LocalDate): Long? =
        plans.loadLatestSnapshot(profile.id, week.toEpochDay())?.version?.id

    private suspend fun versionCountsByWeek(): Map<Long, Int> =
        plans.plans(profile.id).first().associate { it.weekStartEpochDay to plans.versions(profile.id, it.id).first().size }

    private data class Seeded(val planId: Long, val versionId: Long)

    private suspend fun seedWeek(week: LocalDate, days: List<LocalDate>, title: String): Seeded {
        val planId = plans.createPlan(profile.id, week.toEpochDay(), now)
        return Seeded(planId, plans.appendVersion(profile.id, planId, now, draft(days, title)))
    }

    private fun weekDates(monday: LocalDate) = dates(monday, monday.plusDays(6))

    private fun dates(from: LocalDate, to: LocalDate) = NutritionPlanPeriod.dates(DateRange(from, to))

    private fun draft(days: List<LocalDate>, title: String) = PlanVersionDraft(
        source = "TEST",
        reason = "RANGE_FIXTURE",
        targetKcal = 2000,
        targetProteinG = 120f,
        targetCarbsG = 250f,
        targetFatG = 60f,
        days = days.map { date ->
            DayDraft(
                dateEpochDay = date.toEpochDay(),
                totalKcal = 500,
                proteinG = 30f,
                carbsG = 60f,
                fatG = 15f,
                meals = listOf(
                    MealDraft(
                        type = "Pranzo",
                        title = title,
                        timeMinutes = 780,
                        kcal = 500,
                        proteinG = 30f,
                        carbsG = 60f,
                        fatG = 15f,
                        preparation = null,
                        ingredients = listOf(
                            IngredientDraft(
                                name = "Riso",
                                quantity = 80f,
                                unit = "g",
                                displayDose = "80 g",
                                weightState = "RAW",
                                nutritionConfidence = "HIGH",
                                category = "Cereali",
                            ),
                        ),
                    ),
                ),
            )
        },
    )

    private companion object {
        const val RANGE_TITLE = "Range"
    }
}
