package com.myfitai.app.domain.body

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyValueRangesTest {
    @Test
    fun plausibleBia_isAccepted() {
        val values = BodyValueRanges.BiaValues(
            weightKg = 90.7f, bodyFatPercent = 20.4f, visceralFatLevel = 6f, muscleMassKg = 67.3f, skeletalMuscleKg = 41.5f,
            bodyWaterPercent = 58.3f, bmrKcal = 1929f, fatMassKg = 18.5f, leanMassKg = 72.1f, bodyWaterKg = 52.9f,
            subcutaneousFatPercent = 14.6f, boneMassKg = 4.8f, proteinPercent = 15.9f, proteinKg = 14.4f, bodyAgeYears = 42f, bmi = 26.5f,
        )
        assertNull(BodyValueRanges.validateBia(values))
    }

    @Test
    fun impossibleWeight_namesFieldValueAndRange() {
        val message = BodyValueRanges.validateBia(BodyValueRanges.BiaValues(weightKg = 20f))
        assertNotNull(message)
        assertTrue(message!!, message.contains("Peso"))
        assertTrue(message, message.contains("20"))
        assertTrue(message, message.contains("30–300 kg"))
    }

    @Test
    fun everyBiaFieldHasAnEnforcedRange() {
        BodyValueRanges.BiaField.entries.forEach { field ->
            val tooHigh = field.range.max * 2f
            fun pick(target: BodyValueRanges.BiaField) = if (field == target) tooHigh else null
            val values = BodyValueRanges.BiaValues(
                weightKg = pick(BodyValueRanges.BiaField.WEIGHT),
                bodyFatPercent = pick(BodyValueRanges.BiaField.BODY_FAT),
                visceralFatLevel = pick(BodyValueRanges.BiaField.VISCERAL),
                muscleMassKg = pick(BodyValueRanges.BiaField.MUSCLE),
                skeletalMuscleKg = pick(BodyValueRanges.BiaField.SKELETAL),
                bodyWaterPercent = pick(BodyValueRanges.BiaField.WATER_PERCENT),
                bmrKcal = pick(BodyValueRanges.BiaField.BMR),
                fatMassKg = pick(BodyValueRanges.BiaField.FAT_MASS),
                leanMassKg = pick(BodyValueRanges.BiaField.LEAN_MASS),
                bodyWaterKg = pick(BodyValueRanges.BiaField.WATER_KG),
                subcutaneousFatPercent = pick(BodyValueRanges.BiaField.SUBCUTANEOUS),
                boneMassKg = pick(BodyValueRanges.BiaField.BONE),
                proteinPercent = pick(BodyValueRanges.BiaField.PROTEIN_PCT),
                proteinKg = pick(BodyValueRanges.BiaField.PROTEIN_MASS),
                bodyAgeYears = pick(BodyValueRanges.BiaField.BODY_AGE),
                bmi = pick(BodyValueRanges.BiaField.BMI_FIELD),
            )
            val message = BodyValueRanges.validateBia(values)
            assertNotNull(field.name, message)
            assertTrue(message!!, message.contains(field.label))
        }
    }

    @Test
    fun zeroNegativeAndNaN_areRejected() {
        assertNotNull(BodyValueRanges.validateBia(BodyValueRanges.BiaValues(bodyFatPercent = 0f)))
        assertNotNull(BodyValueRanges.validateBia(BodyValueRanges.BiaValues(weightKg = -80f)))
        assertNotNull(BodyValueRanges.validateBia(BodyValueRanges.BiaValues(weightKg = Float.NaN)))
    }

    @Test
    fun leanMassAboveWeight_isIncoherent() {
        val message = BodyValueRanges.validateBia(BodyValueRanges.BiaValues(weightKg = 60f, leanMassKg = 75f))
        assertNotNull(message)
        assertTrue(message!!, message.contains("Massa magra"))
        assertTrue(message, message.contains("peso"))
    }

    @Test
    fun leanMassWithoutWeight_isNotComparedWithAnything() {
        assertNull(BodyValueRanges.validateBia(BodyValueRanges.BiaValues(leanMassKg = 75f)))
    }

    @Test
    fun measurementsValidateWeightAndCircumferences() {
        assertNull(BodyValueRanges.validateMeasurements(listOf("Vita" to 82f, "Torace" to null), 80f))
        val tooBig = BodyValueRanges.validateMeasurements(listOf("Vita" to 400f), null)
        assertTrue(tooBig!!, tooBig.contains("Vita") && tooBig.contains("10–250 cm"))
        val tooSmallWeight = BodyValueRanges.validateMeasurements(listOf("Vita" to 82f), 12f)
        assertTrue(tooSmallWeight!!, tooSmallWeight.contains("Peso"))
        assertNull(BodyValueRanges.orNullIfOutOfRange(500f, BodyValueRanges.WEIGHT_KG))
        assertEquals(80f, BodyValueRanges.orNullIfOutOfRange(80f, BodyValueRanges.WEIGHT_KG))
    }
}
