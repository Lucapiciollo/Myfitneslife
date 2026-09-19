package com.myfitai.app.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiSchemaMapperTest {
    @Test
    fun mapperKeepsContractFieldsAndDropsUnsupportedKeywords() {
        val result = GeminiSchemaMapper.map("""
            {"type":"object","additionalProperties":false,"properties":{
              "days":{"type":"array","minItems":7,"maxItems":7,"items":{
                "type":"object","properties":{"date":{"type":"integer","minimum":1,"maximum":10}},
                "required":["date"],"additionalProperties":false
              }}
            },"required":["days"]}
        """.trimIndent())
        val text = result.schema.toString()
        assertTrue(text.contains("date"))
        assertTrue(text.contains("minItems"))
        assertTrue(text.contains("minimum"))
        assertFalse(text.contains("additionalProperties"))
        assertTrue(result.propertyCount >= 1)
        assertTrue(result.arrayCount == 1)
    }
}
