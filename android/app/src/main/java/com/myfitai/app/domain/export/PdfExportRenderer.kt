package com.myfitai.app.domain.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.core.content.ContextCompat
import com.myfitai.app.R
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
internal class PdfExportRenderer(context: Context) {
    private data class Geometry(
        val cardCornerRadius: Float,
        val cardBorderStrokeWidth: Float,
        val chartLineStrokeWidth: Float,
        val chartGridStrokeWidth: Float,
        val chartPointRadius: Float,
        val pageRuleStrokeWidth: Float,
        val checkboxSize: Float,
        val checkboxCornerRadius: Float,
        val checkboxStrokeWidth: Float,
        val bodyLineHeightLarge: Float,
        val bodyLineHeightRegular: Float,
        val bodyLineHeightCompact: Float,
        val chartLeftInset: Float,
        val chartRightInset: Float,
        val chartTopInset: Float,
        val chartBottomInset: Float,
        val chartTopAxisLabelOffset: Float,
        val chartBottomAxisLabelOffset: Float,
        val chartDateLabelBottomOffset: Float,
        val chartSampleLimit: Int,
        val chartGridlineCount: Int,
        val chartMinRange: Float,
        val mealHeaderToTitleGap: Float,
        val mealTitleLineHeight: Float,
        val mealIngredientLineHeight: Float,
        val mealMacroLineHeight: Float,
        val mealBlockBottomGap: Float,
        val mealBlockLeading: Float,
        val mealDayCardHeaderHeight: Float,
        val mealDayCardHeaderBaseline: Float,
        val mealDayCardContentStart: Float,
        val shoppingCategoryHeaderHeight: Float,
        val shoppingCategoryHeaderBottomGap: Float,
        val shoppingItemMinHeight: Float,
        val shoppingItemTextLineHeight: Float,
        val shoppingItemVerticalPadding: Float,
        val shoppingRowGap: Float,
        val shoppingCategoryBottomPadding: Float,
        val shoppingColumnCount: Int,
        val shoppingColumnLeftX: Float,
        val shoppingColumnRightX: Float,
        val shoppingColumnWidth: Float,
        val shoppingColumnStartY: Float,
        val shoppingColumnBottomLimit: Float,
        val shoppingCategoryHorizontalInset: Float,
        val shoppingCategoryTitleBaseline: Float,
        val shoppingCheckboxVerticalOffset: Float,
        val shoppingItemTextHorizontalInset: Float,
        val shoppingQuantityHorizontalInset: Float,
        val metricCardContentHorizontalInset: Float,
        val metricCardLabelBaseline: Float,
        val metricCardValueBaseline: Float,
        val metricCardSubtitleBottomInset: Float,
        val reportDataRowHeight: Float,
        val reportPlanRowHeight: Float,
        val reportDefaultRowHeight: Float,
        val pageContentInset: Float,
        val profileSnapshotColumnGap: Float,
        val profileSnapshotFirstRowY: Float,
        val profileSnapshotRowGap: Float,
        val profileSnapshotValueBaselineOffset: Float,
        val headerBrandBaseline: Float,
        val headerTitleBaseline: Float,
        val headerSubtitleBaseline: Float,
        val headerRuleY: Float,
        val footerRuleY: Float,
        val footerTextBaseline: Float,
    )

    private data class Typography(
        val pageTitle: Float,
        val heroValue: Float,
        val profileName: Float,
        val section: Float,
        val sectionSecondary: Float,
        val bodyLarge: Float,
        val body: Float,
        val bodyCompact: Float,
        val summary: Float,
        val caption: Float,
        val captionCompact: Float,
        val label: Float,
        val labelCompact: Float,
        val micro: Float,
        val metricSnapshotLabel: Float,
        val metricSnapshotValue: Float,
        val proportionStatus: Float,
        val trendAxis: Float,
        val trendDate: Float,
        val mealType: Float,
        val mealKcal: Float,
        val mealTitle: Float,
        val mealIngredient: Float,
        val mealMacro: Float,
        val metricCardLabel: Float,
        val metricCardValue: Float,
        val metricCardSubtitle: Float,
    )

