package com.myfitai.app.data.repository

import androidx.room.withTransaction
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.dao.LegacyMealAlarmId
import com.myfitai.app.data.local.entity.*
import com.myfitai.app.domain.food.DateRange
import com.myfitai.app.domain.food.FoodPlanDay
import com.myfitai.app.domain.food.FoodPlanSnapshot
import com.myfitai.app.domain.food.FoodSupplement
import com.myfitai.app.domain.food.NutritionPlanPeriod
import com.myfitai.app.domain.food.PlanRangePartitioner
import com.myfitai.app.domain.safety.HealthDeclaration
import com.myfitai.app.domain.safety.HealthDeclarationCodec
import com.myfitai.app.domain.safety.HealthDeclarationDecodeResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/** In-memory CAS token. The raw local health JSON is deliberately omitted from formatting. */
class HealthDeclarationSnapshot internal constructor(
    val profileId: Long,
    val decoded: HealthDeclarationDecodeResult,
    private val rawJson: String?,
) {
    internal fun hasSameRawBaseline(other: HealthDeclarationSnapshot): Boolean =
        profileId == other.profileId && rawJson == other.rawJson

    internal fun rawJsonForCompareAndSet(): String? = rawJson

    override fun toString(): String = "HealthDeclarationSnapshot(profileId=$profileId, rawJson=<redacted>)"
}

class UserProfileRepository(private val db: MyFitAiDatabase) {
    val profiles: Flow<List<UserProfileEntity>> = db.userProfileDao().observeAll()
    fun profile(profileId: Long): Flow<UserProfileEntity?> = db.userProfileDao().observe(profileId)
    suspend fun get(profileId: Long) = db.userProfileDao().get(profileId)
    suspend fun getFirst() = db.userProfileDao().getFirst()
    suspend fun create(value: UserProfileEntity): Long = db.userProfileDao().insert(value)
    suspend fun update(value: UserProfileEntity) = db.withTransaction {
        val dao = db.userProfileDao()
        val current = dao.get(value.id) ?: return@withTransaction
        dao.update(value.copy(healthDeclarationJson = current.healthDeclarationJson))
    }
    /** Null means the profile does not exist; an existing profile with no declaration returns a snapshot decoding to Absent. */
    suspend fun healthDeclarationSnapshot(profileId: Long): HealthDeclarationSnapshot? {
        require(profileId > 0) { "profileId must be > 0" }
        val profile = db.userProfileDao().get(profileId) ?: return null
        return HealthDeclarationSnapshot(
            profileId = profileId,
            decoded = HealthDeclarationCodec.decode(profile.healthDeclarationJson),
            rawJson = profile.healthDeclarationJson,
        )
    }
    /** Compatibility read API; callers that write must retain [HealthDeclarationSnapshot] and use its CAS token. */
    suspend fun healthDeclaration(profileId: Long): HealthDeclarationDecodeResult? =
        healthDeclarationSnapshot(profileId)?.decoded

    /** Returns the committed snapshot, or null when the profile/baseline changed before the atomic update. */
    suspend fun compareAndSetHealthDeclaration(
        baseline: HealthDeclarationSnapshot,
        declaration: HealthDeclaration,
    ): HealthDeclarationSnapshot? {
        val profileId = baseline.profileId
        require(profileId > 0) { "profileId must be > 0" }
        val json = HealthDeclarationCodec.encode(declaration)
        val updated = db.userProfileDao().compareAndSetHealthDeclaration(
            profileId = profileId,
            expectedJson = baseline.rawJsonForCompareAndSet(),
            json = json,
        ) == 1
        if (!updated) return null
        return HealthDeclarationSnapshot(profileId, HealthDeclarationCodec.decode(json), json)
    }
    /** @return true se il profilo esiste ancora ed e' stato aggiornato; tocca solo `dietaryPreferencesJson` e `updatedAtEpochMillis`. */
    suspend fun updateDietaryPreferences(profileId: Long, json: String?, updatedAt: Long): Boolean =
        db.userProfileDao().updateDietaryPreferences(profileId, json, updatedAt) == 1
    suspend fun delete(value: UserProfileEntity) = db.userProfileDao().delete(value)
}

class BiaRepository(private val db: MyFitAiDatabase) {
    fun all(profileId: Long): Flow<List<BiaMeasurementEntity>> = db.biaMeasurementDao().observeAll(profileId)
    fun latest(profileId: Long): Flow<BiaMeasurementEntity?> = db.biaMeasurementDao().observeLatest(profileId)
    fun between(profileId: Long, from: Long, to: Long) = db.biaMeasurementDao().observeBetween(profileId, from, to)
    /** One transaction; date-level duplicates are skipped, never overwritten. */
    suspend fun importMissing(profileId: Long, readings: List<BiaMeasurementEntity>): Pair<Int, Int> = db.withTransaction {
        require(profileId > 0 && readings.all { it.profileId == profileId })
        val dao = db.biaMeasurementDao()
        val known = dao.getAll(profileId).mapTo(hashSetOf()) {
            com.myfitai.app.domain.body.BiaHistoryImportContract.dayKey(it.measuredAtEpochMillis)
        }
        var inserted = 0
        var skipped = 0
        readings.forEach { reading ->
            if (known.add(com.myfitai.app.domain.body.BiaHistoryImportContract.dayKey(reading.measuredAtEpochMillis))) {
                dao.insert(reading.copy(id = 0L))
                inserted++
            } else skipped++
        }
        inserted to skipped
    }

