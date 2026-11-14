package com.myfitai.app.domain.food.nutrition

import org.junit.Assert.assertEquals
import org.junit.Test

class DoseFormatterTest {
    private val tsp = UnitEquivalent("cucchiaino", 4.5, "cucchiaini")
    private val tbsp = UnitEquivalent("cucchiaio", 13.5, "cucchiai")
    private val egg = UnitEquivalent("uovo", 50.0, "uova")

    @Test
    fun noEquivalence_givesGramsOnly() {
        assertEquals("150 g", DoseFormatter.format(150.0))
        assertEquals("150 g", DoseFormatter.format(150.0, emptyList()))
        assertEquals("0 g", DoseFormatter.format(0.0, listOf(tsp)))
    }

    @Test
    fun smallWeights_haveOneDecimalWithComma() {
        assertEquals("2,5 g", DoseFormatter.format(2.5))
        assertEquals("5 g", DoseFormatter.format(5.0))
        assertEquals("12 g", DoseFormatter.format(12.4))
    }

    @Test
    fun wholeUnits() {
        assertEquals("1 cucchiaino", DoseFormatter.format(4.5, listOf(tsp)))
        assertEquals("2 uova", DoseFormatter.format(100.0, listOf(egg)))
        assertEquals("1 uovo", DoseFormatter.format(50.0, listOf(egg)))
        assertEquals("1 cucchiaio", DoseFormatter.format(13.5, listOf(tsp, tbsp)))
    }

    @Test
    fun readableFractions() {
        assertEquals("1/2 cucchiaino", DoseFormatter.format(2.25, listOf(tsp)))
        assertEquals("1/4 cucchiaino", DoseFormatter.format(1.125, listOf(tsp)))
        assertEquals("3/4 cucchiaio", DoseFormatter.format(10.0, listOf(tbsp)))
        assertEquals("1 e 1/2 uova", DoseFormatter.format(75.0, listOf(egg)))
        assertEquals("1 e 1/2 cucchiai", DoseFormatter.format(20.0, listOf(tbsp)))
    }

    @Test
    fun equivalentThatDoesNotMatchWithinTolerance_fallsBackToGrams() {
        // 17 g non sono un numero leggibile di cucchiai da 13,5 g (1,26 -> 1,5 = 20,25 g: scarto 19%).
        assertEquals("17 g", DoseFormatter.format(17.0, listOf(tbsp)))
        // Troppo piccolo per l'unità (0,1 uova).
        assertEquals("5 g", DoseFormatter.format(5.0, listOf(egg)))
        // Troppe unità: oltre il tetto leggibile.
        assertEquals("700 g", DoseFormatter.format(700.0, listOf(egg)))
    }

    @Test
    fun preferWholeOverFractionAndLargerUnitOnTies() {
        // 6,75 g: 1/2 cucchiaio oppure 1 e 1/2 cucchiaini -> a parità vince l'unità più grande.
        assertEquals("1/2 cucchiaio", DoseFormatter.format(6.75, listOf(tsp, tbsp)))
        // 9 g: 2 cucchiaini (intero) batte 2/3 cucchiaio (-> 3/4 con scarto).
        assertEquals("2 cucchiaini", DoseFormatter.format(9.0, listOf(tsp, tbsp)))
    }

    @Test
    fun containerQualifier_isAppended() {
        val sachet = UnitEquivalent("bustina", 30.0, "bustine", qualifier = "da 30 g")
        val glass = UnitEquivalent("bicchiere", 206.0, "bicchieri", qualifier = "da 200 ml")
        assertEquals("1 bustina da 30 g", DoseFormatter.format(30.0, listOf(sachet)))
        assertEquals("2 bustine da 30 g", DoseFormatter.format(60.0, listOf(sachet)))
        assertEquals("1 bicchiere da 200 ml", DoseFormatter.format(206.0, listOf(glass)))
    }

    @Test
    fun invalidEquivalentsAreIgnored_neverInventingWeights() {
        val zero = UnitEquivalent("fetta", 0.0)
        val blank = UnitEquivalent(" ", 30.0)
        assertEquals("30 g", DoseFormatter.format(30.0, listOf(zero, blank)))
    }

    @Test
    fun toleranceIsConfigurable() {
        // 10 g con cucchiaio da 13,5 g: 3/4 = 10,125 g (scarto 1,25%): accettato a 3%, rifiutato a 1%.
        assertEquals("3/4 cucchiaio", DoseFormatter.format(10.0, listOf(tbsp), 0.03))
        assertEquals("10 g", DoseFormatter.format(10.0, listOf(tbsp), 0.01))
    }
}
