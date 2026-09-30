package com.myfitai.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import com.myfitai.app.R
import com.myfitai.app.ui.theme.MyFitAiSemanticColors
import com.myfitai.app.ui.theme.MyFitAiSurfaces
import com.myfitai.app.ui.theme.MyFitAiTheme

/** Visual roles only: data and action behavior remain owned by the calling feature. */
enum class MyFitAiCardRole {
    Standard,
    Hero,
    Metric,
    Insight,
    Action,
    List,
    PositiveStatus,
    WarningStatus,
    CriticalStatus,
    Ai,
}

enum class MyFitAiButtonRole { Primary, Secondary, Text }

enum class MyFitAiStatusRole { Neutral, Positive, Warning, Critical, Information }

/** Shared card surface; callers select a semantic role rather than supplying colors or radii. */
@Composable
fun MyFitAiCard(
    role: MyFitAiCardRole,
    modifier: Modifier = Modifier,
    contentPadding: MyFitAiCardPadding = MyFitAiCardPadding.Standard,
    content: @Composable () -> Unit,
) {
    val colors = cardColors(role)
    val shape = MaterialTheme.shapes.medium
    Surface(
        modifier = modifier,
        shape = shape,
        color = colors.first,
        contentColor = colors.second,
        border = BorderStroke(
            dimensionResource(R.dimen.border_width_default),
            when (role) {
                MyFitAiCardRole.PositiveStatus -> colorResource(R.color.positive_soft_stroke)
                MyFitAiCardRole.WarningStatus -> colorResource(R.color.warning_soft_stroke)
                MyFitAiCardRole.CriticalStatus -> colorResource(R.color.error_soft_stroke)
                MyFitAiCardRole.Ai -> colorResource(R.color.ai_soft_stroke)
                else -> colorResource(R.color.divider)
            },
        ),
        tonalElevation = dimensionResource(R.dimen.elevation_none),
        shadowElevation = dimensionResource(R.dimen.elevation_card),
    ) {
        Column(modifier = Modifier.padding(contentPadding.value())) {
            content()
        }
    }
}

enum class MyFitAiCardPadding { Compact, Standard, Emphasized }

@Composable
private fun MyFitAiCardPadding.value(): Dp = when (this) {
    MyFitAiCardPadding.Compact -> dimensionResource(R.dimen.card_content_padding_compact)
    MyFitAiCardPadding.Standard -> dimensionResource(R.dimen.card_content_padding)
    MyFitAiCardPadding.Emphasized -> dimensionResource(R.dimen.space_20)
}

@Composable
private fun cardColors(role: MyFitAiCardRole): Pair<Color, Color> = when (role) {
    MyFitAiCardRole.Standard,
    MyFitAiCardRole.Metric,
    MyFitAiCardRole.List -> colorResource(R.color.surface_primary) to colorResource(R.color.text_primary)
    MyFitAiCardRole.Hero -> colorResource(R.color.surface_positive_soft) to colorResource(R.color.text_primary)
    MyFitAiCardRole.Insight -> colorResource(R.color.surface_info_soft) to colorResource(R.color.text_primary)
    MyFitAiCardRole.Action -> colorResource(R.color.surface_secondary) to colorResource(R.color.text_primary)
    MyFitAiCardRole.PositiveStatus -> colorResource(R.color.surface_positive_soft) to colorResource(R.color.text_primary)
    MyFitAiCardRole.WarningStatus -> colorResource(R.color.surface_warning_soft) to colorResource(R.color.text_primary)
    MyFitAiCardRole.CriticalStatus -> colorResource(R.color.surface_error_soft) to colorResource(R.color.text_primary)
    MyFitAiCardRole.Ai -> MyFitAiSurfaces.aiContainer to MyFitAiSurfaces.onAiContainer
}

/** Section heading with optional supporting copy and trailing action slot. */
@Composable
fun MyFitAiSectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_8)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            supportingText?.let { supporting ->
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailingContent?.invoke()
    }
}

/** Small semantic state badge; the label remains visible so meaning is never color-only. */
@Composable
fun MyFitAiStatusChip(
    label: String,
    role: MyFitAiStatusRole,
    modifier: Modifier = Modifier,
) {
    val (container, foreground) = statusColors(role)
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = container,
        contentColor = foreground,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(
                horizontal = dimensionResource(R.dimen.space_12),
                vertical = dimensionResource(R.dimen.space_4),
            ),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun statusColors(role: MyFitAiStatusRole): Pair<Color, Color> = when (role) {
    MyFitAiStatusRole.Neutral -> colorResource(R.color.surface_secondary) to colorResource(R.color.text_secondary)
    MyFitAiStatusRole.Positive -> colorResource(R.color.surface_positive_soft) to MyFitAiSemanticColors.positive
    MyFitAiStatusRole.Warning -> colorResource(R.color.surface_warning_soft) to MyFitAiSemanticColors.warning
    MyFitAiStatusRole.Critical -> colorResource(R.color.surface_error_soft) to MyFitAiSemanticColors.critical
    MyFitAiStatusRole.Information -> colorResource(R.color.surface_info_soft) to MyFitAiSemanticColors.information
}

/** Shared action styles; label, enabled state and callback come from the existing feature. */
@Composable
fun MyFitAiButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: MyFitAiButtonRole = MyFitAiButtonRole.Primary,
    enabled: Boolean = true,
) {
    when (role) {
        MyFitAiButtonRole.Primary -> Button(onClick = onClick, modifier = modifier, enabled = enabled) {
            Text(label)
        }
        MyFitAiButtonRole.Secondary -> OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled) {
            Text(label)
        }
        MyFitAiButtonRole.Text -> TextButton(onClick = onClick, modifier = modifier, enabled = enabled) {
            Text(label)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MyFitAiComponentsPreview() {
    MyFitAiTheme {
        Column(
            modifier = Modifier.padding(dimensionResource(R.dimen.space_16)),
            verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_12)),
        ) {
            MyFitAiCard(MyFitAiCardRole.Metric) {
                MyFitAiSectionTitle("Panoramica del corpo", supportingText = "Trend corporeo · ultimi 3 mesi")
                Text("78,4 kg", style = MaterialTheme.typography.headlineSmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_8))) {
                MyFitAiStatusChip("In linea", MyFitAiStatusRole.Positive)
                MyFitAiStatusChip("Da verificare", MyFitAiStatusRole.Warning)
            }
            MyFitAiButton("Apri dettaglio", onClick = {})
        }
    }
}