    suspend fun insert(value: BiaMeasurementEntity) = db.biaMeasurementDao().insert(value)
    suspend fun update(value: BiaMeasurementEntity) = db.biaMeasurementDao().update(value)
    /** Deletes the reading together with the AI analyses that refer to it, in one transaction: no orphan results. */
    suspend fun delete(value: BiaMeasurementEntity) = db.withTransaction {
        db.biaAnalysisResultDao().deleteForMeasurement(value.profileId, value.id)
        db.biaMeasurementDao().delete(value)
    }
}

class BiaAnalysisResultRepository(private val db: MyFitAiDatabase) {
    fun all(profileId: Long): Flow<List<BiaAnalysisResultEntity>> = db.biaAnalysisResultDao().observeAll(profileId)
    suspend fun latestForMeasurement(profileId: Long, measurementId: Long): BiaAnalysisResultEntity? =
        db.biaAnalysisResultDao().latestForMeasurement(profileId, measurementId)
    suspend fun insert(value: BiaAnalysisResultEntity): Long = db.biaAnalysisResultDao().insert(value)
}

class BodyMeasurementRepository(private val db: MyFitAiDatabase) {
    fun all(profileId: Long): Flow<List<BodyMeasurementEntity>> = db.bodyMeasurementDao().observeAll(profileId)
    fun latest(profileId: Long): Flow<BodyMeasurementEntity?> = db.bodyMeasurementDao().observeLatest(profileId)
    fun between(profileId: Long, from: Long, to: Long) = db.bodyMeasurementDao().observeBetween(profileId, from, to)
    suspend fun insert(value: BodyMeasurementEntity) = db.bodyMeasurementDao().insert(value)
    suspend fun update(value: BodyMeasurementEntity) = db.bodyMeasurementDao().update(value)
    suspend fun delete(value: BodyMeasurementEntity) = db.bodyMeasurementDao().delete(value)
}

class CheatEntryRepository(private val db: MyFitAiDatabase) {
    fun all(profileId: Long): Flow<List<CheatEntryEntity>> = db.cheatEntryDao().observeAll(profileId)
    fun between(profileId: Long, from: Long, to: Long) = db.cheatEntryDao().observeBetween(profileId, from, to)
    suspend fun insert(value: CheatEntryEntity) = db.cheatEntryDao().insert(value)
    suspend fun update(value: CheatEntryEntity) = db.cheatEntryDao().update(value)
    suspend fun delete(value: CheatEntryEntity) = db.cheatEntryDao().delete(value)
}

class FoodConsumptionRepository(private val db: MyFitAiDatabase) {
    fun all(profileId: Long): Flow<List<FoodConsumptionEntity>> = db.foodConsumptionDao().observeAll(profileId)
    fun forDay(profileId: Long, dateEpochDay: Long): Flow<List<FoodConsumptionEntity>> = db.foodConsumptionDao().observeForDay(profileId, dateEpochDay)
    fun forVersionDay(profileId: Long, planVersionId: Long, dateEpochDay: Long): Flow<List<FoodConsumptionEntity>> = db.foodConsumptionDao().observeForVersionDay(profileId, planVersionId, dateEpochDay)
    suspend fun getForItem(profileId: Long, planVersionId: Long, itemKey: String) = db.foodConsumptionDao().getForItem(profileId, planVersionId, itemKey)
    fun observeForItem(profileId: Long, planVersionId: Long, itemKey: String): Flow<FoodConsumptionEntity?> = db.foodConsumptionDao().observeForItem(profileId, planVersionId, itemKey)
    suspend fun upsert(value: FoodConsumptionEntity): Long = db.foodConsumptionDao().upsert(value)
    suspend fun deleteForItem(profileId: Long, planVersionId: Long, itemKey: String) = db.foodConsumptionDao().deleteForItem(profileId, planVersionId, itemKey)
    suspend fun deleteByProfile(profileId: Long) = db.foodConsumptionDao().deleteByProfile(profileId)
}

class WeeklyReviewRepository(private val db: MyFitAiDatabase) {
    fun all(profileId: Long): Flow<List<WeeklyReviewEntity>> = db.weeklyReviewDao().observeAll(profileId)
    suspend fun getForWeek(profileId: Long, weekStartEpochDay: Long) = db.weeklyReviewDao().getForWeek(profileId, weekStartEpochDay)
    suspend fun upsert(value: WeeklyReviewEntity) = db.weeklyReviewDao().upsert(value)
}

class BodyExpectationGoalRepository(private val db: MyFitAiDatabase) {
    fun all(profileId: Long): Flow<List<BodyExpectationGoalEntity>> = db.bodyExpectationGoalDao().observeAll(profileId)
    suspend fun getForPeriod(profileId: Long, periodStartEpochDay: Long) = db.bodyExpectationGoalDao().getForPeriod(profileId, periodStartEpochDay)
    suspend fun upsert(value: BodyExpectationGoalEntity): Long = db.bodyExpectationGoalDao().upsert(value)
}

