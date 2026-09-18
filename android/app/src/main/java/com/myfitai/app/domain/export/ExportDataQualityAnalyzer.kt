package com.myfitai.app.domain.export

import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.CheatEntryEntity
import com.myfitai.app.data.local.entity.FoodConsumptionEntity
import com.myfitai.app.data.local.entity.MealPlanEntity
import com.myfitai.app.data.local.entity.MealPlanVersionEntity
import com.myfitai.app.data.local.entity.WeeklyReviewEntity
import com.myfitai.app.data.local.entity.WorkoutEntity
import java.time.Instant
import java.time.ZoneId

enum class ExportDataSource { USER, IMPORT, AI, QA, SYNTHETIC, SYSTEM, CALCULATED }

data class ExportOrigin(val dataSource: ExportDataSource, val reasons: List<String> = emptyList()) {
    fun json(): org.json.JSONObject = org.json.JSONObject().apply {
        put("dataSource", dataSource.name)
        put("originReasons", org.json.JSONArray(reasons))
        put("isTestData", dataSource == ExportDataSource.QA || dataSource == ExportDataSource.SYNTHETIC)
    }
}

object ExportDataQualityAnalyzer {
    fun normalizeWeightState(value: String?): String = when (value?.trim()?.uppercase()) {
        "RAW", "CRUDO" -> "RAW"
        "COOKED", "COTTO" -> "COOKED"
        "DRY", "SECCO" -> "DRY"
        "LIQUID", "LIQUIDO" -> "LIQUID"
        "FRESH", "FRESCO" -> "FRESH"
        else -> "UNKNOWN"
    }

    fun normalizeCategory(value: String?): String = when (value?.trim()?.uppercase()) {
        "PROTEIN", "PROTEINE" -> "PROTEIN"
        "CARBOHYDRATE", "CARBOHYDRATES", "CARBOIDRATI" -> "CARBOHYDRATE"
        "FAT", "GRASSI", "CONDIMENT" -> "FAT"
        "VEGETABLE", "VEGETABLES", "VERDURA" -> "VEGETABLE"
        "FRUIT", "FRUTTA" -> "FRUIT"
        "DAIRY", "LATTICINI" -> "DAIRY"
        "DRINK", "BEVERAGE", "BEVANDA" -> "BEVERAGE"
        else -> "UNKNOWN"
    }

    fun nutritionConfidence(value: String?): Double? = when (value?.trim()?.uppercase()) {
        "HIGH", "ALTA" -> 0.9
        "MEDIUM", "MEDIA" -> 0.6
        "LOW", "BASSA" -> 0.3
        else -> value?.toDoubleOrNull()?.takeIf { it in 0.0..1.0 }
    }

    fun sourceFrom(vararg values: String?): ExportOrigin {
        val text = values.filterNotNull().joinToString(" ").lowercase()
        return when {
            text.contains("qa") || text.contains("fixture") || text.contains("seed") -> ExportOrigin(ExportDataSource.QA, listOf("QA/fixture marker"))
            text.contains("synthetic") || text.contains("stress") || text.contains("test_provider") -> ExportOrigin(ExportDataSource.SYNTHETIC, listOf("synthetic/test marker"))
            text.contains("ai_generation") || text.contains("ai_") -> ExportOrigin(ExportDataSource.AI)
            else -> ExportOrigin(ExportDataSource.USER)
        }
    }

    fun sourceForPlan(version: MealPlanVersionEntity): ExportOrigin = sourceFrom(version.source, version.reason)

