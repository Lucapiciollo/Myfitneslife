package com.myfitai.app.domain.body

import kotlin.math.abs

/**
 * Unica sorgente delle soglie di rumore della bioimpedenziometria.
 *
 * IPOTESI CONSERVATIVE, non misure del singolo apparecchio: una BIA da bilancia domestica ha una
 * ripetibilita tipica dell'ordine di grandezza dei valori qui sotto anche a parita di condizioni.
 * Sono reversibili e vanno confermate dal responsabile prodotto o tarate sul dispositivo usato.
 * Una variazione con valore assoluto <= soglia e "nessuna variazione affidabile".
 */
object BiaNoiseThresholds {
    const val WEIGHT_KG = 0.8
    const val BODY_FAT_POINTS = 1.0
    const val LEAN_MASS_KG = 1.0
    const val BODY_WATER_KG = 1.0

    /**
     * Soglia delle circonferenze corporee misurate col metro (misure corporee, non BIA): IPOTESI REVERSIBILE di
     * +-1,0 cm, l'ordine di grandezza della ripetibilita di una misura a mano. Una variazione piu piccola o uguale
     * e "nessuna variazione affidabile"; una piu grande NON si puo chiamare "normale oscillazione".
     * Da confermare dal responsabile prodotto o tarare sulla persona.
     */
    const val CIRCUMFERENCE_CM = 1.0

    /** Soglia di una misura corporea per unita (`cm` circonferenze, `kg` peso a bilancia); null = unita sconosciuta. */
    fun forBodyMeasurement(unit: String): Double? = when (unit.trim().lowercase()) {
        "cm" -> CIRCUMFERENCE_CM
        "kg" -> WEIGHT_KG
        else -> null
    }

    /**
     * True solo se il delta, arrotondato a un decimale (il rumore di sottrazione dei Float non e una variazione),
     * supera la soglia; con unita sconosciuta non si afferma nulla.
     */
    fun isAboveBodyMeasurementNoise(unit: String, delta: Double): Boolean {
        val threshold = forBodyMeasurement(unit) ?: return false
        return Math.round(abs(delta) * 10.0) / 10.0 > threshold
    }

    /** Soglie raddoppiate quando le condizioni di misura sono diverse o la comparabilita e bassa. */
    const val DEGRADED_MULTIPLIER = 2.0

    /** Metriche coperte, con la chiave usata nel contesto inviato al modello. */
    val COVERED_KEYS = listOf("weightKg", "bodyFatPercent", "leanMassKg", "muscleMassKg", "skeletalMuscleKg", "bodyWaterKg")

    /** Soglia della metrica, null se la metrica non ha una soglia dichiarata. */
    fun forMetric(key: String, degraded: Boolean = false): Double? {
        val base = when (key) {
            "weightKg" -> WEIGHT_KG
            "bodyFatPercent" -> BODY_FAT_POINTS
            "leanMassKg", "muscleMassKg", "skeletalMuscleKg" -> LEAN_MASS_KG
            "bodyWaterKg" -> BODY_WATER_KG
            else -> return null
        }
        return if (degraded) base * DEGRADED_MULTIPLIER else base
    }

    /** True solo se il delta supera la soglia; senza soglia dichiarata non si afferma nulla. */
    fun isReliableChange(key: String, delta: Double, degraded: Boolean = false): Boolean {
        val threshold = forMetric(key, degraded) ?: return false
        return abs(delta) > threshold
    }
}