    private val geometry = Geometry(
        cardCornerRadius = context.resources.getInteger(R.integer.pdf_card_corner_radius_page_units).toFloat(),
        cardBorderStrokeWidth = context.resources.getInteger(R.integer.pdf_card_border_stroke_page_units).toFloat(),
        chartLineStrokeWidth = context.resources.getInteger(R.integer.pdf_chart_line_stroke_page_units).toFloat(),
        chartGridStrokeWidth = context.resources.getInteger(R.integer.pdf_chart_grid_stroke_page_units).toFloat(),
        chartPointRadius = context.resources.getInteger(R.integer.pdf_chart_point_radius_half_page_units) / 2f,
        pageRuleStrokeWidth = context.resources.getInteger(R.integer.pdf_page_rule_stroke_page_units).toFloat(),
        checkboxSize = context.resources.getInteger(R.integer.pdf_checkbox_size_page_units).toFloat(),
        checkboxCornerRadius = context.resources.getInteger(R.integer.pdf_checkbox_corner_radius_page_units).toFloat(),
        checkboxStrokeWidth = context.resources.getInteger(R.integer.pdf_checkbox_stroke_tenths_page_units) / 10f,
        bodyLineHeightLarge = context.resources.getInteger(R.integer.pdf_body_line_height_large_page_units).toFloat(),
        bodyLineHeightRegular = context.resources.getInteger(R.integer.pdf_body_line_height_regular_page_units).toFloat(),
        bodyLineHeightCompact = context.resources.getInteger(R.integer.pdf_body_line_height_compact_page_units).toFloat(),
        chartLeftInset = context.resources.getInteger(R.integer.pdf_chart_left_inset_page_units).toFloat(),
        chartRightInset = context.resources.getInteger(R.integer.pdf_chart_right_inset_page_units).toFloat(),
        chartTopInset = context.resources.getInteger(R.integer.pdf_chart_top_inset_page_units).toFloat(),
        chartBottomInset = context.resources.getInteger(R.integer.pdf_chart_bottom_inset_page_units).toFloat(),
        chartTopAxisLabelOffset = context.resources.getInteger(R.integer.pdf_chart_top_axis_label_offset_page_units).toFloat(),
        chartBottomAxisLabelOffset = context.resources.getInteger(R.integer.pdf_chart_bottom_axis_label_offset_page_units).toFloat(),
        chartDateLabelBottomOffset = context.resources.getInteger(R.integer.pdf_chart_date_label_bottom_offset_page_units).toFloat(),
        chartSampleLimit = context.resources.getInteger(R.integer.pdf_chart_sample_limit),
        chartGridlineCount = context.resources.getInteger(R.integer.pdf_chart_gridline_count),
        chartMinRange = context.resources.getInteger(R.integer.pdf_chart_min_range_tenths) / 10f,
        mealHeaderToTitleGap = context.resources.getInteger(R.integer.pdf_meal_header_to_title_gap_page_units).toFloat(),
        mealTitleLineHeight = context.resources.getInteger(R.integer.pdf_meal_title_line_height_page_units).toFloat(),
        mealIngredientLineHeight = context.resources.getInteger(R.integer.pdf_meal_ingredient_line_height_page_units).toFloat(),
        mealMacroLineHeight = context.resources.getInteger(R.integer.pdf_meal_macro_line_height_page_units).toFloat(),
        mealBlockBottomGap = context.resources.getInteger(R.integer.pdf_meal_block_bottom_gap_page_units).toFloat(),
        mealBlockLeading = context.resources.getInteger(R.integer.pdf_meal_block_leading_page_units).toFloat(),
        mealDayCardHeaderHeight = context.resources.getInteger(R.integer.pdf_meal_day_card_header_height_page_units).toFloat(),
        mealDayCardHeaderBaseline = context.resources.getInteger(R.integer.pdf_meal_day_card_header_baseline_page_units).toFloat(),
        mealDayCardContentStart = context.resources.getInteger(R.integer.pdf_meal_day_card_content_start_page_units).toFloat(),
        shoppingCategoryHeaderHeight = context.resources.getInteger(R.integer.pdf_shopping_category_header_height_page_units).toFloat(),
        shoppingCategoryHeaderBottomGap = context.resources.getInteger(R.integer.pdf_shopping_category_header_bottom_gap_page_units).toFloat(),
        shoppingItemMinHeight = context.resources.getInteger(R.integer.pdf_shopping_item_min_height_page_units).toFloat(),
        shoppingItemTextLineHeight = context.resources.getInteger(R.integer.pdf_shopping_item_text_line_height_page_units).toFloat(),
        shoppingItemVerticalPadding = context.resources.getInteger(R.integer.pdf_shopping_item_vertical_padding_page_units).toFloat(),
        shoppingRowGap = context.resources.getInteger(R.integer.pdf_shopping_row_gap_page_units).toFloat(),
        shoppingCategoryBottomPadding = context.resources.getInteger(R.integer.pdf_shopping_category_bottom_padding_page_units).toFloat(),
        shoppingColumnCount = context.resources.getInteger(R.integer.pdf_shopping_column_count),
        shoppingColumnLeftX = context.resources.getInteger(R.integer.pdf_shopping_column_left_x_page_units).toFloat(),
        shoppingColumnRightX = context.resources.getInteger(R.integer.pdf_shopping_column_right_x_page_units).toFloat(),
        shoppingColumnWidth = context.resources.getInteger(R.integer.pdf_shopping_column_width_page_units).toFloat(),
        shoppingColumnStartY = context.resources.getInteger(R.integer.pdf_shopping_column_start_y_page_units).toFloat(),
        shoppingColumnBottomLimit = context.resources.getInteger(R.integer.pdf_shopping_column_bottom_limit_page_units).toFloat(),
        shoppingCategoryHorizontalInset = context.resources.getInteger(R.integer.pdf_shopping_category_horizontal_inset_page_units).toFloat(),
        shoppingCategoryTitleBaseline = context.resources.getInteger(R.integer.pdf_shopping_category_title_baseline_page_units).toFloat(),
        shoppingCheckboxVerticalOffset = context.resources.getInteger(R.integer.pdf_shopping_checkbox_vertical_offset_page_units).toFloat(),
        shoppingItemTextHorizontalInset = context.resources.getInteger(R.integer.pdf_shopping_item_text_horizontal_inset_page_units).toFloat(),
        shoppingQuantityHorizontalInset = context.resources.getInteger(R.integer.pdf_shopping_quantity_horizontal_inset_page_units).toFloat(),
        metricCardContentHorizontalInset = context.resources.getInteger(R.integer.pdf_metric_card_content_horizontal_inset_page_units).toFloat(),
        metricCardLabelBaseline = context.resources.getInteger(R.integer.pdf_metric_card_label_baseline_page_units).toFloat(),
        metricCardValueBaseline = context.resources.getInteger(R.integer.pdf_metric_card_value_baseline_page_units).toFloat(),
        metricCardSubtitleBottomInset = context.resources.getInteger(R.integer.pdf_metric_card_subtitle_bottom_inset_page_units).toFloat(),
        reportDataRowHeight = context.resources.getInteger(R.integer.pdf_report_data_row_height_page_units).toFloat(),
        reportPlanRowHeight = context.resources.getInteger(R.integer.pdf_report_plan_row_height_page_units).toFloat(),
        reportDefaultRowHeight = context.resources.getInteger(R.integer.pdf_report_default_row_height_page_units).toFloat(),
        pageContentInset = context.resources.getInteger(R.integer.pdf_page_content_inset_page_units).toFloat(),
        profileSnapshotColumnGap = context.resources.getInteger(R.integer.pdf_profile_snapshot_column_gap_page_units).toFloat(),
        profileSnapshotFirstRowY = context.resources.getInteger(R.integer.pdf_profile_snapshot_first_row_y_page_units).toFloat(),
        profileSnapshotRowGap = context.resources.getInteger(R.integer.pdf_profile_snapshot_row_gap_page_units).toFloat(),
        profileSnapshotValueBaselineOffset = context.resources.getInteger(R.integer.pdf_profile_snapshot_value_baseline_offset_page_units).toFloat(),
        headerBrandBaseline = context.resources.getInteger(R.integer.pdf_header_brand_baseline_page_units).toFloat(),
        headerTitleBaseline = context.resources.getInteger(R.integer.pdf_header_title_baseline_page_units).toFloat(),
        headerSubtitleBaseline = context.resources.getInteger(R.integer.pdf_header_subtitle_baseline_page_units).toFloat(),
        headerRuleY = context.resources.getInteger(R.integer.pdf_header_rule_y_page_units).toFloat(),
        footerRuleY = context.resources.getInteger(R.integer.pdf_footer_rule_y_page_units).toFloat(),
        footerTextBaseline = context.resources.getInteger(R.integer.pdf_footer_text_baseline_page_units).toFloat(),
    )
    private val pageWidth = context.resources.getInteger(R.integer.pdf_page_width_page_units)
    private val pageHeight = context.resources.getInteger(R.integer.pdf_page_height_page_units)
    private val pageMargin = context.resources.getInteger(R.integer.pdf_page_margin_page_units).toFloat()
    private val contentLeft = pageMargin + geometry.pageContentInset
    private val contentWidth = pageWidth - 2f * pageMargin
    private val contentRight = pageMargin + contentWidth

