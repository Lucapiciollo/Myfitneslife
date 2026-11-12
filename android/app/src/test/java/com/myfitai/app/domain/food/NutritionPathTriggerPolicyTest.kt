package com.myfitai.app.domain.food

import com.myfitai.app.data.profile.AiAutomationPreferences.Feature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionPathTriggerPolicyTest {
    @Test
    fun noProvider_neverAsksNorQueues() {
        assertEquals(
            NutritionPathTrigger.Action.NONE,
            NutritionPathTrigger.decide(providerConfigured = false, automationEnabled = true),
        )
    }

    @Test
    fun withoutExplicitAutomation_onlyAnInviteIsShown_neverAPaidJob() {
        assertEquals(
            NutritionPathTrigger.Action.SUGGEST,
            NutritionPathTrigger.decide(providerConfigured = true, automationEnabled = false),
        )
    }

    @Test
    fun explicitAutomation_queuesTheJob() {
        assertEquals(
            NutritionPathTrigger.Action.ENQUEUE,
            NutritionPathTrigger.decide(providerConfigured = true, automationEnabled = true),
        )
    }

    @Test
    fun automationFeatures_declareWhichOnesReallyScheduleSomething() {
        assertTrue(Feature.BIA_PROGRESS_COACH.automaticSupported)
        assertTrue(Feature.PROGRESS_ANALYSIS.automaticSupported)
        assertTrue(Feature.NUTRITION_GOAL_AFTER_MEASURES.automaticSupported)
        // No background job exists for these two: the switch must not pretend otherwise.
        assertFalse(Feature.BODY_PROPORTIONS.automaticSupported)
        assertFalse(Feature.WEEKLY_REVIEW.automaticSupported)
        assertFalse(Feature.NUTRITION_GOAL_AFTER_MEASURES.scheduled)
        assertTrue(Feature.BIA_PROGRESS_COACH.scheduled)
    }
}
