package com.myfitai.app.domain.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Renderer PDF condiviso dai due export umani dell'app.
 *
 * Regole:
 * - usa esclusivamente dati gia persistiti/calcolati dall'app;
 * - non inventa valori mancanti: mostra "-"/"Dati insufficienti";
 * - mantiene la palette dark dell'app e impaginazione A4 coerente con le anteprime approvate;
 * - il report profilo e il piano settimanale restano separati dal JSON canonico.
 */
internal object PdfExportRenderer {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 40f
    private const val CONTENT_RIGHT = PAGE_WIDTH - MARGIN

    private val BG = Color.rgb(13, 17, 23)
    private val PANEL = Color.rgb(22, 27, 34)
    private val PANEL_2 = Color.rgb(31, 38, 48)
    private val TEXT = Color.rgb(245, 247, 250)
    private val MUTED = Color.rgb(152, 162, 179)
    private val ACCENT = Color.rgb(126, 231, 135)
    private val ACCENT_2 = Color.rgb(88, 166, 255)
    private val WARNING = Color.rgb(242, 204, 96)
    private val DANGER = Color.rgb(255, 123, 114)
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

        // 1. Riepilogo profilo
        run {
            val page = startPage(document, pageNumber)
            val canvas = page.canvas
            drawHeader(canvas, "Riepilogo profilo", "Report del profilo attivo")

            drawCard(canvas, 40f, 150f, 515f, 78f)
            text(canvas, input.profile.name, 58f, 183f, 18f, TEXT, true)
            text(canvas, "Aggiornato al ${formatDate(LocalDate.now())}", 58f, 208f, 9.5f, MUTED)
            text(canvas, input.profile.goal?.uppercase(Locale.ITALIAN) ?: "OBIETTIVO NON IMPOSTATO", 537f, 183f, 9.5f, ACCENT, true, alignRight = true)

            val weightDelta = trendDelta(input.weightTrend)
            val waistDelta = trendDelta(input.waistTrend)
            drawMetricCard(canvas, 40f, 248f, 247f, 90f, "PESO", formatKg(input.latestBia?.weightKg ?: input.profile.currentWeightKg), weightDelta?.let { "${formatSigned(it)} kg nel periodo" }, ACCENT)
            drawMetricCard(canvas, 308f, 248f, 247f, 90f, "MASSA GRASSA", formatPercent(input.latestBia?.bodyFatPercent), null, ACCENT)
            drawMetricCard(canvas, 40f, 358f, 247f, 90f, "MASSA MUSCOLARE", formatKg(input.latestBia?.muscleMassKg), null, ACCENT_2)
            drawMetricCard(canvas, 308f, 358f, 247f, 90f, "VITA", formatCm(input.latestBody?.waistCm), waistDelta?.let { "${formatSigned(it)} cm nel periodo" }, ACCENT)

            drawCard(canvas, 40f, 475f, 515f, 155f)
            text(canvas, "Snapshot attuale", 58f, 505f, 15f, TEXT, true)
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
                val y = 540f + row * 56f
                text(canvas, label.uppercase(Locale.ITALIAN), x, y, 8.5f, MUTED, true)
                text(canvas, value, x, y + 23f, 13.5f, TEXT, true)
            }

