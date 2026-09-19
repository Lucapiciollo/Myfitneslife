package com.myfitai.app.domain.food

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanReviewPolicyTest {

    @Test
    fun fullPlansAreReviewed() {
        assertTrue(PlanReviewPolicy.shouldReview(PlanReviewPolicy.Reason.NEW_WEEKLY_PLAN))
        assertTrue(PlanReviewPolicy.shouldReview(PlanReviewPolicy.Reason.FULL_REGENERATION))
        assertTrue(PlanReviewPolicy.shouldReview(PlanReviewPolicy.Reason.SIGNIFICANT_TARGET_CHANGE))
    }

    @Test
    fun smallEditsSkipRemoteReview() {
        assertFalse(PlanReviewPolicy.shouldReview(PlanReviewPolicy.Reason.MEAL_SWAP))
        assertFalse(PlanReviewPolicy.shouldReview(PlanReviewPolicy.Reason.DEVIATION_ADJUSTMENT))
        assertFalse(PlanReviewPolicy.shouldReview(PlanReviewPolicy.Reason.TIME_ONLY_CHANGE))
    }
}
