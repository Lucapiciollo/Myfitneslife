package com.myfitai.app.domain.body

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

class BodyFlagEngineTest {

    // ---- Perimetro ----

    @Test
    fun minor_orUnknownAge_producesNoFlagAndDeclaresWhy() {
        val minor = eval(profile(age = 17), body = listOf(body(0, waist = 120f, weight = 100f)))
        assertTrue(minor.flags.isEmpty())
        assertEquals(BodyFlagRule.entries.size, minor.skipped.size)
        assertTrue(minor.skipped.all { it.reason == BodyFlagSkipReason.NOT_ADULT })

        val unknown = eval(profile(age = null), body = listOf(body(0, waist = 120f, weight = 100f)))
        assertTrue(unknown.flags.isEmpty())
        assertTrue(unknown.skipped.all { it.reason == BodyFlagSkipReason.AGE_MISSING })
    }

    @Test
    fun eighteenIsAdult() {
        val result = eval(profile(age = 18), body = listOf(body(0, waist = 108f)))
        assertTrue(BodyFlagCode.WHTR_HIGH in codes(result))
    }

    // ---- (a) WHtR ----

    @Test
    fun whtr_049_noFlag() {
        val result = eval(profile(sex = null), body = listOf(body(5, waist = 88.2f)))
        assertFalse(codes(result).any { it == BodyFlagCode.WHTR_ABOVE_HALF || it == BodyFlagCode.WHTR_HIGH })
    }

    @Test
    fun whtr_050_isAboveHalf_attentionWithHighReliability() {
        val result = eval(profile(sex = null), body = listOf(body(5, waist = 90f)))
        val flag = result.flags.single { it.code == BodyFlagCode.WHTR_ABOVE_HALF }
        assertEquals(BodyFlagSeverity.ATTENZIONE, flag.severity)
        assertEquals(BodyFlagReliability.ALTA, flag.reliability)
        assertEquals(0.5, flag.evidence.first { it.key == "whtr" }.value, 1e-9)
        assertEquals(listOf(BodyFlagThresholds.SRC_NICE_NG246_2025), flag.sourceIds)
        assertEquals("body_flag_whtr_above_half", flag.messageKey)
    }

    @Test
    fun whtr_060_isHigh_andSupersedesAboveHalf() {
        val result = eval(profile(sex = null), body = listOf(body(5, waist = 108f)))
        assertTrue(BodyFlagCode.WHTR_HIGH in codes(result))
        assertFalse(BodyFlagCode.WHTR_ABOVE_HALF in codes(result))
    }

    @Test
    fun whtr_missingWaistOrHeight_noFlagAndReason() {
        val noWaist = eval(profile(sex = null))
        assertEquals(BodyFlagSkipReason.WAIST_MISSING, skipReason(noWaist, BodyFlagRule.WAIST_HEIGHT_RATIO))
        val noHeight = eval(profile(sex = null, height = null), body = listOf(body(5, waist = 120f)))
        assertTrue(noHeight.flags.isEmpty())
        assertEquals(BodyFlagSkipReason.HEIGHT_MISSING, skipReason(noHeight, BodyFlagRule.WAIST_HEIGHT_RATIO))
    }

    @Test
    fun whtr_waistReliability_dependsOnRecency() {
        fun reliabilityFor(daysAgo: Int) = eval(profile(sex = null), body = listOf(body(daysAgo, waist = 95f)))
            .flags.singleOrNull { it.code == BodyFlagCode.WHTR_ABOVE_HALF }?.reliability
        assertEquals(BodyFlagReliability.ALTA, reliabilityFor(90))
        assertEquals(BodyFlagReliability.MEDIA, reliabilityFor(91))
        assertEquals(BodyFlagReliability.MEDIA, reliabilityFor(180))
        assertEquals(null, reliabilityFor(181))
        assertEquals(
            BodyFlagSkipReason.WAIST_TOO_OLD,
            skipReason(eval(profile(sex = null), body = listOf(body(181, waist = 95f))), BodyFlagRule.WAIST_HEIGHT_RATIO),
        )
    }

    @Test
    fun waist_usesLatestWaistEvenWhenNewerRowHasNoWaist() {
        val result = eval(profile(sex = null), body = listOf(body(20, waist = 95f), body(2, weight = 80f)))
        assertTrue(BodyFlagCode.WHTR_ABOVE_HALF in codes(result))
    }

