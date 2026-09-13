package com.myfitai.app.domain.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.domain.body.BodyProportionEngine
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import com.myfitai.app.domain.food.FoodMeal
import com.myfitai.app.domain.food.FoodPlanDay
import com.myfitai.app.domain.food.FoodPlanSnapshot
import com.myfitai.app.domain.shopping.ShoppingListEngine
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

internal object PdfExportRenderer {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 40f

    private val BG = Color.rgb(13, 17, 23)
    private val PANEL = Color.rgb(22, 27, 34)
    private val PANEL_2 = Color.rgb(31, 38, 48)
    private val TEXT = Color.rgb(245, 247, 250)
    private val MUTED = Color.rgb(152, 162, 179)
    private val ACCENT = Color.rgb(126, 231, 135)
    private val ACCENT_2 = Color.rgb(88, 166, 255)
    private val WARNING = Color.rgb(242, 204, 96)
    private val LINE = Color.rgb(48, 54, 61)

    data class ProfileReportInput(
        val profile: UserProfileEntity,
        val latestBia: BiaMeasurementEntity?,
        val latestBody: BodyMeasurementEntity?,
        val calculation: LocalCalculationEngine.Result,
        val proportions: BodyProportionEngine.Report,
        val weightTrend: List<Pair<Long, Float>>,
        val waistTrend: List<Pair<Long, Float>>,
        val biaCount: Int,
        val bodyCount: Int,
        val workoutCount: Int,
        val cheatCount: Int,
        val reviewCount: Int,
        val planCount: Int,
        val latestPlanTargetKcal: Int?,
        val latestPlanTargetProteinG: Float?,
        val latestPlanTargetCarbsG: Float?,
        val latestPlanTargetFatG: Float?,
    )