data class IngredientDraft(
    val name: String,
    val quantity: Float,
    val unit: String,
    val displayDose: String?,
    val weightState: String?,
    val nutritionConfidence: String?,
    val category: String?,
    /** Snapshot nutrizionale dal catalogo (migrazione 22); null per le bozze create da piani precedenti. */
    val foodId: String? = null,
    val grams: Float? = null,
    val kcal: Float? = null,
    val proteinG: Float? = null,
    val carbsG: Float? = null,
    val fatG: Float? = null,
    val fiberG: Float? = null,
    val sodiumMg: Float? = null,
)

data class MealDraft(
    val type: String,
    val title: String,
    val timeMinutes: Int?,
    val kcal: Int?,
    val proteinG: Float?,
    val carbsG: Float?,
    val fatG: Float?,
    val preparation: String?,
    val ingredients: List<IngredientDraft>,
    val nutritionSource: String = com.myfitai.app.domain.food.FoodMeal.NUTRITION_SOURCE_DECLARED,
)

data class SupplementDraft(
    val kind: String,
    val name: String,
    val dose: Float,
    val unit: String,
    val timeMinutes: Int?,
    val kcal: Int,
    val proteinG: Float,
    val carbsG: Float,
    val fatG: Float,
    val notes: String?,
)

data class DayDraft(
    val dateEpochDay: Long,
    val totalKcal: Int?,
    val proteinG: Float?,
    val carbsG: Float?,
    val fatG: Float?,
    val meals: List<MealDraft>,
    val supplements: List<SupplementDraft> = emptyList(),
    val hydrationNote: String? = null,
    val targetKcal: Int? = null,
    val targetProteinG: Float? = null,
    val targetCarbsG: Float? = null,
    val targetFatG: Float? = null,
    val baseTargetKcal: Int? = null,
)

data class PlanVersionDraft(
    val source: String,
    val reason: String?,
    val targetKcal: Int?,
    val targetProteinG: Float?,
    val targetCarbsG: Float?,
    val targetFatG: Float?,
    val days: List<DayDraft>,
    val appValidationJson: String? = null,
    /** Solo la generazione la imposta; adattamenti e scambi ereditano quella della versione da cui derivano. */
    val inputsFingerprint: String? = null,
    /** Versione del catalogo alimenti con cui sono stati calcolati i macro; null per le versioni dichiarate dal modello. */
    val catalogVersion: String? = null,
)

data class LegacyMealAlarmId(val versionId: Long, val mealId: Long)

/** Pure plan-for-a-week rule shared by Food and Home, kept free of Room so it is unit-testable. */
object MealPlanWeekSelector {
    suspend fun <T : Any> pick(
        planWeekStarts: List<Long>,
        weekStartEpochDay: Long,
        load: suspend (Long) -> T?,
        coveredDays: (T) -> List<Long>,
    ): T? =
        planWeekStarts.firstOrNull { it == weekStartEpochDay }?.let { load(it) }
            ?: planWeekStarts.sortedDescending().firstNotNullOfOrNull { start ->
                load(start)?.takeIf { snapshot -> coveredDays(snapshot).any { it in weekStartEpochDay..(weekStartEpochDay + 6) } }
            }
}

class MealPlanRepository(private val db: MyFitAiDatabase) {
    data class PlanVersionBaseline(val planId: Long?, val versionId: Long?)

    data class GenerationPlanSnapshot(
        val baseline: PlanVersionBaseline,
        val snapshot: com.myfitai.app.domain.food.FoodPlanSnapshot?,
    )

    class SafetySnapshotChanged : IllegalStateException("SAFETY_PROFILE_CHANGED")
    class PlanSnapshotChanged : IllegalStateException("PLAN_SNAPSHOT_STALE")

    /** Per-week CAS token of [range]: Monday epoch day -> plan/version baseline, captured in one read transaction. */
    data class RangePlanBaseline(
        val profileId: Long,
        val range: DateRange,
        val weeks: Map<Long, PlanVersionBaseline>,
        /** Plan/version actually selected for each week by the same resolver used for range reads. */
        val effectiveWeeks: Map<Long, PlanVersionBaseline?> = weeks.mapValues { (_, baseline) ->
            baseline.takeIf { it.planId != null && it.versionId != null }
        },
    )

    data class RangeWeekCommit(val weekStartEpochDay: Long, val planId: Long, val versionId: Long)

    /**
     * Effective day of [dateEpochDay] with its provenance. [planWeekStartEpochDay] may precede the date's own week
     * when the day comes from a legacy multi-week plan stored on its first Monday.
     */
    data class RangePlanDay(
        val dateEpochDay: Long,
        val planId: Long,
        val planWeekStartEpochDay: Long,
        val versionId: Long,
        val versionNumber: Int,
        val day: FoodPlanDay,
    )

