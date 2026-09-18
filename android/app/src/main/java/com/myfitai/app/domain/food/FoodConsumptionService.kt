package com.myfitai.app.domain.food

import com.myfitai.app.data.local.entity.FoodConsumptionEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.FoodConsumptionRepository
import com.myfitai.app.domain.time.SystemTimeProvider
import com.myfitai.app.domain.time.TimeProvider

/** Records explicit user statements; it never infers consumption from a plan. */
class FoodConsumptionService(
    private val repository: FoodConsumptionRepository,
    private val activeProfileStore: ActiveProfileStore,
    private val recoveryRepository: com.myfitai.app.data.repository.NutritionRecoveryRepository? = null,
    private val time: TimeProvider = SystemTimeProvider,
) {
    suspend fun setMealStatus(
        planId: Long,
        planVersionId: Long,
        dayId: Long,
        plannedDateEpochDay: Long,
        meal: FoodMeal,
        status: FoodConsumptionStatus,
        quantityFactor: Float = 1f,
        note: String? = null,
    ): FoodConsumptionEntity = upsert(
        profileId = activeProfile(),
        planId = planId,
        planVersionId = planVersionId,
        dayId = dayId,
        plannedDateEpochDay = plannedDateEpochDay,
        itemType = FoodConsumptionItemType.MEAL,
        itemKey = FoodConsumptionKeys.meal(meal.id),
        mealId = meal.id,
        supplementKey = null,
        status = status,
        quantityFactor = quantityFactor,
        kcal = meal.kcal,
        proteinG = meal.proteinG,
        carbsG = meal.carbsG,
        fatG = meal.fatG,
        note = note,
    )

    suspend fun setSupplementStatus(
        planId: Long,
        planVersionId: Long,
        dayId: Long,
        plannedDateEpochDay: Long,
        supplementKey: String,
        supplement: FoodSupplement,
        status: FoodConsumptionStatus,
        quantityFactor: Float = 1f,
        note: String? = null,
    ): FoodConsumptionEntity = upsert(
        profileId = activeProfile(),
        planId = planId,
        planVersionId = planVersionId,
        dayId = dayId,
        plannedDateEpochDay = plannedDateEpochDay,
        itemType = FoodConsumptionItemType.SUPPLEMENT,
        itemKey = FoodConsumptionKeys.supplement(supplementKey),
        mealId = null,
        supplementKey = supplementKey,
        status = status,
        quantityFactor = quantityFactor,
        kcal = supplement.kcal,
        proteinG = supplement.proteinG,
        carbsG = supplement.carbsG,
        fatG = supplement.fatG,
        note = note,
    )

    suspend fun clear(planVersionId: Long, itemKey: String) {
        val profileId = activeProfile()
        repository.deleteForItem(profileId, planVersionId, itemKey)
    }

    private suspend fun upsert(
        profileId: Long,
        planId: Long,
        planVersionId: Long,
        dayId: Long,
        plannedDateEpochDay: Long,
        itemType: FoodConsumptionItemType,
        itemKey: String,
        mealId: Long?,
        supplementKey: String?,
        status: FoodConsumptionStatus,
        quantityFactor: Float,
        kcal: Int?,
        proteinG: Float?,
        carbsG: Float?,
        fatG: Float?,
        note: String?,
    ): FoodConsumptionEntity {
        require(quantityFactor > 0f) { "quantityFactor must be greater than zero" }
        val now = time.nowEpochMillis()
        val previous = repository.getForItem(profileId, planVersionId, itemKey)
        val value = FoodConsumptionEntity(
            id = previous?.id ?: 0L,
            profileId = profileId,
            planId = planId,
            planVersionId = planVersionId,
            dayId = dayId,
            plannedDateEpochDay = plannedDateEpochDay,
            itemType = itemType.name,
            itemKey = itemKey,
            mealId = mealId,
            supplementKey = supplementKey,
            status = status.name,
            recordedAtEpochMillis = previous?.recordedAtEpochMillis ?: now,
            updatedAtEpochMillis = now,
            quantityFactor = quantityFactor,
            kcal = kcal?.let { (it * quantityFactor).toInt() },
            proteinG = proteinG?.times(quantityFactor),
            carbsG = carbsG?.times(quantityFactor),
            fatG = fatG?.times(quantityFactor),
            note = note,
        )
        val persistedId = repository.upsert(value)
        if (status == FoodConsumptionStatus.CONSUMED) {
            recoveryRepository?.let { recovery ->
                recovery.confirmWithdrawal(profileId, plannedDateEpochDay, now)
            }
        }
        return value.copy(id = if (value.id == 0L) persistedId else value.id)
    }

    private fun activeProfile(): Long = activeProfileStore.currentIdOrNull()
        ?: error("Nessun profilo attivo")
}
