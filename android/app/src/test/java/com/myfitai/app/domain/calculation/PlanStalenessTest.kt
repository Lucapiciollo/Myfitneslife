package com.myfitai.app.domain.calculation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** UX7: UNA regola per "piano non aggiornato" (Home, Cibo, review, PDF). */
class PlanStalenessTest {

    @Test
    fun withFingerprintOnlyTheComparisonDecides() {
        assertTrue(PlanStaleness.isStale("aaa", "bbb", legacyPendingFlag = false))
        assertTrue(PlanStaleness.isStale("aaa", "bbb", legacyPendingFlag = true))
        // Cambio e ripristino: impronte uguali => nessun avviso, anche se il flag e rimasto acceso.
        assertFalse(PlanStaleness.isStale("aaa", "aaa", legacyPendingFlag = true))
        assertFalse(PlanStaleness.isStale("aaa", "aaa", legacyPendingFlag = false))
    }

    @Test
    fun withoutPlanFingerprintTheLegacyFlagDecides() {
        assertTrue(PlanStaleness.isStale(null, "bbb", legacyPendingFlag = true))
        assertFalse(PlanStaleness.isStale(null, "bbb", legacyPendingFlag = false))
        assertTrue(PlanStaleness.isStale(null, null, legacyPendingFlag = true))
        assertFalse(PlanStaleness.isStale(null, null, legacyPendingFlag = false))
    }

    @Test
    fun fingerprintThatCannotBeComparedNowIsNeverStaleByItself() {
        assertFalse(PlanStaleness.isStale("aaa", null, legacyPendingFlag = true))
        assertFalse(PlanStaleness.isStale("aaa", null, legacyPendingFlag = false))
    }
}
