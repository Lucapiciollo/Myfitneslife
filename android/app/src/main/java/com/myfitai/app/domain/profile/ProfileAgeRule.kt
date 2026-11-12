package com.myfitai.app.domain.profile

import androidx.annotation.StringRes
import com.myfitai.app.R
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import java.time.LocalDate
import java.time.Period

/**
 * Regola unica sull'età del profilo, allineata a [LocalCalculationEngine]
 * (età in anni compiuti tra [MIN_AGE_YEARS] e [MAX_AGE_YEARS] inclusi).
 * Usata da form profilo, onboarding, avvisi e prerequisiti di generazione.
 */
object ProfileAgeRule {
    const val MIN_AGE_YEARS = LocalCalculationEngine.MIN_AGE_YEARS
    const val MAX_AGE_YEARS = LocalCalculationEngine.MAX_AGE_YEARS

    enum class Status(@StringRes val messageRes: Int?) {
        VALID(null),
        MISSING(R.string.validation_required_birth_date),
        FUTURE(R.string.profile_birth_date_future),
        TOO_YOUNG(R.string.profile_birth_date_invalid),
        TOO_OLD(R.string.profile_birth_date_invalid),
    }

    /** Anni compiuti alla data [today]; null se la data manca o è nel futuro. */
    fun ageYears(birthEpochDay: Long?, today: LocalDate): Int? {
        if (birthEpochDay == null) return null
        val birth = LocalDate.ofEpochDay(birthEpochDay)
        if (birth.isAfter(today)) return null
        return Period.between(birth, today).years
    }

    fun check(birthEpochDay: Long?, today: LocalDate): Status {
        if (birthEpochDay == null) return Status.MISSING
        val age = ageYears(birthEpochDay, today) ?: return Status.FUTURE
        return when {
            age < MIN_AGE_YEARS -> Status.TOO_YOUNG
            age > MAX_AGE_YEARS -> Status.TOO_OLD
            else -> Status.VALID
        }
    }

    /**
     * Testo localizzato dello stato; null se [Status.VALID]. [resolve] riceve la risorsa e i limiti
     * (min, max) da inserire nei segnaposto %1$d/%2$d, così il range non è mai scritto a mano nelle stringhe.
     */
    fun message(status: Status, resolve: (messageRes: Int, minAge: Int, maxAge: Int) -> String): String? =
        status.messageRes?.let { resolve(it, MIN_AGE_YEARS, MAX_AGE_YEARS) }

    /**
     * Stato da mostrare come avviso sul profilo già salvato: una data assente non è un'età errata
     * (il servizio la richiede solo quando serve), quindi viene trattata come VALID.
     */
    fun warningStatus(birthEpochDay: Long?, today: LocalDate): Status =
        check(birthEpochDay, today).takeIf { it != Status.MISSING } ?: Status.VALID

    fun isValid(birthEpochDay: Long?, today: LocalDate): Boolean = check(birthEpochDay, today) == Status.VALID

    /** Vero solo se la data è presente ma l'età calcolata è fuori range (o la data è futura). */
    fun isPresentButInvalid(birthEpochDay: Long?, today: LocalDate): Boolean =
        birthEpochDay != null && !isValid(birthEpochDay, today)
}