    /** At most one entry per range date, ascending; [uncoveredEpochDays] lists the range dates with no planned day. */
    data class RangePlanDays(
        val range: DateRange,
        val days: List<RangePlanDay>,
        val uncoveredEpochDays: List<Long>,
    )

    data class CheatPlanCommit(
        val cheatId: Long,
        val planVersionId: Long?,
        val alreadyCommitted: Boolean = false,
    )

    data class MealDetailContext(
        val planId: Long,
        val planVersionId: Long,
        val dayId: Long,
        val dateEpochDay: Long,
        val meal: com.myfitai.app.domain.food.FoodMeal,
    )
    data class HistoricalMealContext(
        val planId: Long,
        val weekStartEpochDay: Long,
        val planVersionId: Long,
        val versionNumber: Int,
        val dateEpochDay: Long,
        val meal: com.myfitai.app.domain.food.FoodMeal,
    )
    fun plans(profileId: Long): Flow<List<MealPlanEntity>> = db.mealPlanDao().observePlans(profileId)
    suspend fun legacyMealAlarmIds(profileId: Long): List<LegacyMealAlarmId> = db.mealPlanDao().getLegacyMealAlarmIds(profileId)
    fun versions(profileId: Long, planId: Long): Flow<List<MealPlanVersionEntity>> = db.mealPlanDao().observeVersions(profileId, planId)
    suspend fun getPlanForWeek(profileId: Long, weekStartEpochDay: Long) = db.mealPlanDao().getPlanForWeek(profileId, weekStartEpochDay)
    fun latestSnapshot(profileId: Long, weekStartEpochDay: Long): Flow<com.myfitai.app.domain.food.FoodPlanSnapshot?> =
        db.mealPlanDao().observeLatestVersionIdForWeek(profileId, weekStartEpochDay)
            .map { loadLatestSnapshot(profileId, weekStartEpochDay) }
    fun latestSnapshotForDisplayedWeek(profileId: Long, weekStartEpochDay: Long): Flow<com.myfitai.app.domain.food.FoodPlanSnapshot?> =
        // A regeneration appends a version without touching the plan row, so the version table is observed too.
        kotlinx.coroutines.flow.combine(
            db.mealPlanDao().observePlans(profileId),
            db.mealPlanDao().observeNewestVersionId(profileId),
        ) { plans, _ -> plans }.map { plans -> snapshotForDisplayedWeek(profileId, plans, weekStartEpochDay) }

    /**
     * Same rule for every screen (Food, Home): the plan of that week, otherwise the newest plan whose days
     * cover it (a multi-week period is stored once, on the first week).
     */
    suspend fun snapshotForDisplayedWeek(
        profileId: Long,
        plans: List<MealPlanEntity>,
        weekStartEpochDay: Long,
    ): com.myfitai.app.domain.food.FoodPlanSnapshot? = MealPlanWeekSelector.pick(
        planWeekStarts = plans.map { it.weekStartEpochDay },
        weekStartEpochDay = weekStartEpochDay,
        load = { loadLatestSnapshot(profileId, it) },
        coveredDays = { snapshot -> snapshot.version.days.map { it.dateEpochDay } },
    )

    suspend fun createPlan(profileId: Long, weekStartEpochDay: Long, createdAtEpochMillis: Long): Long =
        db.mealPlanDao().insertPlan(
            MealPlanEntity(
                profileId = profileId,
                createdAtEpochMillis = createdAtEpochMillis,
                weekStartEpochDay = weekStartEpochDay,
                status = "ACTIVE",
            )
        )

    /** Creates the week row, when needed, and its first version as one rollback-safe operation. */
    suspend fun createOrAppendVersion(
        profileId: Long,
        weekStartEpochDay: Long,
        createdAtEpochMillis: Long,
        draft: PlanVersionDraft,
        expectedProfileSnapshot: UserProfileEntity,
        expectedPlanBaseline: PlanVersionBaseline? = null,
        revalidateBeforeWrite: suspend () -> Unit,
        validateBeforeAppend: suspend () -> Unit = {},
    ): Pair<Long, Long> = db.withTransaction {
        if (expectedProfileSnapshot.id != profileId || db.userProfileDao().get(profileId) != expectedProfileSnapshot) {
            throw SafetySnapshotChanged()
        }
        revalidateBeforeWrite()
        if (db.userProfileDao().get(profileId) != expectedProfileSnapshot) throw SafetySnapshotChanged()
        val dao = db.mealPlanDao()
        val currentPlan = dao.getPlanForWeek(profileId, weekStartEpochDay)
        val currentVersion = currentPlan?.let { dao.getLatestVersion(profileId, it.id) }
        if (
            expectedPlanBaseline != null &&
            PlanVersionBaseline(currentPlan?.id, currentVersion?.id) != expectedPlanBaseline
        ) {
            throw PlanSnapshotChanged()
        }
        // Final domain guard after profile/safety/CAS rechecks, still before any plan or version write.
        validateBeforeAppend()
        val planId = currentPlan?.id
            ?: dao.insertPlan(
                MealPlanEntity(
                    profileId = profileId,
                    createdAtEpochMillis = createdAtEpochMillis,
                    weekStartEpochDay = weekStartEpochDay,
                    status = "ACTIVE",
                ),
            )
        val versionId = appendVersionInTransaction(profileId, planId, createdAtEpochMillis, draft, dao)
        planId to versionId
    }

