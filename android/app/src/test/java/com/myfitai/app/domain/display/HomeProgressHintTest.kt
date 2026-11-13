package com.myfitai.app.domain.display

import com.myfitai.app.domain.display.HomeProgressHint.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeProgressHintTest {
    @Test
    fun withoutTargetTheLineIsHidden() {
        assertEquals(Kind.HIDDEN, HomeProgressHint.of(target = null, consumedCount = 0, consumedKcal = null))
        assertEquals(Kind.HIDDEN, HomeProgressHint.of(target = null, consumedCount = 3, consumedKcal = 900))
    }

    @Test
    fun aNonPositiveTargetIsNotACalculationAndHidesTheLine() {
        assertEquals(Kind.HIDDEN, HomeProgressHint.of(target = 0, consumedCount = 2, consumedKcal = 500))
    }

    @Test
    fun withTargetAndNothingConsumedTodayAsksToRecordMeals() {
        assertEquals(Kind.REGISTER_MEALS, HomeProgressHint.of(target = 2000, consumedCount = 0, consumedKcal = null))
        assertEquals(Kind.REGISTER_MEALS, HomeProgressHint.of(target = 2000, consumedCount = 0, consumedKcal = 0))
    }

    @Test
    fun withTargetAndConsumptionKeepsTheMockEncouragement() {
        assertEquals(Kind.ENCOURAGEMENT, HomeProgressHint.of(target = 2000, consumedCount = 1, consumedKcal = 450))
        assertEquals(Kind.ENCOURAGEMENT, HomeProgressHint.of(target = 2000, consumedCount = 0, consumedKcal = 120))
    }
}
