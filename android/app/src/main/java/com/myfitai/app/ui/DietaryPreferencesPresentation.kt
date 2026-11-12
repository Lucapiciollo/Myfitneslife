package com.myfitai.app.ui

import com.myfitai.app.domain.food.DietaryCategory
import com.myfitai.app.domain.food.DietaryProfile
import com.myfitai.app.domain.food.DietaryProfile.NoticeCategory
import com.myfitai.app.domain.food.DietaryProfile.NoticeReason
import com.myfitai.app.domain.food.FoodConstraintValidator

/**
 * Logica di presentazione pura (nessuna dipendenza Android) della schermata Preferenze alimentari:
 * quali avvisi mostrare per ogni voce e quando chiedere conferma prima di salvare.
 */
object DietaryPreferencesPresentation {
    /** Avviso su una voce di una categoria; [constraint] coincide con il testo del chip. */
    data class ItemNotice(val constraint: String, val reason: NoticeReason)

    /** Esito del controllo pre-salvataggio. */
    data class SaveCheck(val confirmEmptyConstraints: Boolean, val confirmLegacyTextDropped: Boolean) {
        val needsConfirmation: Boolean get() = confirmEmptyConstraints || confirmLegacyTextDropped
    }

    /** Categoria di avviso del dominio per le liste che sono vincoli rigidi; null per preferiti e non graditi. */
    fun noticeCategory(category: DietaryCategory): NoticeCategory? = when (category) {
        DietaryCategory.ALLERGY -> NoticeCategory.ALLERGY
        DietaryCategory.INTOLERANCE -> NoticeCategory.INTOLERANCE
        DietaryCategory.EXCLUDED -> NoticeCategory.EXCLUDED
        DietaryCategory.DISLIKED, DietaryCategory.PREFERRED -> null
    }

    /** Avvisi delle voci della [category], nell'ordine in cui compaiono nel profilo. */
    fun noticesFor(profile: DietaryProfile, category: DietaryCategory): List<ItemNotice> {
        val target = noticeCategory(category) ?: return emptyList()
        return profile.constraintNotices
            .filter { it.category == target }
            .map { ItemNotice(it.constraint, it.reason) }
    }

    /** Avvisi, tra quelli della categoria, relativi alle sole [values] (voci appena aggiunte). */
    fun noticesForValues(profile: DietaryProfile, category: DietaryCategory, values: List<String>): List<ItemNotice> {
        val keys = values.map(DietaryProfile::normalize).toSet()
        return noticesFor(profile, category).filter { DietaryProfile.normalize(it.constraint) in keys }
    }

    /** Avviso sul regime alimentare quando e' testo libero non riconosciuto (altrimenti null). */
    fun styleNotice(profile: DietaryProfile): ItemNotice? = profile.constraintNotices
        .firstOrNull { it.category == NoticeCategory.DIET_STYLE }
        ?.let { ItemNotice(it.constraint, it.reason) }

    /** Testo originale da mostrare quando il profilo salvato era illeggibile; null se non c'e' nulla da mostrare. */
    fun legacyTextToShow(baseline: DietaryProfile?): String? =
        baseline?.takeIf { it.isUnreadable }?.rawLegacyText?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Un profilo che era illeggibile non deve essere sovrascritto in silenzio:
     * - senza alcun vincolo rigido (liste vuote e regime libero) serve la conferma esplicita;
     * - se il testo originale non e' piu' nelle note serve il consenso a perderlo.
     * Per un profilo leggibile non si chiede nulla.
     */
    fun saveCheck(baseline: DietaryProfile?, candidate: DietaryProfile): SaveCheck {
        if (baseline == null || !baseline.isUnreadable) return SaveCheck(false, false)
        val empty = !FoodConstraintValidator.hasHardConstraints(
            candidate.copy(status = DietaryProfile.Status.OK, rawLegacyText = null),
        )
        val raw = legacyTextToShow(baseline)
        val kept = raw == null || candidate.notes.orEmpty().contains(raw)
        return SaveCheck(confirmEmptyConstraints = empty, confirmLegacyTextDropped = !kept)
    }

    /** Profilo da tenere come base/bozza dopo un salvataggio riuscito: ora e' leggibile e il testo legacy non serve piu'. */
    fun afterSave(saved: DietaryProfile): DietaryProfile =
        saved.copy(status = DietaryProfile.Status.OK, rawLegacyText = null)

    /** Riapplica lo stato "illeggibile" a un profilo ricostruito dal JSON (che non lo conserva). */
    fun withLegacyState(profile: DietaryProfile, rawLegacyText: String?): DietaryProfile =
        if (rawLegacyText == null) profile
        else profile.copy(status = DietaryProfile.Status.UNREADABLE, rawLegacyText = rawLegacyText)
}
