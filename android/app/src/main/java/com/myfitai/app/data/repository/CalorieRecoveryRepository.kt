package com.myfitai.app.data.repository

import com.myfitai.app.data.local.MyFitAiDatabase

class CalorieRecoveryRepository(private val db: MyFitAiDatabase) {
    suspend fun wasCheatAdapted(profileId: Long, cheatId: Long): Boolean =
        db.calorieRecoveryQueryDao().hasExactReason(profileId, "CHEAT_ADAPTATION:$cheatId")

    /** Sum only allocations belonging to prior/different weeks; current-week regeneration replaces its own plan. */
    suspend fun plannedRecoveryKcalOutsideWeek(
        profileId: Long,
        cheatId: Long,
        currentWeekStartEpochDay: Long,
    ): Int {
        val prefix = recoveryTokenPrefix(cheatId)
        return db.calorieRecoveryQueryDao().recoveryReasonsOutsideWeek(
            profileId = profileId,
            currentWeekStartEpochDay = currentWeekStartEpochDay,
            tokenPrefix = prefix,
        ).sumOf { reason ->
            TOKEN_REGEX.findAll(reason)
                .filter { it.groupValues[1].toLongOrNull() == cheatId }
                .sumOf { it.groupValues[2].toIntOrNull() ?: 0 }
        }
    }

    companion object {
        private val TOKEN_REGEX = Regex("RC#(\\d+)@(\\d+)")
        fun recoveryTokenPrefix(cheatId: Long): String = "RC#$cheatId@"
        fun recoveryToken(cheatId: Long, kcal: Int): String = "RC#$cheatId@${kcal.coerceAtLeast(0)}"
    }
}