    suspend fun appendVersion(
        profileId: Long,
        planId: Long,
        createdAtEpochMillis: Long,
        draft: PlanVersionDraft,
    ): Long = db.withTransaction {
        appendVersionInTransaction(profileId, planId, createdAtEpochMillis, draft, db.mealPlanDao())
    }

    /**
     * Commits cheat tracking and its optional adaptation together. A failed append rolls the cheat row back;
     * a failed safety recheck commits tracking only and never appends a plan version.
     */
    suspend fun recordCheatAndAppendVersion(
        entry: CheatEntryEntity,
        expectedProfileSnapshot: UserProfileEntity,
        planId: Long,
        expectedPlanVersionId: Long,
        createdAtEpochMillis: Long,
        draft: (cheatId: Long) -> PlanVersionDraft,
        revalidateBeforeWrite: suspend () -> Boolean,
    ): CheatPlanCommit = db.withTransaction {
        val profileId = entry.profileId
        require(profileId > 0 && expectedProfileSnapshot.id == profileId) { "PROFILE_ID_MISMATCH" }
        val profileDao = db.userProfileDao()
        val currentProfile = profileDao.get(profileId) ?: error("PROFILE_NOT_FOUND")
        findPreviouslyCommittedCheat(entry, planId)?.let { return@withTransaction it }

        val planDao = db.mealPlanDao()
        val isExpectedPlanVersion = planDao.getLatestVersion(profileId, planId)?.id == expectedPlanVersionId
        val safetySnapshotIsCurrent = revalidateBeforeWrite()
        val canAppend = currentProfile == expectedProfileSnapshot && isExpectedPlanVersion && safetySnapshotIsCurrent &&
            profileDao.get(profileId) == expectedProfileSnapshot
        val cheatId = db.cheatEntryDao().insert(entry)
        val versionId = if (canAppend) {
            appendVersionInTransaction(profileId, planId, createdAtEpochMillis, draft(cheatId), db.mealPlanDao())
        } else {
            null
        }
        CheatPlanCommit(cheatId, versionId)
    }

    /** Stores tracking-only outcomes atomically and returns an existing event on an exact replay. */
    suspend fun recordCheatTracking(entry: CheatEntryEntity): CheatPlanCommit = db.withTransaction {
        require(entry.profileId > 0) { "PROFILE_ID_MISMATCH" }
        findPreviouslyCommittedCheat(entry)?.let { return@withTransaction it }
        CheatPlanCommit(db.cheatEntryDao().insert(entry), null)
    }

    /**
     * Uses the persisted event payload plus its profile/time as an internal content key. No replay token is written
     * to user-visible data. `planVersionId` is excluded because a successful replay observes its own new version.
     */
    private suspend fun findPreviouslyCommittedCheat(
        entry: CheatEntryEntity,
        knownPlanId: Long? = null,
    ): CheatPlanCommit? {
        val existing = db.cheatEntryDao()
            .observeBetween(entry.profileId, entry.occurredAtEpochMillis, entry.occurredAtEpochMillis)
            .first()
            .firstOrNull { row -> row.copy(id = entry.id, planVersionId = entry.planVersionId) == entry }
            ?: return null
        val versionId = if (knownPlanId != null) {
            db.mealPlanDao().observeVersions(entry.profileId, knownPlanId).first()
                .firstOrNull { it.reason == "CHEAT_WEEKLY_RESERVOIR:${existing.id}" }
                ?.id
        } else {
            val marker = "CHEAT_WEEKLY_RESERVOIR:${existing.id}"
            var found: Long? = null
            for (plan in db.mealPlanDao().observePlans(entry.profileId).first()) {
                found = db.mealPlanDao().observeVersions(entry.profileId, plan.id).first()
                    .firstOrNull { it.reason == marker }
                    ?.id
                if (found != null) break
            }
            found
        }
        return CheatPlanCommit(existing.id, versionId, alreadyCommitted = true)
    }

