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

    @Test
    fun savedReviewsWithLegacyTrainingCodesRemainReadable() {
        val result = PlanReviewCompactContract.parse(
            """{"data":"PR1\nS|A|88\nI|PW|W|1|SNACK\nI|TC|I|-|-"}"""
        )

        assertEquals(
            listOf(PlanReviewCompactContract.IssueCode.PREWORKOUT_LOAD, PlanReviewCompactContract.IssueCode.TRAINING_COHERENCE),
            result.issues.map { it.code },
        )
        assertTrue(result.accepted)
    }

    @Test
    fun reviewPromptNoLongerMentionsWorkouts() {
        val prompt = PlanReviewService.SYSTEM_PROMPT
        listOf("workout", "training").forEach {
            assertFalse("prompt must not mention '$it'", prompt.contains(it, ignoreCase = true))
        }
        listOf("WO|", "PW ", "TC ").forEach {
            assertFalse("prompt must not mention '$it'", prompt.contains(it))
        }
        assertTrue(prompt.contains("BC body-context coherence"))
        assertTrue(prompt.contains("DC concrete digestive-comfort concern"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun approvedCannotContainBlockingIssue() {
        PlanReviewCompactContract.parse(
            """{"data":"PR1\nS|A|90\nI|FC|C|1|LUNCH"}"""
        )
    }
}
