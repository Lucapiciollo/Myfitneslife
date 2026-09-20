package com.myfitai.app.domain.body

import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Offline, non-destructive import of historical BIA measurements. */
object BiaHistoryImportContract {
    const val SCHEMA = "myfitai_bia_history_v1"
    private val metrics = listOf(
        "weightKg", "bodyFatPercent", "visceralFatLevel", "muscleMassKg",
        "skeletalMuscleKg", "bodyWaterPercent", "bmrKcal", "fatMassKg",
        "leanMassKg", "bodyWaterKg", "subcutaneousFatPercent", "boneMassKg",
        "proteinPercent", "proteinKg", "bmi",
    )

    fun parse(json: String, profileId: Long): List<BiaMeasurementEntity> {
        require(json.length <= 1_000_000) { "File JSON troppo grande" }
        require(profileId > 0) { "Profilo non valido" }
        val root = JSONObject(json)
        require(root.optString("schema") == SCHEMA) { "Schema JSON non supportato: richiesto " + SCHEMA }
        val rows = root.getJSONArray("biaMeasurements")
        require(rows.length() in 1..1000) { "Lo storico deve contenere da 1 a 1000 rilevazioni" }
        val dates = hashSetOf<String>()
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val timestamp = if (row.has("measuredAtEpochMillis") && !row.isNull("measuredAtEpochMillis")) {
                row.getLong("measuredAtEpochMillis")
            } else {
                val date = row.getString("date")
                require(Regex("""\d{4}-\d{2}-\d{2}""").matches(date)) { "Formato data non valido alla riga " + (index + 1) }
                val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false }.parse(date)
                    ?: error("Data non valida alla riga " + (index + 1))
                Calendar.getInstance().apply {
                    time = parsed
                    set(Calendar.HOUR_OF_DAY, 12)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            }
            require(timestamp > 0L && timestamp <= System.currentTimeMillis() + 86_400_000L) { "Data non valida alla riga " + (index + 1) }
            require(dates.add(dayKey(timestamp))) { "Data duplicata nel file: " + dayKey(timestamp) }
            val values = metrics.associateWith { key -> number(row, key) }
            require(values.values.any { it != null }) { "Nessun valore BIA alla riga " + (index + 1) }
            listOf("bodyFatPercent", "bodyWaterPercent", "subcutaneousFatPercent", "proteinPercent").forEach { key ->
                values[key]?.let { require(it in 0f..100f) { key + " non valido alla riga " + (index + 1) } }
            }
            val age = if (row.has("bodyAgeYears") && !row.isNull("bodyAgeYears")) row.getInt("bodyAgeYears").also {
                require(it in 1..120) { "Età corporea non valida alla riga " + (index + 1) }
            } else null
            val note = row.optString("notes").takeIf { it.isNotBlank() }
            require((note?.length ?: 0) <= 2000) { "Note troppo lunghe alla riga " + (index + 1) }
            BiaMeasurementEntity(
                profileId = profileId,
                measuredAtEpochMillis = timestamp,
                weightKg = values["weightKg"],
                bodyFatPercent = values["bodyFatPercent"],
                visceralFatLevel = values["visceralFatLevel"],
                muscleMassKg = values["muscleMassKg"],
                skeletalMuscleKg = values["skeletalMuscleKg"],
                bodyWaterPercent = values["bodyWaterPercent"],
                bmrKcal = values["bmrKcal"],
                fasting = row.optBoolean("fasting", false),
                justWokeUp = row.optBoolean("justWokeUp", false),
                afterBathroom = row.optBoolean("afterBathroom", false),
                noRecentWorkout = row.optBoolean("noRecentWorkout", false),
                notes = note,
                fatMassKg = values["fatMassKg"],
                leanMassKg = values["leanMassKg"],
                bodyWaterKg = values["bodyWaterKg"],
                subcutaneousFatPercent = values["subcutaneousFatPercent"],
                boneMassKg = values["boneMassKg"],
                proteinPercent = values["proteinPercent"],
                proteinKg = values["proteinKg"],
                bodyAgeYears = age,
                bmi = values["bmi"],
            )
        }
    }

    private fun number(row: JSONObject, key: String): Float? {
        if (!row.has(key) || row.isNull(key)) return null
        val raw = row.get(key)
        require(raw is Number) { key + " deve essere un numero o null" }
        val value = raw.toFloat()
        require(value.isFinite() && value > 0f && value < 100_000f) { key + " fuori intervallo" }
        return value
    }

    fun dayKey(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(timestamp))
}
