package com.myfitai.app.ui

import androidx.annotation.StringRes
import com.myfitai.app.R
import com.myfitai.app.domain.display.DisplayNumberFormatter
import com.myfitai.app.domain.food.FoodIngredient
import com.myfitai.app.domain.food.FoodMeal
import com.myfitai.app.domain.food.FoodPlanVersion
import com.myfitai.app.domain.food.FoodCatalogPlanBuilder
import com.myfitai.app.domain.food.NutritionIntegrityValidator

/**
 * L6a: scelta dei testi in base all'origine dei valori nutrizionali, senza dipendenze da View.
 *
 * DECLARED = kcal/macro dichiarati dal modello (piani storici): testi invariati ("stime elaborate dall'IA").
 * COMPUTED = calcolati dall'app dagli alimenti e dai grammi del piano con il catalogo alimentare (USDA): restano
 * approssimati (simbolo ≈, invariato), ma non sono piu' una stima del modello.
 */
object NutritionSourcePresentation {
    enum class Basis { DECLARED, COMPUTED, MIXED }

    private const val NBSP = "\u00A0"
    private const val SEPARATOR = " \u00B7 "
    private const val LABEL_ESTIMATE_CONFIDENCE = "C"

    fun isComputed(nutritionSource: String?): Boolean = nutritionSource == FoodMeal.NUTRITION_SOURCE_COMPUTED

    fun basisOf(nutritionSource: String?): Basis = if (isComputed(nutritionSource)) Basis.COMPUTED else Basis.DECLARED

    /** Tutti i pasti calcolati = COMPUTED, nessuno = DECLARED (anche senza pasti), un misto = MIXED. */
    fun basisOf(sources: Collection<String?>): Basis {
        val computed = sources.count(::isComputed)
        return when {
            computed == 0 -> Basis.DECLARED
            computed == sources.size -> Basis.COMPUTED
            else -> Basis.MIXED
        }
    }

    fun planBasis(version: FoodPlanVersion?): Basis =
        basisOf(version?.days.orEmpty().flatMap { day -> day.meals.map { it.nutritionSource } })

    @StringRes
    fun infoTitleRes(basis: Basis): Int = when (basis) {
        Basis.DECLARED -> R.string.food_plan_nutrition_info_title_declared
        Basis.COMPUTED -> R.string.food_plan_nutrition_info_title_computed
        Basis.MIXED -> R.string.food_plan_nutrition_info_title_mixed
    }

    @StringRes
    fun infoBodyRes(basis: Basis): Int = when (basis) {
        Basis.DECLARED -> R.string.food_plan_nutrition_info_body_declared
        Basis.COMPUTED -> R.string.food_plan_nutrition_info_body_computed
        Basis.MIXED -> R.string.food_plan_nutrition_info_body_mixed
    }

    @StringRes
    fun infoIconDescriptionRes(basis: Basis): Int = when (basis) {
        Basis.DECLARED -> R.string.food_plan_nutrition_info_icon_declared
        Basis.COMPUTED -> R.string.food_plan_nutrition_info_icon_computed
        Basis.MIXED -> R.string.food_plan_nutrition_info_icon_mixed
    }

    /** Un'alternativa e' calcolata se porta la versione del catalogo (IA/MFP2) o se viene da un pasto storico COMPUTED. */
    fun alternativeIsComputed(catalogVersion: Int?, historicalSource: String?): Boolean =
        catalogVersion != null || isComputed(historicalSource)

    /**
     * Riga secondaria dell'ingrediente: "≈ 120 kcal · P 12 g · C 3 g · G 4 g". Null senza snapshot nutrizionale
     * (piani dichiarati): la riga resta com'e'. Gli spazi interni sono non separabili, cosi' una parola o un
     * numero con la sua unita' non si spezzano; si va a capo solo sui separatori.
     */
    fun ingredientNutritionLine(ingredient: FoodIngredient): String? {
        val parts = listOfNotNull(
            ingredient.kcal?.let { "\u2248$NBSP${DisplayNumberFormatter.decimal(it, 0)}${NBSP}kcal" },
            macro("P", ingredient.proteinG),
            macro("C", ingredient.carbsG),
            macro("G", ingredient.fatG),
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)
    }

    private fun macro(label: String, value: Float?): String? =
        value?.let { "$label$NBSP${DisplayNumberFormatter.decimal(it, 1)}${NBSP}g" }

    /** Riga "Fonte" del dettaglio pasto: solo per i pasti calcolati; con una voce di tipo C aggiunge la nota etichetta. */
    @StringRes
    fun mealSourceRes(meal: FoodMeal): Int? = when {
        !isComputed(meal.nutritionSource) -> null
        meal.ingredients.any { it.nutritionConfidence.equals(LABEL_ESTIMATE_CONFIDENCE, ignoreCase = true) } ->
            R.string.meal_detail_source_catalog_label_estimate
        else -> R.string.meal_detail_source_catalog
    }

    /** Valori (arrotondati, con unita') dell'avviso proteine; null quando il documento non li riporta. */
    data class ProteinNote(val actual: String?, val target: String?) {
        val hasValues: Boolean get() = actual != null && target != null
    }

    /**
     * Avviso non bloccante PROTEIN_BELOW_TARGET di [dayEpochDay] dal documento appValidation del piano.
     * Lettura tollerante: documento assente o malformato = nessun avviso.
     */
    fun proteinBelowTarget(appValidationJson: String?, dayEpochDay: Long): ProteinNote? {
        val warning = NutritionIntegrityValidator.parse(appValidationJson)?.warnings
            ?.firstOrNull { it.code == FoodCatalogPlanBuilder.PROTEIN_BELOW_TARGET && it.dayEpochDay == dayEpochDay }
            ?: return null
        val actual = warning.actual
        val target = warning.expected
        return ProteinNote(
            actual = actual?.let { "${DisplayNumberFormatter.decimal(it, 0)}${NBSP}g" },
            target = target?.let { "${DisplayNumberFormatter.decimal(it, 0)}${NBSP}g" },
        )
    }
}
