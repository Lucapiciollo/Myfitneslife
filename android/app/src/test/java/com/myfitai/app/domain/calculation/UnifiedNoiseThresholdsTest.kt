package com.myfitai.app.domain.calculation

import com.myfitai.app.domain.body.BiaNoiseThresholds
import com.myfitai.app.domain.body.MeasurementTrendInterpreter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K2 (3): "stabile/variato" ha UN solo verdetto, deciso da BiaNoiseThresholds con un solo operatore
 * (|delta| <= soglia = nessuna variazione affidabile). Lo stesso delta deve dare lo stesso verdetto in
 * LocalCalculationEngine.trend, AdaptiveNutritionTargetEngine, MeasurementTrendInterpreter (usato da
 * BiaTrendBinder) e LocalCalculationEngine.classifyRecomposition.
 */
class UnifiedNoiseThresholdsTest {

    private fun series(start: Double, delta: Double) = listOf(
        LocalCalculationEngine.TimedValue(1_000L, start),
        LocalCalculationEngine.TimedValue(2_000L, start + delta),
    )

    private fun evidence(delta: Double) = AdaptiveNutritionTargetEngine.Evidence(count = 3, spanDays = 28, delta = delta)

    /** Grasso e muscolo con il delta indicato; vita/addome non stabili ne favorevoli per isolare i segnali BIA. */
    private fun adaptive(fatDelta: Double, muscleDelta: Double) = AdaptiveNutritionTargetEngine.adjust(
        AdaptiveNutritionTargetEngine.Input(
            goal = LocalCalculationEngine.Goal.WEIGHT_LOSS,
            tdeeKcal = 2800.0,
            baseTargetKcal = 2380.0,
            currentWeightKg = 90.0,
            weight = evidence(0.0),
            bodyFat = evidence(fatDelta),
            muscleMass = evidence(muscleDelta),
            waist = evidence(0.8),
            abdomen = evidence(0.8),
        )
    )

    @Test
    fun trialDelta_fatMinus0_6_andMuscleMinus0_5_areNoiseInAllFourEngines() {
        // 1) trend locale
        assertEquals(
            LocalCalculationEngine.TrendDirection.STABLE,
            LocalCalculationEngine.trend(series(15.0, -0.6), metricKey = "bodyFatPercent").direction,
        )
        assertEquals(
            LocalCalculationEngine.TrendDirection.STABLE,
            LocalCalculationEngine.trend(series(40.0, -0.5), metricKey = "muscleMassKg").direction,
        )
        // 2) adattamento: ne calo muscolare ne segnale favorevole -> nessun cambio sicuro
        val adaptive = adaptive(fatDelta = -0.6, muscleDelta = -0.5)
        assertEquals(AdaptiveNutritionTargetEngine.Decision.KEEP, adaptive.decision)
        assertEquals("NO_SAFE_CHANGE", adaptive.reasonCode)
        // 3) interprete usato da BiaTrendBinder
        assertEquals(
            MeasurementTrendInterpreter.Direction.STABLE,
            MeasurementTrendInterpreter.interpret("Grasso", -0.6f, 4, MeasurementTrendInterpreter.Direction.DOWN, metricKey = "bodyFatPercent").direction,
        )
        assertEquals(
            MeasurementTrendInterpreter.Direction.STABLE,
            MeasurementTrendInterpreter.interpret("Muscolo", -0.5f, 4, MeasurementTrendInterpreter.Direction.UP, metricKey = "muscleMassKg").direction,
        )
        // 4) ricomposizione
        assertEquals(LocalCalculationEngine.RecompositionState.STABLE, LocalCalculationEngine.classifyRecomposition(-0.6, -0.5))
    }

    @Test
    fun deltaAboveNoise_isARealChangeInAllFourEngines() {
        assertEquals(
            LocalCalculationEngine.TrendDirection.DOWN,
            LocalCalculationEngine.trend(series(15.0, -1.2), metricKey = "bodyFatPercent").direction,
        )
        // Calo muscolare reale -> riduce il deficit; grasso in calo reale -> segnale favorevole.
        assertEquals(AdaptiveNutritionTargetEngine.Decision.REDUCE_DEFICIT, adaptive(fatDelta = 0.0, muscleDelta = -1.2).decision)
        assertEquals("FAVORABLE_PROGRESS", adaptive(fatDelta = -1.2, muscleDelta = 0.0).reasonCode)
        assertEquals(
            MeasurementTrendInterpreter.Direction.DOWN,
            MeasurementTrendInterpreter.interpret("Grasso", -1.2f, 4, metricKey = "bodyFatPercent").direction,
        )
        assertEquals(
            LocalCalculationEngine.RecompositionState.FAT_LOSS_WITH_STABLE_MUSCLE,
            LocalCalculationEngine.classifyRecomposition(-1.2, 0.0),
        )
    }

    @Test
    fun deltaExactlyAtThreshold_isStableEverywhere_oneOperator() {
        assertFalse(BiaNoiseThresholds.isReliableChange("bodyFatPercent", -1.0))
        assertEquals(
            LocalCalculationEngine.TrendDirection.STABLE,
            LocalCalculationEngine.trend(series(15.0, -1.0), metricKey = "bodyFatPercent").direction,
        )
        assertEquals(
            MeasurementTrendInterpreter.Direction.STABLE,
            MeasurementTrendInterpreter.interpret("Grasso", -1.0f, 4, metricKey = "bodyFatPercent").direction,
        )
        assertEquals(LocalCalculationEngine.RecompositionState.STABLE, LocalCalculationEngine.classifyRecomposition(-1.0, 1.0))
        assertEquals("NO_SAFE_CHANGE", adaptive(fatDelta = -1.0, muscleDelta = -1.0).reasonCode)
    }

    @Test
    fun weightThreshold_isZeroPointEight_andFloatNoiseDoesNotDecide() {
        // 80,8 - 80,0 in Float vale 0,79999924: arrotondato a 2 decimali = 0,80 = soglia = stabile.
        val delta = 80.8f - 80.0f
        assertEquals(
            MeasurementTrendInterpreter.Direction.STABLE,
            MeasurementTrendInterpreter.interpret("Peso", delta, 4, metricKey = "weightKg").direction,
        )
        assertEquals(
            MeasurementTrendInterpreter.Direction.UP,
            MeasurementTrendInterpreter.interpret("Peso", 0.9f, 4, metricKey = "weightKg").direction,
        )
    }

    @Test
    fun degradedComparability_doublesTheThreshold() {
        assertEquals(
            MeasurementTrendInterpreter.Direction.DOWN,
            MeasurementTrendInterpreter.interpret("Grasso", -1.5f, 4, metricKey = "bodyFatPercent").direction,
        )
        assertEquals(
            MeasurementTrendInterpreter.Direction.STABLE,
            MeasurementTrendInterpreter.interpret("Grasso", -1.5f, 4, metricKey = "bodyFatPercent", degraded = true).direction,
        )
    }

    @Test
    fun metricsWithoutDeclaredThreshold_keepTheLegacyThreshold() {
        // Acqua % non ha soglia in BiaNoiseThresholds: resta la soglia storica passata dal chiamante.
        assertTrue(BiaNoiseThresholds.forMetric("bodyWaterPercent") == null)
        assertEquals(
            MeasurementTrendInterpreter.Direction.STABLE,
            MeasurementTrendInterpreter.interpret("Acqua", 0.1f, 4, stableThreshold = 0.3f, metricKey = null).direction,
        )
    }
}