    @Test
    fun thresholdsAreData_customVersionIsReported() {
        val custom = BodyFlagThresholds.DEFAULT.copy(version = "test.1", whtrFirstLevel = 0.55)
        val result = eval(profile(sex = null), body = listOf(body(5, waist = 90f)), thresholds = custom)
        assertEquals("test.1", result.thresholdsVersion)
        assertFalse(BodyFlagCode.WHTR_ABOVE_HALF in codes(result))
    }

    // ---- (b) circonferenza vita ----

    @Test
    fun waist_male_levels() {
        fun code(cm: Float) = codes(eval(profile(sex = MALE, height = 230.0), body = listOf(body(3, waist = cm))))
            .singleOrNull { it == BodyFlagCode.WAIST_FIRST_LEVEL || it == BodyFlagCode.WAIST_SECOND_LEVEL }
        assertEquals(null, code(93.9f))
        assertEquals(BodyFlagCode.WAIST_FIRST_LEVEL, code(94f))
        assertEquals(BodyFlagCode.WAIST_FIRST_LEVEL, code(101.9f))
        assertEquals(BodyFlagCode.WAIST_SECOND_LEVEL, code(102f))
    }

    @Test
    fun waist_female_levels() {
        fun code(cm: Float) = codes(eval(profile(sex = FEMALE, height = 230.0), body = listOf(body(3, waist = cm))))
            .singleOrNull { it == BodyFlagCode.WAIST_FIRST_LEVEL || it == BodyFlagCode.WAIST_SECOND_LEVEL }
        assertEquals(null, code(79.9f))
        assertEquals(BodyFlagCode.WAIST_FIRST_LEVEL, code(80f))
        assertEquals(BodyFlagCode.WAIST_FIRST_LEVEL, code(87.9f))
        assertEquals(BodyFlagCode.WAIST_SECOND_LEVEL, code(88f))
    }

    @Test
    fun waist_sameValueDiffersBySex() {
        val male = eval(profile(sex = MALE, height = 230.0), body = listOf(body(3, waist = 85f)))
        val female = eval(profile(sex = FEMALE, height = 230.0), body = listOf(body(3, waist = 85f)))
        assertFalse(BodyFlagCode.WAIST_FIRST_LEVEL in codes(male))
        assertTrue(BodyFlagCode.WAIST_FIRST_LEVEL in codes(female))
    }

    @Test
    fun waist_unknownSex_noFlagAndReason() {
        val result = eval(profile(sex = null, height = 230.0), body = listOf(body(3, waist = 130f)))
        assertFalse(codes(result).any { it == BodyFlagCode.WAIST_FIRST_LEVEL || it == BodyFlagCode.WAIST_SECOND_LEVEL })
        assertEquals(BodyFlagSkipReason.SEX_MISSING, skipReason(result, BodyFlagRule.WAIST_CIRCUMFERENCE))
    }

    // ---- (c) BMI ----

    @Test
    fun bmi_bands_areInfoOnly() {
        fun code(kg: Float) = codes(eval(profile(sex = null, height = 200.0), body = listOf(body(2, weight = kg))))
        assertEquals(listOf(BodyFlagCode.BMI_BAND_UNDER), code(73.6f))
        assertTrue(code(74f).isEmpty())
        assertTrue(code(99.6f).isEmpty())
        assertEquals(listOf(BodyFlagCode.BMI_BAND_OVER), code(100f))
        assertEquals(listOf(BodyFlagCode.BMI_BAND_OVER), code(119.6f))
        assertEquals(listOf(BodyFlagCode.BMI_BAND_HIGH), code(120f))
        val all = eval(profile(sex = null, height = 200.0), body = listOf(body(2, weight = 120f)))
        assertTrue(all.flags.all { it.severity == BodyFlagSeverity.INFO })
    }

    @Test
    fun bmi_missingOrStaleWeight_noFlagAndReason() {
        assertEquals(BodyFlagSkipReason.WEIGHT_MISSING, skipReason(eval(profile()), BodyFlagRule.BMI))
        val stale = eval(profile(), body = listOf(body(91, weight = 130f)))
        assertEquals(BodyFlagSkipReason.WEIGHT_TOO_OLD, skipReason(stale, BodyFlagRule.BMI))
        assertTrue(stale.flags.isEmpty())
        val noHeight = eval(profile(height = null), body = listOf(body(2, weight = 130f)))
        assertEquals(BodyFlagSkipReason.HEIGHT_MISSING, skipReason(noHeight, BodyFlagRule.BMI))
    }

