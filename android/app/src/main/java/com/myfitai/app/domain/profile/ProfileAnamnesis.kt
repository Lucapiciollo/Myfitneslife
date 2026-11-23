package com.myfitai.app.domain.profile

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class AnamnesisDose(val name: String, val dosage: String) {
    override fun toString(): String = "AnamnesisDose(<redacted>)"
}

data class AnamnesisDocument(val id: String, val displayName: String, val mimeType: String) {
    override fun toString(): String = "AnamnesisDocument(<redacted>)"
}

data class ProfileAnamnesis(
    val usualWeightKg: Float? = null,
    val profession: String? = null,
    val averageDailySteps: Int? = null,
    val trainingType: String? = null,
    val trainingSessionsPerWeek: Int? = null,
    val dailyWaterMl: Int? = null,
    val cycleStatus: CycleStatus? = null,
    val contraceptivesUsed: Boolean? = null,
    val contraceptiveDetails: String? = null,
    val gynecologicalConditions: String? = null,
    val menopause: Boolean? = null,
    val healthConditions: String? = null,
    val gastrointestinalSymptoms: String? = null,
    val bowelHabits: String? = null,
    val medications: List<AnamnesisDose> = emptyList(),
    val supplements: List<AnamnesisDose> = emptyList(),
    val recentExams: Boolean? = null,
    val documents: List<AnamnesisDocument> = emptyList(),
    val desiredOutcome: DesiredOutcome? = null,
    val desiredTimeframe: String? = null,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    enum class CycleStatus { REGULAR, IRREGULAR, ABSENT, NOT_APPLICABLE }
    enum class DesiredOutcome { WEIGHT_LOSS, BODY_RECOMPOSITION, MUSCLE_GAIN, PERFORMANCE, HEALTH_EDUCATION }

    override fun toString(): String = "ProfileAnamnesis(<redacted>)"

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

sealed interface ProfileAnamnesisDecodeResult {
    data object Absent : ProfileAnamnesisDecodeResult
    data object Invalid : ProfileAnamnesisDecodeResult
    data class UnsupportedVersion(val version: Int) : ProfileAnamnesisDecodeResult {
        override fun toString(): String = "UnsupportedVersion(version=$version)"
    }
    data class Valid(val anamnesis: ProfileAnamnesis) : ProfileAnamnesisDecodeResult {
        override fun toString(): String = "ProfileAnamnesisDecodeResult.Valid(<redacted>)"
    }
}

object ProfileAnamnesisCodec {
    private const val MAX_JSON_LENGTH = 65_536
    private val fields = setOf(
        "schemaVersion", "usualWeightKg", "profession", "averageDailySteps", "trainingType",
        "trainingSessionsPerWeek", "dailyWaterMl", "cycleStatus", "contraceptivesUsed",
        "contraceptiveDetails", "gynecologicalConditions", "menopause", "healthConditions",
        "gastrointestinalSymptoms", "bowelHabits", "medications", "supplements", "recentExams",
        "documents", "desiredOutcome", "desiredTimeframe",
    )

    fun encode(value: ProfileAnamnesis): String {
        require(value.schemaVersion == ProfileAnamnesis.CURRENT_SCHEMA_VERSION) { "Schema anamnesi non supportato" }
        validate(value)
        return JSONObject().apply {
            put("schemaVersion", value.schemaVersion)
            putNullable("usualWeightKg", value.usualWeightKg)
            putNullable("profession", value.profession)
            putNullable("averageDailySteps", value.averageDailySteps)
            putNullable("trainingType", value.trainingType)
            putNullable("trainingSessionsPerWeek", value.trainingSessionsPerWeek)
            putNullable("dailyWaterMl", value.dailyWaterMl)
            putNullable("cycleStatus", value.cycleStatus?.name)
            putNullable("contraceptivesUsed", value.contraceptivesUsed)
            putNullable("contraceptiveDetails", value.contraceptiveDetails)
            putNullable("gynecologicalConditions", value.gynecologicalConditions)
            putNullable("menopause", value.menopause)
            putNullable("healthConditions", value.healthConditions)
            putNullable("gastrointestinalSymptoms", value.gastrointestinalSymptoms)
            putNullable("bowelHabits", value.bowelHabits)
            put("medications", value.medications.toDoseJsonArray())
            put("supplements", value.supplements.toDoseJsonArray())
            putNullable("recentExams", value.recentExams)
            put("documents", value.documents.toDocumentJsonArray())
            putNullable("desiredOutcome", value.desiredOutcome?.name)
            putNullable("desiredTimeframe", value.desiredTimeframe)
        }.toString()
    }

    fun decode(json: String?): ProfileAnamnesisDecodeResult {
        if (json == null) return ProfileAnamnesisDecodeResult.Absent
        if (json.length > MAX_JSON_LENGTH) return ProfileAnamnesisDecodeResult.Invalid
        val root = try {
            JSONObject(json)
        } catch (_: JSONException) {
            return ProfileAnamnesisDecodeResult.Invalid
        } catch (_: RuntimeException) {
            return ProfileAnamnesisDecodeResult.Invalid
        }
        val version = root.int("schemaVersion") ?: return ProfileAnamnesisDecodeResult.Invalid
        if (version != ProfileAnamnesis.CURRENT_SCHEMA_VERSION) return ProfileAnamnesisDecodeResult.UnsupportedVersion(version)
        if (root.keys().asSequence().toSet() != fields) return ProfileAnamnesisDecodeResult.Invalid

        return try {
            val value = ProfileAnamnesis(
                usualWeightKg = root.floatOrNull("usualWeightKg"),
                profession = root.stringOrNull("profession"),
                averageDailySteps = root.intOrNull("averageDailySteps"),
                trainingType = root.stringOrNull("trainingType"),
                trainingSessionsPerWeek = root.intOrNull("trainingSessionsPerWeek"),
                dailyWaterMl = root.intOrNull("dailyWaterMl"),
                cycleStatus = root.enumOrNull("cycleStatus", ProfileAnamnesis.CycleStatus.entries),
                contraceptivesUsed = root.booleanOrNull("contraceptivesUsed"),
                contraceptiveDetails = root.stringOrNull("contraceptiveDetails"),
                gynecologicalConditions = root.stringOrNull("gynecologicalConditions"),
                menopause = root.booleanOrNull("menopause"),
                healthConditions = root.stringOrNull("healthConditions"),
                gastrointestinalSymptoms = root.stringOrNull("gastrointestinalSymptoms"),
                bowelHabits = root.stringOrNull("bowelHabits"),
                medications = root.doseList("medications"),
                supplements = root.doseList("supplements"),
                recentExams = root.booleanOrNull("recentExams"),
                documents = root.documentList("documents"),
                desiredOutcome = root.enumOrNull("desiredOutcome", ProfileAnamnesis.DesiredOutcome.entries),
                desiredTimeframe = root.stringOrNull("desiredTimeframe"),
                schemaVersion = version,
            )
            validate(value)
            ProfileAnamnesisDecodeResult.Valid(value)
        } catch (_: JSONException) {
            ProfileAnamnesisDecodeResult.Invalid
        } catch (_: IllegalArgumentException) {
            ProfileAnamnesisDecodeResult.Invalid
        }
    }

    private fun validate(value: ProfileAnamnesis) {
        require(value.usualWeightKg == null || value.usualWeightKg in 20f..400f)
        require(value.averageDailySteps == null || value.averageDailySteps in 0..200_000)
        require(value.trainingSessionsPerWeek == null || value.trainingSessionsPerWeek in 0..21)
        require(value.dailyWaterMl == null || value.dailyWaterMl in 0..15_000)
        listOf(
            value.profession, value.trainingType, value.contraceptiveDetails, value.gynecologicalConditions,
            value.healthConditions, value.gastrointestinalSymptoms, value.bowelHabits, value.desiredTimeframe,
        ).forEach { require(it == null || it.length <= 2_000) }
        (value.medications + value.supplements).forEach {
            require(it.name.isNotBlank() && it.name.length <= 200)
            require(it.dosage.length <= 200)
        }
        value.documents.forEach {
            require(it.id.isNotBlank() && it.id.length <= 80)
            require(it.displayName.isNotBlank() && it.displayName.length <= 255)
            require(it.mimeType in ALLOWED_DOCUMENT_MIME_TYPES)
        }
    }

    private fun List<AnamnesisDose>.toDoseJsonArray() = JSONArray().apply {
        forEach { item -> put(JSONObject().put("name", item.name).put("dosage", item.dosage)) }
    }

    private fun List<AnamnesisDocument>.toDocumentJsonArray() = JSONArray().apply {
        forEach { item -> put(JSONObject().put("id", item.id).put("displayName", item.displayName).put("mimeType", item.mimeType)) }
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.int(key: String): Int? = (opt(key) as? Number)?.toInt()

    private fun JSONObject.intOrNull(key: String): Int? = when (val value = opt(key)) {
        JSONObject.NULL -> null
        is Number -> value.toInt()
        else -> throw IllegalArgumentException("Valore non valido")
    }

    private fun JSONObject.floatOrNull(key: String): Float? = when (val value = opt(key)) {
        JSONObject.NULL -> null
        is Number -> value.toFloat()
        else -> throw IllegalArgumentException("Valore non valido")
    }

    private fun JSONObject.stringOrNull(key: String): String? = when (val value = opt(key)) {
        JSONObject.NULL -> null
        is String -> value
        else -> throw IllegalArgumentException("Valore non valido")
    }

    private fun JSONObject.booleanOrNull(key: String): Boolean? = when (val value = opt(key)) {
        JSONObject.NULL -> null
        is Boolean -> value
        else -> throw IllegalArgumentException("Valore non valido")
    }

    private fun <T : Enum<T>> JSONObject.enumOrNull(key: String, values: List<T>): T? =
        stringOrNull(key)?.let { text -> values.firstOrNull { it.name == text } ?: throw IllegalArgumentException("Valore non valido") }

    private fun JSONObject.doseList(key: String): List<AnamnesisDose> =
        getJSONArray(key).let { array -> List(array.length()) { index ->
            val item = array.getJSONObject(index)
            require(item.keys().asSequence().toSet() == setOf("name", "dosage"))
            AnamnesisDose(item.getString("name"), item.getString("dosage"))
        } }

    private fun JSONObject.documentList(key: String): List<AnamnesisDocument> =
        getJSONArray(key).let { array -> List(array.length()) { index ->
            val item = array.getJSONObject(index)
            require(item.keys().asSequence().toSet() == setOf("id", "displayName", "mimeType"))
            AnamnesisDocument(item.getString("id"), item.getString("displayName"), item.getString("mimeType"))
        } }

    private val ALLOWED_DOCUMENT_MIME_TYPES = setOf("application/pdf", "image/jpeg", "image/png")
}