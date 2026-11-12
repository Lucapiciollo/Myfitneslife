package com.myfitai.app.domain.body

import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiaHistoryTextTest {
    private fun reading(
        weight: Float? = 79.4f,
        fat: Float? = 20f,
        muscle: Float? = null,
        bmi: Float? = null,
        fasting: Boolean = false,
        woke: Boolean = false,
    ) = BiaMeasurementEntity(
        profileId = 1L, measuredAtEpochMillis = 0L, weightKg = weight, bodyFatPercent = fat,
        visceralFatLevel = null, muscleMassKg = muscle, skeletalMuscleKg = null, bodyWaterPercent = null, bmrKcal = null,
        fasting = fasting, justWokeUp = woke, afterBathroom = false, noRecentWorkout = false, bmi = bmi,
    )

    @Test
    fun values_listsPresentValuesWithUnitsAndItalianDecimals() {
        assertEquals("Peso 79,4 kg · Grasso 20 %", BiaHistoryText.values(reading()))
    }

    @Test
    fun values_withoutAnyValue_hasExplicitText() {
        assertEquals("Valori parziali", BiaHistoryText.values(reading(weight = null, fat = null)))
    }

    @Test
    fun mainValues_keepsOnlyWeightFatAndMuscle() {
        val text = BiaHistoryText.mainValues(reading(muscle = 33.2f, bmi = 24.1f))
        assertEquals("Peso 79,4 kg · Grasso 20 % · Massa muscolare 33,2 kg", text)
    }

    @Test
    fun mainValues_fallsBackToAllValuesWhenNoMainValue() {
        assertEquals("BMI 24,1", BiaHistoryText.mainValues(reading(weight = null, fat = null, bmi = 24.1f)))
    }

    @Test
    fun conditions_areWordsOrNull() {
        assertNull(BiaHistoryText.conditions(reading()))
        assertEquals("Condizioni: a digiuno · appena sveglio", BiaHistoryText.conditions(reading(fasting = true, woke = true)))
    }

    @Test
    fun cardDescription_hasDateValuesThenConditions_withoutGestureHints() {
        val template = "Rilevazione BIA del %1\$s. %2\$s."
        val plain = BiaHistoryText.cardDescription(template, "12/11/2026 08:30", "Peso 79,4 kg", null)
        assertEquals("Rilevazione BIA del 12/11/2026 08:30. Peso 79,4 kg.", plain)
        val withConditions = BiaHistoryText.cardDescription(template, "12/11/2026 08:30", "Peso 79,4 kg", "Condizioni: a digiuno")
        assertEquals("Rilevazione BIA del 12/11/2026 08:30. Peso 79,4 kg. Condizioni: a digiuno.", withConditions)
        assertTrue(!withConditions.contains("Tieni premuto"))
    }
}