    fun dataQuality(
        bia: List<BiaMeasurementEntity>, body: List<BodyMeasurementEntity>, workouts: List<WorkoutEntity>,
        cheats: List<CheatEntryEntity>, consumptions: List<FoodConsumptionEntity>, reviews: List<WeeklyReviewEntity>, plans: List<MealPlanEntity>,
    ): org.json.JSONObject {
        val origins = buildList {
            bia.forEach { add(sourceFrom(it.notes)) }
            body.forEach { add(sourceFrom(it.notes)) }
            workouts.forEach { add(sourceFrom(it.type, it.title, it.notes)) }
            cheats.forEach { add(sourceFrom(it.description, it.notes)) }
        }
        val containsQa = origins.any { it.dataSource == ExportDataSource.QA }
        val containsSynthetic = origins.any { it.dataSource == ExportDataSource.SYNTHETIC }
        val warnings = buildList {
            if (containsQa) add("Il profilo contiene dati QA")
            if (containsSynthetic) add("Il profilo contiene dati sintetici")
            if (consumptions.isEmpty()) add("La cronologia dei consumi alimentari è vuota")
            if (reviews.isEmpty()) add("Non sono presenti review settimanali")
            if (plans.isNotEmpty() && consumptions.isEmpty()) add("Sono presenti piani alimentari ma nessun consumo reale registrato")
        }
        return org.json.JSONObject().apply {
            put("overallConfidence", when {
                containsQa || containsSynthetic -> "LOW"
                consumptions.isEmpty() -> "MEDIUM"
                else -> "HIGH"
            })
            put("containsQaData", containsQa)
            put("containsSyntheticData", containsSynthetic)
            put("coverage", org.json.JSONObject().apply {
                put("bia", bia.isNotEmpty()); put("bodyMeasurements", body.isNotEmpty()); put("workouts", workouts.isNotEmpty())
                put("foodConsumptions", consumptions.isNotEmpty()); put("weeklyReviews", reviews.isNotEmpty()); put("mealPlans", plans.isNotEmpty())
            })
            put("warnings", org.json.JSONArray(warnings))
        }
    }
}

object ExportTrendCalculator {
    fun trends(bia: List<BiaMeasurementEntity>, body: List<BodyMeasurementEntity>): org.json.JSONObject = org.json.JSONObject().apply {
        put("bia", deltaObject(bia.size, bia.firstOrNull(), bia.lastOrNull()))
        put("bodyMeasurements", bodyDeltaObject(body.size, body.firstOrNull(), body.lastOrNull()))
    }

    private fun deltaObject(count: Int, first: BiaMeasurementEntity?, last: BiaMeasurementEntity?): org.json.JSONObject = org.json.JSONObject().apply {
        put("measurementCount", count)
        putNullable("firstMeasuredAtEpochMillis", first?.measuredAtEpochMillis)
        putNullable("lastMeasuredAtEpochMillis", last?.measuredAtEpochMillis)
        put("delta", org.json.JSONObject().apply {
            putNullable("weightKg", delta(first?.weightKg, last?.weightKg)); putNullable("bodyFatPercent", delta(first?.bodyFatPercent, last?.bodyFatPercent))
            putNullable("muscleMassKg", delta(first?.muscleMassKg, last?.muscleMassKg)); putNullable("skeletalMuscleKg", delta(first?.skeletalMuscleKg, last?.skeletalMuscleKg))
            putNullable("bodyWaterPercent", delta(first?.bodyWaterPercent, last?.bodyWaterPercent)); putNullable("visceralFatLevel", delta(first?.visceralFatLevel, last?.visceralFatLevel))
        })
    }

    private fun bodyDeltaObject(count: Int, first: BodyMeasurementEntity?, last: BodyMeasurementEntity?): org.json.JSONObject = org.json.JSONObject().apply {
        put("measurementCount", count)
        put("delta", org.json.JSONObject().apply {
            putNullable("chestCm", delta(first?.chestCm, last?.chestCm)); putNullable("waistCm", delta(first?.waistCm, last?.waistCm)); putNullable("abdomenCm", delta(first?.abdomenCm, last?.abdomenCm))
            putNullable("shouldersCm", delta(first?.shouldersCm, last?.shouldersCm)); putNullable("glutesCm", delta(first?.glutesCm, last?.glutesCm)); putNullable("armLeftCm", delta(first?.armLeftCm, last?.armLeftCm)); putNullable("armRightCm", delta(first?.armRightCm, last?.armRightCm))
            putNullable("thighLeftCm", delta(first?.thighLeftCm, last?.thighLeftCm)); putNullable("thighRightCm", delta(first?.thighRightCm, last?.thighRightCm)); putNullable("calfLeftCm", delta(first?.calfLeftCm, last?.calfLeftCm)); putNullable("calfRightCm", delta(first?.calfRightCm, last?.calfRightCm))
        })
    }

    private fun delta(first: Float?, last: Float?): Double? = if (first != null && last != null) (last - first).toDouble() else null
    private fun org.json.JSONObject.putNullable(key: String, value: Any?) { put(key, value ?: org.json.JSONObject.NULL) }
}
