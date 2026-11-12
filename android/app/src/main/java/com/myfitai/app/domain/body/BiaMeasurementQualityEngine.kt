package com.myfitai.app.domain.body

import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

object BiaMeasurementQualityEngine {
    enum class Level { HIGH, MEDIUM, LOW, INSUFFICIENT }

    data class Result(
        val level: Level,
        val score: Int,
        val reasons: List<String>,
        /** Motivo principale in una riga, pronto per la UI (senza punteggio). */
        val summary: String = "",
    )

    /** Oltre questa distanza tra le due letture il confronto non puo essere di qualita ALTA (ipotesi reversibile). */
    const val MAX_GAP_DAYS_FOR_HIGH = 90L

    fun evaluate(current: BiaMeasurementEntity, previous: BiaMeasurementEntity?, zoneId: ZoneId = ZoneId.systemDefault()): Result {
        if (previous == null) return Result(Level.INSUFFICIENT, 0, listOf("Manca una rilevazione precedente confrontabile"), "Manca una rilevazione precedente")

        var score = 0
        val reasons = mutableListOf<String>()
        var undeclared = false
        var different = false

        val conditionPairs = listOf(
            Triple(current.fasting, previous.fasting, "digiuno"),
            Triple(current.justWokeUp, previous.justWokeUp, "appena sveglio"),
            Triple(current.afterBathroom, previous.afterBathroom, "dopo bagno"),
            Triple(current.noRecentWorkout, previous.noRecentWorkout, "assenza di allenamento recente"),
        )
        conditionPairs.forEach { (a, b, label) ->
            when {
                // Coerente solo se dichiarata in entrambe le letture: "non dichiarato" in entrambe non e una prova.
                a && b -> {
                    score += 15
                    reasons += "Condizione coerente: $label"
                }
                a != b -> {
                    different = true
                    reasons += "Condizione diversa: $label"
                }
                else -> {
                    undeclared = true
                    reasons += "Condizione non dichiarata: $label"
                }
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

        // Giorni di calendario locali (non millisecondi/86400000): il cambio ora legale non sposta il conteggio.
        val gapDays = abs(
            ChronoUnit.DAYS.between(
                Instant.ofEpochMilli(previous.measuredAtEpochMillis).atZone(zoneId).toLocalDate(),
                Instant.ofEpochMilli(current.measuredAtEpochMillis).atZone(zoneId).toLocalDate(),
            ),
        )
        val tooFarApart = gapDays > MAX_GAP_DAYS_FOR_HIGH
        if (tooFarApart) reasons += "Letture distanti oltre $MAX_GAP_DAYS_FOR_HIGH giorni"

        var level = when {
            score >= 80 -> Level.HIGH
            score >= 55 -> Level.MEDIUM
            else -> Level.LOW
        }
        if (tooFarApart && level == Level.HIGH) level = Level.MEDIUM
        val summary = when {
            tooFarApart -> "Letture distanti nel tempo"
            level == Level.HIGH -> "Condizioni e orario coerenti"
            hourDelta > 6f -> "Orario molto diverso"
            different -> "Condizioni di misura diverse"
            undeclared -> "Condizioni di misura non dichiarate"
            else -> "Orario poco simile"
        }
        return Result(level, score.coerceIn(0, 100), reasons, summary)
    }

    fun label(level: Level): String = when (level) {
        Level.HIGH -> "Alta"
        Level.MEDIUM -> "Media"
        Level.LOW -> "Bassa"
        Level.INSUFFICIENT -> "Non valutabile"
    }
}
