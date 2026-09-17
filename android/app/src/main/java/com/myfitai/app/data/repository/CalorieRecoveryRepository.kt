package com.myfitai.app.data.repository

import com.myfitai.app.data.local.MyFitAiDatabase

class CalorieRecoveryRepository(private val db: MyFitAiDatabase) {
    suspend fun wasCheatAdapted(profileId: Long, cheatId: Long): Boolean =
        db.calorieRecoveryQueryDao().hasExactReason(profileId, "CHEAT_ADAPTATION:$cheatId")

    suspend fun wasRecoveryPlannedOutsideWeek(
        profileId: Long,
        cheatId: Long,
        currentWeekStartEpochDay: Long,
    ): Boolean = db.calorieRecoveryQueryDao().hasRecoveryTokenOutsideWeek(
        profileId = profileId,
        currentWeekStartEpochDay = currentWeekStartEpochDay,
        token = recoveryToken(cheatId),
    )

    companion object {
        fun recoveryToken(cheatId: Long): String = "RC#$cheatId"
    }
}
