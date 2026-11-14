package com.myfitai.app.domain.body

import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.domain.body.FlagFixtures.FEMALE
import com.myfitai.app.domain.body.FlagFixtures.MALE
import com.myfitai.app.domain.body.FlagFixtures.bia
import com.myfitai.app.domain.body.FlagFixtures.body
import com.myfitai.app.domain.body.FlagFixtures.codes
import com.myfitai.app.domain.body.FlagFixtures.eval
import com.myfitai.app.domain.body.FlagFixtures.profile
import com.myfitai.app.domain.body.FlagFixtures.skipReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Profili sintetici con i flag attesi (ordine incluso). Se una soglia cambia, questo test deve cambiare di proposito. */
class BodyFlagGoldenTest {

    private class Case(
        val name: String,
        val profile: BodyFlagProfile,
        val bia: List<BiaMeasurementEntity> = emptyList(),
        val body: List<BodyMeasurementEntity> = emptyList(),
        val expected: List<BodyFlagCode>,
    )

    private val cases = listOf(
        Case(
            "donna 28 anni sana e stabile",
            profile(FEMALE, 28, 165.0),
            body = listOf(body(4, waist = 70f, weight = 60f)),
            expected = emptyList(),
        ),
        Case(
            "uomo 45 anni vita 104 e BMI 30,9",
            profile(MALE, 45, 180.0),
            body = listOf(body(4, waist = 104f, weight = 100f)),
            expected = listOf(BodyFlagCode.WAIST_SECOND_LEVEL, BodyFlagCode.WHTR_ABOVE_HALF, BodyFlagCode.BMI_BAND_HIGH),
        ),
        Case(
            "donna 52 anni vita 90 con viscerale in salita",
            profile(FEMALE, 52, 160.0),
            bia = listOf(bia(56, weight = 78f, visceral = 8f), bia(28, weight = 78f, visceral = 9f), bia(0, weight = 78f, visceral = 10f)),
            body = listOf(body(4, waist = 90f)),
            expected = listOf(
                BodyFlagCode.WAIST_SECOND_LEVEL, BodyFlagCode.WHTR_ABOVE_HALF,
                BodyFlagCode.VISCERAL_FAT_RISING, BodyFlagCode.BMI_BAND_HIGH,
            ),
        ),
        Case(
            "donna 62 anni con calo di muscolo scheletrico",
            profile(FEMALE, 62, 158.0),
            bia = listOf(
                bia(56, weight = 55f, skeletal = 20.0f), bia(28, weight = 55f, skeletal = 19.0f), bia(0, weight = 55f, skeletal = 18.2f),
            ),
            body = listOf(body(4, waist = 74f)),
            expected = listOf(BodyFlagCode.SKELETAL_MUSCLE_DECLINE),
        ),
        Case(
            "uomo 30 anni con calo peso 1,2 kg a settimana",
            profile(MALE, 30, 178.0),
            body = listOf(body(14, waist = 85f, weight = 90f), body(7, weight = 88.8f), body(0, weight = 87.6f)),
            expected = listOf(BodyFlagCode.RAPID_WEIGHT_LOSS, BodyFlagCode.BMI_BAND_OVER),
        ),
        Case(
            "donna 35 anni con quota di massa magra persa 50%",
            profile(FEMALE, 35, 170.0),
            bia = listOf(
                bia(56, weight = 70f, lean = 50f, fat = 20.0f), bia(28, weight = 67f, lean = 48.5f, fat = 20.4f),
                bia(0, weight = 64f, lean = 47f, fat = 20.6f),
            ),
            body = listOf(body(4, waist = 70f)),
            expected = listOf(BodyFlagCode.LEAN_MASS_LOSS_SHARE),
        ),
        Case(
            "ragazzo 17 anni fuori perimetro",
            profile(MALE, 17, 175.0),
            body = listOf(body(4, waist = 110f, weight = 120f)),
            expected = emptyList(),
        ),
        Case(
            "uomo 65 anni con serie rumorosa (condizioni diverse)",
            profile(MALE, 65, 175.0),
            bia = listOf(
                bia(56, weight = 70f, skeletal = 26f, fat = 25f),
                bia(28, weight = 70f, skeletal = 24.5f, fat = 23.5f, conditions = false),
                bia(0, weight = 70f, skeletal = 23f, fat = 22f),
            ),
            body = listOf(body(4, waist = 80f)),
            expected = emptyList(),
        ),
        Case(
            "uomo 40 anni con soli dati di peso: nessun input per le altre regole",
            profile(MALE, 40, 175.0),
            body = listOf(body(4, weight = 70f)),
            expected = emptyList(),
        ),
    )

    @Test
    fun goldenProfiles_produceExpectedFlags() {
        cases.forEach { case ->
            assertEquals(case.name, case.expected, codes(eval(case.profile, case.bia, case.body)))
        }
    }

    @Test
    fun goldenProfiles_areStable_acrossRepeatedAndShuffledEvaluation() {
        cases.forEach { case ->
            val first = eval(case.profile, case.bia, case.body)
            assertEquals(case.name, first, eval(case.profile, case.bia, case.body))
            assertEquals(case.name, first.flags, eval(case.profile, case.bia.reversed(), case.body.reversed()).flags)
        }
    }

    @Test
    fun goldenProfiles_missingDataIsDeclared() {
        val onlyWeight = cases.last()
        val result = eval(onlyWeight.profile, onlyWeight.bia, onlyWeight.body)
        assertEquals(BodyFlagSkipReason.WAIST_MISSING, skipReason(result, BodyFlagRule.WAIST_HEIGHT_RATIO))
        assertEquals(BodyFlagSkipReason.BIA_MISSING, skipReason(result, BodyFlagRule.BODY_FAT_TREND))
        assertEquals(BodyFlagSkipReason.WAIST_MISSING, skipReason(result, BodyFlagRule.VISCERAL_FAT))
        assertEquals(BodyFlagThresholds.VERSION, result.thresholdsVersion)
    }

    @Test
    fun everyFlag_hasEvidenceAndKnownSources() {
        cases.forEach { case ->
            eval(case.profile, case.bia, case.body).flags.forEach { flag ->
                assertTrue("${case.name}: evidenza mancante per ${flag.code}", flag.evidence.isNotEmpty())
                assertTrue("${case.name}: fonti mancanti per ${flag.code}", flag.sourceIds.isNotEmpty())
                flag.sourceIds.forEach { id ->
                    val source = BodyFlagThresholds.SOURCES[id]
                    assertTrue("fonte sconosciuta $id", source != null)
                    assertFalse(
                        "fonte non verificata e non usata referenziata: $id",
                        source!!.verification == SourceVerification.UNVERIFIED_NOT_USED,
                    )
                }
                assertEquals(flag.code.messageKey, flag.messageKey)
            }
        }
    }
}