    private val palette = Palette(
        background = ContextCompat.getColor(context, R.color.bg_primary),
        panel = ContextCompat.getColor(context, R.color.white),
        panelSecondary = ContextCompat.getColor(context, R.color.surface_secondary),
        text = ContextCompat.getColor(context, R.color.text_primary),
        muted = ContextCompat.getColor(context, R.color.text_secondary),
        accent = ContextCompat.getColor(context, R.color.accent_green),
        accentSecondary = ContextCompat.getColor(context, R.color.accent_blue),
        warning = ContextCompat.getColor(context, R.color.semantic_warning),
        danger = ContextCompat.getColor(context, R.color.semantic_error),
        line = ContextCompat.getColor(context, R.color.divider),
    )
    private val typography = Typography(
        pageTitle = pdfTextSize(context, R.integer.pdf_type_page_title_tenths),
        heroValue = pdfTextSize(context, R.integer.pdf_type_hero_value_tenths),
        profileName = pdfTextSize(context, R.integer.pdf_type_profile_name_tenths),
        section = pdfTextSize(context, R.integer.pdf_type_section_tenths),
        sectionSecondary = pdfTextSize(context, R.integer.pdf_type_section_secondary_tenths),
        bodyLarge = pdfTextSize(context, R.integer.pdf_type_body_large_tenths),
        body = pdfTextSize(context, R.integer.pdf_type_body_tenths),
        bodyCompact = pdfTextSize(context, R.integer.pdf_type_body_compact_tenths),
        summary = pdfTextSize(context, R.integer.pdf_type_summary_tenths),
        caption = pdfTextSize(context, R.integer.pdf_type_caption_tenths),
        captionCompact = pdfTextSize(context, R.integer.pdf_type_caption_compact_tenths),
        label = pdfTextSize(context, R.integer.pdf_type_label_tenths),
        labelCompact = pdfTextSize(context, R.integer.pdf_type_label_compact_tenths),
        micro = pdfTextSize(context, R.integer.pdf_type_micro_tenths),
        metricSnapshotLabel = pdfTextSize(context, R.integer.pdf_type_metric_snapshot_label_tenths),
        metricSnapshotValue = pdfTextSize(context, R.integer.pdf_type_metric_snapshot_value_tenths),
        proportionStatus = pdfTextSize(context, R.integer.pdf_type_proportion_status_tenths),
        trendAxis = pdfTextSize(context, R.integer.pdf_type_trend_axis_tenths),
        trendDate = pdfTextSize(context, R.integer.pdf_type_trend_date_tenths),
        mealType = pdfTextSize(context, R.integer.pdf_type_meal_type_tenths),
        mealKcal = pdfTextSize(context, R.integer.pdf_type_meal_kcal_tenths),
        mealTitle = pdfTextSize(context, R.integer.pdf_type_meal_title_tenths),
        mealIngredient = pdfTextSize(context, R.integer.pdf_type_meal_ingredient_tenths),
        mealMacro = pdfTextSize(context, R.integer.pdf_type_meal_macro_tenths),
        metricCardLabel = pdfTextSize(context, R.integer.pdf_type_metric_card_label_tenths),
        metricCardValue = pdfTextSize(context, R.integer.pdf_type_metric_card_value_tenths),
        metricCardSubtitle = pdfTextSize(context, R.integer.pdf_type_metric_card_subtitle_tenths),
    )

    private fun pdfTextSize(context: Context, resourceId: Int): Float =
        context.resources.getInteger(resourceId) / 10f

    private data class Palette(
        val background: Int,
        val panel: Int,
        val panelSecondary: Int,
        val text: Int,
        val muted: Int,
        val accent: Int,
        val accentSecondary: Int,
        val warning: Int,
        val danger: Int,
        val line: Int,
    )

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
        val weightDelta = trendDelta(input.weightTrend)
        val waistDelta = trendDelta(input.waistTrend)

