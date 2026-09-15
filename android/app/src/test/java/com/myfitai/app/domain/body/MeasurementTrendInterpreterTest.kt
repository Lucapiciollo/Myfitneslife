package com.myfitai.app.domain.body

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementTrendInterpreterTest {
    @Test
    fun `returns insufficient with less than two points`() {
        val result = MeasurementTrendInterpreter.interpret("Peso", null, 1)
        assertEquals(MeasurementTrendInterpreter.Tone.INSUFFICIENT, result.tone)
    }

    @Test
    fun `marks expected favorable direction as favorable`() {
        val result = MeasurementTrendInterpreter.interpret(
            label = "Grasso corporeo",
            delta = -1.2f,
            pointCount = 4,
            favorableDirection = MeasurementTrendInterpreter.Direction.DOWN,
        )
        assertEquals(MeasurementTrendInterpreter.Tone.FAVORABLE, result.tone)
        assertEquals(MeasurementTrendInterpreter.Direction.DOWN, result.direction)
    }

    @Test
    fun `keeps neutral metrics descriptive`() {
        val result = MeasurementTrendInterpreter.interpret(
            label = "Peso",
            delta = -1.0f,
            pointCount = 4,
            favorableDirection = null,
        )
        assertEquals(MeasurementTrendInterpreter.Tone.NEUTRAL, result.tone)
        assertTrue(result.message.contains("contesto dell'obiettivo"))
    }

    @Test
    fun `stable values are neutral`() {
        val result = MeasurementTrendInterpreter.interpret(
            label = "Vita",
            delta = 0.1f,
            pointCount = 3,
            stableThreshold = 0.2f,
        )
        assertEquals(MeasurementTrendInterpreter.Direction.STABLE, result.direction)
        assertEquals("Trend stabile", result.title)
    }
}