    @Test
    fun bmi_profileWeightIsNotUsed_onlyMeasuredWeight() {
        // Il motore non riceve il peso del profilo: senza misure non inventa un BMI.
        assertTrue(eval(profile(height = 170.0)).flags.isEmpty())
    }

    // ---- (d) grasso viscerale ----

    private fun visceralSeries(vararg values: Float) =
        values.mapIndexed { i, v -> bia(daysAgo = (values.size - 1 - i) * 28, visceral = v) }

    @Test
    fun visceral_risingOverFourWeeksWithWhtrAbove05_flagsWithLowReliability() {
        val result = eval(profile(), bia = visceralSeries(10f, 11f, 12f), body = listOf(body(3, waist = 95f)))
        val flag = result.flags.single { it.code == BodyFlagCode.VISCERAL_FAT_RISING }
        assertEquals(BodyFlagReliability.BASSA, flag.reliability)
        assertEquals(BodyFlagSeverity.INFO, flag.severity)
    }

    @Test
    fun visceral_whtrBelow05_noFlag() {
        val result = eval(profile(), bia = visceralSeries(10f, 11f, 12f), body = listOf(body(3, waist = 88f)))
        assertFalse(BodyFlagCode.VISCERAL_FAT_RISING in codes(result))
    }

    @Test
    fun visceral_stableOrNotMonotonic_noFlag() {
        val waist = listOf(body(3, waist = 95f))
        assertFalse(BodyFlagCode.VISCERAL_FAT_RISING in codes(eval(profile(), bia = visceralSeries(10f, 10f, 10f), body = waist)))
        assertFalse(BodyFlagCode.VISCERAL_FAT_RISING in codes(eval(profile(), bia = visceralSeries(10f, 13f, 11f), body = waist)))
    }

    @Test
    fun visceral_fewerThanThreeMeasurements_orTooShortSpan_noFlagAndReason() {
        val waist = listOf(body(3, waist = 95f))
        val two = eval(profile(), bia = visceralSeries(10f, 12f), body = waist)
        assertEquals(BodyFlagSkipReason.BIA_NOT_ENOUGH_COHERENT_MEASUREMENTS, skipReason(two, BodyFlagRule.VISCERAL_FAT))
        val shortSpan = eval(
            profile(),
            bia = listOf(bia(14, visceral = 10f), bia(7, visceral = 11f), bia(0, visceral = 12f)),
            body = waist,
        )
        assertEquals(BodyFlagSkipReason.OBSERVATION_WINDOW_TOO_SHORT, skipReason(shortSpan, BodyFlagRule.VISCERAL_FAT))
        assertTrue(shortSpan.flags.none { it.code == BodyFlagCode.VISCERAL_FAT_RISING })
    }

    @Test
    fun visceral_noisyMeasurement_breaksSeries_noFlag() {
        val noisy = listOf(bia(56, visceral = 10f), bia(28, visceral = 11f, conditions = false), bia(0, visceral = 12f))
        val result = eval(profile(), bia = noisy, body = listOf(body(3, waist = 95f)))
        assertFalse(BodyFlagCode.VISCERAL_FAT_RISING in codes(result))
        assertEquals(BodyFlagSkipReason.BIA_NOT_ENOUGH_COHERENT_MEASUREMENTS, skipReason(result, BodyFlagRule.VISCERAL_FAT))
    }

    @Test
    fun visceral_missingWaist_noFlagAndReason() {
        val result = eval(profile(), bia = visceralSeries(10f, 11f, 12f))
        assertEquals(BodyFlagSkipReason.WAIST_MISSING, skipReason(result, BodyFlagRule.VISCERAL_FAT))
    }

    @Test
    fun bia_tooOld_noTrendFlag() {
        val old = listOf(bia(130, visceral = 10f), bia(110, visceral = 11f), bia(90, visceral = 12f))
        val result = eval(profile(), bia = old, body = listOf(body(3, waist = 95f)))
        assertEquals(BodyFlagSkipReason.BIA_TOO_OLD, skipReason(result, BodyFlagRule.VISCERAL_FAT))
    }

    // ---- (e) muscolo scheletrico ----

    private fun skeletal(vararg values: Float, weight: Float = 70f) =
        values.mapIndexed { i, v -> bia(daysAgo = (values.size - 1 - i) * 28, skeletal = v, weight = weight) }