        // 1. Riepilogo profilo
        run {
            val page = startPage(document, pageNumber)
            val canvas = page.canvas
            drawHeader(canvas, "Riepilogo profilo", "Report del profilo attivo")

            drawCard(canvas, pageMargin, 150f, contentWidth, 78f)
            text(canvas, input.profile.name, contentLeft, 183f, typography.profileName, palette.text, true)
            text(canvas, "Aggiornato al ${formatDate(LocalDate.now())}", contentLeft, 208f, typography.captionCompact, palette.muted)
            text(canvas, input.profile.goal?.uppercase(Locale.ITALIAN) ?: "OBIETTIVO NON IMPOSTATO", 537f, 183f, typography.captionCompact, palette.accent, true, alignRight = true)

            drawMetricCard(canvas, pageMargin, 248f, 247f, 90f, "PESO", formatKg(input.latestBia?.weightKg ?: input.profile.currentWeightKg), weightDelta?.let { "${formatSigned(it)} kg nel periodo" }, palette.accent)
            drawMetricCard(canvas, 308f, 248f, 247f, 90f, "MASSA GRASSA", formatPercent(input.latestBia?.bodyFatPercent), null, palette.accent)
            drawMetricCard(canvas, pageMargin, 358f, 247f, 90f, "MASSA MUSCOLARE", formatKg(input.latestBia?.muscleMassKg), null, palette.accentSecondary)
            drawMetricCard(canvas, 308f, 358f, 247f, 90f, "VITA", formatCm(input.latestBody?.waistCm), waistDelta?.let { "${formatSigned(it)} cm nel periodo" }, palette.accent)

            drawCard(canvas, pageMargin, 475f, contentWidth, 155f)
            text(canvas, "Snapshot attuale", contentLeft, 505f, typography.section, palette.text, true)
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
                val x = contentLeft + col * geometry.profileSnapshotColumnGap
                val y = geometry.profileSnapshotFirstRowY + row * geometry.profileSnapshotRowGap
                text(canvas, label.uppercase(Locale.ITALIAN), x, y, typography.metricSnapshotLabel, palette.muted, true)
                text(canvas, value, x, y + geometry.profileSnapshotValueBaselineOffset, typography.metricSnapshotValue, palette.text, true)
            }

