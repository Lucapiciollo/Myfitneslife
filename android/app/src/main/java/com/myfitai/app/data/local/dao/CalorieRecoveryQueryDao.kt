package com.myfitai.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query

/** Read-only queries used to keep calorie-recovery planning idempotent across plan versions. */
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

    @Query(
        """SELECT v.reason FROM meal_plan_versions v
            INNER JOIN meal_plans p ON p.id = v.planId
            WHERE p.profileId = :profileId
              AND p.weekStartEpochDay != :currentWeekStartEpochDay
              AND v.reason LIKE '%' || :tokenPrefix || '%'
              AND v.reason IS NOT NULL
            ORDER BY v.createdAtEpochMillis ASC, v.id ASC
        """
    )
    suspend fun recoveryReasonsOutsideWeek(
        profileId: Long,
        currentWeekStartEpochDay: Long,
        tokenPrefix: String,
    ): List<String>
}
