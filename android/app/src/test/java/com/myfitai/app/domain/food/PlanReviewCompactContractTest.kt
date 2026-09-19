package com.myfitai.app.domain.food

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanReviewCompactContractTest {

    @Test
    fun approved_allows_nonBlockingWarnings() {
        val result = PlanReviewCompactContract.parse(
            """{"data":"PR1\nS|A|91\nI|DC|W|2|DINNER"}"""
        )

        assertEquals(PlanReviewCompactContract.Status.APPROVED, result.status)
        assertEquals(91, result.score)
        assertFalse(result.hasBlockingIssues)
        assertTrue(result.accepted)
    }

    @Test
    fun rejected_requiresBlockingIssue() {
        val result = PlanReviewCompactContract.parse(
            """{"data":"PR1\nS|R|68\nI|PD|M|4|DINNER"}"""
        )

        assertEquals(PlanReviewCompactContract.Status.REJECTED, result.status)
        assertTrue(result.hasBlockingIssues)
        assertFalse(result.accepted)
        assertEquals(PlanReviewCompactContract.IssueCode.PROTEIN_DISTRIBUTION, result.issues.single().code)
    }

    @Test(expected = IllegalArgumentException::class)
    fun approvedCannotContainBlockingIssue() {
        PlanReviewCompactContract.parse(
            """{"data":"PR1\nS|A|90\nI|FC|C|1|LUNCH"}"""
        )
    }
}
