package com.myfitai.app.ai

import org.json.JSONObject

/** Compatibility facade for existing callers. New code should use GeminiSchemaMapper directly. */
object GeminiJsonSchema {
    fun from(json: String): JSONObject = GeminiSchemaMapper.map(json).schema
}