    private suspend fun appendVersionInTransaction(
        profileId: Long,
        planId: Long,
        createdAtEpochMillis: Long,
        draft: PlanVersionDraft,
        dao: com.myfitai.app.data.local.dao.MealPlanDao,
    ): Long {
        val plan = dao.getPlan(profileId, planId) ?: error("PLAN_NOT_FOUND_FOR_PROFILE")
        val previous = dao.getLatestVersion(profileId, plan.id)
        val nextVersion = (previous?.versionNumber ?: 0) + 1
        val versionId = dao.insertVersion(
            MealPlanVersionEntity(
                planId = planId,
                versionNumber = nextVersion,
                createdAtEpochMillis = createdAtEpochMillis,
                source = draft.source,
                reason = draft.reason,
                targetKcal = draft.targetKcal,
                targetProteinG = draft.targetProteinG,
                targetCarbsG = draft.targetCarbsG,
                targetFatG = draft.targetFatG,
                appValidationJson = draft.appValidationJson,
                inputsFingerprint = draft.inputsFingerprint ?: previous?.inputsFingerprint,
                catalogVersion = draft.catalogVersion,
            )
        )

        draft.days.forEach { day ->
            val dayId = dao.insertDays(listOf(MealPlanDayEntity(
                versionId = versionId,
                dateEpochDay = day.dateEpochDay,
                totalKcal = day.totalKcal,
                proteinG = day.proteinG,
                carbsG = day.carbsG,
                fatG = day.fatG,
                targetKcal = day.targetKcal,
                targetProteinG = day.targetProteinG,
                targetCarbsG = day.targetCarbsG,
                targetFatG = day.targetFatG,
                baseTargetKcal = day.baseTargetKcal,
                supplementsJson = serializeSupplements(day.supplements),
                hydrationNote = day.hydrationNote,
            ))).single()

            day.meals.forEachIndexed { mealIndex, meal ->
                val mealId = dao.insertMeals(listOf(meal.toEntity(dayId = dayId, sortOrder = mealIndex))).single()

                if (meal.ingredients.isNotEmpty()) {
                    dao.insertIngredients(meal.ingredients.mapIndexed { ingredientIndex, ingredient ->
                        ingredient.toEntity(mealId = mealId, sortOrder = ingredientIndex)
                    })
                }
            }
        }
        return versionId
    }

    suspend fun loadLatestSnapshot(profileId: Long, weekStartEpochDay: Long): com.myfitai.app.domain.food.FoodPlanSnapshot? = db.withTransaction {
        val dao = db.mealPlanDao()
        val plan = dao.getPlanForWeek(profileId, weekStartEpochDay) ?: return@withTransaction null
        val version = dao.getLatestVersion(profileId, plan.id) ?: return@withTransaction null
        loadPlanSnapshot(profileId, plan, version, dao)
    }

    /** Captures the exact plan/version token and the data used by generation in one Room read transaction. */
    suspend fun loadGenerationSnapshot(profileId: Long, weekStartEpochDay: Long): GenerationPlanSnapshot = db.withTransaction {
        val dao = db.mealPlanDao()
        val plan = dao.getPlanForWeek(profileId, weekStartEpochDay)
        val version = plan?.let { dao.getLatestVersion(profileId, it.id) }
        GenerationPlanSnapshot(
            baseline = PlanVersionBaseline(plan?.id, version?.id),
            snapshot = if (plan != null && version != null) loadPlanSnapshot(profileId, plan, version, dao) else null,
        )
    }

    /** Captures the plan/version token of every week touched by [range] in one Room read transaction. */
    suspend fun loadRangeBaseline(profileId: Long, range: DateRange): RangePlanBaseline = db.withTransaction {
        val dao = db.mealPlanDao()
        val weekStarts = PlanRangePartitioner.weekStarts(range)
        RangePlanBaseline(
            profileId = profileId,
            range = range,
            weeks = weekStarts.associateWith { currentWeekBaseline(profileId, it, dao) },
            effectiveWeeks = currentEffectiveWeekBaselines(profileId, weekStarts, dao),
        )
    }

    /**
     * Range regeneration replaces the touched weeks: every touched Monday gets a new version (its week row is created
     * when missing) holding only the range days of that week; previous versions are never modified or deleted.
     * Profile, safety and the baselines of all weeks are rechecked in one transaction, as in [createOrAppendVersion]:
     * every version is appended or none is.
     *
     * @throws PlanRangePartitioner.CoverageMismatch or [IllegalArgumentException] before any database access
     * @throws SafetySnapshotChanged / [PlanSnapshotChanged] with no write
     */
    suspend fun commitRangeVersions(
        profileId: Long,
        range: DateRange,
        createdAtEpochMillis: Long,
        draft: PlanVersionDraft,
        expectedProfileSnapshot: UserProfileEntity,
        expectedBaseline: RangePlanBaseline,
        revalidateBeforeWrite: suspend () -> Unit,
        validateBeforeAppend: suspend () -> Unit = {},
    ): List<RangeWeekCommit> {
        val weeks = PlanRangePartitioner.partition(range, draft)
        val expectedWeekStarts = weeks.mapTo(hashSetOf()) { it.weekStartEpochDay }
        require(
            expectedBaseline.profileId == profileId && expectedBaseline.range == range &&
                expectedBaseline.weeks.keys == expectedWeekStarts &&
                expectedBaseline.effectiveWeeks.keys == expectedWeekStarts,
        ) { "PLAN_RANGE_BASELINE_MISMATCH" }
        return db.withTransaction {
            if (expectedProfileSnapshot.id != profileId || db.userProfileDao().get(profileId) != expectedProfileSnapshot) {
                throw SafetySnapshotChanged()
            }
            revalidateBeforeWrite()
            if (db.userProfileDao().get(profileId) != expectedProfileSnapshot) throw SafetySnapshotChanged()
            val dao = db.mealPlanDao()
            val currentEffectiveWeeks = currentEffectiveWeekBaselines(
                profileId,
                weeks.map { it.weekStartEpochDay },
                dao,
            )
            // Both the exact row and the resolver-selected source are compared before the first write.
            // A stale intermediate or legacy-backed week leaves every week untouched.
            weeks.forEach { week ->
                val weekStart = week.weekStartEpochDay
                if (
                    currentWeekBaseline(profileId, weekStart, dao) != expectedBaseline.weeks[weekStart] ||
                    currentEffectiveWeeks[weekStart] != expectedBaseline.effectiveWeeks[weekStart]
                ) {
                    throw PlanSnapshotChanged()
                }
            }
            validateBeforeAppend()
            weeks.map { week ->
                val planId = dao.getPlanForWeek(profileId, week.weekStartEpochDay)?.id
                    ?: dao.insertPlan(
                        MealPlanEntity(
                            profileId = profileId,
                            createdAtEpochMillis = createdAtEpochMillis,
                            weekStartEpochDay = week.weekStartEpochDay,
                            status = "ACTIVE",
                        ),
                    )
                RangeWeekCommit(
                    weekStartEpochDay = week.weekStartEpochDay,
                    planId = planId,
                    versionId = appendVersionInTransaction(profileId, planId, createdAtEpochMillis, week.draft, dao),
                )
            }
        }
    }

