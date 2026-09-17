package com.myfitai.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query

/** Read-only queries used to keep calorie-recovery planning idempotent across immutable plan versions. */
@Dao
interface CalorieRecoveryQueryDao {
    @Query(
        """SELECT EXISTS(
            SELECT 1 FROM meal_plan_versions v
            INNER JOIN meal_plans p ON p.id = v.planId
            WHERE p.profileId = :profileId AND v.reason = :reason
        )"""
    )
    suspend fun hasExactReason(profileId: Long, reason: String): Boolean

    /** Only the latest version of each other week is authoritative; historical versions must not double-spend recovery. */
    @Query(
        """SELECT v.reason FROM meal_plan_versions v
            INNER JOIN meal_plans p ON p.id = v.planId
            WHERE p.profileId = :profileId
              AND p.weekStartEpochDay != :currentWeekStartEpochDay
              AND v.id = (
                  SELECT v2.id FROM meal_plan_versions v2
                  WHERE v2.planId = p.id
                  ORDER BY v2.versionNumber DESC, v2.id DESC
                  LIMIT 1
              )
              AND v.reason LIKE '%' || :tokenPrefix || '%'
              AND v.reason IS NOT NULL
            ORDER BY p.weekStartEpochDay ASC
        """
    )
    suspend fun recoveryReasonsOutsideWeek(
        profileId: Long,
        currentWeekStartEpochDay: Long,
        tokenPrefix: String,
    ): List<String>
}
