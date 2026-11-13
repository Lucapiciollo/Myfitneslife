package com.myfitai.app.domain.calculation

/**
 * Quota prudente del vecchio check-in giornaliero. Non entra piu in nessun calcolo: TDEE e target vengono
 * solo dal livello di attivita del profilo ([OperationalNutritionMetrics]). Resta [Intensity] e il calcolo della
 * quota perche la card "Attivita di oggi" della Home e la scheda programma delle impostazioni li usano ancora
 * fino alle tranche UI che le rimuovono.
 */
object DailyActivityCheckInEngine {
    enum class Intensity { LIGHT, MODERATE, HARD }

    /** Tetto del contributo del check-in (kcal). */
    const val MAX_ADJUSTMENT_KCAL = 225

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
