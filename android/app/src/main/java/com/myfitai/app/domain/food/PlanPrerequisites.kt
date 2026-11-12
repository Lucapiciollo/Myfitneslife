package com.myfitai.app.domain.food

import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.domain.profile.ProfileAgeRule
import java.time.LocalDate

/** Dato mancante o non valido che impedisce la generazione del piano, con causa leggibile e destinazione. */
enum class PlanPrerequisite(val cause: String, val destination: Destination) {
    ACTIVE_PROFILE("seleziona o crea un profilo attivo", Destination.NONE),
    PROFILE("il profilo non è disponibile: selezionalo di nuovo", Destination.NONE),
    PROFILE_DATA("completa i dati del profilo", Destination.PROFILE_EDITOR),
    BIRTH_DATE_MISSING("inserisci la data di nascita nel profilo", Destination.PROFILE_EDITOR),
    BIRTH_DATE_INVALID(
        "controlla la data di nascita: l'età deve essere compresa tra ${ProfileAgeRule.MIN_AGE_YEARS} e ${ProfileAgeRule.MAX_AGE_YEARS} anni",
        Destination.PROFILE_EDITOR,
    ),
    SEX("seleziona il sesso biologico nel profilo", Destination.PROFILE_EDITOR),
    HEIGHT("inserisci l'altezza nel profilo", Destination.PROFILE_EDITOR),
    WEIGHT("inserisci il peso nel profilo", Destination.PROFILE_EDITOR),
    GOAL("scegli l'obiettivo nel profilo", Destination.PROFILE_EDITOR),
    TARGETS("controlla i dati del profilo: servono per calcolare calorie e macro", Destination.PROFILE_EDITOR);

    enum class Destination { PROFILE_EDITOR, NONE }
}

object PlanPrerequisites {
    const val PREFIX = "Completa prima: "

    fun message(prerequisites: List<PlanPrerequisite>): String =
        PREFIX + prerequisites.distinct().joinToString("; ") { it.cause }

    /** True se il messaggio "Completa prima: ..." richiede di aprire l'editor profilo. */
    fun needsProfileEditor(message: String?): Boolean {
        if (message == null || !message.startsWith(PREFIX)) return false
        return PlanPrerequisite.entries.any { it.destination == PlanPrerequisite.Destination.PROFILE_EDITOR && message.contains(it.cause) }
    }

    /**
     * Dati di profilo mancanti o non validi per il calcolo locale dei target, nell'ordine in cui
     * l'utente li incontra nel form. Lista vuota se il profilo contiene i dati minimi.
     */
    fun profileProblems(profile: UserProfileEntity, latestWeightKg: Float?, today: LocalDate): List<PlanPrerequisite> = buildList {
        when (ProfileAgeRule.check(profile.birthDateEpochDay, today)) {
            ProfileAgeRule.Status.VALID -> Unit
            ProfileAgeRule.Status.MISSING -> add(PlanPrerequisite.BIRTH_DATE_MISSING)
            else -> add(PlanPrerequisite.BIRTH_DATE_INVALID)
        }
        if (profile.biologicalSex.isNullOrBlank()) add(PlanPrerequisite.SEX)
        if (profile.heightCm == null) add(PlanPrerequisite.HEIGHT)
        if (latestWeightKg == null) add(PlanPrerequisite.WEIGHT)
    }
}
