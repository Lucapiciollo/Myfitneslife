package com.myfitai.app.domain.body

import kotlin.math.abs

/** Local, deterministic interpretation of measurement trends. No AI call is required. */
object MeasurementTrendInterpreter {
    enum class Direction { UP, DOWN, STABLE, INSUFFICIENT }
    enum class Tone { FAVORABLE, NEUTRAL, MONITOR, INSUFFICIENT }

    data class Result(
        val direction: Direction,
        val tone: Tone,
        val title: String,
        val message: String,
    )

    fun interpret(
        label: String,
        delta: Float?,
        pointCount: Int,
        favorableDirection: Direction? = null,
        stableThreshold: Float = 0.2f,
        qualityLabel: String? = null,
    ): Result {
        if (delta == null || pointCount < 2) {
            return Result(
                direction = Direction.INSUFFICIENT,
                tone = Tone.INSUFFICIENT,
                title = "Dati insufficienti",
                message = "Servono almeno due rilevazioni nel periodo per descrivere un andamento affidabile.",
            )
        }

        val direction = when {
            abs(delta) < stableThreshold -> Direction.STABLE
            delta > 0f -> Direction.UP
            else -> Direction.DOWN
        }

        val tone = when {
            direction == Direction.STABLE -> Tone.NEUTRAL
            favorableDirection == null -> Tone.NEUTRAL
            direction == favorableDirection -> Tone.FAVORABLE
            else -> Tone.MONITOR
        }

        val title = when (tone) {
            Tone.FAVORABLE -> "Trend favorevole"
            Tone.MONITOR -> "Da monitorare"
            Tone.NEUTRAL -> when (direction) {
                Direction.STABLE -> "Trend stabile"
                Direction.UP -> "Trend in aumento"
                Direction.DOWN -> "Trend in diminuzione"
                Direction.INSUFFICIENT -> "Dati insufficienti"
            }
            Tone.INSUFFICIENT -> "Dati insufficienti"
        }

        val movement = when (direction) {
            Direction.UP -> "$label è in aumento nel periodo selezionato."
            Direction.DOWN -> "$label è in diminuzione nel periodo selezionato."
            Direction.STABLE -> "$label è sostanzialmente stabile nel periodo selezionato."
            Direction.INSUFFICIENT -> ""
        }
        val quality = qualityLabel?.takeIf { it.isNotBlank() }?.let { " Qualità del confronto: $it." }.orEmpty()
        val caution = if (favorableDirection == null) " Il significato va letto nel contesto dell'obiettivo personale." else ""

        return Result(direction, tone, title, movement + quality + caution)
    }
}
