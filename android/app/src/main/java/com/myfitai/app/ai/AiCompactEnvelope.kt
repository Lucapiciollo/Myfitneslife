package com.myfitai.app.ai

import org.json.JSONObject

/** Tiny JSON wrapper used only to keep provider structured-output guarantees around compact payloads. */
object AiCompactEnvelope {
    val schemaJson: String = JSONObject(
        """
        {"type":"object","additionalProperties":false,"properties":{"data":{"type":"string"}},"required":["data"]}
        """.trimIndent()
    ).toString()

    fun data(jsonText: String): String = JSONObject(jsonText).getString("data")

    fun clean(value: String?): String = value.orEmpty()
        .replace('|', '/')
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()
}
