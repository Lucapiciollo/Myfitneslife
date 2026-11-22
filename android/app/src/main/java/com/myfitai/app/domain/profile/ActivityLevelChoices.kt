package com.myfitai.app.domain.profile

import com.myfitai.app.data.profile.ActivityLevelFactors
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import com.myfitai.app.domain.calculation.ProfileCalculationMapper
import java.math.BigDecimal
import java.util.Locale

/**
 * Opzioni visuali del livello di attività. La label decorata non è un valore persistibile:
 * [Choice.value] resta la stringa canonica attesa da [ProfileCalculationMapper].
 */
object ActivityLevelChoices {
    data class Choice(
        val level: LocalCalculationEngine.ActivityLevel,
        val value: String,
        val label: String,
        val description: String,
    )

    /** API legacy mantenuta per i chiamanti esistenti; le schermate correnti usano [catalogOptions]. */
    fun options(base: List<String>, stored: String?): List<String> =
        if (isLegacy(base, stored)) base + stored!!.trim() else base

    fun isLegacy(base: List<String>, stored: String?): Boolean {
        val value = stored?.trim().orEmpty()
        return value.isNotEmpty() && base.none { it.trim().equals(value, ignoreCase = true) }
    }

    fun catalogOptions(
        base: List<String>,
        coefficients: Map<LocalCalculationEngine.ActivityLevel, Double>?,
    ): List<Choice> {
        val catalog = ActivityLevelFactors.options
        val orderedLevels = mutableSetOf<LocalCalculationEngine.ActivityLevel>()
        val ordered = buildList {
            base.forEach { name ->
                catalog.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }?.let { option ->
                    if (orderedLevels.add(option.level)) add(option)
                }
            }
            catalog.filter { orderedLevels.add(it.level) }.forEach(::add)
        }
        return ordered.map { option ->
            Choice(
                level = option.level,
                value = option.name,
                label = decoratedLabel(option.name, option.level, coefficients),
                description = option.description,
            )
        }
    }

    /** Mostra un eventuale valore storico riconosciuto senza riscriverlo quando si salva il profilo. */
    fun displayValue(
        stored: String?,
        coefficients: Map<LocalCalculationEngine.ActivityLevel, Double>?,
    ): String {
        val value = stored.orEmpty()
        val level = ProfileCalculationMapper.activity(value) ?: return value
        val option = ActivityLevelFactors.options.firstOrNull { it.level == level } ?: return value
        val name = value.trim().takeUnless { it.equals(option.name, ignoreCase = true) } ?: option.name
        return decoratedLabel(name, level, coefficients)
    }

    /** Restituisce una key canonica per una scelta decorata, ma conserva il valore storico se invariato. */
    fun storedValue(
        displayed: String?,
        original: String?,
        coefficients: Map<LocalCalculationEngine.ActivityLevel, Double>?,
        choices: List<Choice>,
    ): String? {
        val value = displayed?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (original != null && value == displayValue(original, coefficients)) return original
        choices.firstOrNull { it.label == value }?.let { return it.value }
        // Unknown legacy/custom values remain unknown; never persist decorated UI text as a level.
        return value.takeUnless { " · " in it }
    }

    fun descriptionFor(stored: String?): String? {
        val level = ProfileCalculationMapper.activity(stored) ?: return null
        return ActivityLevelFactors.options.firstOrNull { it.level == level }?.description
    }

    private fun decoratedLabel(
        name: String,
        level: LocalCalculationEngine.ActivityLevel,
        coefficients: Map<LocalCalculationEngine.ActivityLevel, Double>?,
    ): String {
        val coefficient = coefficients?.get(level)?.takeIf { it.isFinite() }?.let(::formatCoefficient) ?: "—"
        return "$name · $coefficient"
    }

    private fun formatCoefficient(value: Double): String {
        val decimals = BigDecimal.valueOf(value).stripTrailingZeros().scale()
            .coerceIn(2, ActivityLevelFactors.MAX_DECIMAL_PLACES)
        return String.format(Locale.ITALIAN, "%.${decimals}f", value)
    }
}

