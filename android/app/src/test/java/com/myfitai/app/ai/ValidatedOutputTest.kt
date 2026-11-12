package com.myfitai.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatedOutputTest {
    private val schema = """{"type":"object","additionalProperties":false,"required":["kcal"],"properties":{"kcal":{"type":"integer","minimum":0}}}"""

    @Test fun createdOnlyAfterSchemaAndBusinessPass() {
        var businessCalls = 0
        val result = ValidatedOutput.validate("""{"kcal":10}""", schema, "s") { json ->
            businessCalls++
            Result.success(json.length)
        }
        assertTrue(result.isSuccess)
        assertEquals(1, businessCalls)
        assertEquals("s", result.getOrThrow().schemaName)
        assertEquals("""{"kcal":10}""".length, result.getOrThrow().value)
    }

    @Test fun schemaFailureSkipsBusinessAndExposesOnlyPaths() {
        var businessCalls = 0
        val result = ValidatedOutput.validate("""{"kcal":-77123}""", schema, "s") { businessCalls++; Result.success(Unit) }
        val error = result.exceptionOrNull() as ValidatedOutput.Rejection.Schema
        assertEquals(0, businessCalls)
        assertEquals("$.kcal", error.issues.single().path)
        assertEquals(CanonicalJsonSchemaValidator.Category.OUT_OF_RANGE, error.issues.single().category)
        assertFalse(error.issues.joinToString { it.message }.contains("77123"))
    }

    @Test fun businessFailureIsRejected() {
        val result = ValidatedOutput.validate("""{"kcal":1}""", schema, "s") { Result.failure<Unit>(IllegalStateException("KCAL_OFF_TARGET")) }
        assertEquals("KCAL_OFF_TARGET", (result.exceptionOrNull() as ValidatedOutput.Rejection.Business).reason)
    }

    @Test fun businessExceptionBecomesRejection() {
        val result = ValidatedOutput.validate<Unit>("""{"kcal":1}""", schema, "s") { error("BOOM") }
        assertTrue(result.exceptionOrNull() is ValidatedOutput.Rejection.Business)
    }

    @Test fun toStringDoesNotLeakPayload() {
        val out = ValidatedOutput.validate("""{"kcal":4242}""", schema, "s") { Result.success(it) }.getOrThrow()
        assertFalse(out.toString().contains("4242"))
    }
}
