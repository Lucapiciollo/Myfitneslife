package com.myfitai.app.ui.widgets

import com.myfitai.app.ui.widgets.ChartAccessibility.EvolutionMetric
import com.myfitai.app.ui.widgets.ChartAccessibility.Point
import com.myfitai.app.ui.widgets.ChartAccessibility.SummaryTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartAccessibilityTest {
    private val templates = SummaryTemplates(
        empty = "%1\$s, nessuna rilevazione nel periodo",
        single = "%1\$s, 1 rilevazione del %2\$s: %3\$s",
        multiple = "%1\$s, %2\$d rilevazioni dal %3\$s al %4\$s, da %5\$s a %6\$s",
    )

    private val weights = listOf(
        Point("01/10/2026", 82.4f),
        Point("15/10/2026", 81f),
        Point("12/11/2026", 79.9f),
    )

    @Test
    fun summary_describesLabelCountPeriodAndRange() {
        assertEquals(
            "Peso, 3 rilevazioni dal 01/10/2026 al 12/11/2026, da 82,4 a 79,9 kg",
            ChartAccessibility.summary(templates, "Peso", "kg", weights),
        )
    }

    @Test
    fun summary_usesTheSelectedMetricAndUnit() {
        val fat = listOf(Point("01/10/2026", 24.5f), Point("12/11/2026", 23f))
        assertEquals(
            "Grasso corporeo, 2 rilevazioni dal 01/10/2026 al 12/11/2026, da 24,5 a 23,0%",
            ChartAccessibility.summary(templates, "Grasso corporeo", "%", fat),
        )
        val waist = listOf(Point("01/10/2026", 90f), Point("12/11/2026", 88.5f))
        assertTrue(ChartAccessibility.summary(templates, "Vita", "cm", waist).startsWith("Vita, 2 rilevazioni"))
        assertTrue(ChartAccessibility.summary(templates, "Vita", "cm", waist).endsWith("da 90,0 a 88,5 cm"))
    }

    @Test
    fun summary_handlesEmptyAndSinglePoint() {
        assertEquals("Peso, nessuna rilevazione nel periodo", ChartAccessibility.summary(templates, "Peso", "kg", emptyList()))
        assertEquals(
            "Peso, 1 rilevazione del 12/11/2026: 79,9 kg",
            ChartAccessibility.summary(templates, "Peso", "kg", listOf(Point("12/11/2026", 79.9f))),
        )
    }

    @Test
    fun tableRows_listEveryPointWithDateAndValueInOrder() {
        assertEquals(
            listOf("01/10/2026: 82,4 kg", "15/10/2026: 81,0 kg", "12/11/2026: 79,9 kg"),
            ChartAccessibility.tableRows("kg", weights),
        )
        assertEquals(listOf("01/10/2026: 24,5%"), ChartAccessibility.tableRows("%", listOf(Point("01/10/2026", 24.5f))))
        assertTrue(ChartAccessibility.tableRows("cm", emptyList()).isEmpty())
    }

    @Test
    fun legendAndAxis_alwaysCarryMetricAndUnit() {
        assertEquals("Grasso corporeo (%)", ChartAccessibility.legend("Grasso corporeo", "%"))
        assertEquals("Peso (kg)", ChartAccessibility.legend("Peso", "kg"))
        assertEquals("80,5 kg", ChartAccessibility.axisValue(80.5f, "kg"))
        assertEquals("23,0%", ChartAccessibility.axisValue(23f, "%"))
        assertEquals("90,0 cm", ChartAccessibility.axisValue(90f, "cm"))
    }

    @Test
    fun explanation_saysWhetherTheChartIsNormalized() {
        val real = "Valori reali di %1\$s in %2\$s."
        val normalized = "Variazione percentuale di %1\$s rispetto alla prima rilevazione."
        assertEquals("Valori reali di Vita in cm.", ChartAccessibility.explanation(false, real, normalized, "Vita", "cm"))
        assertEquals(
            "Variazione percentuale di Vita rispetto alla prima rilevazione.",
            ChartAccessibility.explanation(true, real, normalized, "Vita", "cm"),
        )
    }

    @Test
    fun evolutionMetric_picksUnitFromTheSelectedSegment() {
        assertEquals(EvolutionMetric.WEIGHT, EvolutionMetric.fromIndex(0))
        assertEquals("kg", EvolutionMetric.fromIndex(0).unit)
        assertEquals("%", EvolutionMetric.fromIndex(1).unit)
        assertEquals(EvolutionMetric.BODY_FAT, EvolutionMetric.fromIndex(1))
        assertEquals("kg", EvolutionMetric.fromIndex(2).unit)
        assertEquals(EvolutionMetric.MUSCLE, EvolutionMetric.fromIndex(2))
        assertEquals(EvolutionMetric.WEIGHT, EvolutionMetric.fromIndex(42))
    }

    @Test
    fun seriesStyle_firstFourSeriesDifferWithoutRelyingOnColor() {
        val styles = (0..3).map { ChartSeriesStyle.forIndex(it) }
        assertEquals(4, styles.toSet().size)
        assertEquals(ChartSeriesStyle.forIndex(0), ChartSeriesStyle.forIndex(4))
        assertNotEquals(ChartSeriesStyle.forIndex(0), ChartSeriesStyle.forIndex(1))
        assertEquals(ChartSeriesStyle.forIndex(0), ChartSeriesStyle.forIndex(-4))
    }
}
