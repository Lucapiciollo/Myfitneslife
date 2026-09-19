package com.myfitai.app.domain.body

import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

object BiaMeasurementQualityEngine {
    enum class Level { HIGH, MEDIUM, LOW, INSUFFICIENT }

    data class Result(
        val level: Level,
        val score: Int,
        val reasons: List<String>,
    )

    fun evaluate(current: BiaMeasurementEntity, previous: BiaMeasurementEntity?, zoneId: ZoneId = ZoneId.systemDefault()): Result {
        if (previous == null) return Result(Level.INSUFFICIENT, 0, listOf("Manca una rilevazione precedente confrontabile"))

        var score = 0
        val reasons = mutableListOf<String>()

        val conditionPairs = listOf(
            Triple(current.fasting, previous.fasting, "digiuno"),
            Triple(current.justWokeUp, previous.justWokeUp, "appena sveglio"),
            Triple(current.afterBathroom, previous.afterBathroom, "dopo bagno"),
            Triple(current.noRecentWorkout, previous.noRecentWorkout, "assenza di allenamento recente"),
        )
        conditionPairs.forEach { (a, b, label) ->
            if (a == b) {
                score += 15
                reasons += "Condizione coerente: $label"
            } else {
                reasons += "Condizione diversa: $label"
            }
        }

        val currentHour = Instant.ofEpochMilli(current.measuredAtEpochMillis).atZone(zoneId).toLocalTime().toSecondOfDay() / 3600f
        val previousHour = Instant.ofEpochMilli(previous.measuredAtEpochMillis).atZone(zoneId).toLocalTime().toSecondOfDay() / 3600f
        val rawHourDelta = abs(currentHour - previousHour)
        // Measurements close to midnight can be only a few minutes apart despite crossing 00:00.
        val hourDelta = minOf(rawHourDelta, 24f - rawHourDelta)
        when {
            hourDelta <= 1.5f -> {
                score += 40
                reasons += "Orario molto simile"
            }
            hourDelta <= 3f -> {
                score += 25
                reasons += "Orario abbastanza simile"
            }
            hourDelta <= 6f -> {
                score += 10
                reasons += "Orario poco simile"
            }
            else -> reasons += "Orario molto diverso"
        }

        val level = when {
            score >= 80 -> Level.HIGH
            score >= 55 -> Level.MEDIUM
            else -> Level.LOW
        }
        return Result(level, score.coerceIn(0, 100), reasons)
    }

    fun label(level: Level): String = when (level) {
        Level.HIGH -> "Alta"
        Level.MEDIUM -> "Media"
        Level.LOW -> "Bassa"
        Level.INSUFFICIENT -> "Non valutabile"
    }
}