    fun writeProfileReport(file: File, input: ProfileReportInput) {
        val document = PdfDocument()
        var pageNumber = 1

        run {
            val page = startPage(document, pageNumber)
            val canvas = page.canvas
            drawHeader(canvas, "Riepilogo profilo", input.profile.name)
            drawMetricCard(canvas, 40f, 160f, 247f, 82f, "PESO", formatKg(input.latestBia?.weightKg ?: input.profile.currentWeightKg), ACCENT)
            drawMetricCard(canvas, 308f, 160f, 247f, 82f, "MASSA GRASSA", formatPercent(input.latestBia?.bodyFatPercent), ACCENT)
            drawMetricCard(canvas, 40f, 258f, 247f, 82f, "MASSA MUSCOLARE", formatKg(input.latestBia?.muscleMassKg), ACCENT_2)
            drawMetricCard(canvas, 308f, 258f, 247f, 82f, "VITA", formatCm(input.latestBody?.waistCm), ACCENT)

            drawCard(canvas, 40f, 365f, 515f, 178f)
            text(canvas, "Snapshot attuale", 58f, 394f, 16f, TEXT, true)
            val snapshot = listOf(
                "Altezza" to formatCm(input.profile.heightCm),
                "BMI" to format1(input.calculation.bmi),
                "BMR" to formatKcal(input.calculation.bmrKcal),
                "TDEE" to formatKcal(input.calculation.tdeeKcal),
                "Target" to formatKcal(input.calculation.targetKcal),
                "Metodo BMR" to (input.calculation.bmrMethod ?: "Dati insufficienti"),
            )
            snapshot.forEachIndexed { index, (label, value) ->
                val col = index % 3
                val row = index / 3
                val x = 58f + col * 165f
                val y = 426f + row * 62f
                text(canvas, label.uppercase(Locale.ITALIAN), x, y, 9f, MUTED, true)
                text(canvas, value, x, y + 22f, 14f, TEXT, true)
            }

            drawCard(canvas, 40f, 568f, 515f, 124f, PANEL_2)
            text(canvas, "Contenuto del report", 58f, 596f, 14f, TEXT, true)
            paragraph(
                canvas,
                "Il PDF e un report leggibile del profilo attivo: BIA, misure, proporzioni, trend, alimentazione e attivita. Per analisi complete e condivisione con ChatGPT resta consigliato anche il JSON, che conserva tutte le versioni storiche dei piani.",
                58f,
                620f,
                476f,
                11f,
                MUTED,
                16f,
            )
            drawFooter(canvas, pageNumber)
            document.finishPage(page)
            pageNumber++
        }

        run {
            val page = startPage(document, pageNumber)
            val canvas = page.canvas
            drawHeader(canvas, "Composizione e misure", "BIA, circonferenze e proporzioni corporee")

            drawCard(canvas, 40f, 150f, 247f, 250f)
            text(canvas, "BIA - ultima rilevazione", 58f, 178f, 15f, TEXT, true)
            val biaRows = listOf(
                "Peso" to formatKg(input.latestBia?.weightKg),
                "Grasso" to formatPercent(input.latestBia?.bodyFatPercent),
                "Muscolo" to formatKg(input.latestBia?.muscleMassKg),
                "Muscolo scheletrico" to formatKg(input.latestBia?.skeletalMuscleKg),
                "Acqua" to formatPercent(input.latestBia?.bodyWaterPercent),
                "Grasso viscerale" to format1(input.latestBia?.visceralFatLevel?.toDouble()),
                "BMR BIA" to input.latestBia?.bmrKcal?.let { "${it.toInt()} kcal" }.orDash(),
            )
            drawRows(canvas, 58f, 210f, 211f, biaRows)

            drawCard(canvas, 308f, 150f, 247f, 250f)
            text(canvas, "Misure corporee", 326f, 178f, 15f, TEXT, true)
            val bodyRows = listOf(
                "Torace" to formatCm(input.latestBody?.chestCm),
                "Vita" to formatCm(input.latestBody?.waistCm),
                "Addome" to formatCm(input.latestBody?.abdomenCm),
                "Spalle" to formatCm(input.latestBody?.shouldersCm),
                "Glutei" to formatCm(input.latestBody?.glutesCm),
                "Braccio sx/dx" to pairCm(input.latestBody?.armLeftCm, input.latestBody?.armRightCm),
                "Coscia sx/dx" to pairCm(input.latestBody?.thighLeftCm, input.latestBody?.thighRightCm),
                "Polpaccio sx/dx" to pairCm(input.latestBody?.calfLeftCm, input.latestBody?.calfRightCm),
            )
            drawRows(canvas, 326f, 210f, 211f, bodyRows)

            drawCard(canvas, 40f, 425f, 515f, 245f)
            text(canvas, "Proporzioni corporee", 58f, 455f, 15f, TEXT, true)
            text(canvas, balanceLabel(input.proportions.status), 535f, 455f, 11f, statusColor(input.proportions.status), true, alignRight = true)
            var y = 486f
            val lines = buildList {
                input.proportions.ratios.take(5).forEach { add(it.label to String.format(Locale.ITALIAN, "%.2f", it.value)) }
                input.proportions.asymmetries.take(3).forEach { add("${it.label} dx/sx" to String.format(Locale.ITALIAN, "%.1f%%", it.percent)) }
            }
            lines.forEach { (label, value) ->
                text(canvas, label, 58f, y, 11f, MUTED)
                text(canvas, value, 535f, y, 11f, TEXT, true, alignRight = true)
                y += 24f
            }
            paragraph(canvas, input.proportions.note, 58f, 620f, 476f, 9.5f, MUTED, 14f)
            drawFooter(canvas, pageNumber)
            document.finishPage(page)
            pageNumber++
        }

        run {
            val page = startPage(document, pageNumber)
            val canvas = page.canvas
            drawHeader(canvas, "Trend", "Andamento delle rilevazioni disponibili")
            text(canvas, "Peso (kg)", 40f, 145f, 14f, TEXT, true)
            drawLineChart(canvas, 40f, 165f, 515f, 235f, input.weightTrend.map { it.second })
            text(canvas, "Vita (cm)", 40f, 445f, 14f, TEXT, true)
            drawLineChart(canvas, 40f, 465f, 515f, 235f, input.waistTrend.map { it.second })
            drawFooter(canvas, pageNumber)
            document.finishPage(page)
            pageNumber++
        }

        run {
            val page = startPage(document, pageNumber)
            val canvas = page.canvas
            drawHeader(canvas, "Alimentazione e attivita", "Sintesi operativa del profilo")

            drawCard(canvas, 40f, 150f, 515f, 205f)
            text(canvas, "Piano alimentare", 58f, 180f, 15f, TEXT, true)
            val nutrition = listOf(
                "Target energia" to input.latestPlanTargetKcal?.let { "$it kcal" }.orDash(),
                "Proteine" to input.latestPlanTargetProteinG?.let { formatGram(it) }.orDash(),
                "Carboidrati" to input.latestPlanTargetCarbsG?.let { formatGram(it) }.orDash(),
                "Grassi" to input.latestPlanTargetFatG?.let { formatGram(it) }.orDash(),
                "Settimane con piano" to input.planCount.toString(),
            )
            drawRows(canvas, 58f, 216f, 475f, nutrition)

            drawMetricCard(canvas, 40f, 385f, 247f, 105f, "ALLENAMENTI / RIPOSI", input.workoutCount.toString(), ACCENT_2)
            drawMetricCard(canvas, 308f, 385f, 247f, 105f, "SGARRI REGISTRATI", input.cheatCount.toString(), WARNING)
            drawMetricCard(canvas, 40f, 515f, 247f, 105f, "RILEVAZIONI BIA", input.biaCount.toString(), ACCENT)
            drawMetricCard(canvas, 308f, 515f, 247f, 105f, "MISURE CORPOREE", input.bodyCount.toString(), ACCENT)

            drawCard(canvas, 40f, 645f, 515f, 80f, PANEL_2)
            text(canvas, "Weekly review salvate: ${input.reviewCount}", 58f, 675f, 12f, TEXT, true)
            text(canvas, "Il report non sostituisce il JSON completo.", 58f, 699f, 10f, MUTED)
            drawFooter(canvas, pageNumber)
            document.finishPage(page)
        }

        FileOutputStream(file).use(document::writeTo)
        document.close()
    }

