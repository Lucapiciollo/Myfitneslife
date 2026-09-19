package com.myfitai.app.domain.calculation

import kotlin.math.abs

/**
 * Adatta in modo conservativo il target calorico locale usando trend corporei sufficientemente lunghi.
 * L'LLM non partecipa mai a questa decisione.
 */
object AdaptiveNutritionTargetEngine {

    enum class Decision {
        KEEP,
        INCREASE_DEFICIT,
        REDUCE_DEFICIT,
        INSUFFICIENT_DATA,
        NOT_APPLICABLE,
    }

    data class Evidence(
        val count: Int,
        val spanDays: Int,
        val delta: Double?,
    )

    data class Input(
        val goal: LocalCalculationEngine.Goal,
        val tdeeKcal: Double?,
        val baseTargetKcal: Double?,
        val currentWeightKg: Double?,
        val weight: Evidence,
        val bodyFat: Evidence,
        val muscleMass: Evidence,
        val waist: Evidence,
        val abdomen: Evidence,
    )

    data class Result(
        val targetKcal: Double?,
        val decision: Decision,
        val evidenceWindowDays: Int,
        val energyFactor: Double?,
        val reasonCode: String,
    )

    fun adjust(input: Input): Result {
        val tdee = input.tdeeKcal?.takeIf { it.isFinite() && it > 0.0 }
        val base = input.baseTargetKcal?.takeIf { it.isFinite() && it > 0.0 }
        if (tdee == null || base == null) {
            return Result(base, Decision.INSUFFICIENT_DATA, 0, null, "MISSING_BASE_TARGET")
        }

        if (input.goal != LocalCalculationEngine.Goal.WEIGHT_LOSS && input.goal != LocalCalculationEngine.Goal.RECOMPOSITION) {
            return Result(base, Decision.NOT_APPLICABLE, 0, base / tdee, "GOAL_NOT_ADAPTIVE")
        }

        val relevant = listOf(input.weight, input.bodyFat, input.muscleMass, input.waist, input.abdomen)
            .filter { it.count >= 2 && it.delta != null }
        val evidenceDays = relevant.maxOfOrNull { it.spanDays } ?: 0
        val enoughDuration = evidenceDays >= MIN_EVIDENCE_DAYS
        val enoughSignals = relevant.count { it.spanDays >= MIN_EVIDENCE_DAYS } >= 2
        if (!enoughDuration || !enoughSignals) {
            return Result(base, Decision.INSUFFICIENT_DATA, evidenceDays, base / tdee, "INSUFFICIENT_TREND")
        }

        val weeklyWeightPct = weeklyWeightPercent(input.weight, input.currentWeightKg)
        val muscleDecline = input.muscleMass.usableDelta() <= -0.5
        val rapidWeightLoss = weeklyWeightPct != null && weeklyWeightPct <= -1.0
        val favorableFatSignal = input.bodyFat.usableDelta() <= -0.5
        val favorableCircumferenceSignal = input.waist.usableDelta() <= -1.0 || input.abdomen.usableDelta() <= -1.0
        val favorable = favorableFatSignal || favorableCircumferenceSignal

        val stalledWeight = weeklyWeightPct != null && abs(weeklyWeightPct) < 0.15
        val stalledWaist = input.waist.isStable(0.5)
        val stalledAbdomen = input.abdomen.isStable(0.5)
        val stalledFat = input.bodyFat.isStable(0.3)
        val stallSignals = listOf(stalledWaist, stalledAbdomen, stalledFat).count { it } >= 2
        val sustainedStall = evidenceDays >= STALL_EVIDENCE_DAYS && stalledWeight && stallSignals

        val baseFactor = LocalCalculationEngine.goalEnergyFactor(input.goal)
        val decision = when {
            muscleDecline || rapidWeightLoss -> Decision.REDUCE_DEFICIT
            favorable -> Decision.KEEP
            sustainedStall -> Decision.INCREASE_DEFICIT
            else -> Decision.KEEP
        }
        val factor = when (decision) {
            Decision.INCREASE_DEFICIT -> baseFactor - STEP
            Decision.REDUCE_DEFICIT -> baseFactor + STEP
            else -> baseFactor
        }.coerceIn(minFactor(input.goal), maxFactor(input.goal))

        return Result(
            targetKcal = tdee * factor,
            decision = decision,
            evidenceWindowDays = evidenceDays,
            energyFactor = factor,
            reasonCode = when (decision) {
                Decision.INCREASE_DEFICIT -> "SUSTAINED_STALL"
                Decision.REDUCE_DEFICIT -> if (muscleDecline) "MUSCLE_TREND_DOWN" else "WEIGHT_LOSS_TOO_FAST"
                Decision.KEEP -> if (favorable) "FAVORABLE_PROGRESS" else "NO_SAFE_CHANGE"
                else -> "NO_CHANGE"
            },
        )
    }

    private fun weeklyWeightPercent(evidence: Evidence, currentWeightKg: Double?): Double? {
        val delta = evidence.delta ?: return null
        val current = currentWeightKg?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        if (evidence.spanDays < 7) return null
        return (delta / current) * (7.0 / evidence.spanDays.toDouble()) * 100.0
    }

    private fun Evidence.usableDelta(): Double = delta?.takeIf { count >= 2 && spanDays >= MIN_EVIDENCE_DAYS } ?: 0.0

    private fun Evidence.isStable(threshold: Double): Boolean =
        count >= 2 && spanDays >= MIN_EVIDENCE_DAYS && delta?.let { abs(it) < threshold } == true

    private fun minFactor(goal: LocalCalculationEngine.Goal): Double = when (goal) {
        LocalCalculationEngine.Goal.WEIGHT_LOSS -> 0.825
        LocalCalculationEngine.Goal.RECOMPOSITION -> 0.925
        else -> LocalCalculationEngine.goalEnergyFactor(goal)
    }

    private fun maxFactor(goal: LocalCalculationEngine.Goal): Double = when (goal) {
        LocalCalculationEngine.Goal.WEIGHT_LOSS -> 0.875
        LocalCalculationEngine.Goal.RECOMPOSITION -> 0.975
        else -> LocalCalculationEngine.goalEnergyFactor(goal)
    }

    const val MIN_EVIDENCE_DAYS = 21
    const val STALL_EVIDENCE_DAYS = 28
    private const val STEP = 0.025
}
