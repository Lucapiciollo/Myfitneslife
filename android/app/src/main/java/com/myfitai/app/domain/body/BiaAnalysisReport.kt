package com.myfitai.app.domain.body

import com.myfitai.app.data.local.entity.BiaMeasurementEntity

/**
 * Deterministic input of the BIA Progress Coach, derived from Room.
 *
 * The report is built where the job runs and never travels through WorkManager `Data`
 * (hard limit: 10 KB). Sending the whole history as a job parameter made the app crash
 * as soon as the history exceeded roughly 17 readings.
 */
object BiaAnalysisReport {
    /** Newest readings sent to the model; older ones add tokens without improving the trend. */
    const val MAX_HISTORY_ROWS = 36

    private val DELTA_KEYS = setOf(
        "weightKg", "bodyFatPercent", "muscleMassKg", "skeletalMuscleKg", "bodyWaterPercent",
        "fatMassKg", "leanMassKg", "bodyWaterKg", "subcutaneousFatPercent", "boneMassKg",
        "proteinPercent", "proteinKg", "bmi",
    )

    data class Report(
        /** Every saved reading of the profile, not only the ones sent as history. */
        val measurementCount: Int,
        val latest: BiaMeasurementEntity,
        val current: Map<String, Float>,
        /** Difference from the most recent earlier reading that has the same metric. */
        val previousDelta: Map<String, Float>,
        /** Chronological, ending with [latest]; later readings are never included. */
        val history: List<BiaMeasurementEntity>,
        val conditions: String,
    )

    /**
     * @param all every reading of the profile, in any order.
     * @param selectedId reading to analyse; the most recent one when null or unknown.
     * @return null when there is no reading with at least one measured value.
     */
    fun build(all: List<BiaMeasurementEntity>, selectedId: Long? = null): Report? {
        val chronological = all.sortedWith(compareBy({ it.measuredAtEpochMillis }, { it.id }))
        val latest = selectedId?.let { id -> chronological.firstOrNull { it.id == id } }
            ?: chronological.lastOrNull()
            ?: return null
        val current = measurementValues(latest)
        if (current.isEmpty()) return null

        val untilLatest = chronological.subList(0, chronological.indexOfFirst { it.id == latest.id } + 1)
        val earlierNewestFirst = untilLatest.dropLast(1).asReversed().map(::measurementValues)
        val delta = linkedMapOf<String, Float>()
        current.forEach { (key, value) ->
            if (key !in DELTA_KEYS) return@forEach
            earlierNewestFirst.firstNotNullOfOrNull { it[key] }?.let { delta[key] = value - it }
        }
        return Report(
            measurementCount = all.size,
            latest = latest,
            current = current,
            previousDelta = delta,
            history = untilLatest.takeLast(MAX_HISTORY_ROWS),
            conditions = conditionLine(latest) ?: "?",
        )
    }

    /** Keys are stable provider-facing names; null metrics are omitted, never sent as zero. */
    fun measurementValues(item: BiaMeasurementEntity): Map<String, Float> = linkedMapOf<String, Float>().apply {
        item.weightKg?.let { put("weightKg", it) }
        item.bodyFatPercent?.let { put("bodyFatPercent", it) }
        item.visceralFatLevel?.let { put("visceralFatLevel", it) }
        item.muscleMassKg?.let { put("muscleMassKg", it) }
        item.skeletalMuscleKg?.let { put("skeletalMuscleKg", it) }
        item.bodyWaterPercent?.let { put("bodyWaterPercent", it) }
        item.bmrKcal?.let { put("bmrKcal", it) }
        item.fatMassKg?.let { put("fatMassKg", it) }
        item.leanMassKg?.let { put("leanMassKg", it) }
        item.bodyWaterKg?.let { put("bodyWaterKg", it) }
        item.subcutaneousFatPercent?.let { put("subcutaneousFatPercent", it) }
        item.boneMassKg?.let { put("boneMassKg", it) }
        item.proteinPercent?.let { put("proteinPercent", it) }
        item.proteinKg?.let { put("proteinKg", it) }
        item.bodyAgeYears?.let { put("bodyAgeYears", it.toFloat()) }
        item.bmi?.let { put("bmi", it) }
    }

    fun conditionLine(item: BiaMeasurementEntity): String? {
        val conditions = listOfNotNull(
            "a digiuno".takeIf { item.fasting },
            "appena sveglio".takeIf { item.justWokeUp },
            "dopo bagno".takeIf { item.afterBathroom },
            "nessun allenamento recente".takeIf { item.noRecentWorkout },
        )
        return conditions.takeIf { it.isNotEmpty() }?.joinToString(" · ", prefix = "Condizioni: ")
    }

    fun valuesForPrompt(values: Map<String, Float>): String =
        values.entries.joinToString(",") { (key, value) -> "$key=$value" }.ifBlank { "?" }

    fun historyForPrompt(history: List<BiaMeasurementEntity>): String =
        history.joinToString(";") { row ->
            "date=${BiaHistoryImportContract.dayKey(row.measuredAtEpochMillis)}," + valuesForPrompt(measurementValues(row))
        }.ifBlank { "?" }
}