    @Test
    fun skeletal_age62WithReliableDecline_flagsCautiouslyWithLowReliability() {
        val result = eval(profile(age = 62, height = 170.0), bia = skeletal(24.0f, 23.2f, 22.2f))
        val flag = result.flags.single { it.code == BodyFlagCode.SKELETAL_MUSCLE_DECLINE }
        assertEquals(BodyFlagReliability.BASSA, flag.reliability)
        assertEquals("body_flag_skeletal_muscle_decline_check_strength", flag.messageKey)
        assertTrue(BodyFlagThresholds.SRC_EWGSOP2_2019 in flag.sourceIds)
    }

    @Test
    fun skeletal_youngAdultWithSameDecline_noFlag() {
        val result = eval(profile(age = 30, height = 170.0), bia = skeletal(24.0f, 23.2f, 22.2f))
        assertFalse(BodyFlagCode.SKELETAL_MUSCLE_DECLINE in codes(result))
        assertEquals(BodyFlagSkipReason.SECOND_INDICATOR_MISSING, skipReason(result, BodyFlagRule.SKELETAL_MUSCLE))
    }

    @Test
    fun skeletal_youngAdultWithLowBmi_isTheOtherIndicator() {
        val result = eval(profile(age = 30, height = 170.0), bia = skeletal(24.0f, 23.2f, 22.2f, weight = 50f))
        assertTrue(BodyFlagCode.SKELETAL_MUSCLE_DECLINE in codes(result))
    }

    @Test
    fun skeletal_ageExactly60_isIncluded_andSmallDeclineIsIgnored() {
        assertTrue(BodyFlagCode.SKELETAL_MUSCLE_DECLINE in codes(eval(profile(age = 60, height = 170.0), bia = skeletal(24.0f, 23.2f, 22.2f))))
        assertFalse(BodyFlagCode.SKELETAL_MUSCLE_DECLINE in codes(eval(profile(age = 70, height = 170.0), bia = skeletal(24.0f, 23.4f, 22.6f))))
    }

    @Test
    fun skeletal_noisyOrTwoMeasurements_noFlag() {
        val noisy = listOf(
            bia(56, skeletal = 24.0f, weight = 70f),
            bia(28, skeletal = 23.2f, weight = 70f, conditions = false),
            bia(0, skeletal = 22.2f, weight = 70f),
        )
        assertFalse(BodyFlagCode.SKELETAL_MUSCLE_DECLINE in codes(eval(profile(age = 62, height = 170.0), bia = noisy)))
        val two = skeletal(24.0f, 22.2f)
        val result = eval(profile(age = 62, height = 170.0), bia = two)
        assertFalse(BodyFlagCode.SKELETAL_MUSCLE_DECLINE in codes(result))
        assertEquals(BodyFlagSkipReason.BIA_NOT_ENOUGH_COHERENT_MEASUREMENTS, skipReason(result, BodyFlagRule.SKELETAL_MUSCLE))
    }

    @Test
    fun skeletal_recoveredLastValue_noFlag() {
        // Calo e risalita: 24 -> 21 -> 23,5 (ultimo valore non piu basso del minimo precedente + rumore).
        val result = eval(profile(age = 62, height = 170.0), bia = skeletal(25.0f, 21.0f, 23.5f))
        assertFalse(BodyFlagCode.SKELETAL_MUSCLE_DECLINE in codes(result))
    }

    // ---- (f) ritmo di calo del peso ----

    private fun weights(w14: Float, w7: Float, w0: Float) =
        listOf(body(14, weight = w14), body(7, weight = w7), body(0, weight = w0))

    @Test
    fun rapidLoss_09PerWeek_noFlag_11PerWeek_flag_10Boundary_noFlag() {
        assertFalse(BodyFlagCode.RAPID_WEIGHT_LOSS in codes(eval(profile(), body = weights(80f, 79.1f, 78.2f))))
        assertFalse(BodyFlagCode.RAPID_WEIGHT_LOSS in codes(eval(profile(), body = weights(80f, 79f, 78f))))
        val flagged = eval(profile(), body = weights(80f, 78.9f, 77.8f))
        val flag = flagged.flags.single { it.code == BodyFlagCode.RAPID_WEIGHT_LOSS }
        assertEquals(BodyFlagSeverity.ATTENZIONE, flag.severity)
        assertEquals(BodyFlagReliability.ALTA, flag.reliability)
    }

