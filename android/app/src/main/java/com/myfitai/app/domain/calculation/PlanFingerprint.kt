package com.myfitai.app.domain.calculation

import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.data.local.entity.UserProfileEntity
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Impronta dei SOLI input che determinano target e TDEE del piano (UX7): obiettivo, livello di attivita,
 * data di nascita, sesso, altezza, peso del profilo, peso effettivo e grasso (con il suo peso di riferimento)
 * scelti dal motore ([LocalCalculationEngine]). Nome, foto, orari e preferenze alimentari NON entrano.
 *
 * Funzione pura e stabile: stringa canonica con valori normalizzati (testo trim+maiuscolo, decimali a 1 cifra)
 * poi SHA-256 esadecimale troncato. Cambiare un input e rimetterlo com'era restituisce la stessa impronta.
 */
object PlanFingerprint {
    const val LENGTH = 16
    private const val VERSION = "v1"

    fun of(
        profile: UserProfileEntity,
        resolvedWeightKg: Float?,
        bodyFatPercent: Float?,
        bodyFatReferenceWeightKg: Float?,
        activityMultiplier: Double? = null,
    ): String {
        val canonical = listOf(
            VERSION,
            "goal=${text(profile.goal)}",
            "act=${text(profile.activityLevel)}",
            "birth=${profile.birthDateEpochDay ?: "-"}",
            "sex=${text(profile.biologicalSex)}",
            "h=${decimal(profile.heightCm)}",
            "pw=${decimal(profile.currentWeightKg)}",
            "w=${decimal(resolvedWeightKg)}",
            "bf=${decimal(bodyFatPercent)}",
            "bfw=${decimal(bodyFatReferenceWeightKg)}",
        ).joinToString("|") + configuredActivityFactor(profile, activityMultiplier)?.let { "|actf=$it" }.orEmpty()
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(LENGTH)
    }

    /** Stessa scelta di peso e grasso di [ProfileCalculationService]: [WeightResolver] e [KatchBodyFatSelector]. */
    fun of(
        profile: UserProfileEntity,
        biaHistory: List<BiaMeasurementEntity>,
        bodyHistory: List<BodyMeasurementEntity>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        activityMultiplier: Double? = null,
    ): String {
        val weight = WeightResolver.resolve(WeightResolver.observations(biaHistory, bodyHistory, profile.id), profile.currentWeightKg)
        val bodyFat = KatchBodyFatSelector.select(biaHistory, today, zone)
        return of(profile, weight.valueKg, bodyFat.bodyFatPercent, bodyFat.referenceWeightKg, activityMultiplier)
    }

    /** Omits configured defaults so fingerprints created before this field remain current. */
    private fun configuredActivityFactor(profile: UserProfileEntity, activityMultiplier: Double?): String? {
        val level = ProfileCalculationMapper.activity(profile.activityLevel) ?: return null
        val factor = activityMultiplier?.takeIf { it.isFinite() } ?: return null
        val normalized = BigDecimal.valueOf(factor).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros()
        val default = BigDecimal.valueOf(level.multiplier).stripTrailingZeros()
        return normalized.takeUnless { it.compareTo(default) == 0 }?.toPlainString()
    }

    private fun text(value: String?): String = value?.trim()?.takeIf { it.isNotEmpty() }?.uppercase(Locale.ROOT) ?: "-"

    private fun decimal(value: Float?): String =
        value?.takeIf { it.isFinite() }
            ?.let { String.format(Locale.ROOT, "%.1f", (it.toDouble() * 10.0).roundToLong() / 10.0) }
            ?: "-"
}

/**
 * Unica regola di "piano non aggiornato". Con l'impronta del piano presente decide SOLO il confronto con
 * quella di adesso; senza impronta (piani creati prima della migrazione 21) resta il vecchio flag
 * [com.myfitai.app.data.profile.NutritionPlanUpdatePreferences], che si spegne alla rigenerazione.
 */
object PlanStaleness {
    fun isStale(planFingerprint: String?, currentFingerprint: String?, legacyPendingFlag: Boolean): Boolean = when {
        planFingerprint == null -> legacyPendingFlag
        currentFingerprint == null -> false
        else -> planFingerprint != currentFingerprint
    }
}