    fun writeWeeklyPlanReport(
        file: File,
        profileName: String,
        snapshot: FoodPlanSnapshot,
        shoppingItems: List<ShoppingListEngine.Item>,
    ) {
        val document = PdfDocument()
        var pageNumber = 1
        val weekStart = LocalDate.ofEpochDay(snapshot.weekStartEpochDay)
        val weekEnd = weekStart.plusDays(6)
        val dateRange = "${formatDate(weekStart)} - ${formatDate(weekEnd)}"

        run {
            val page = startPage(document, pageNumber)
            val canvas = page.canvas
            drawHeader(canvas, "Piano alimentare settimanale", "$dateRange | $profileName")
            drawCard(canvas, 40f, 155f, 515f, 155f)
            text(canvas, "Target giornaliero", 58f, 185f, 13f, MUTED, true)
            text(canvas, snapshot.version.targetKcal?.let { "$it kcal" }.orDash(), 58f, 235f, 30f, TEXT, true)
            val macros = listOf(
                "Proteine" to snapshot.version.targetProteinG?.let(::formatGram).orDash(),
                "Carboidrati" to snapshot.version.targetCarbsG?.let(::formatGram).orDash(),
                "Grassi" to snapshot.version.targetFatG?.let(::formatGram).orDash(),
            )
            macros.forEachIndexed { index, pair ->
                val x = 315f + (index % 2) * 115f
                val y = 194f + (index / 2) * 54f
                text(canvas, pair.first.uppercase(Locale.ITALIAN), x, y, 8.5f, MUTED, true)
                text(canvas, pair.second, x, y + 23f, 13f, if (index == 0) ACCENT else if (index == 1) ACCENT_2 else WARNING, true)
            }
            drawCard(canvas, 40f, 335f, 515f, 170f, PANEL_2)
            text(canvas, "Come leggere il piano", 58f, 365f, 14f, TEXT, true)
            paragraph(
                canvas,
                "Ogni giornata riporta orario, tipo di pasto, piatto, ingredienti con le dosi e macro del pasto. Dopo i sette giorni trovi la lista della spesa aggregata per categoria, calcolata deterministicamente dagli ingredienti del piano.",
                58f,
                395f,
                476f,
                11f,
                MUTED,
                16f,
            )
            text(canvas, "Versione piano: ${snapshot.version.versionNumber}", 58f, 472f, 10f, MUTED)
            drawFooter(canvas, pageNumber)
            document.finishPage(page)
            pageNumber++
        }

        snapshot.version.days.sortedBy { it.dateEpochDay }.forEach { day ->
            val page = startPage(document, pageNumber)
            val canvas = page.canvas
            drawHeader(canvas, dayTitle(day), dateRange)
            drawDay(canvas, day)
            drawFooter(canvas, pageNumber)
            document.finishPage(page)
            pageNumber++
        }

        val groups = shoppingItems.groupBy { it.category }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
        var page = startPage(document, pageNumber)
        var canvas = page.canvas
        drawHeader(canvas, "Lista della spesa", "Aggregata dal piano settimanale")
        var y = 150f

        fun finishShoppingPage() {
            drawFooter(canvas, pageNumber)
            document.finishPage(page)
            pageNumber++
            page = startPage(document, pageNumber)
            canvas = page.canvas
            drawHeader(canvas, "Lista della spesa", "Continua")
            y = 150f
        }

        groups.forEach { (category, items) ->
            val required = 54f + items.size * 27f
            if (y + required > 775f) finishShoppingPage()
            drawCard(canvas, 40f, y, 515f, required - 10f)
            text(canvas, category.uppercase(Locale.ITALIAN), 58f, y + 27f, 11f, ACCENT, true)
            var rowY = y + 52f
            items.forEach { item ->
                text(canvas, "[ ]", 58f, rowY, 10f, MUTED, true)
                text(canvas, item.name, 88f, rowY, 10.5f, TEXT)
                text(canvas, item.displayQuantity(), 535f, rowY, 10.5f, TEXT, true, alignRight = true)
                rowY += 27f
            }
            y += required
        }
        drawFooter(canvas, pageNumber)
        document.finishPage(page)

        FileOutputStream(file).use(document::writeTo)
        document.close()
    }

