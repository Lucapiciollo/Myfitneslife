package com.myfitai.app.ui.food

import com.myfitai.app.domain.food.FoodPlanMetrics

/**
 * Regole pure di presentazione della schermata Cibo (UX2): quali blocchi sono visibili e
 * quale messaggio di stato mostrare. Nessun accesso a View o Context, quindi testabile in JVM.
 */
object FoodPlanPresentation {

    enum class StatusKind { NONE, RUNNING, ERROR, AGE_WARNING, SUCCESS, GOAL_CHANGED }

    data class Inputs(
        val hasPlan: Boolean,
        val currentWeek: Boolean,
        val periodCanBeGenerated: Boolean,
        val generationRunning: Boolean,
        val generationError: Boolean,
        val generationSuccess: Boolean,
        val profileAgeInvalid: Boolean,
        /** Obiettivo o misure cambiati dopo la generazione, oppure aggiornamento piano in attesa. */
        val goalChanged: Boolean,
        val hasDayContent: Boolean,
        val scheduleActive: Boolean,
    )

    data class Visibility(
        /** Banner di avviso "dati cambiati" sopra la card dei pasti. */
        val goalChangedBanner: Boolean,
        val statusKind: StatusKind,
        /** Banner di stato della generazione sopra la card dei pasti. */
        val statusBanner: Boolean,
        val mealsCard: Boolean,
        /** Card dello stato vuoto, che ospita anche la CTA di generazione. */
        val emptyCard: Boolean,
        /** La CTA di generazione sta nella card vuota (senza piano) o in fondo (con piano). */
        val generateInEmptyCard: Boolean,
        val mealCountHint: Boolean,
        val scheduleStatus: Boolean,
    )

    fun resolve(input: Inputs): Visibility {
        val goalChangedBanner = input.currentWeek && input.goalChanged
        val ageWarning = input.profileAgeInvalid && !input.generationError && !input.generationRunning
        val statusKind = when {
            input.generationRunning -> StatusKind.RUNNING
            input.generationError -> StatusKind.ERROR
            ageWarning -> StatusKind.AGE_WARNING
            input.generationSuccess -> StatusKind.SUCCESS
            // Il banner dedicato dice già la stessa cosa: non duplicarla nello stato di generazione.
            input.goalChanged && !goalChangedBanner -> StatusKind.GOAL_CHANGED
            else -> StatusKind.NONE
        }
        return Visibility(
            goalChangedBanner = goalChangedBanner,
            statusKind = statusKind,
            statusBanner = input.periodCanBeGenerated && statusKind != StatusKind.NONE,
            mealsCard = input.hasDayContent,
            emptyCard = !input.hasPlan,
            generateInEmptyCard = !input.hasPlan,
            mealCountHint = input.hasPlan,
            scheduleStatus = input.scheduleActive,
        )
    }

    /** Le colonne Carboidrati/Grassi "Nel menu" compaiono solo se almeno un valore è noto: mai target inventati. */
    fun macroColumnsVisible(totals: FoodPlanMetrics.Totals): Boolean =
        totals.carbsG != null || totals.fatG != null
}
