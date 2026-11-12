package com.myfitai.app.ai

import com.myfitai.app.R
import com.myfitai.app.ai.AiFailureMessages.Action
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiFailureMessagesTest {

    private val allKnown: List<AiFailure> = AiFailure.ALL_STATIC + listOf(
        AiFailure.Refused(AiFailure.RefusalReason.SAFETY),
        AiFailure.BusinessRuleViolated("BP_SUMMARY_MISSING"),
        AiFailure.BusinessRuleViolated(null),
    )

    /** Se qualcuno aggiunge una nuova categoria a AiFailure senza elencarla qui e nella tabella, questo test fallisce. */
    @Test
    fun everyConcreteAiFailureCategory_isCoveredByTheTable() {
        val declared = AiFailure::class.java.declaredClasses
            .filter { AiFailure::class.java.isAssignableFrom(it) && it != AiFailure::class.java }
            .filter { !Modifier.isAbstract(it.modifiers) }
            .toSet()
        val covered = allKnown.map { it::class.java }.toSet()
        assertEquals("categorie AiFailure non coperte dalla tabella", declared, covered)
        allKnown.forEach { failure ->
            val presentation = AiFailureMessages.forFailure(failure)
            assertTrue("${failure.code}: messaggio mancante", presentation.messageRes != 0)
        }
    }

    @Test
    fun retryIsOfferedOnlyWhenTheFailureIsRetryable() {
        allKnown.forEach { failure ->
            assertEquals(failure.code, failure.retryable, AiFailureMessages.forFailure(failure).canRetry)
        }
        assertTrue(AiFailureMessages.forFailure(AiFailure.Network).canRetry)
        assertTrue(AiFailureMessages.forFailure(AiFailure.RateLimited).canRetry)
        assertTrue(AiFailureMessages.forFailure(AiFailure.ProviderUnavailable).canRetry)
        assertFalse(AiFailureMessages.forFailure(AiFailure.Quota).canRetry)
        assertFalse(AiFailureMessages.forFailure(AiFailure.SchemaInvalid).canRetry)
    }

    @Test
    fun settingsActionIsOfferedForQuotaKeyModelAndMissingProvider() {
        listOf(AiFailure.Quota, AiFailure.Auth, AiFailure.NotConfigured, AiFailure.ModelUnavailable).forEach {
            assertEquals(it.code, Action.OPEN_AI_SETTINGS, AiFailureMessages.forFailure(it).action)
        }
        listOf(AiFailure.Network, AiFailure.SchemaInvalid, AiFailure.Truncated, AiFailure.Cancelled, AiFailure.Unexpected).forEach {
            assertEquals(it.code, Action.NONE, AiFailureMessages.forFailure(it).action)
        }
    }

    @Test
    fun mainCategories_useTheAgreedMessages() {
        assertEquals(R.string.ai_failure_quota, AiFailureMessages.forFailure(AiFailure.Quota).messageRes)
        assertEquals(R.string.ai_failure_auth, AiFailureMessages.forFailure(AiFailure.Auth).messageRes)
        assertEquals(R.string.ai_failure_network, AiFailureMessages.forFailure(AiFailure.Network).messageRes)
        assertEquals(R.string.ai_failure_invalid_response, AiFailureMessages.forFailure(AiFailure.SchemaInvalid).messageRes)
        assertEquals(R.string.ai_failure_invalid_response, AiFailureMessages.forFailure(AiFailure.MalformedJson).messageRes)
        assertEquals(R.string.ai_failure_blocked, AiFailureMessages.forFailure(AiFailure.Refused(AiFailure.RefusalReason.CONTENT_FILTER)).messageRes)
    }

    @Test
    fun technicalCodeIsTheStableFailureCode() {
        assertEquals("QUOTA_EXHAUSTED", AiFailureMessages.forFailure(AiFailure.Quota).technicalCode)
        assertEquals("REFUSED", AiFailureMessages.forFailure(AiFailure.Refused(AiFailure.RefusalReason.SAFETY)).technicalCode)
    }

    /** Quello che i job scrivono davvero in WorkManager (testo per MESSAGE, codice per CODE) torna alla stessa categoria. */
    @Test
    fun surfacedJobStrings_resolveToTheirCategory() {
        AiFailure.ALL_STATIC.forEach { failure ->
            val surfaced = when (failure.surface) {
                AiFailure.Surface.MESSAGE -> failure.userMessage
                AiFailure.Surface.CODE -> failure.surfaceCode
            }
            val resolved = AiFailureMessages.resolve(surfaced)
            assertEquals("${failure.code} <- '$surfaced'", failure.retryable, resolved.canRetry)
            assertEquals("${failure.code} <- '$surfaced'", AiFailureMessages.forFailure(failure).messageRes, resolved.messageRes)
        }
    }

    @Test
    fun refusedAndBusinessCodes_resolve() {
        val refused = AiFailureMessages.resolve("REFUSED")
        assertEquals(R.string.ai_failure_blocked, refused.messageRes)
        assertEquals("REFUSED", refused.technicalCode)
        AiFailure.BUSINESS_CODES.forEach { code ->
            val resolved = AiFailureMessages.resolve(code)
            assertFalse("$code non deve proporre Riprova", resolved.canRetry)
            assertTrue("$code deve avere un messaggio", resolved.messageRes != 0)
        }
        val business = AiFailureMessages.resolve("BP_SUMMARY_MISSING")
        assertEquals(R.string.ai_failure_rejected, business.messageRes)
        assertEquals("BP_SUMMARY_MISSING", business.technicalCode)
    }

    @Test
    fun unknownText_isGenericAndNeverShownAsTechnicalCode() {
        val text = AiFailureMessages.resolve("Interpretazione misure non disponibile")
        assertEquals(R.string.ai_failure_generic, text.messageRes)
        assertNull(text.technicalCode)
        assertFalse(text.canRetry)
        assertEquals(Action.NONE, text.action)

        val blank = AiFailureMessages.resolve("  ")
        assertEquals(R.string.ai_failure_generic, blank.messageRes)
        assertNull(AiFailureMessages.resolve(null).technicalCode)
    }

    @Test
    fun unknownCodeShapedString_isGenericButKeepsTheCodeForDetails() {
        val resolved = AiFailureMessages.resolve("SOMETHING_NEW_42")
        assertEquals(R.string.ai_failure_generic, resolved.messageRes)
        assertEquals("SOMETHING_NEW_42", resolved.technicalCode)
        assertFalse(resolved.canRetry)
    }

    @Test
    fun longOrMultilineUnknownText_neverLeaksAsTechnicalCode() {
        val resolved = AiFailureMessages.resolve("ERRORE:\nmodello ha detto cose")
        assertNull(resolved.technicalCode)
    }

    @Test
    fun couldNotStart_allowsRetryWithoutTechnicalCode() {
        val start = AiFailureMessages.couldNotStart()
        assertEquals(R.string.ai_failure_start, start.messageRes)
        assertTrue(start.canRetry)
        assertNull(start.technicalCode)
        assertEquals(Action.NONE, start.action)
    }
}