    private fun drawDay(canvas: Canvas, day: FoodPlanDay) {
        var y = 145f
        val total = day.totalKcal?.let { "$it kcal" }.orDash()
        text(canvas, "Totale giornata: $total", 555f, 145f, 10.5f, ACCENT, true, alignRight = true)
        y += 24f
        day.meals.sortedBy(FoodMeal::sortOrder).forEach { meal ->
            val ingredientText = meal.ingredients.sortedBy { it.sortOrder }.joinToString(" - ") { ingredient ->
                val dose = ingredient.displayDose?.takeIf { it.isNotBlank() }
                    ?: "${formatNumber(ingredient.quantity)} ${ingredient.unit}"
                "${ingredient.name} $dose"
            }
            val ingredientLines = wrapLines(ingredientText, 430f, 10f, false)
            val prepLines = meal.preparation?.takeIf { it.isNotBlank() }?.let { wrapLines(it, 430f, 9.5f, false).take(2) }.orEmpty()
            val height = 74f + ingredientLines.size * 14f + prepLines.size * 13f
            if (y + height > 765f) break
            drawCard(canvas, 40f, y, 515f, height)
            val time = meal.timeMinutes?.let { String.format(Locale.ITALIAN, "%02d:%02d", it / 60, it % 60) }.orDash()
            text(canvas, time, 58f, y + 26f, 10f, ACCENT_2, true)
            text(canvas, meal.type.uppercase(Locale.ITALIAN), 118f, y + 26f, 9f, MUTED, true)
            text(canvas, meal.title, 205f, y + 26f, 11.5f, TEXT, true)
            text(canvas, meal.kcal?.let { "$it kcal" }.orDash(), 535f, y + 26f, 9.5f, ACCENT, true, alignRight = true)
            var lineY = y + 48f
            ingredientLines.forEach { line ->
                text(canvas, line, 118f, lineY, 9.5f, MUTED)
                lineY += 14f
            }
            if (prepLines.isNotEmpty()) {
                prepLines.forEach { line ->
                    text(canvas, line, 118f, lineY, 9f, MUTED)
                    lineY += 13f
                }
            }
            val macroText = listOfNotNull(
                meal.proteinG?.let { "P ${formatNumber(it)} g" },
                meal.carbsG?.let { "C ${formatNumber(it)} g" },
                meal.fatG?.let { "F ${formatNumber(it)} g" },
            ).joinToString(" | ")
            if (macroText.isNotBlank()) text(canvas, macroText, 118f, y + height - 16f, 9f, ACCENT)
            y += height + 12f
        }
    }

