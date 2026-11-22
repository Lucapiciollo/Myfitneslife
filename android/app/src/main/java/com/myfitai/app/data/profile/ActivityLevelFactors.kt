package com.myfitai.app.data.profile

import android.content.Context
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import org.json.JSONObject
import java.math.BigDecimal
import java.util.WeakHashMap

/** Catalogo canonico dei livelli di attività e dei coefficienti configurabili a livello app. */
object ActivityLevelFactors {
    data class Option(
        val level: LocalCalculationEngine.ActivityLevel,
        val name: String,
        val defaultCoefficient: Double,
        val description: String,
    )

    val options = listOf(
        Option(
            LocalCalculationEngine.ActivityLevel.SEDENTARY,
            "Sedentario",
            1.20,
            "Lavoro sedentario, pochi passi, nessun allenamento.",
        ),
        Option(
            LocalCalculationEngine.ActivityLevel.LIGHT,
            "Leggermente attivo",
            1.375,
            "Vita prevalentemente sedentaria, ma qualche camminata o 1–3 allenamenti leggeri.",
        ),
        Option(
            LocalCalculationEngine.ActivityLevel.MODERATE,
            "Moderatamente attivo",
            1.55,
            "Movimento quotidiano discreto e allenamento regolare, circa 3–5 volte a settimana.",
        ),
        Option(
            LocalCalculationEngine.ActivityLevel.VERY_ACTIVE,
            "Molto attivo",
            1.725,
            "Lavoro attivo o allenamenti intensi e frequenti.",
        ),
        Option(
            LocalCalculationEngine.ActivityLevel.EXTREME,
            "Estremamente attivo",
            1.90,
            "Attività fisica molto elevata, spesso associata a lavoro fisico impegnativo e allenamenti intensi.",
        ),
    )

    val defaultCoefficients: Map<LocalCalculationEngine.ActivityLevel, Double> =
        options.associate { it.level to it.defaultCoefficient }

    const val MIN_COEFFICIENT = 1.0
    const val MAX_COEFFICIENT = 2.5
    const val MAX_DECIMAL_PLACES = 3

    /** Validazione dell'intera tabella: nessun livello mancante, duplicato o fuori ordine è accettato. */
    fun isValid(coefficients: Map<LocalCalculationEngine.ActivityLevel, Double>): Boolean {
        if (coefficients.keys != options.map { it.level }.toSet()) return false
        val ordered = options.map { option -> coefficients[option.level] ?: return false }
        if (ordered.any { value ->
                !value.isFinite() || value !in MIN_COEFFICIENT..MAX_COEFFICIENT ||
                    BigDecimal.valueOf(value).stripTrailingZeros().scale().coerceAtLeast(0) > MAX_DECIMAL_PLACES
            }
        ) return false
        return ordered.zipWithNext().all { (left, right) -> left < right }
    }
}

