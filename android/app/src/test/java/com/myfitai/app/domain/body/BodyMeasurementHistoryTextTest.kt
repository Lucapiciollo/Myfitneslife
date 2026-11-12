package com.myfitai.app.domain.body

import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyMeasurementHistoryTextTest {
    private fun entity(
        waist: Float? = null,
        hips: Float? = null,
        armLeft: Float? = null,
        weight: Float? = null,
    ) = BodyMeasurementEntity(
        profileId = 1L, measuredAtEpochMillis = 0L,
        chestCm = null, waistCm = waist, abdomenCm = null, shouldersCm = null, glutesCm = null,
        armLeftCm = armLeft, armRightCm = null, thighLeftCm = null, thighRightCm = null,
        calfLeftCm = null, calfRightCm = null, hipsCm = hips, weightKg = weight,
    )

    @Test
    fun values_listsWeightAndCircumferencesInOrderWithUnits() {
        val text = BodyMeasurementHistoryText.values(entity(waist = 90f, hips = 101.5f, weight = 80f), 80f, false)
        assertEquals("Peso corporeo 80,0 kg · Fianchi 101,5 cm · Vita 90 cm", text)
    }

    @Test
    fun values_marksWeightComingFromBia() {
        val text = BodyMeasurementHistoryText.values(entity(), 79.4f, true)
        assertEquals("Peso BIA (stessa data) 79,4 kg", text)
    }

    @Test
    fun values_emptyEntry_hasExplicitText() {
        assertEquals("Rilevazione senza valori", BodyMeasurementHistoryText.values(entity(), null, false))
    }

    @Test
    fun values_includesSideLabelsInWords() {
        val text = BodyMeasurementHistoryText.values(entity(armLeft = 31f), null, false)
        assertTrue(text, text.contains("Braccio sx 31 cm"))
    }

    @Test
    fun cardDescription_hasDateThenValues_andNoLongPressHints() {
        val description = BodyMeasurementHistoryText.cardDescription(
            template = "Misurazione del %1\$s. %2\$s.",
            date = "12/11/2026",
            values = "Vita 90 cm",
            waistNote = null,
        )
        assertEquals("Misurazione del 12/11/2026. Vita 90 cm.", description)
        val withNote = BodyMeasurementHistoryText.cardDescription(
            template = "Misurazione del %1\$s. %2\$s.",
            date = "12/11/2026",
            values = "Vita 90 cm",
            waistNote = "Vita -1,0 cm rispetto al valore precedente disponibile",
        )
        assertEquals("Misurazione del 12/11/2026. Vita 90 cm. Vita -1,0 cm rispetto al valore precedente disponibile.", withNote)
    }
}