    /**
     * Effective day of every date in [range] with its plan/version provenance, using the Food/Home week rule
     * ([MealPlanWeekSelector]): the exact week plan first, otherwise the newest multi-week plan covering that week.
     * One read transaction keeps all weeks consistent.
     */
    suspend fun loadRangeDays(profileId: Long, range: DateRange): RangePlanDays = db.withTransaction {
        val dao = db.mealPlanDao()
        val planWeekStarts = dao.getPlans(profileId).map { it.weekStartEpochDay }
        val latestByPlanWeek = HashMap<Long, FoodPlanSnapshot?>()
        val selectedByWeek = HashMap<Long, FoodPlanSnapshot?>()
        val days = mutableListOf<RangePlanDay>()
        val uncovered = mutableListOf<Long>()
        NutritionPlanPeriod.dates(range).forEach { date ->
            val epochDay = date.toEpochDay()
            val week = NutritionPlanPeriod.monday(date).toEpochDay()
            if (week !in selectedByWeek) {
                selectedByWeek[week] = MealPlanWeekSelector.pick(
                    planWeekStarts = planWeekStarts,
                    weekStartEpochDay = week,
                    load = { start ->
                        if (start !in latestByPlanWeek) latestByPlanWeek[start] = latestSnapshotInTransaction(profileId, start, dao)
                        latestByPlanWeek[start]
                    },
                    coveredDays = { snapshot -> snapshot.version.days.map { it.dateEpochDay } },
                )
            }
            val snapshot = selectedByWeek[week]
            val day = snapshot?.version?.days?.firstOrNull { it.dateEpochDay == epochDay }
            if (snapshot != null && day != null) {
                days += RangePlanDay(
                    dateEpochDay = epochDay,
                    planId = snapshot.planId,
                    planWeekStartEpochDay = snapshot.weekStartEpochDay,
                    versionId = snapshot.version.id,
                    versionNumber = snapshot.version.versionNumber,
                    day = day,
                )
            } else {
                uncovered += epochDay
            }
        }
        RangePlanDays(range, days, uncovered)
    }

    private suspend fun currentWeekBaseline(
        profileId: Long,
        weekStartEpochDay: Long,
        dao: com.myfitai.app.data.local.dao.MealPlanDao,
    ): PlanVersionBaseline {
        val plan = dao.getPlanForWeek(profileId, weekStartEpochDay)
        return PlanVersionBaseline(plan?.id, plan?.let { dao.getLatestVersion(profileId, it.id) }?.id)
    }

    /** Captures the exact plans selected for the requested weeks by the Food/Home resolver rule. */
    private suspend fun currentEffectiveWeekBaselines(
        profileId: Long,
        weekStarts: List<Long>,
        dao: com.myfitai.app.data.local.dao.MealPlanDao,
    ): Map<Long, PlanVersionBaseline?> {
        val planWeekStarts = dao.getPlans(profileId).map { it.weekStartEpochDay }
        val latestByPlanWeek = HashMap<Long, FoodPlanSnapshot?>()
        return weekStarts.associateWith { weekStart ->
            val snapshot = MealPlanWeekSelector.pick(
                planWeekStarts = planWeekStarts,
                weekStartEpochDay = weekStart,
                load = { start ->
                    if (start !in latestByPlanWeek) {
                        latestByPlanWeek[start] = latestSnapshotInTransaction(profileId, start, dao)
                    }
                    latestByPlanWeek[start]
                },
                coveredDays = { selected -> selected.version.days.map { it.dateEpochDay } },
            )
            snapshot?.let { PlanVersionBaseline(it.planId, it.version.id) }
        }
    }

    private suspend fun latestSnapshotInTransaction(
        profileId: Long,
        weekStartEpochDay: Long,
        dao: com.myfitai.app.data.local.dao.MealPlanDao,
    ): FoodPlanSnapshot? {
        val plan = dao.getPlanForWeek(profileId, weekStartEpochDay) ?: return null
        val version = dao.getLatestVersion(profileId, plan.id) ?: return null
        return loadPlanSnapshot(profileId, plan, version, dao)
    }

