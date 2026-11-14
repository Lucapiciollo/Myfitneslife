package com.myfitai.app.domain.display

import java.time.LocalDate

/**
 * UX5b: logica pura della card "Totale giornaliero" di Cibo e delle note "piano da aggiornare".
 * Nessun testo qui: la UI mappa [Origin.Kind] sulle stringhe del glossario.
 */
object FoodTotalsPresentation {
    /** Una sola riga di origine dell'obiettivo; [profileKcal] e' valorizzato solo quando va mostrato "Obiettivo da profilo: X". */
    data class Origin(val kind: Kind, val profileKcal: Int?) {
        enum class Kind { PLAN_DAY, PLAN_WEEK, PLAN_STALE, PROFILE, NONE }
    }

    /**
     * Stessa regola della Home ([TargetModeLine.isStale]): con l'aggiornamento del piano in sospeso l'obiettivo del piano
     * e' quello del piano precedente. L'obiettivo da profilo si mostra solo per un obiettivo del piano e solo se differisce.
     */
    fun origin(resolved: DayTargetResolver.Resolved, planUpdatePending: Boolean): Origin {
        val stale = TargetModeLine.isStale(planUpdatePending, resolved.source)
        val kind = when {
            stale -> Origin.Kind.PLAN_STALE
            resolved.source == DayTargetResolver.Source.PLAN_DAY -> Origin.Kind.PLAN_DAY
            resolved.source == DayTargetResolver.Source.PLAN_WEEK -> Origin.Kind.PLAN_WEEK
            resolved.source == DayTargetResolver.Source.THEORETICAL -> Origin.Kind.PROFILE
            else -> Origin.Kind.NONE
        }
        val planTarget = kind == Origin.Kind.PLAN_STALE || kind == Origin.Kind.PLAN_DAY
        val profile = resolved.theoreticalKcal
        return Origin(kind, profile?.takeIf { planTarget && it != resolved.kcal })
    }

    /** Tocco rapido "Segna come consumato": solo per pasti di oggi o di giorni passati, mai per i giorni futuri. */
    fun quickConsumeAvailable(mealDayEpochDay: Long, today: LocalDate): Boolean = mealDayEpochDay <= today.toEpochDay()

    /** Nota "piano non aggiornato" in review e PDF: serve il flag in sospeso e un obiettivo del piano da annotare. */
    fun planUpdateNoteVisible(planUpdatePending: Boolean, hasPlanTarget: Boolean): Boolean = planUpdatePending && hasPlanTarget

    /**
     * Review: la nota riguarda solo la settimana in corso. Una settimana conclusa e stata vissuta con il piano di allora,
     * quindi un cambio di profilo successivo non la rende "non aggiornata".
     */
    fun planUpdateNoteVisible(planUpdatePending: Boolean, hasPlanTarget: Boolean, weekStart: LocalDate, today: LocalDate): Boolean {
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        return planUpdateNoteVisible(planUpdatePending, hasPlanTarget) && weekStart == monday
    }
}
