package com.myfitai.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

internal object HttpJsonClient {
    suspend fun post(
        provider: AiProviderType,
        url: String,
        headers: Map<String, String>,
        body: String,
    ): String = withContext(Dispatchers.IO) {
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 60_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            try {
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    logSanitizedError(provider, status, response)
                    val metadata = extractErrorMetadata(response)
                    throw AiTransportException.Http(
                        provider = provider,
                        statusCode = status,
                        failureKind = AiTransportFailureClassifier.classify(status, response),
                        retryAfterSeconds = metadata.retryAfterSeconds,
                        quotaLimit = metadata.quotaLimit,
                    )
                }
                response
            } finally {
                connection.disconnect()
            }
        } catch (error: AiTransportException) {
            throw error
        } catch (_: SocketTimeoutException) {
            throw AiTransportException.Network()
        } catch (_: IOException) {
            throw AiTransportException.Network()
        }
    }

    /**
     * Logs ONLY the provider error envelope (error.status/code + a truncated error.message).
     * Never logs the API key, request body, headers or the full raw response.
     * The error message from Gemini/OpenAI describes schema/validation problems and contains no secrets.
     */
    private fun logSanitizedError(provider: AiProviderType, status: Int, responseBody: String) {
        val detail = runCatching {
            val error = org.json.JSONObject(responseBody).optJSONObject("error") ?: return@runCatching null
            val gStatus = error.optString("status").ifBlank { error.optString("code") }
            val gType = error.optString("type")
            val msg = error.optString("message").take(300)
            val details = error.optJSONArray("details")?.toString()?.take(600).orEmpty()
            "gStatus=$gStatus type=$gType msg=$msg details=$details"
        }.getOrNull() ?: "envelope not parseable"
        android.util.Log.w("MyFitAiHttp", "provider=$provider http=$status $detail (key/request never logged)")
    }

    private fun extractErrorMetadata(responseBody: String): ErrorMetadata = runCatching {
        val error = org.json.JSONObject(responseBody).optJSONObject("error") ?: return@runCatching ErrorMetadata()
        val details = error.optJSONArray("details") ?: return@runCatching ErrorMetadata()
        var retryAfter: Long? = null
        var quotaLimit: Int? = null
        for (index in 0 until details.length()) {
            val detail = details.optJSONObject(index) ?: continue
            val type = detail.optString("@type")
            if (type.endsWith("RetryInfo")) {
                retryAfter = parseDurationSeconds(detail.optString("retryDelay"))
            }
            if (type.endsWith("QuotaFailure")) {
                val violations = detail.optJSONArray("violations") ?: continue
                for (violationIndex in 0 until violations.length()) {
                    val value = violations.optJSONObject(violationIndex)?.optString("quotaValue").orEmpty()
                    value.toIntOrNull()?.let { quotaLimit = it }
                }
            }
        }
        ErrorMetadata(retryAfter, quotaLimit)
    }.getOrDefault(ErrorMetadata())

    private fun parseDurationSeconds(value: String): Long? = when {
        value.endsWith("s") -> value.dropLast(1).toLongOrNull()
        value.endsWith("m") -> value.dropLast(1).toLongOrNull()?.times(60)
        else -> null
    }

    private data class ErrorMetadata(val retryAfterSeconds: Long? = null, val quotaLimit: Int? = null)
}