    private suspend fun loadPlanSnapshot(
        profileId: Long,
        plan: MealPlanEntity,
        version: MealPlanVersionEntity,
        dao: com.myfitai.app.data.local.dao.MealPlanDao,
    ): com.myfitai.app.domain.food.FoodPlanSnapshot {
        val days = dao.getDays(profileId, version.id).map { day ->
            com.myfitai.app.domain.food.FoodPlanDay(
                id = day.id,
                dateEpochDay = day.dateEpochDay,
                totalKcal = day.totalKcal,
                proteinG = day.proteinG,
                carbsG = day.carbsG,
                fatG = day.fatG,
                targetKcal = day.targetKcal,
                targetProteinG = day.targetProteinG,
                targetCarbsG = day.targetCarbsG,
                targetFatG = day.targetFatG,
                baseTargetKcal = day.baseTargetKcal,
                meals = dao.getMeals(profileId, day.id).map { meal ->
                    meal.toFoodMeal(dao.getIngredients(profileId, meal.id).map { it.toFoodIngredient() })
                },
                supplements = parseSupplements(day.supplementsJson),
                hydrationNote = day.hydrationNote,
            )
        }
        return com.myfitai.app.domain.food.FoodPlanSnapshot(
            planId = plan.id,
            profileId = plan.profileId,
            weekStartEpochDay = plan.weekStartEpochDay,
            version = com.myfitai.app.domain.food.FoodPlanVersion(
                id = version.id,
                versionNumber = version.versionNumber,
                createdAtEpochMillis = version.createdAtEpochMillis,
                source = version.source,
                reason = version.reason,
                targetKcal = version.targetKcal,
                targetProteinG = version.targetProteinG,
                targetCarbsG = version.targetCarbsG,
                targetFatG = version.targetFatG,
                days = days,
                inputsFingerprint = version.inputsFingerprint,
                catalogVersion = version.catalogVersion,
                appValidationJson = version.appValidationJson,
            ),
        )
    }

    suspend fun getMealDetail(profileId: Long, mealId: Long): com.myfitai.app.domain.food.FoodMeal? = db.withTransaction {
        val dao = db.mealPlanDao()
        val meal = dao.getMeal(profileId, mealId) ?: return@withTransaction null
        meal.toFoodMeal(dao.getIngredients(profileId, meal.id).map { it.toFoodIngredient() })
    }

    suspend fun getMealContext(profileId: Long, mealId: Long): MealDetailContext? = db.withTransaction {
        val dao = db.mealPlanDao()
        val row = dao.getMealContext(profileId, mealId) ?: return@withTransaction null
        val ingredients = dao.getIngredients(profileId, mealId).map { it.toFoodIngredient() }
        MealDetailContext(
            planId = row.planId,
            planVersionId = row.planVersionId,
            dayId = row.dayId,
            dateEpochDay = row.dateEpochDay,
            meal = row.toFoodMeal(ingredients),
        )
    }

    suspend fun historicalMeals(profileId: Long, excludedMealId: Long): List<HistoricalMealContext> = db.withTransaction {
        val dao = db.mealPlanDao()
        dao.getHistoricalMealRows(profileId, excludedMealId).map { row ->
            HistoricalMealContext(
                planId = row.planId,
                weekStartEpochDay = row.weekStartEpochDay,
                planVersionId = row.planVersionId,
                versionNumber = row.versionNumber,
                dateEpochDay = row.dateEpochDay,
                meal = row.toFoodMeal(dao.getIngredients(profileId, row.mealId).map { it.toFoodIngredient() }),
        )
    }
}

    private fun serializeSupplements(values: List<SupplementDraft>): String? {
        if (values.isEmpty()) return null
        val array = JSONArray()
        values.forEach { value ->
            array.put(JSONObject().apply {
                put("kind", value.kind)
                put("name", value.name)
                put("dose", value.dose.toDouble())
                put("unit", value.unit)
                if (value.timeMinutes == null) put("timeMinutes", JSONObject.NULL) else put("timeMinutes", value.timeMinutes)
                put("kcal", value.kcal)
                put("proteinG", value.proteinG.toDouble())
                put("carbsG", value.carbsG.toDouble())
                put("fatG", value.fatG.toDouble())
                put("notes", value.notes ?: "")
            })
        }
        return array.toString()
    }

    private fun parseSupplements(json: String?): List<FoodSupplement> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(FoodSupplement(
                        kind = item.optString("kind"),
                        name = item.optString("name"),
                        dose = item.optDouble("dose", 0.0).toFloat(),
                        unit = item.optString("unit"),
                        timeMinutes = if (item.isNull("timeMinutes")) null else item.optInt("timeMinutes"),
                        kcal = item.optInt("kcal", 0),
                        proteinG = item.optDouble("proteinG", 0.0).toFloat(),
                        carbsG = item.optDouble("carbsG", 0.0).toFloat(),
                        fatG = item.optDouble("fatG", 0.0).toFloat(),
                        notes = item.optString("notes").takeIf { it.isNotBlank() },
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }
}