    @Test
    fun rapidLoss_onlyOneWeekAbove_noFlag() {
        assertFalse(BodyFlagCode.RAPID_WEIGHT_LOSS in codes(eval(profile(), body = weights(80f, 79.5f, 78.4f))))
        assertFalse(BodyFlagCode.RAPID_WEIGHT_LOSS in codes(eval(profile(), body = weights(80f, 78.8f, 78.4f))))
    }

    @Test
    fun rapidLoss_weightGain_noFlag() {
        assertFalse(BodyFlagCode.RAPID_WEIGHT_LOSS in codes(eval(profile(), body = weights(76f, 78f, 80f))))
    }

    @Test
    fun rapidLoss_dailyWeighInsAreSupported() {
        val daily = (0..14).map { d -> body(d, weight = (77.8 + d * 0.16).toFloat()) }
        assertTrue(BodyFlagCode.RAPID_WEIGHT_LOSS in codes(eval(profile(), body = daily)))
    }

    @Test
    fun rapidLoss_tooFewOrSparsePoints_noFlagAndReason() {
        val two = eval(profile(), body = listOf(body(7, weight = 80f), body(0, weight = 77f)))
        assertEquals(BodyFlagSkipReason.WEIGHT_MISSING, skipReason(two, BodyFlagRule.RAPID_WEIGHT_LOSS))
        val sparse = eval(profile(), body = listOf(body(14, weight = 85f), body(5, weight = 80f), body(0, weight = 76f)))
        assertFalse(BodyFlagCode.RAPID_WEIGHT_LOSS in codes(sparse))
        assertEquals(BodyFlagSkipReason.OBSERVATION_WINDOW_TOO_SHORT, skipReason(sparse, BodyFlagRule.RAPID_WEIGHT_LOSS))
    }

    @Test
    fun rapidLoss_staleChain_noFlag() {
        val stale = listOf(body(34, weight = 80f), body(27, weight = 78.8f), body(20, weight = 77.6f))
        assertFalse(BodyFlagCode.RAPID_WEIGHT_LOSS in codes(eval(profile(), body = stale)))
    }

    @Test
    fun rapidLoss_mixedSources_mediumReliability() {
        val mixed = eval(
            profile(),
            bia = listOf(bia(7, weight = 78.8f)),
            body = listOf(body(14, weight = 80f), body(0, weight = 77.0f)),
        )
        val flag = mixed.flags.single { it.code == BodyFlagCode.RAPID_WEIGHT_LOSS }
        assertEquals(BodyFlagReliability.MEDIA, flag.reliability)
    }

    // ---- (g) calo di massa magra ----

    private fun leanSeries(weights: List<Float>, lean: List<Float>) =
        lean.indices.map { i -> bia(daysAgo = (lean.size - 1 - i) * 28, weight = weights[i], lean = lean[i]) }

    @Test
    fun leanLoss_shareAbove40Percent_flagsWithLowReliability() {
        val result = eval(profile(sex = FEMALE, age = 35, height = 170.0), bia = leanSeries(listOf(80f, 77f, 74f), listOf(60f, 58.5f, 57.5f)))
        val flag = result.flags.single { it.code == BodyFlagCode.LEAN_MASS_LOSS_SHARE }
        assertEquals(BodyFlagReliability.BASSA, flag.reliability)
        assertEquals(BodyFlagSeverity.ATTENZIONE, flag.severity)
    }

    @Test
    fun leanLoss_boundaryDeltaMinus2_isIncluded_andMinus19IsNot() {
        val p = profile(age = 35, height = 170.0)
        assertTrue(BodyFlagCode.LEAN_MASS_LOSS_SHARE in codes(eval(p, bia = leanSeries(listOf(80f, 78f, 76f), listOf(60f, 59f, 58f)))))
        assertFalse(BodyFlagCode.LEAN_MASS_LOSS_SHARE in codes(eval(p, bia = leanSeries(listOf(80f, 78f, 76f), listOf(60f, 59f, 58.1f)))))
    }

    @Test
    fun leanLoss_lowShare_noFlag() {
        val result = eval(profile(age = 35, height = 170.0), bia = leanSeries(listOf(80f, 77f, 74f), listOf(60f, 59f, 58f)))
        assertFalse(BodyFlagCode.LEAN_MASS_LOSS_SHARE in codes(result))
    }