            drawCard(canvas, 40f, 655f, 515f, 98f, PANEL_2)
            text(canvas, "Lettura sintetica", 58f, 684f, 13f, TEXT, true)
            val summary = buildProfileSummary(input)
            paragraph(canvas, summary, 58f, 709f, 476f, 10f, MUTED, 15f)
            drawFooter(canvas, pageNumber, "Report profilo")
            document.finishPage(page)
            pageNumber++
        }

        // 2. BIA / misure / proporzioni
        run {
            val page = startPage(document, pageNumber)
            val canvas = page.canvas
            drawHeader(canvas, "Composizione e misure", "BIA, circonferenze e proporzioni corporee")

            drawCard(canvas, 40f, 150f, 247f, 225f)
            text(canvas, "BIA - ultima rilevazione", 58f, 180f, 14f, TEXT, true)
            drawRows(canvas, 58f, 215f, 211f, listOf(
                "Peso" to formatKg(input.latestBia?.weightKg),
                "Grasso" to formatPercent(input.latestBia?.bodyFatPercent),
                "Muscolo" to formatKg(input.latestBia?.muscleMassKg),
                "Acqua" to formatPercent(input.latestBia?.bodyWaterPercent),
                "Grasso viscerale" to format1(input.latestBia?.visceralFatLevel?.toDouble()),
                "BMR BIA" to input.latestBia?.bmrKcal?.let { "${it.toInt()} kcal" }.orDash(),
            ), rowHeight = 25f)

            drawCard(canvas, 308f, 150f, 247f, 225f)
            text(canvas, "Misure corporee", 326f, 180f, 14f, TEXT, true)
            drawRows(canvas, 326f, 215f, 211f, listOf(
                "Torace" to formatCm(input.latestBody?.chestCm),
                "Vita" to formatCm(input.latestBody?.waistCm),
                "Addome" to formatCm(input.latestBody?.abdomenCm),
                "Spalle" to formatCm(input.latestBody?.shouldersCm),
                "Braccio sx/dx" to pairCm(input.latestBody?.armLeftCm, input.latestBody?.armRightCm),
                "Coscia sx/dx" to pairCm(input.latestBody?.thighLeftCm, input.latestBody?.thighRightCm),
            ), rowHeight = 25f)

            drawCard(canvas, 40f, 400f, 515f, 235f)
            text(canvas, "Proporzioni corporee", 58f, 432f, 15f, TEXT, true)
            text(canvas, balanceLabel(input.proportions.status), 535f, 432f, 10f, statusColor(input.proportions.status), true, alignRight = true)

            val proportionRows = buildList {
                input.proportions.ratios.take(4).forEach { ratio ->
                    add(ratio.label to String.format(Locale.ITALIAN, "%.2f", ratio.value))
                }
                input.proportions.asymmetries.take(3).forEach { asymmetry ->
                    add("${asymmetry.label} dx/sx" to String.format(Locale.ITALIAN, "%.1f%%", asymmetry.percent))
                }
            }
            drawRows(canvas, 58f, 468f, 477f, proportionRows, rowHeight = 25f)

            drawCard(canvas, 40f, 660f, 515f, 92f, PANEL_2)
            text(canvas, "Nota metodologica", 58f, 688f, 12f, TEXT, true)
            paragraph(canvas, input.proportions.note, 58f, 712f, 476f, 9.5f, MUTED, 14f)
            drawFooter(canvas, pageNumber, "Report profilo")
            document.finishPage(page)
            pageNumber++
        }

        // 3. Trend
        run {
            val page = startPage(document, pageNumber)
            val canvas = page.canvas
            drawHeader(canvas, "Trend", "Andamento reale nel periodo disponibile")
            text(canvas, "Peso (kg)", 40f, 160f, 13f, TEXT, true)
            drawLineChart(canvas, 40f, 180f, 515f, 220f, input.weightTrend)
            text(canvas, "Vita (cm)", 40f, 450f, 13f, TEXT, true)
            drawLineChart(canvas, 40f, 470f, 515f, 220f, input.waistTrend)

            drawCard(canvas, 40f, 715f, 515f, 55f, PANEL_2)
            val periodSummary = listOfNotNull(
                weightDelta?.let { "Peso ${formatSigned(it)} kg" },
                waistDelta?.let { "Vita ${formatSigned(it)} cm" },
            ).joinToString("  |  ").ifBlank { "Dati insufficienti per calcolare i delta del periodo" }
            text(canvas, periodSummary, 58f, 748f, 10.5f, TEXT, true)
            drawFooter(canvas, pageNumber, "Report profilo")
            document.finishPage(page)
            pageNumber++
        }

        // 4. Alimentazione / allenamento
        run {
            val page = startPage(document, pageNumber)
            val canvas = page.canvas
            drawHeader(canvas, "Alimentazione e allenamento", "Sintesi operativa del periodo")

            drawCard(canvas, 40f, 150f, 515f, 210f)
            text(canvas, "Piano alimentare attivo", 58f, 182f, 15f, TEXT, true)
            drawRows(canvas, 58f, 220f, 477f, listOf(
                "Target energia" to input.latestPlanTargetKcal?.let { "$it kcal" }.orDash(),
                "Proteine" to input.latestPlanTargetProteinG?.let(::formatGram).orDash(),
                "Carboidrati" to input.latestPlanTargetCarbsG?.let(::formatGram).orDash(),
                "Grassi" to input.latestPlanTargetFatG?.let(::formatGram).orDash(),
                "Settimane con piano" to input.planCount.toString(),
            ), rowHeight = 28f)

            drawMetricCard(canvas, 40f, 390f, 247f, 105f, "ALLENAMENTI / RIPOSI", input.workoutCount.toString(), "eventi registrati", ACCENT_2)
            drawMetricCard(canvas, 308f, 390f, 247f, 105f, "SGARRI REGISTRATI", input.cheatCount.toString(), "eventi nel periodo", WARNING)
            drawMetricCard(canvas, 40f, 520f, 247f, 105f, "RILEVAZIONI BIA", input.biaCount.toString(), "storico profilo", ACCENT)
            drawMetricCard(canvas, 308f, 520f, 247f, 105f, "MISURE CORPOREE", input.bodyCount.toString(), "storico profilo", ACCENT)

            drawCard(canvas, 40f, 650f, 515f, 100f, PANEL_2)
            text(canvas, "Interpretazione", 58f, 680f, 12f, TEXT, true)
            paragraph(
                canvas,
                "Il PDF riassume dati e risultati gia presenti nell'app. Le decisioni numeriche restano prodotte dal motore locale e validate dall'app; il JSON conserva il dettaglio completo per analisi esterne.",
                58f,
                705f,
                476f,
                9.5f,
                MUTED,
                14f,
            )
            drawFooter(canvas, pageNumber, "Report profilo")
            document.finishPage(page)
            pageNumber++
        }

        // 5. Contenuto export / metodologia
        run {
            val page = startPage(document, pageNumber)
            val canvas = page.canvas
            drawHeader(canvas, "Contenuto dell'export", "Struttura del report MyFitAI")
            drawCard(canvas, 40f, 150f, 515f, 500f)
            val sections = listOf(
                "1. Profilo" to "dati del profilo, obiettivo, attivita, peso iniziale e corrente",
                "2. Snapshot" to "BMI, BMR, TDEE, target nutrizionali e metodo di calcolo",
                "3. BIA" to "ultima rilevazione e storico disponibile",
                "4. Misure" to "circonferenze, delta, asimmetrie e proporzioni",
                "5. Trend" to "grafici peso e vita basati sulle rilevazioni persistite",
                "6. Alimentazione" to "target del piano corrente e numero di settimane disponibili",
                "7. Allenamenti" to "conteggio sessioni e riposi registrati",
                "8. Sgarri" to "conteggio degli eventi registrati",
                "9. Weekly Review" to "review settimanali persistite (${input.reviewCount})",
                "10. Metodologia" to "dati mancanti restano mancanti; nessuna stima viene inventata dal PDF",
            )
            var y = 188f
            sections.forEach { (title, description) ->
                text(canvas, title, 58f, y, 11f, TEXT, true)
                paragraph(canvas, description, 58f, y + 20f, 455f, 9f, MUTED, 13f)
                y += 45f
            }
            drawCard(canvas, 40f, 680f, 515f, 72f, PANEL_2)
            text(canvas, "JSON = export completo per analisi / ChatGPT", 58f, 709f, 10.5f, ACCENT, true)
            text(canvas, "PDF = report umano, leggibile e condivisibile", 58f, 735f, 10.5f, TEXT, true)
            drawFooter(canvas, pageNumber, "Report profilo")
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
        val days = snapshot.version.days.sortedBy { it.dateEpochDay }

        // Copertina settimanale
        run {
            val page = startPage(document, pageNumber)
            val canvas = page.canvas
            drawHeader(canvas, "Piano alimentare settimanale", "$dateRange | $profileName")
            drawCard(canvas, 40f, 150f, 515f, 120f)
            text(canvas, "Target giornaliero", 58f, 180f, 11f, MUTED, true)
            text(canvas, snapshot.version.targetKcal?.let { "$it kcal" }.orDash(), 58f, 222f, 28f, TEXT, true)
            val macros = listOf(
                Triple("Proteine", snapshot.version.targetProteinG?.let(::formatGram).orDash(), ACCENT),
                Triple("Carbo", snapshot.version.targetCarbsG?.let(::formatGram).orDash(), ACCENT_2),
                Triple("Grassi", snapshot.version.targetFatG?.let(::formatGram).orDash(), WARNING),
            )
            macros.forEachIndexed { index, item ->
                val x = 300f + index * 83f
                text(canvas, item.first.uppercase(Locale.ITALIAN), x, 190f, 8f, MUTED, true)
                text(canvas, item.second, x, 218f, 11f, item.third, true)
            }

            drawCard(canvas, 40f, 295f, 515f, 130f, PANEL_2)
            text(canvas, "Come leggere il piano", 58f, 326f, 13f, TEXT, true)
            paragraph(
                canvas,
                "Ogni giornata riporta orario, tipo di pasto, piatto, ingredienti e dosi, calorie e macro. La lista della spesa finale viene aggregata deterministicamente dagli ingredienti della stessa versione del piano.",
                58f,
                352f,
                475f,
                10f,
                MUTED,
                15f,
            )
            text(canvas, "Versione piano: ${snapshot.version.versionNumber}", 58f, 404f, 9f, MUTED)

            drawCard(canvas, 40f, 450f, 515f, 275f)
            text(canvas, "Settimana", 58f, 482f, 14f, TEXT, true)
            var y = 515f
            days.forEach { day ->
                val date = LocalDate.ofEpochDay(day.dateEpochDay)
                val label = date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN))
                    .replaceFirstChar { it.titlecase(Locale.ITALIAN) }
                text(canvas, label, 58f, y, 10.5f, TEXT, true)
                text(canvas, "${day.meals.size} pasti", 535f, y, 9f, MUTED, alignRight = true)
                y += 30f
            }
            drawFooter(canvas, pageNumber, "Dieta settimanale")
            document.finishPage(page)
            pageNumber++
        }

        // Due giorni per pagina quando possibile, mantenendo blocchi leggibili.
        var index = 0
        while (index < days.size) {
            val page = startPage(document, pageNumber)
            val canvas = page.canvas
            val first = days[index]
            val second = days.getOrNull(index + 1)
            drawHeader(canvas, "Dieta settimanale", dateRange)

            val firstBottom = drawCompactDayCard(canvas, first, 150f, 295f)
            var consumedSecond = false
            if (second != null && firstBottom <= 455f) {
                val secondBottom = drawCompactDayCard(canvas, second, max(firstBottom + 18f, 445f), 295f)
                consumedSecond = secondBottom <= 785f
            }
            drawFooter(canvas, pageNumber, "Dieta settimanale")
            document.finishPage(page)
            pageNumber++
            index += if (consumedSecond) 2 else 1
        }

        // Lista spesa: categorie e checkbox, con paginazione automatica.
        val groups = shoppingItems.groupBy { it.category }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
        var page = startPage(document, pageNumber)
        var canvas = page.canvas
        drawHeader(canvas, "Lista della spesa", "Aggregata dal piano settimanale")
        var column = 0
        val columnX = floatArrayOf(40f, 307f)
        val columnWidth = 248f
        val columnBottom = floatArrayOf(150f, 150f)

        fun finishShoppingPage() {
            drawFooter(canvas, pageNumber, "Dieta settimanale")
            document.finishPage(page)
            pageNumber++
            page = startPage(document, pageNumber)
            canvas = page.canvas
            drawHeader(canvas, "Lista della spesa", "Continua")
            column = 0
            columnBottom[0] = 150f
            columnBottom[1] = 150f
        }

        groups.forEach { (category, items) ->
            val required = 48f + items.size * 25f
            if (columnBottom[column] + required > 775f) {
                column++
                if (column > 1) finishShoppingPage()
            }
            if (columnBottom[column] + required > 775f) finishShoppingPage()

            val x = columnX[column]
            val y = columnBottom[column]
            drawCard(canvas, x, y, columnWidth, required - 8f)
            text(canvas, category.uppercase(Locale.ITALIAN), x + 16f, y + 26f, 10.5f, ACCENT, true)
            var rowY = y + 52f
            items.forEach { item ->
                drawCheckbox(canvas, x + 16f, rowY - 10f)
                text(canvas, ellipsize(item.name, 150f, 9.5f), x + 42f, rowY, 9.5f, TEXT)
                text(canvas, item.displayQuantity(), x + columnWidth - 16f, rowY, 9.5f, TEXT, true, alignRight = true)
                rowY += 25f
            }
            columnBottom[column] += required + 12f
        }

        drawFooter(canvas, pageNumber, "Dieta settimanale")
        document.finishPage(page)

        FileOutputStream(file).use(document::writeTo)
        document.close()
    }

    private fun drawCompactDayCard(canvas: Canvas, day: FoodPlanDay, top: Float, maxHeight: Float): Float {
        val meals = day.meals.sortedBy(FoodMeal::sortOrder)
        val estimated = 58f + meals.sumOf { meal ->
            val ingredientText = meal.ingredients.sortedBy { it.sortOrder }.joinToString(" - ") { ingredient ->
                val dose = ingredient.displayDose?.takeIf { it.isNotBlank() }
                    ?: "${formatNumber(ingredient.quantity)} ${ingredient.unit}"
                "${ingredient.name} $dose"
            }
            40 + wrapLines(ingredientText, 410f, 8.5f, false).take(2).size * 12
        }.toFloat()
        val height = estimated.coerceAtMost(maxHeight)
        drawCard(canvas, 40f, top, 515f, height)

        val date = LocalDate.ofEpochDay(day.dateEpochDay)
        val dayName = date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN))
            .replaceFirstChar { it.titlecase(Locale.ITALIAN) }
        text(canvas, dayName, 58f, top + 28f, 13f, TEXT, true)
        text(canvas, day.totalKcal?.let { "$it kcal" }.orDash(), 535f, top + 28f, 9f, ACCENT, true, alignRight = true)

        var y = top + 56f
        meals.forEach { meal ->
            if (y > top + height - 28f) return@forEach
            val time = meal.timeMinutes?.let { String.format(Locale.ITALIAN, "%02d:%02d", it / 60, it % 60) }.orDash()
            text(canvas, time, 58f, y, 8.5f, ACCENT_2, true)
            text(canvas, meal.type.uppercase(Locale.ITALIAN), 108f, y, 7.8f, MUTED, true)
            text(canvas, ellipsize(meal.title, 205f, 9.2f, true), 182f, y, 9.2f, TEXT, true)
            text(canvas, meal.kcal?.let { "$it kcal" }.orDash(), 535f, y, 8.2f, ACCENT, alignRight = true)
            y += 15f

            val ingredientText = meal.ingredients.sortedBy { it.sortOrder }.joinToString(" - ") { ingredient ->
                val dose = ingredient.displayDose?.takeIf { it.isNotBlank() }
                    ?: "${formatNumber(ingredient.quantity)} ${ingredient.unit}"
                "${ingredient.name} $dose"
            }
            wrapLines(ingredientText, 405f, 8.2f, false).take(2).forEach { line ->
                text(canvas, line, 108f, y, 8.2f, MUTED)
                y += 11f
            }
            val macros = listOfNotNull(
                meal.proteinG?.let { "P ${formatNumber(it)} g" },
                meal.carbsG?.let { "C ${formatNumber(it)} g" },
                meal.fatG?.let { "F ${formatNumber(it)} g" },
            ).joinToString(" | ")
            if (macros.isNotBlank()) {
                text(canvas, macros, 108f, y, 7.7f, ACCENT)
                y += 14f
            }
            y += 7f
        }
        return top + height
    }

    private fun drawRows(
        canvas: Canvas,
        x: Float,
        startY: Float,
        width: Float,
        rows: List<Pair<String, String>>,
        rowHeight: Float = 26f,
    ) {
        var y = startY
        rows.forEach { (label, value) ->
            text(canvas, label, x, y, 10f, MUTED)
            text(canvas, value, x + width, y, 10f, TEXT, true, alignRight = true)
            y += rowHeight
        }
    }

    private fun drawLineChart(canvas: Canvas, x: Float, y: Float, width: Float, height: Float, points: List<Pair<Long, Float>>) {
        drawCard(canvas, x, y, width, height)
        if (points.size < 2) {
            text(canvas, "Dati insufficienti", x + 18f, y + 42f, 11f, MUTED)
            return
        }
        val sample = points.sortedBy { it.first }.takeLast(24)
        val values = sample.map { it.second }
        val minValue = values.minOrNull() ?: return
        val maxValue = values.maxOrNull() ?: return
        val range = max(0.5f, maxValue - minValue)
        val left = x + 38f
        val right = x + width - 24f
        val top = y + 28f
        val bottom = y + height - 38f
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT_2; strokeWidth = 3f; style = Paint.Style.STROKE }
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT_2; style = Paint.Style.FILL }
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LINE; strokeWidth = 1f }
        repeat(4) { index ->
            val gy = top + (bottom - top) * index / 3f
            canvas.drawLine(left, gy, right, gy, gridPaint)
        }
        val path = Path()
        sample.forEachIndexed { index, (_, value) ->
            val px = left + (right - left) * index / (sample.size - 1).toFloat()
            val py = bottom - (value - minValue) / range * (bottom - top)
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
            canvas.drawCircle(px, py, 3.5f, dotPaint)
        }
        canvas.drawPath(path, linePaint)
        text(canvas, String.format(Locale.ITALIAN, "%.1f", maxValue), left, top + 3f, 8f, MUTED)
        text(canvas, String.format(Locale.ITALIAN, "%.1f", minValue), left, bottom + 16f, 8f, MUTED)

        val firstDate = Instant.ofEpochMilli(sample.first().first).atZone(ZoneId.systemDefault()).toLocalDate()
        val lastDate = Instant.ofEpochMilli(sample.last().first).atZone(ZoneId.systemDefault()).toLocalDate()
        text(canvas, firstDate.format(DateTimeFormatter.ofPattern("dd/MM", Locale.ITALIAN)), left, y + height - 12f, 7.5f, MUTED)
        text(canvas, lastDate.format(DateTimeFormatter.ofPattern("dd/MM", Locale.ITALIAN)), right, y + height - 12f, 7.5f, MUTED, alignRight = true)
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
        canvas.drawLine(MARGIN, 135f, CONTENT_RIGHT, 135f, paint)
    }

    private fun drawFooter(canvas: Canvas, pageNumber: Int, documentLabel: String) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LINE; strokeWidth = 1f }
        canvas.drawLine(MARGIN, 805f, CONTENT_RIGHT, 805f, paint)
        text(canvas, "$documentLabel - MyFitAI", MARGIN, 826f, 8f, MUTED)
        text(canvas, "Pagina $pageNumber", CONTENT_RIGHT, 826f, 8f, MUTED, alignRight = true)
    }

    private fun drawCard(canvas: Canvas, x: Float, y: Float, width: Float, height: Float, color: Int = PANEL) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        canvas.drawRoundRect(RectF(x, y, x + width, y + height), 12f, 12f, paint)
    }

    private fun drawMetricCard(
        canvas: Canvas,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        label: String,
        value: String,
        subtitle: String?,
        valueColor: Int,
    ) {
        drawCard(canvas, x, y, width, height)
        text(canvas, label, x + 18f, y + 25f, 8.5f, MUTED, true)
        text(canvas, value, x + 18f, y + 57f, 20f, valueColor, true)
        subtitle?.let { text(canvas, it, x + 18f, y + height - 12f, 8.5f, valueColor, true) }
    }

    private fun drawCheckbox(canvas: Canvas, x: Float, y: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; style = Paint.Style.STROKE; strokeWidth = 1.4f }
        canvas.drawRoundRect(RectF(x, y, x + 12f, y + 12f), 2f, 2f, paint)
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

    private fun ellipsize(value: String, width: Float, size: Float, bold: Boolean = false): String {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        }
        if (paint.measureText(value) <= width) return value
        var result = value
        while (result.isNotEmpty() && paint.measureText("$result…") > width) result = result.dropLast(1)
        return if (result.isBlank()) "…" else "$result…"
    }

    private fun buildProfileSummary(input: ProfileReportInput): String {
        val parts = mutableListOf<String>()
        trendDelta(input.weightTrend)?.let { delta ->
            parts += if (abs(delta) < 0.05f) "Il peso e rimasto sostanzialmente stabile" else "Il peso e ${if (delta < 0f) "diminuito" else "aumentato"} di ${formatNumber(abs(delta))} kg"
        }
        trendDelta(input.waistTrend)?.let { delta ->
            parts += if (abs(delta) < 0.05f) "la vita e rimasta stabile" else "la vita e ${if (delta < 0f) "diminuita" else "aumentata"} di ${formatNumber(abs(delta))} cm"
        }
        if (parts.isEmpty()) return "Dati insufficienti per produrre una sintesi numerica del periodo."
        return parts.joinToString("; ") + "."
    }

    private fun trendDelta(points: List<Pair<Long, Float>>): Float? {
        val ordered = points.sortedBy { it.first }
        if (ordered.size < 2) return null
        return ordered.last().second - ordered.first().second
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
        BodyProportionEngine.BalanceStatus.NOTICEABLE_IMBALANCE -> DANGER
        BodyProportionEngine.BalanceStatus.INSUFFICIENT_DATA -> MUTED
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
    private fun formatSigned(value: Float): String = String.format(Locale.ITALIAN, if (value > 0f) "+%.1f" else "%.1f", value)
    private fun String?.orDash(): String = this?.takeIf { it.isNotBlank() } ?: "-"
}