    private fun drawRows(canvas: Canvas, x: Float, startY: Float, width: Float, rows: List<Pair<String, String>>) {
        var y = startY
        rows.forEach { (label, value) ->
            text(canvas, label, x, y, 10.5f, MUTED)
            text(canvas, value, x + width, y, 10.5f, TEXT, true, alignRight = true)
            y += 26f
        }
    }

    private fun drawLineChart(canvas: Canvas, x: Float, y: Float, width: Float, height: Float, values: List<Float>) {
        drawCard(canvas, x, y, width, height)
        if (values.size < 2) {
            text(canvas, "Dati insufficienti", x + 18f, y + 40f, 11f, MUTED)
            return
        }
        val sample = values.takeLast(24)
        val minValue = sample.minOrNull() ?: return
        val maxValue = sample.maxOrNull() ?: return
        val range = max(0.5f, maxValue - minValue)
        val left = x + 28f
        val right = x + width - 22f
        val top = y + 28f
        val bottom = y + height - 32f
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT_2; strokeWidth = 3f; style = Paint.Style.STROKE }
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LINE; strokeWidth = 1f }
        repeat(4) { index ->
            val gy = top + (bottom - top) * index / 3f
            canvas.drawLine(left, gy, right, gy, gridPaint)
        }
        sample.forEachIndexed { index, value ->
            if (index == 0) return@forEachIndexed
            val prev = sample[index - 1]
            val x1 = left + (right - left) * (index - 1) / (sample.size - 1).toFloat()
            val x2 = left + (right - left) * index / (sample.size - 1).toFloat()
            val y1 = bottom - (prev - minValue) / range * (bottom - top)
            val y2 = bottom - (value - minValue) / range * (bottom - top)
            canvas.drawLine(x1, y1, x2, y2, linePaint)
        }
        text(canvas, String.format(Locale.ITALIAN, "%.1f", maxValue), left, top + 2f, 8f, MUTED)
        text(canvas, String.format(Locale.ITALIAN, "%.1f", minValue), left, bottom + 15f, 8f, MUTED)
    }

    private fun startPage(document: PdfDocument, pageNumber: Int): PdfDocument.Page {
        val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
        page.canvas.drawColor(BG)
        return page
    }

    private fun drawHeader(canvas: Canvas, title: String, subtitle: String) {
        text(canvas, "MYFITAI", MARGIN, 55f, 12f, ACCENT, true)
        text(canvas, title, MARGIN, 92f, 25f, TEXT, true)
        text(canvas, subtitle, MARGIN, 120f, 10f, MUTED)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LINE; strokeWidth = 1f }
        canvas.drawLine(MARGIN, 135f, PAGE_WIDTH - MARGIN, 135f, paint)
    }

    private fun drawFooter(canvas: Canvas, pageNumber: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LINE; strokeWidth = 1f }
        canvas.drawLine(MARGIN, 805f, PAGE_WIDTH - MARGIN, 805f, paint)
        text(canvas, "Export locale MyFitAI - profilo attivo", MARGIN, 826f, 8f, MUTED)
        text(canvas, "Pagina $pageNumber", PAGE_WIDTH - MARGIN, 826f, 8f, MUTED, alignRight = true)
    }

    private fun drawCard(canvas: Canvas, x: Float, y: Float, width: Float, height: Float, color: Int = PANEL) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        canvas.drawRoundRect(RectF(x, y, x + width, y + height), 12f, 12f, paint)
    }

    private fun drawMetricCard(canvas: Canvas, x: Float, y: Float, width: Float, height: Float, label: String, value: String, valueColor: Int) {
        drawCard(canvas, x, y, width, height)
        text(canvas, label, x + 18f, y + 27f, 9f, MUTED, true)
        text(canvas, value, x + 18f, y + 59f, 20f, valueColor, true)
    }

    private fun text(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        bold: Boolean = false,
        alignRight: Boolean = false,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            textAlign = if (alignRight) Paint.Align.RIGHT else Paint.Align.LEFT
        }
        canvas.drawText(value, x, y, paint)
    }

    private fun paragraph(canvas: Canvas, value: String, x: Float, y: Float, width: Float, size: Float, color: Int, lineHeight: Float) {
        wrapLines(value, width, size, false).forEachIndexed { index, line ->
            text(canvas, line, x, y + index * lineHeight, size, color)
        }
    }

    private fun wrapLines(value: String, width: Float, size: Float, bold: Boolean): List<String> {
        if (value.isBlank()) return emptyList()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        }
        val lines = mutableListOf<String>()
        var current = ""
        value.trim().split(Regex("\\s+")).forEach { word ->
            val candidate = if (current.isBlank()) word else "$current $word"
            if (paint.measureText(candidate) <= width || current.isBlank()) current = candidate
            else {
                lines += current
                current = word
            }
        }
        if (current.isNotBlank()) lines += current
        return lines
    }

    private fun balanceLabel(status: BodyProportionEngine.BalanceStatus): String = when (status) {
        BodyProportionEngine.BalanceStatus.BALANCED -> "Equilibrato"
        BodyProportionEngine.BalanceStatus.MILD_IMBALANCE -> "Lieve differenza"
        BodyProportionEngine.BalanceStatus.NOTICEABLE_IMBALANCE -> "Differenza da monitorare"
        BodyProportionEngine.BalanceStatus.INSUFFICIENT_DATA -> "Dati insufficienti"
    }

    private fun statusColor(status: BodyProportionEngine.BalanceStatus): Int = when (status) {
        BodyProportionEngine.BalanceStatus.BALANCED -> ACCENT
        BodyProportionEngine.BalanceStatus.MILD_IMBALANCE -> WARNING
        BodyProportionEngine.BalanceStatus.NOTICEABLE_IMBALANCE -> Color.rgb(255, 123, 114)
        BodyProportionEngine.BalanceStatus.INSUFFICIENT_DATA -> MUTED
    }

    private fun dayTitle(day: FoodPlanDay): String {
        val date = LocalDate.ofEpochDay(day.dateEpochDay)
        return date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN)).replaceFirstChar { it.titlecase(Locale.ITALIAN) }
    }

    private fun formatDate(value: LocalDate): String = value.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ITALIAN))
    private fun formatKg(value: Float?): String = value?.let { "${formatNumber(it)} kg" }.orDash()
    private fun formatCm(value: Float?): String = value?.let { "${formatNumber(it)} cm" }.orDash()
    private fun formatPercent(value: Float?): String = value?.let { "${formatNumber(it)}%" }.orDash()
    private fun formatGram(value: Float): String = "${formatNumber(value)} g"
    private fun pairCm(left: Float?, right: Float?): String = if (left == null && right == null) "-" else "${left?.let(::formatNumber) ?: "-"} / ${right?.let(::formatNumber) ?: "-"} cm"
    private fun format1(value: Double?): String = value?.let { String.format(Locale.ITALIAN, "%.1f", it) }.orDash()
    private fun formatKcal(value: Double?): String = value?.let { "${it.toInt()} kcal" }.orDash()
    private fun formatNumber(value: Float): String = if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.ITALIAN, "%.1f", value)
    private fun String?.orDash(): String = this?.takeIf { it.isNotBlank() } ?: "-"
}
