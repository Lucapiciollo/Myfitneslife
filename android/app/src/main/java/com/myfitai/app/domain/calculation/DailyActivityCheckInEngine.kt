package com.myfitai.app.domain.calculation

/**
 * Conservative, local-only adjustment for the current-day activity check-in.
 *
 * MODELLO (scelta K2, dichiarata): il check-in e una QUOTA PRUDENTE sopra il TDEE abituale del profilo, NON il
 * costo energetico dell'allenamento. Il TDEE abituale (livello di attivita scelto dall'utente) include gia in
 * media gli allenamenti abituali, quindi sommare il costo pieno li conterebbe due volte. Il programma
 * settimanale invece parte da un fattore "solo vita quotidiana" e somma il costo reale
 * ([DayEnergyEngine.exerciseKcal] = MET netti x peso x ore): per questo 60 min moderati valgono +140 nel
 * check-in e +312 (78 kg) / +358 (89,5 kg) nel programma. I due numeri non sono in contraddizione perche
 * non vengono mai sommati: con un programma attivo vince il programma (OperationalNutritionMetrics),
 * il check-in vale solo senza programma.
 * Invariante (testata): a durata e intensita uguali il check-in non supera mai il costo del programma
 * (peso >= 40 kg) e mai [MAX_ADJUSTMENT_KCAL].
 * Decisione di prodotto aperta: allineare il check-in alla formula del programma richiederebbe il peso
 * nel salvataggio del check-in e di rivedere il doppio conteggio con il TDEE abituale.
 */
object DailyActivityCheckInEngine {
    enum class Intensity { LIGHT, MODERATE, HARD }

    /** Tetto del contributo del check-in (kcal). */
    const val MAX_ADJUSTMENT_KCAL = 225

    fun effectiveTdeeKcal(
        bmrKcal: Double?,
        habitualTdeeKcal: Double?,
        status: String?,
        adjustmentKcal: Int,
    ): Double? {
        val bmr = bmrKcal?.takeIf { it.isFinite() && it > 0.0 } ?: return habitualTdeeKcal
        val habitual = habitualTdeeKcal?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        return when (status) {
            "REST" -> minOf(habitual, bmr * LocalCalculationEngine.ActivityLevel.SEDENTARY.multiplier)
            "PLANNED_WORKOUT" -> habitual + adjustmentKcal.coerceIn(0, MAX_ADJUSTMENT_KCAL)
            else -> habitual
        }
    }

    fun adjustmentKcal(durationMinutes: Int, intensity: Intensity): Int {
        val duration = when {
            durationMinutes <= 30 -> 30
            durationMinutes <= 45 -> 45
            durationMinutes <= 60 -> 60
            else -> 75
        }
        val base = when (duration) {
            30 -> 50
            45 -> 75
            60 -> 100
            else -> 125
        }
        val extra = when (intensity) {
            Intensity.LIGHT -> 0
            Intensity.MODERATE -> when (duration) { 30 -> 25; 45 -> 35; 60 -> 40; else -> 45 }
            Intensity.HARD -> when (duration) { 30 -> 50; 45 -> 75; 60 -> 90; else -> 100 }
        }
        return (base + extra).coerceAtMost(MAX_ADJUSTMENT_KCAL)
    }
}