/** Preferenza unica, globale e persistita localmente per tutti i profili. */
class ActivityLevelFactorsStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /**
     * Restituisce i default solo quando la configurazione non è mai stata salvata. Una configurazione
     * presente ma illeggibile non viene sostituita silenziosamente: il chiamante può interrompere il calcolo.
     */
    fun read(): Map<LocalCalculationEngine.ActivityLevel, Double>? = synchronized(PROCESS_LOCK) {
        val persisted = persistedConfiguration()
        if (!persisted.isPresent) {
            ActivityLevelFactors.defaultCoefficients.toMap()
        } else {
            persisted.encoded?.let(::decode)
        }
    }

    fun coefficientFor(level: LocalCalculationEngine.ActivityLevel): Double? = read()?.get(level)

    /**
     * Sostituisce tutti i coefficienti con una singola scrittura atomica. Fa snapshot all'ingresso;
     * valori parziali o non validi non modificano la configurazione già persistita.
     */
    fun save(coefficients: Map<LocalCalculationEngine.ActivityLevel, Double>): Boolean {
        val snapshot = defensiveSnapshot(coefficients) ?: return false
        if (!ActivityLevelFactors.isValid(snapshot)) return false
        val encoded = runCatching {
            JSONObject().apply {
                ActivityLevelFactors.options.forEach { option ->
                    put(option.level.name, snapshot.getValue(option.level))
                }
            }.toString()
        }.getOrNull() ?: return false

        return synchronized(PROCESS_LOCK) {
            // SharedPreferences may change its process-local map even when disk persistence fails.
            // Keep the prior snapshot for reads unless commit confirms the write.
            persistedConfiguration()
            val committed = runCatching {
                preferences.edit().putString(KEY_COEFFICIENTS, encoded).commit()
            }.getOrDefault(false)
            if (committed) {
                processConfigurations[preferences] = PersistedConfiguration(isPresent = true, encoded = encoded)
            }
            committed
        }
    }

    fun resetToDefaults(): Boolean = save(ActivityLevelFactors.defaultCoefficients)

    private fun decode(encoded: String): Map<LocalCalculationEngine.ActivityLevel, Double>? = runCatching {
        val json = JSONObject(encoded)
        val expectedKeys = ActivityLevelFactors.options.map { it.level.name }.toSet()
        val actualKeys = buildSet {
            val keys = json.keys()
            while (keys.hasNext()) add(keys.next())
        }
        require(actualKeys == expectedKeys)
        val coefficients = ActivityLevelFactors.options.associate { option ->
            val value = json.opt(option.level.name) as? Number ?: error("Invalid activity coefficient")
            option.level to value.toDouble()
        }
        coefficients.takeIf(ActivityLevelFactors::isValid) ?: error("Invalid activity coefficient table")
    }.getOrNull()

    /** Copies and checks the caller-owned map; inconsistent or fail-fast iteration is rejected. */
    private fun defensiveSnapshot(
        coefficients: Map<LocalCalculationEngine.ActivityLevel, Double>,
    ): Map<LocalCalculationEngine.ActivityLevel, Double>? = runCatching {
        synchronized(coefficients) {
            val initialSize = coefficients.size
            fun copyCurrentEntries() = LinkedHashMap<LocalCalculationEngine.ActivityLevel, Double>(coefficients.size)
                .also { copy -> coefficients.forEach { (level, value) -> copy[level] = value } }

            val snapshot = copyCurrentEntries()
            val verification = copyCurrentEntries()
            require(
                coefficients.size == initialSize &&
                    snapshot.size == initialSize &&
                    verification == snapshot &&
                    coefficients == snapshot,
            )
            snapshot
        }
    }.getOrNull()

    /** Must be called under PROCESS_LOCK; cached separately for each SharedPreferences instance. */
    private fun persistedConfiguration(): PersistedConfiguration =
        processConfigurations[preferences] ?: run {
            val persisted = runCatching {
                if (!preferences.contains(KEY_COEFFICIENTS)) {
                    PersistedConfiguration(isPresent = false, encoded = null)
                } else {
                    // A present but unreadable value is corruption, not absence/defaults.
                    PersistedConfiguration(
                        isPresent = true,
                        encoded = runCatching { preferences.getString(KEY_COEFFICIENTS, null) }.getOrNull(),
                    )
                }
            }.getOrElse {
                PersistedConfiguration(isPresent = true, encoded = null)
            }
            processConfigurations[preferences] = persisted
            persisted
        }

    private data class PersistedConfiguration(val isPresent: Boolean, val encoded: String?)

    private companion object {
        const val PREFERENCES_NAME = "activity_level_factors"
        const val KEY_COEFFICIENTS = "coefficients_v1"
        val PROCESS_LOCK = Any()
        // Process-local guard against failed commit() mutating SharedPreferences' in-memory map.
        // A new process starts from the on-disk value, so this needs no schema/key migration.
        val processConfigurations = WeakHashMap<android.content.SharedPreferences, PersistedConfiguration>()
    }
}
