package com.myfitai.app.data.repository

import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.NutritionRecoveryEventEntity
import com.myfitai.app.data.local.entity.NutritionRecoveryWithdrawalEntity
import com.myfitai.app.domain.food.NutritionRecoveryTargetEngine
import androidx.room.withTransaction

class NutritionRecoveryRepository(private val db: MyFitAiDatabase) {
    fun events(profileId: Long) = db.nutritionRecoveryDao().observeEvents(profileId)
    suspend fun activeEvents(profileId: Long, epochDay: Long) = db.nutritionRecoveryDao().activeEvents(profileId, epochDay)
    suspend fun activeEventForSource(profileId: Long, epochDay: Long, source: String) = db.nutritionRecoveryDao().activeEventForSource(profileId, epochDay, source)
    suspend fun expireDue(profileId: Long, epochDay: Long) = db.nutritionRecoveryDao().expireDue(profileId, epochDay)
    suspend fun insertEvent(value: NutritionRecoveryEventEntity) = db.nutritionRecoveryDao().insertEvent(value)
    suspend fun updateEvent(value: NutritionRecoveryEventEntity) = db.nutritionRecoveryDao().updateEvent(value)
    suspend fun deleteByProfile(profileId: Long) {
        db.nutritionRecoveryDao().deleteWithdrawalsByProfile(profileId)
        db.nutritionRecoveryDao().deleteEventsByProfile(profileId)
    }
    suspend fun withdrawal(profileId: Long, epochDay: Long) = db.nutritionRecoveryDao().getWithdrawal(profileId, epochDay)
    suspend fun latestWithdrawalAtOrBefore(profileId: Long, epochDay: Long) = db.nutritionRecoveryDao().latestWithdrawalAtOrBefore(profileId, epochDay)
    suspend fun plannedBefore(profileId: Long, epochDay: Long) = db.nutritionRecoveryDao().plannedBefore(profileId, epochDay)
    suspend fun upsertWithdrawal(value: NutritionRecoveryWithdrawalEntity) = db.nutritionRecoveryDao().upsertWithdrawal(value)
    suspend fun confirmWithdrawal(profileId: Long, epochDay: Long, updatedAtEpochMillis: Long): Boolean = db.withTransaction {
        val dao = db.nutritionRecoveryDao()
        val withdrawal = dao.getWithdrawal(profileId, epochDay) ?: return@withTransaction false
        val delta = (withdrawal.plannedRecoveryKcal - withdrawal.confirmedRecoveryKcal).coerceAtLeast(0)
        if (delta == 0) return@withTransaction false
        val event = dao.getEvent(withdrawal.eventId) ?: return@withTransaction false
        dao.upsertWithdrawal(withdrawal.copy(confirmedRecoveryKcal = withdrawal.plannedRecoveryKcal, updatedAtEpochMillis = updatedAtEpochMillis))
        dao.updateEvent(event.copy(
            remainingKcal = (event.remainingKcal - delta).coerceAtLeast(0),
            recoveredKcal = event.recoveredKcal + delta,
            status = if (event.remainingKcal - delta <= 0) NutritionRecoveryTargetEngine.STATUS_RECOVERED else event.status,
        ))
        true
    }
    fun withdrawals(profileId: Long) = db.nutritionRecoveryDao().observeWithdrawals(profileId)
}