            drawCard(canvas, pageMargin, 655f, contentWidth, 98f, palette.panelSecondary)
            text(canvas, "Lettura sintetica", contentLeft, 684f, typography.bodyLarge, palette.text, true)
            val summary = buildProfileSummary(input)
            paragraph(canvas, summary, contentLeft, 709f, 476f, typography.caption, palette.muted, geometry.bodyLineHeightLarge)
            drawFooter(canvas, pageNumber, "Report profilo")
            document.finishPage(page)
            pageNumber++
        }

        // 2. BIA / misure / proporzioni
        run {
            val page = startPage(document, pageNumber)
            val canvas = page.canvas
            drawHeader(canvas, "Composizione e misure", "BIA, circonferenze e proporzioni corporee")

            drawCard(canvas, pageMargin, 150f, 247f, 225f)
            text(canvas, "BIA - ultima rilevazione", contentLeft, 180f, typography.sectionSecondary, palette.text, true)
            drawRows(canvas, contentLeft, 215f, 211f, listOf(
                "Peso" to formatKg(input.latestBia?.weightKg),
                "Grasso" to formatPercent(input.latestBia?.bodyFatPercent),
                "Muscolo" to formatKg(input.latestBia?.muscleMassKg),
                "Acqua" to formatPercent(input.latestBia?.bodyWaterPercent),
                "Grasso viscerale" to format1(input.latestBia?.visceralFatLevel?.toDouble()),
                "BMR BIA" to input.latestBia?.bmrKcal?.let { "${it.toInt()} kcal" }.orDash(),
            ), rowHeight = geometry.reportDataRowHeight)

            drawCard(canvas, pageMargin, 150f, contentWidth, 225f)
            text(canvas, "Misure corporee", contentLeft, 180f, typography.sectionSecondary, palette.text, true)
            drawRows(canvas, contentLeft, 215f, 211f, listOf(
                "Torace" to formatCm(input.latestBody?.chestCm),
                "Vita" to formatCm(input.latestBody?.waistCm),
                "Addome" to formatCm(input.latestBody?.abdomenCm),
                "Spalle" to formatCm(input.latestBody?.shouldersCm),
                "Glutei" to formatCm(input.latestBody?.glutesCm),
            ), rowHeight = geometry.reportDataRowHeight)
            drawRows(canvas, 326f, 215f, 211f, listOf(
                "Fianchi" to formatCm(input.latestBody?.hipsCm),
                "Braccio sx/dx" to pairCm(input.latestBody?.armLeftCm, input.latestBody?.armRightCm),
                "Coscia sx/dx" to pairCm(input.latestBody?.thighLeftCm, input.latestBody?.thighRightCm),
                "Polpaccio sx/dx" to pairCm(input.latestBody?.calfLeftCm, input.latestBody?.calfRightCm),
                "Peso rilevazione" to formatKg(input.latestBody?.weightKg),
            ), rowHeight = geometry.reportDataRowHeight)

            drawCard(canvas, pageMargin, 400f, contentWidth, 235f)
            text(canvas, "Proporzioni corporee", contentLeft, 432f, typography.section, palette.text, true)
            text(canvas, balanceLabel(input.proportions.status), 535f, 432f, typography.proportionStatus, statusColor(input.proportions.status), true, alignRight = true)

            val proportionRows = buildList {
                input.proportions.ratios.take(4).forEach { ratio ->
                    add(ratio.label to String.format(Locale.ITALIAN, "%.2f", ratio.value))
                }
                input.proportions.asymmetries.take(3).forEach { asymmetry ->
                    add("${asymmetry.label} dx/sx" to String.format(Locale.ITALIAN, "%.1f%%", asymmetry.percent))
                }
            }
            drawRows(canvas, contentLeft, 468f, 477f, proportionRows, rowHeight = geometry.reportDataRowHeight)

            drawCard(canvas, pageMargin, 660f, contentWidth, 92f, palette.panelSecondary)
            text(canvas, "Nota metodologica", contentLeft, 688f, typography.body, palette.text, true)
            paragraph(canvas, input.proportions.note, contentLeft, 712f, 476f, typography.captionCompact, palette.muted, geometry.bodyLineHeightRegular)
            drawFooter(canvas, pageNumber, "Report profilo")
            document.finishPage(page)
            pageNumber++
        }

        // 3. Trend
        run {
            val page = startPage(document, pageNumber)
            val canvas = page.canvas
            drawHeader(canvas, "Trend", "Andamento reale nel periodo disponibile")
            text(canvas, "Peso (kg)", pageMargin, 160f, typography.bodyLarge, palette.text, true)
            drawLineChart(canvas, pageMargin, 180f, contentWidth, 220f, input.weightTrend)
            text(canvas, "Vita (cm)", pageMargin, 450f, typography.bodyLarge, palette.text, true)
            drawLineChart(canvas, pageMargin, 470f, contentWidth, 220f, input.waistTrend)

            drawCard(canvas, pageMargin, 715f, contentWidth, 55f, palette.panelSecondary)
            val periodSummary = listOfNotNull(
                weightDelta?.let { "Peso ${formatSigned(it)} kg" },
                waistDelta?.let { "Vita ${formatSigned(it)} cm" },
            ).joinToString("  |  ").ifBlank { "Dati insufficienti per calcolare i delta del periodo" }
            text(canvas, periodSummary, contentLeft, 748f, typography.summary, palette.text, true)
            drawFooter(canvas, pageNumber, "Report profilo")
            document.finishPage(page)
            pageNumber++
        }

        // 4. Alimentazione / allenamento
        run {
            val page = startPage(document, pageNumber)
            val canvas = page.canvas
            drawHeader(canvas, "Alimentazione e allenamento", "Sintesi operativa del periodo")

            drawCard(canvas, pageMargin, 150f, contentWidth, 210f)
            text(canvas, "Piano alimentare attivo", contentLeft, 182f, typography.section, palette.text, true)
            drawRows(canvas, contentLeft, 220f, 477f, listOf(
                "Target energia" to input.latestPlanTargetKcal?.let { "$it kcal" }.orDash(),
                "Proteine" to input.latestPlanTargetProteinG?.let(::formatGram).orDash(),
                "Carboidrati" to input.latestPlanTargetCarbsG?.let(::formatGram).orDash(),
                "Grassi" to input.latestPlanTargetFatG?.let(::formatGram).orDash(),
                "Settimane con piano" to input.planCount.toString(),
            ), rowHeight = geometry.reportPlanRowHeight)

            drawMetricCard(canvas, pageMargin, 390f, 247f, 105f, "ALLENAMENTI / RIPOSI", input.workoutCount.toString(), "eventi registrati", palette.accentSecondary)
            drawMetricCard(canvas, 308f, 390f, 247f, 105f, "SGARRI REGISTRATI", input.cheatCount.toString(), "eventi nel periodo", palette.warning)
            drawMetricCard(canvas, pageMargin, 520f, 247f, 105f, "RILEVAZIONI BIA", input.biaCount.toString(), "storico profilo", palette.accent)
            drawMetricCard(canvas, 308f, 520f, 247f, 105f, "MISURE CORPOREE", input.bodyCount.toString(), "storico profilo", palette.accent)

            drawCard(canvas, pageMargin, 650f, contentWidth, 100f, palette.panelSecondary)
            text(canvas, "Interpretazione", contentLeft, 680f, typography.body, palette.text, true)
            paragraph(
                canvas,
                "Il PDF riassume dati e risultati gia presenti nell'app. Le decisioni numeriche restano prodotte dal motore locale e validate dall'app; il JSON conserva il dettaglio completo per analisi esterne.",
                contentLeft,
                705f,
                476f,
                typography.captionCompact,
                palette.muted,
                geometry.bodyLineHeightRegular,
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
            drawCard(canvas, pageMargin, 150f, contentWidth, 500f)
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
                text(canvas, title, contentLeft, y, typography.bodyCompact, palette.text, true)
                paragraph(canvas, description, contentLeft, y + 20f, 455f, typography.label, palette.muted, geometry.bodyLineHeightCompact)
                y += 45f
            }
            drawCard(canvas, pageMargin, 680f, contentWidth, 72f, palette.panelSecondary)
            text(canvas, "JSON = export completo per analisi / ChatGPT", contentLeft, 709f, typography.summary, palette.accent, true)
            text(canvas, "PDF = report umano, leggibile e condivisibile", contentLeft, 735f, typography.summary, palette.text, true)
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
        val weekStart = LocalDate.ofEpochDay(snapshot.version.days.minOfOrNull { it.dateEpochDay } ?: snapshot.weekStartEpochDay)
        val weekEnd = LocalDate.ofEpochDay(snapshot.version.days.maxOfOrNull { it.dateEpochDay } ?: snapshot.weekStartEpochDay)
        val dateRange = "${formatDate(weekStart)} - ${formatDate(weekEnd)}"
        val days = snapshot.version.days.sortedBy { it.dateEpochDay }
        val periodLabel = if (days.size > 7) "Piano alimentare per periodo" else "Piano alimentare settimanale"
        val shortLabel = if (days.size > 7) "Dieta periodo" else "Dieta settimanale"

        // Copertina settimanale
        run {
            val page = startPage(document, pageNumber)
            val canvas = page.canvas
            drawHeader(canvas, periodLabel, "$dateRange | $profileName")
            drawCard(canvas, pageMargin, 150f, contentWidth, 120f)
            text(canvas, "Target giornaliero", contentLeft, 180f, typography.bodyCompact, palette.muted, true)
            text(canvas, snapshot.version.targetKcal?.let { "$it kcal" }.orDash(), contentLeft, 222f, typography.heroValue, palette.text, true)
            val macros = listOf(
                Triple("Proteine", snapshot.version.targetProteinG?.let(::formatGram).orDash(), palette.accent),
                Triple("Carbo", snapshot.version.targetCarbsG?.let(::formatGram).orDash(), palette.accentSecondary),
                Triple("Grassi", snapshot.version.targetFatG?.let(::formatGram).orDash(), palette.warning),
            )
            macros.forEachIndexed { index, item ->
                val x = 300f + index * 83f
                text(canvas, item.first.uppercase(Locale.ITALIAN), x, 190f, typography.micro, palette.muted, true)
                text(canvas, item.second, x, 218f, typography.bodyCompact, item.third, true)
            }

            drawCard(canvas, pageMargin, 295f, contentWidth, 130f, palette.panelSecondary)
            text(canvas, "Come leggere il piano", contentLeft, 326f, typography.bodyLarge, palette.text, true)
            paragraph(
                canvas,
                "Ogni giornata riporta orario, tipo di pasto, piatto, ingredienti e dosi, calorie e macro. La lista della spesa finale viene aggregata deterministicamente dagli ingredienti della stessa versione del piano.",
                contentLeft,
                352f,
                475f,
                typography.caption,
                palette.muted,
                geometry.bodyLineHeightLarge,
            )
            text(canvas, "Versione piano: ${snapshot.version.versionNumber}", contentLeft, 404f, typography.label, palette.muted)

            drawCard(canvas, pageMargin, 450f, contentWidth, 275f)
            text(canvas, if (days.size > 7) "Periodo" else "Settimana", contentLeft, 482f, typography.sectionSecondary, palette.text, true)
            var y = 515f
            days.forEach { day ->
                val date = LocalDate.ofEpochDay(day.dateEpochDay)
                val label = date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN))
                    .replaceFirstChar { it.titlecase(Locale.ITALIAN) }
                text(canvas, label, contentLeft, y, typography.summary, palette.text, true)
                text(canvas, "${day.meals.size} pasti", 535f, y, typography.label, palette.muted, alignRight = true)
                y += 30f
            }
            drawFooter(canvas, pageNumber, shortLabel)
            document.finishPage(page)
            pageNumber++
        }

        // Una giornata per pagina, con pagine di continuazione quando il contenuto
        // non entra. Nessun pasto viene scartato per rispettare un'altezza fissa.
        days.forEach { day ->
            val mealPages = paginateMeals(day.meals.sortedBy(FoodMeal::sortOrder))
            mealPages.forEachIndexed { partIndex, meals ->
                val page = startPage(document, pageNumber)
                val canvas = page.canvas
                drawHeader(canvas, periodLabel, dateRange)
                drawDayCard(canvas, day, meals, 150f, partIndex > 0)
                drawFooter(canvas, pageNumber, shortLabel)
                document.finishPage(page)
                pageNumber++
            }
        }

        // Lista spesa: categorie e checkbox, con paginazione automatica.
        val groups = shoppingItems.groupBy { it.category }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
        var page = startPage(document, pageNumber)
        var canvas = page.canvas
        drawHeader(canvas, "Lista della spesa", "Aggregata dal piano")
        var column = 0
        val columnX = floatArrayOf(geometry.shoppingColumnLeftX, geometry.shoppingColumnRightX)
        val columnWidth = geometry.shoppingColumnWidth
        val columnBottom = FloatArray(geometry.shoppingColumnCount) { geometry.shoppingColumnStartY }

        fun finishShoppingPage() {
            drawFooter(canvas, pageNumber, shortLabel)
            document.finishPage(page)
            pageNumber++
            page = startPage(document, pageNumber)
            canvas = page.canvas
            drawHeader(canvas, "Lista della spesa", "Continua")
            column = 0
            columnBottom.indices.forEach { columnBottom[it] = geometry.shoppingColumnStartY }
        }

        groups.forEach { (category, items) ->
            val itemLines = items.associateWith { wrapLines(it.name, 135f, typography.captionCompact, false) }
            val required = geometry.shoppingCategoryHeaderHeight + items.sumOf { item ->
                max(
                    geometry.shoppingItemMinHeight.toInt(),
                    (itemLines.getValue(item).size * geometry.shoppingItemTextLineHeight + geometry.shoppingItemVerticalPadding).toInt(),
                )
            }
            if (columnBottom[column] + required > geometry.shoppingColumnBottomLimit) {
                column++
                if (column >= geometry.shoppingColumnCount) finishShoppingPage()
            }
            if (columnBottom[column] + required > geometry.shoppingColumnBottomLimit) finishShoppingPage()

            val x = columnX[column]
            val y = columnBottom[column]
            drawCard(canvas, x, y, columnWidth, required - geometry.shoppingCategoryBottomPadding)
            text(
                canvas,
                category.uppercase(Locale.ITALIAN),
                x + geometry.shoppingCategoryHorizontalInset,
                y + geometry.shoppingCategoryTitleBaseline,
                typography.summary,
                palette.accent,
                true,
            )
            var rowY = y + geometry.shoppingCategoryHeaderHeight + geometry.shoppingCategoryHeaderBottomGap
            items.forEach { item ->
                drawCheckbox(canvas, x + geometry.shoppingCategoryHorizontalInset, rowY - geometry.shoppingCheckboxVerticalOffset)
                itemLines.getValue(item).forEachIndexed { lineIndex, line ->
                    text(canvas, line, x + geometry.shoppingItemTextHorizontalInset, rowY + lineIndex * geometry.shoppingItemTextLineHeight, typography.captionCompact, palette.text)
                }
                text(canvas, item.displayQuantity(), x + columnWidth - geometry.shoppingQuantityHorizontalInset, rowY, typography.captionCompact, palette.text, true, alignRight = true)
                rowY += max(
                    geometry.shoppingItemMinHeight.toInt(),
                    (itemLines.getValue(item).size * geometry.shoppingItemTextLineHeight + geometry.shoppingItemVerticalPadding).toInt(),
                ).toFloat()
            }
            columnBottom[column] += required + geometry.shoppingRowGap
        }

            drawFooter(canvas, pageNumber, shortLabel)
        document.finishPage(page)

        FileOutputStream(file).use(document::writeTo)
        document.close()
    }

    internal fun paginateMeals(meals: List<FoodMeal>, availableHeight: Float = 555f): List<List<FoodMeal>> {
        if (meals.isEmpty()) return listOf(emptyList())
        val pages = mutableListOf<MutableList<FoodMeal>>()
        var current = mutableListOf<FoodMeal>()
        var used = 0f
        meals.forEach { meal ->
            val required = mealBlockHeight(meal)
            if (current.isNotEmpty() && used + required > availableHeight) {
                pages += current
                current = mutableListOf()
                used = 0f
            }
            current += meal
            used += required
        }
        if (current.isNotEmpty()) pages += current
        return pages
    }

    private fun drawDayCard(canvas: Canvas, day: FoodPlanDay, meals: List<FoodMeal>, top: Float, continuation: Boolean) {
        val height = geometry.mealDayCardHeaderHeight + meals.sumOfFloat(::mealBlockHeight)
        drawCard(canvas, pageMargin, top, contentWidth, height)
        val date = LocalDate.ofEpochDay(day.dateEpochDay)
        val dayName = date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN))
            .replaceFirstChar { it.titlecase(Locale.ITALIAN) }
        val headerBaseline = top + geometry.mealDayCardHeaderBaseline
        text(canvas, if (continuation) "$dayName - continua" else dayName, contentLeft, headerBaseline, typography.bodyLarge, palette.text, true)
        text(canvas, day.totalKcal?.let { "$it kcal" }.orDash(), 535f, headerBaseline, typography.label, palette.accent, true, alignRight = true)

        var y = top + geometry.mealDayCardContentStart
        meals.forEach { meal ->
            val time = meal.timeMinutes?.let { String.format(Locale.ITALIAN, "%02d:%02d", it / 60, it % 60) }.orDash()
            text(canvas, time, contentLeft, y, typography.labelCompact, palette.accentSecondary, true)
            text(canvas, meal.type.uppercase(Locale.ITALIAN), 108f, y, typography.mealType, palette.muted, true)
            text(canvas, meal.kcal?.let { "$it kcal" }.orDash(), 535f, y, typography.mealKcal, palette.accent, alignRight = true)
            y += geometry.mealHeaderToTitleGap

            wrapLines(meal.title, 427f, typography.mealTitle, true).forEach { line ->
                text(canvas, line, 108f, y, typography.mealTitle, palette.text, true)
                y += geometry.mealTitleLineHeight
            }

            wrapLines(ingredientText(meal), 427f, typography.mealIngredient, false).forEach { line ->
                text(canvas, line, 108f, y, typography.mealIngredient, palette.muted)
                y += geometry.mealIngredientLineHeight
            }
            val macros = listOfNotNull(
                meal.proteinG?.let { "P ${formatNumber(it)} g" },
                meal.carbsG?.let { "C ${formatNumber(it)} g" },
                meal.fatG?.let { "F ${formatNumber(it)} g" },
            ).joinToString(" | ")
            if (macros.isNotBlank()) {
                text(canvas, macros, 108f, y, typography.mealMacro, palette.accent)
                y += geometry.mealMacroLineHeight
            }
            y += geometry.mealBlockBottomGap
        }
    }

    private fun mealBlockHeight(meal: FoodMeal): Float {
        val titleLines = max(1, wrapLines(meal.title, 427f, typography.mealTitle, true).size)
        val ingredientLines = wrapLines(ingredientText(meal), 427f, typography.mealIngredient, false).size
        val hasMacros = meal.proteinG != null || meal.carbsG != null || meal.fatG != null
        return geometry.mealBlockLeading +
            titleLines * geometry.mealTitleLineHeight +
            ingredientLines * geometry.mealIngredientLineHeight +
            (if (hasMacros) geometry.mealMacroLineHeight else 0f) +
            geometry.mealBlockBottomGap
    }

    private fun ingredientText(meal: FoodMeal): String = meal.ingredients
        .sortedBy { it.sortOrder }
        .joinToString(" - ") { ingredient ->
            val dose = ingredient.displayDose?.takeIf { it.isNotBlank() }
                ?: "${formatNumber(ingredient.quantity)} ${ingredient.unit}"
            "${ingredient.name} $dose"
        }

    private inline fun <T> Iterable<T>.sumOfFloat(selector: (T) -> Float): Float = fold(0f) { sum, item ->
        sum + selector(item)
    }

    private fun drawRows(
        canvas: Canvas,
        x: Float,
        startY: Float,
        width: Float,
        rows: List<Pair<String, String>>,
        rowHeight: Float = geometry.reportDefaultRowHeight,
    ) {
        var y = startY
        rows.forEach { (label, value) ->
            text(canvas, label, x, y, typography.caption, palette.muted)
            text(canvas, value, x + width, y, typography.caption, palette.text, true, alignRight = true)
            y += rowHeight
        }
    }

    private fun drawLineChart(canvas: Canvas, x: Float, y: Float, width: Float, height: Float, points: List<Pair<Long, Float>>) {
        drawCard(canvas, x, y, width, height)
        if (points.size < 2) {
            text(canvas, "Dati insufficienti", x + 18f, y + 42f, typography.bodyCompact, palette.muted)
            return
        }
        val sample = points.sortedBy { it.first }.takeLast(geometry.chartSampleLimit)
        val values = sample.map { it.second }
        val minValue = values.minOrNull() ?: return
        val maxValue = values.maxOrNull() ?: return
        val range = max(geometry.chartMinRange, maxValue - minValue)
        val left = x + geometry.chartLeftInset
        val right = x + width - geometry.chartRightInset
        val top = y + geometry.chartTopInset
        val bottom = y + height - geometry.chartBottomInset
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accentSecondary; strokeWidth = geometry.chartLineStrokeWidth; style = Paint.Style.STROKE }
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accentSecondary; style = Paint.Style.FILL }
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.line; strokeWidth = geometry.chartGridStrokeWidth }
        val gridIntervals = (geometry.chartGridlineCount - 1).toFloat()
        repeat(geometry.chartGridlineCount) { index ->
            val gy = top + (bottom - top) * index / gridIntervals
            canvas.drawLine(left, gy, right, gy, gridPaint)
        }
        val path = Path()
        sample.forEachIndexed { index, (_, value) ->
            val px = left + (right - left) * index / (sample.size - 1).toFloat()
            val py = bottom - (value - minValue) / range * (bottom - top)
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
            canvas.drawCircle(px, py, geometry.chartPointRadius, dotPaint)
        }
        canvas.drawPath(path, linePaint)
        text(canvas, String.format(Locale.ITALIAN, "%.1f", maxValue), left, top + geometry.chartTopAxisLabelOffset, typography.trendAxis, palette.muted)
        text(canvas, String.format(Locale.ITALIAN, "%.1f", minValue), left, bottom + geometry.chartBottomAxisLabelOffset, typography.trendAxis, palette.muted)

        val firstDate = Instant.ofEpochMilli(sample.first().first).atZone(ZoneId.systemDefault()).toLocalDate()
        val lastDate = Instant.ofEpochMilli(sample.last().first).atZone(ZoneId.systemDefault()).toLocalDate()
        val dateLabelY = y + height - geometry.chartDateLabelBottomOffset
        text(canvas, firstDate.format(DateTimeFormatter.ofPattern("dd/MM", Locale.ITALIAN)), left, dateLabelY, typography.trendDate, palette.muted)
        text(canvas, lastDate.format(DateTimeFormatter.ofPattern("dd/MM", Locale.ITALIAN)), right, dateLabelY, typography.trendDate, palette.muted, alignRight = true)
    }

    private fun startPage(document: PdfDocument, pageNumber: Int): PdfDocument.Page {
        val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        page.canvas.drawColor(palette.background)
        return page
    }

    private fun drawHeader(canvas: Canvas, title: String, subtitle: String) {
        text(canvas, "MYFITAI", pageMargin, geometry.headerBrandBaseline, typography.body, palette.accent, true)
        text(canvas, title, pageMargin, geometry.headerTitleBaseline, typography.pageTitle, palette.text, true)
        text(canvas, subtitle, pageMargin, geometry.headerSubtitleBaseline, typography.caption, palette.muted)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.line; strokeWidth = geometry.pageRuleStrokeWidth }
        canvas.drawLine(pageMargin, geometry.headerRuleY, contentRight, geometry.headerRuleY, paint)
    }

    private fun drawFooter(canvas: Canvas, pageNumber: Int, documentLabel: String) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.line; strokeWidth = geometry.pageRuleStrokeWidth }
        canvas.drawLine(pageMargin, geometry.footerRuleY, contentRight, geometry.footerRuleY, paint)
        text(canvas, "$documentLabel - MyFitAI", pageMargin, geometry.footerTextBaseline, typography.micro, palette.muted)
        text(canvas, "Pagina $pageNumber", contentRight, geometry.footerTextBaseline, typography.micro, palette.muted, alignRight = true)
    }

    private fun drawCard(canvas: Canvas, x: Float, y: Float, width: Float, height: Float, color: Int = palette.panel) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        canvas.drawRoundRect(RectF(x, y, x + width, y + height), geometry.cardCornerRadius, geometry.cardCornerRadius, paint)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = palette.line
            style = Paint.Style.STROKE
            strokeWidth = geometry.cardBorderStrokeWidth
        }
        canvas.drawRoundRect(RectF(x, y, x + width, y + height), geometry.cardCornerRadius, geometry.cardCornerRadius, stroke)
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
        val contentX = x + geometry.metricCardContentHorizontalInset
        text(canvas, label, contentX, y + geometry.metricCardLabelBaseline, typography.metricCardLabel, palette.muted, true)
        text(canvas, value, contentX, y + geometry.metricCardValueBaseline, typography.metricCardValue, valueColor, true)
        subtitle?.let {
            text(canvas, it, contentX, y + height - geometry.metricCardSubtitleBottomInset, typography.metricCardSubtitle, valueColor, true)
        }
    }

    private fun drawCheckbox(canvas: Canvas, x: Float, y: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.muted; style = Paint.Style.STROKE; strokeWidth = geometry.checkboxStrokeWidth }
        canvas.drawRoundRect(
            RectF(x, y, x + geometry.checkboxSize, y + geometry.checkboxSize),
            geometry.checkboxCornerRadius,
            geometry.checkboxCornerRadius,
            paint,
        )
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
        value.trim().split(Regex("\\s+")).forEach { sourceWord ->
            splitLongWord(sourceWord, paint, width).forEach { word ->
                val candidate = if (current.isBlank()) word else "$current $word"
                if (paint.measureText(candidate) <= width || current.isBlank()) current = candidate
                else {
                    lines += current
                    current = word
                }
            }
        }
        if (current.isNotBlank()) lines += current
        return lines
    }

    private fun splitLongWord(word: String, paint: Paint, width: Float): List<String> {
        if (paint.measureText(word) <= width) return listOf(word)
        val chunks = mutableListOf<String>()
        var current = ""
        word.forEach { character ->
            val candidate = current + character
            if (current.isNotEmpty() && paint.measureText(candidate) > width) {
                chunks += current
                current = character.toString()
            } else current = candidate
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
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
        BodyProportionEngine.BalanceStatus.BALANCED -> palette.accent
        BodyProportionEngine.BalanceStatus.MILD_IMBALANCE -> palette.warning
        BodyProportionEngine.BalanceStatus.NOTICEABLE_IMBALANCE -> palette.danger
        BodyProportionEngine.BalanceStatus.INSUFFICIENT_DATA -> palette.muted
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
