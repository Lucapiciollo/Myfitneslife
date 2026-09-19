package com.myfitai.app.ai

import org.json.JSONObject

/** Tiny JSON wrapper used only to keep provider structured-output guarantees around compact payloads. */
object AiCompactEnvelope {
    val schemaJson: String = schemaJson("")

    fun schemaJson(protocolDescription: String): String = JSONObject()
        .put("type", "object")
        .put("additionalProperties", false)
        .put(
            "properties",
            JSONObject().put(
                "data",
                JSONObject().put("type", "string").apply {
                    if (protocolDescription.isNotBlank()) put("description", protocolDescription)
                }
            )
        )
        .put("required", org.json.JSONArray().put("data"))
        .toString()

    fun data(jsonText: String): String = JSONObject(jsonText).getString("data")

    fun clean(value: String?): String = value.orEmpty()
        .replace('|', '/')
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()
}
