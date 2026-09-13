package com.myfitai.app.domain.export

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.profile.ActiveProfileStore
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProfileExportService(
    context: Context,
    private val db: MyFitAiDatabase,
    private val activeProfileStore: ActiveProfileStore,
) {
    enum class Format { JSON, CSV_ZIP, PDF }
    data class ExportedFile(val file: File, val mimeType: String)

    private val appContext = context.applicationContext

    suspend fun export(format: Format): ExportedFile {
        val profileId = activeProfileStore.currentIdOrNull() ?: error("Nessun profilo attivo")
        val profile = db.userProfileDao().get(profileId) ?: error("Profilo non disponibile")
        val bia = db.biaMeasurementDao().observeAll(profileId).first().sortedBy { it.measuredAtEpochMillis }
        val body = db.bodyMeasurementDao().observeAll(profileId).first().sortedBy { it.measuredAtEpochMillis }
        val workouts = db.workoutDao().observeAll(profileId).first().sortedBy { it.startedAtEpochMillis }
        val cheats = db.cheatEntryDao().observeAll(profileId).first().sortedBy { it.occurredAtEpochMillis }
        val reviews = db.weeklyReviewDao().observeAll(profileId).first().sortedBy { it.weekStartEpochDay }
        val plans = db.mealPlanDao().observePlans(profileId).first().sortedBy { it.weekStartEpochDay }

        val root = JSONObject().apply {
            put("schema", "myfitai_profile_export_v1")
            put("exportedAtEpochMillis", System.currentTimeMillis())
            put("profile", JSONObject().apply {
                put("id", profile.id); put("name", profile.name); putNullable("birthDateEpochDay", profile.birthDateEpochDay)
                putNullable("heightCm", profile.heightCm); putNullable("currentWeightKg", profile.currentWeightKg)
                putNullable("goal", profile.goal); putNullable("activityLevel", profile.activityLevel)
                putNullable("wakeTimeMinutes", profile.wakeTimeMinutes); putNullable("sleepTimeMinutes", profile.sleepTimeMinutes)
                putNullable("dietaryPreferencesJson", profile.dietaryPreferencesJson)
            })
            put("biaMeasurements", JSONArray().apply { bia.forEach { r -> put(JSONObject().apply {
                put("id", r.id); put("measuredAtEpochMillis", r.measuredAtEpochMillis)
                putNullable("weightKg", r.weightKg); putNullable("bodyFatPercent", r.bodyFatPercent); putNullable("visceralFatLevel", r.visceralFatLevel)
                putNullable("muscleMassKg", r.muscleMassKg); putNullable("skeletalMuscleKg", r.skeletalMuscleKg); putNullable("bodyWaterPercent", r.bodyWaterPercent)
                putNullable("bmrKcal", r.bmrKcal); put("fasting", r.fasting); put("justWokeUp", r.justWokeUp); put("afterBathroom", r.afterBathroom); put("noRecentWorkout", r.noRecentWorkout); putNullable("notes", r.notes)
            }) } })
            put("bodyMeasurements", JSONArray().apply { body.forEach { r -> put(JSONObject().apply {
                put("id", r.id); put("measuredAtEpochMillis", r.measuredAtEpochMillis); putNullable("chestCm", r.chestCm); putNullable("waistCm", r.waistCm)
                putNullable("abdomenCm", r.abdomenCm); putNullable("shouldersCm", r.shouldersCm); putNullable("glutesCm", r.glutesCm); putNullable("armLeftCm", r.armLeftCm)
                putNullable("armRightCm", r.armRightCm); putNullable("thighLeftCm", r.thighLeftCm); putNullable("thighRightCm", r.thighRightCm); putNullable("calfLeftCm", r.calfLeftCm); putNullable("calfRightCm", r.calfRightCm); putNullable("notes", r.notes)
            }) } })
            put("workouts", JSONArray().apply { workouts.forEach { r -> put(JSONObject().apply {
                put("id", r.id); put("startedAtEpochMillis", r.startedAtEpochMillis); put("type", r.type); put("title", r.title); putNullable("durationMinutes", r.durationMinutes); put("isRestDay", r.isRestDay); putNullable("notes", r.notes)
            }) } })
            put("cheatEntries", JSONArray().apply { cheats.forEach { r -> put(JSONObject().apply {
                put("id", r.id); put("occurredAtEpochMillis", r.occurredAtEpochMillis); put("description", r.description); putNullable("quantityText", r.quantityText); putNullable("estimatedKcal", r.estimatedKcal); putNullable("estimatedProteinG", r.estimatedProteinG); putNullable("estimatedCarbsG", r.estimatedCarbsG); putNullable("estimatedFatG", r.estimatedFatG); putNullable("planVersionId", r.planVersionId); putNullable("notes", r.notes)
            }) } })
            put("weeklyReviews", JSONArray().apply { reviews.forEach { r -> put(JSONObject().apply {
                put("id", r.id); put("weekStartEpochDay", r.weekStartEpochDay); put("createdAtEpochMillis", r.createdAtEpochMillis); putNullable("adherencePercent", r.adherencePercent); put("summary", r.summary); putNullable("structuredJson", r.structuredJson)
            }) } })
        }

        val planJson = JSONArray()
        plans.forEach { plan ->
            val versions = db.mealPlanDao().observeVersions(plan.id).first().sortedBy { it.versionNumber }
            val versionsJson = JSONArray()
            versions.forEach { version ->
                val daysJson = JSONArray()
                db.mealPlanDao().getDays(version.id).forEach { day ->
                    val mealsJson = JSONArray()
                    db.mealPlanDao().getMeals(day.id).forEach { meal ->
                        val ingredients = JSONArray()
                        db.mealPlanDao().getIngredients(meal.id).forEach { ing -> ingredients.put(JSONObject().apply {
                            put("id", ing.id); put("name", ing.name); put("quantity", ing.quantity); put("unit", ing.unit); putNullable("displayDose", ing.displayDose); putNullable("weightState", ing.weightState); putNullable("nutritionConfidence", ing.nutritionConfidence); putNullable("category", ing.category); put("sortOrder", ing.sortOrder)
                        }) }
                        mealsJson.put(JSONObject().apply {
                            put("id", meal.id); put("sortOrder", meal.sortOrder); put("type", meal.type); put("title", meal.title); putNullable("timeMinutes", meal.timeMinutes); putNullable("kcal", meal.kcal); putNullable("proteinG", meal.proteinG); putNullable("carbsG", meal.carbsG); putNullable("fatG", meal.fatG); putNullable("preparation", meal.preparation); put("ingredients", ingredients)
                        })
                    }
                    daysJson.put(JSONObject().apply { put("id", day.id); put("dateEpochDay", day.dateEpochDay); putNullable("totalKcal", day.totalKcal); putNullable("proteinG", day.proteinG); putNullable("carbsG", day.carbsG); putNullable("fatG", day.fatG); put("meals", mealsJson) })
                }
                versionsJson.put(JSONObject().apply { put("id", version.id); put("versionNumber", version.versionNumber); put("createdAtEpochMillis", version.createdAtEpochMillis); put("source", version.source); putNullable("reason", version.reason); putNullable("targetKcal", version.targetKcal); putNullable("targetProteinG", version.targetProteinG); putNullable("targetCarbsG", version.targetCarbsG); putNullable("targetFatG", version.targetFatG); put("days", daysJson) })
            }
            planJson.put(JSONObject().apply { put("id", plan.id); put("weekStartEpochDay", plan.weekStartEpochDay); put("createdAtEpochMillis", plan.createdAtEpochMillis); put("status", plan.status); put("versions", versionsJson) })
        }
        root.put("mealPlans", planJson)

        return when (format) {
            Format.JSON -> writeJson(profile.name, root)
            Format.CSV_ZIP -> writeCsvZip(profile.name, root)
            Format.PDF -> writePdf(profile.name, bia.size, body.size, workouts.size, cheats.size, reviews.size, plans.size)
        }
    }

    fun contentUri(file: File) = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)

    private fun writeJson(name: String, root: JSONObject): ExportedFile {
        val file = exportFile(name, "json")
        file.writeText(root.toString(2))
        return ExportedFile(file, "application/json")
    }

    private fun writeCsvZip(name: String, root: JSONObject): ExportedFile {
        val file = exportFile(name, "zip")
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            val tables = listOf("biaMeasurements", "bodyMeasurements", "workouts", "cheatEntries", "weeklyReviews")
            tables.forEach { key -> addCsv(zip, "$key.csv", root.getJSONArray(key)) }
            val profile = JSONArray().put(root.getJSONObject("profile")); addCsv(zip, "profile.csv", profile)
            zip.putNextEntry(ZipEntry("README.txt")); zip.write("MyFitAI CSV export. mealPlans are preserved completely in meal_plans.json because the hierarchy plan/version/day/meal/ingredient is not losslessly representable in one flat CSV.\n".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("meal_plans.json")); zip.write(root.getJSONArray("mealPlans").toString(2).toByteArray()); zip.closeEntry()
        }
        return ExportedFile(file, "application/zip")
    }

    private fun addCsv(zip: ZipOutputStream, name: String, rows: JSONArray) {
        zip.putNextEntry(ZipEntry(name))
        if (rows.length() == 0) { zip.closeEntry(); return }
        val keys = rows.getJSONObject(0).keys().asSequence().toList()
        zip.write((keys.joinToString(",") + "\n").toByteArray())
        repeat(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            zip.write((keys.joinToString(",") { csv(row.opt(it).takeUnless { v -> v == JSONObject.NULL }?.toString().orEmpty()) } + "\n").toByteArray())
        }
        zip.closeEntry()
    }

    private fun writePdf(name: String, bia: Int, body: Int, workouts: Int, cheats: Int, reviews: Int, plans: Int): ExportedFile {
        val file = exportFile(name, "pdf")
        val document = PdfDocument(); val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f }; var y = 60f
        page.canvas.drawText("MyFitAI — Riepilogo export", 40f, y, paint); y += 42f; paint.textSize = 13f
        listOf("Profilo: $name", "Generato: ${Instant.now()}", "Rilevazioni BIA: $bia", "Misure corporee: $body", "Allenamenti/riposi: $workouts", "Sgarri registrati: $cheats", "Review settimanali: $reviews", "Settimane con piano: $plans", "", "Per analisi complete e ChatGPT usa l'export JSON, che conserva tutte le versioni dei piani.").forEach { line -> page.canvas.drawText(line, 40f, y, paint); y += 26f }
        document.finishPage(page); FileOutputStream(file).use(document::writeTo); document.close()
        return ExportedFile(file, "application/pdf")
    }

    private fun exportFile(name: String, ext: String): File {
        val dir = File(appContext.cacheDir, "exports").apply { mkdirs() }
        val safe = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "profile" }
        return File(dir, "myfitai-$safe-${DateTimeFormatter.BASIC_ISO_DATE.format(LocalDate.now())}.$ext")
    }

    private fun csv(value: String) = "\"${value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ")}\""
    private fun JSONObject.putNullable(key: String, value: Any?) { put(key, value ?: JSONObject.NULL) }
}
