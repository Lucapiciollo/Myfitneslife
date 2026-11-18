package com.myfitai.app.domain.safety

import org.json.JSONException
import org.json.JSONObject

/** Local, versioned health declaration. Answers are intentionally nullable: unknown is not false. */
data class HealthDeclaration(
    val pregnancyOrLactation: Boolean?,
    val clinicianManagedConditionOrMedication: Boolean?,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val policyVersion: String = CURRENT_POLICY_VERSION,
    val confirmedAtEpochMillis: Long,
) {
    /** Health answers must never be exposed by accidental logging or debugger formatting. */
    override fun toString(): String = "HealthDeclaration(<redacted>)"

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val CURRENT_POLICY_VERSION = "S2-A.1"
    }
}

/** The result is explicit so callers cannot confuse a missing or unrecognized declaration with consent. */
sealed interface HealthDeclarationDecodeResult {
    data object Absent : HealthDeclarationDecodeResult
    data class Invalid(val reason: InvalidReason) : HealthDeclarationDecodeResult
    data class UnsupportedVersion(val schemaVersion: Int, val policyVersion: String) : HealthDeclarationDecodeResult
    data class Valid(val declaration: HealthDeclaration) : HealthDeclarationDecodeResult
    data class Incomplete(val declaration: HealthDeclaration) : HealthDeclarationDecodeResult

    enum class InvalidReason {
        MALFORMED_JSON,
        TOO_LARGE,
        UNEXPECTED_FIELDS,
        INVALID_VALUE,
    }
}

/** Strict, deterministic JSON codec for local use only. It has no persistence or AI integration. */
object HealthDeclarationCodec {
    private const val MAX_JSON_LENGTH = 4_096
    private val fields = setOf(
        "schemaVersion",
        "policyVersion",
        "pregnancyOrLactation",
        "clinicianManagedConditionOrMedication",
        "confirmedAtEpochMillis",
    )

    /** Encodes the canonical shape and key order; unsupported or invalid model versions are rejected. */
    fun encode(declaration: HealthDeclaration): String {
        require(declaration.schemaVersion == HealthDeclaration.CURRENT_SCHEMA_VERSION) { "Schema version non supportata" }
        require(declaration.policyVersion == HealthDeclaration.CURRENT_POLICY_VERSION) { "Policy version non supportata" }
        require(declaration.confirmedAtEpochMillis > 0L) { "Data di conferma non valida" }

        return buildString {
            append("{\"schemaVersion\":")
            append(declaration.schemaVersion)
            append(",\"policyVersion\":\"")
            append(declaration.policyVersion)
            append("\",\"pregnancyOrLactation\":")
            append(declaration.pregnancyOrLactation?.toString() ?: "null")
            append(",\"clinicianManagedConditionOrMedication\":")
            append(declaration.clinicianManagedConditionOrMedication?.toString() ?: "null")
            append(",\"confirmedAtEpochMillis\":")
            append(declaration.confirmedAtEpochMillis)
            append('}')
        }
    }

    /** Null means no stored declaration; every other malformed or unsupported value fails closed. */
    fun decode(json: String?): HealthDeclarationDecodeResult {
        if (json == null) return HealthDeclarationDecodeResult.Absent
        if (json.length > MAX_JSON_LENGTH) {
            return HealthDeclarationDecodeResult.Invalid(HealthDeclarationDecodeResult.InvalidReason.TOO_LARGE)
        }

        val root = try {
            JSONObject(json)
        } catch (_: JSONException) {
            return HealthDeclarationDecodeResult.Invalid(HealthDeclarationDecodeResult.InvalidReason.MALFORMED_JSON)
        } catch (_: RuntimeException) {
            return HealthDeclarationDecodeResult.Invalid(HealthDeclarationDecodeResult.InvalidReason.MALFORMED_JSON)
        }

        if (root.keys().asSequence().toSet() != fields) {
            return HealthDeclarationDecodeResult.Invalid(HealthDeclarationDecodeResult.InvalidReason.UNEXPECTED_FIELDS)
        }

        val schemaVersion = root.strictInt("schemaVersion")
            ?: return HealthDeclarationDecodeResult.Invalid(HealthDeclarationDecodeResult.InvalidReason.INVALID_VALUE)
        val policyVersion = root.strictString("policyVersion")
            ?.takeIf { it.isNotBlank() && it.length <= 64 }
            ?: return HealthDeclarationDecodeResult.Invalid(HealthDeclarationDecodeResult.InvalidReason.INVALID_VALUE)

        if (schemaVersion <= 0) {
            return HealthDeclarationDecodeResult.Invalid(HealthDeclarationDecodeResult.InvalidReason.INVALID_VALUE)
        }
        if (schemaVersion != HealthDeclaration.CURRENT_SCHEMA_VERSION || policyVersion != HealthDeclaration.CURRENT_POLICY_VERSION) {
            return HealthDeclarationDecodeResult.UnsupportedVersion(schemaVersion, policyVersion)
        }

        val confirmedAtEpochMillis = root.strictLong("confirmedAtEpochMillis")
            ?.takeIf { it > 0L }
            ?: return HealthDeclarationDecodeResult.Invalid(HealthDeclarationDecodeResult.InvalidReason.INVALID_VALUE)
        val pregnancyOrLactation = root.strictNullableBoolean("pregnancyOrLactation")
            ?: return HealthDeclarationDecodeResult.Invalid(HealthDeclarationDecodeResult.InvalidReason.INVALID_VALUE)
        val clinicianManagedConditionOrMedication = root.strictNullableBoolean("clinicianManagedConditionOrMedication")
            ?: return HealthDeclarationDecodeResult.Invalid(HealthDeclarationDecodeResult.InvalidReason.INVALID_VALUE)

        val declaration = HealthDeclaration(
            pregnancyOrLactation = pregnancyOrLactation.value,
            clinicianManagedConditionOrMedication = clinicianManagedConditionOrMedication.value,
            schemaVersion = schemaVersion,
            policyVersion = policyVersion,
            confirmedAtEpochMillis = confirmedAtEpochMillis,
        )
        return if (pregnancyOrLactation.value != null && clinicianManagedConditionOrMedication.value != null) {
            HealthDeclarationDecodeResult.Valid(declaration)
        } else {
            HealthDeclarationDecodeResult.Incomplete(declaration)
        }
    }

    private fun JSONObject.strictString(key: String): String? = get(key) as? String

    private fun JSONObject.strictInt(key: String): Int? = when (val value = get(key)) {
        is Int -> value
        is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        else -> null
    }

    private fun JSONObject.strictLong(key: String): Long? = when (val value = get(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }

    /** A null return means the JSON value was not a boolean or explicit JSON null. */
    private fun JSONObject.strictNullableBoolean(key: String): NullableBoolean? = when (val value = get(key)) {
        JSONObject.NULL -> NullableBoolean(null)
        is Boolean -> NullableBoolean(value)
        else -> null
    }

    private data class NullableBoolean(val value: Boolean?)
}