    @Test
    fun leanLoss_weightLossWithinNoise_noFlagAndReason() {
        val result = eval(profile(age = 35, height = 170.0), bia = leanSeries(listOf(80f, 80.5f, 80f), listOf(60f, 59f, 58f)))
        assertFalse(BodyFlagCode.LEAN_MASS_LOSS_SHARE in codes(result))
        assertEquals(BodyFlagSkipReason.WEIGHT_LOSS_BELOW_NOISE, skipReason(result, BodyFlagRule.LEAN_MASS_LOSS))
    }

    @Test
    fun leanLoss_noisyOrTwoMeasurements_noFlag() {
        val noisy = listOf(
            bia(56, weight = 80f, lean = 60f),
            bia(28, weight = 77f, lean = 58.5f, conditions = false),
            bia(0, weight = 74f, lean = 57.5f),
        )
        assertFalse(BodyFlagCode.LEAN_MASS_LOSS_SHARE in codes(eval(profile(age = 35, height = 170.0), bia = noisy)))
        val two = eval(profile(age = 35, height = 170.0), bia = leanSeries(listOf(80f, 74f), listOf(60f, 57f)))
        assertFalse(BodyFlagCode.LEAN_MASS_LOSS_SHARE in codes(two))
    }

    // ---- (h) % massa grassa: solo trend ----

    private fun fatSeries(vararg values: Float, hours: List<Int>? = null) =
        values.mapIndexed { i, v -> bia(daysAgo = (values.size - 1 - i) * 28, fat = v, hour = hours?.get(i) ?: 7) }

    @Test
    fun bodyFat_trendBeyondNoise_isInfoWithMediumReliabilityWhenAllHighQuality() {
        val down = eval(profile(), bia = fatSeries(25f, 24f, 22.5f)).flags.single { it.code == BodyFlagCode.BODY_FAT_TREND_DOWN }
        assertEquals(BodyFlagSeverity.INFO, down.severity)
        assertEquals(BodyFlagReliability.MEDIA, down.reliability)
        assertTrue(BodyFlagCode.BODY_FAT_TREND_UP in codes(eval(profile(), bia = fatSeries(20f, 21f, 22f))))
    }

    @Test
    fun bodyFat_withinNoise_noFlag() {
        assertTrue(codes(eval(profile(), bia = fatSeries(25f, 24.5f, 24f))).none { it.name.startsWith("BODY_FAT") })
    }

    @Test
    fun bodyFat_mediumQualityPair_lowersReliability() {
        val result = eval(profile(), bia = fatSeries(25f, 24f, 22.5f, hours = listOf(7, 12, 12)))
        assertEquals(BodyFlagReliability.BASSA, result.flags.single { it.code == BodyFlagCode.BODY_FAT_TREND_DOWN }.reliability)
    }

    @Test
    fun bodyFat_noisy_noFlag() {
        val noisy = listOf(bia(56, fat = 25f), bia(28, fat = 24f, conditions = false), bia(0, fat = 22f))
        assertTrue(codes(eval(profile(), bia = noisy)).none { it.name.startsWith("BODY_FAT") })
    }

    // ---- Stabilita ----

    @Test
    fun futureMeasurements_areIgnored() {
        val result = eval(profile(sex = null), body = listOf(body(-2, waist = 130f)))
        assertTrue(result.flags.isEmpty())
    }

    @Test
    fun evaluate_isIdempotent_andIndependentFromInputOrder() {
        val bias = visceralSeries(10f, 11f, 12f) + skeletal(24.0f, 23.2f, 22.2f).map { it.copy(id = it.id + 100, visceralFatLevel = null) }
        val bodies = listOf(body(3, waist = 105f, weight = 100f), body(10, weight = 99f))
        val p = profile(age = 62)
        val a = eval(p, bias, bodies)
        val b = eval(p, bias, bodies)
        val c = eval(p, bias.reversed(), bodies.reversed())
        assertEquals(a, b)
        assertEquals(a.flags, c.flags)
    }

    @Test
    fun flags_areOrderedBySeverityThenPriority() {
        val result = eval(profile(height = 180.0), body = listOf(body(3, waist = 108f, weight = 120f)))
        val severities = result.flags.map { it.severity.rank }
        assertEquals(severities.sortedDescending(), severities)
        assertEquals(BodyFlagSeverity.ATTENZIONE, result.flags.first().severity)
        assertEquals(BodyFlagSeverity.INFO, result.flags.last().severity)
    }
}
