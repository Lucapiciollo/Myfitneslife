package com.myfitai.app.domain.food

import org.junit.Assert.assertEquals
import org.junit.Test

class SportsNutritionClassifierTest {

    @Test
    fun sedentary_isNormal() {
        assertEquals(SportsNutritionClassifier.Mode.NORMAL, SportsNutritionClassifier.classify("sedentario"))
    }

    @Test
    fun moderateAndLightLevels_areNormal() {
        assertEquals(SportsNutritionClassifier.Mode.NORMAL, SportsNutritionClassifier.classify("moderatamente attivo"))
        assertEquals(SportsNutritionClassifier.Mode.NORMAL, SportsNutritionClassifier.classify("leggermente attivo"))
    }

    @Test
    fun veryActive_isSport_regardlessOfCaseAndSpaces() {
        assertEquals(SportsNutritionClassifier.Mode.SPORT, SportsNutritionClassifier.classify("molto attivo"))
        assertEquals(SportsNutritionClassifier.Mode.SPORT, SportsNutritionClassifier.classify("  Molto attivo "))
    }

    @Test
    fun storedExtremelyActiveLevel_isSport() {
        assertEquals(SportsNutritionClassifier.Mode.SPORT, SportsNutritionClassifier.classify("Estremamente attivo"))
    }

    @Test
    fun missingOrUnknownLevel_isNormal() {
        assertEquals(SportsNutritionClassifier.Mode.NORMAL, SportsNutritionClassifier.classify(null))
        assertEquals(SportsNutritionClassifier.Mode.NORMAL, SportsNutritionClassifier.classify("boh"))
    }
}
