package com.myfitai.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import com.myfitai.app.R
import com.myfitai.app.ui.theme.MyFitAiTheme

enum class MetricDeltaTone { Positive, Negative, Neutral }

data class DashboardMetricPresentation(
    val label: String,
    val value: String,
    val delta: String,
    val tone: MetricDeltaTone,
    @param:DrawableRes val trendIcon: Int,
)

/** Three highlighted body metric tiles; source values, deltas and meanings are supplied by Home. */
@Composable
fun DashboardMetricsPanel(
    metrics: List<DashboardMetricPresentation>,
    modifier: Modifier = Modifier,
) {
    MyFitAiCard(
        role = MyFitAiCardRole.Standard,
        modifier = modifier.fillMaxWidth(),
        contentPadding = MyFitAiCardPadding.Compact,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_8)),
        ) {
            metrics.forEachIndexed { index, metric ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(colorResource(R.color.surface_secondary))
                        .padding(dimensionResource(R.dimen.dashboard_metric_card_padding))
                        .testTag("dashboardMetricTile$index"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_4)),
                ) {
                    Box(
                        modifier = Modifier
                            .size(dimensionResource(R.dimen.dashboard_metric_tile_icon_size))
                            .testTag("dashboardMetricIcon$index"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(dimensionResource(R.dimen.dashboard_metric_tile_icon_size))
                                .clip(CircleShape)
                                .background(colorResource(R.color.surface_info_soft)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                painter = painterResource(metric.trendIcon),
                                contentDescription = null,
                                modifier = Modifier.size(dimensionResource(R.dimen.inline_icon_size)),
                                colorFilter = ColorFilter.tint(colorResource(R.color.accent_green_dark)),
                            )
                        }
                    }
                    Box(
                        modifier = Modifier.fillMaxWidth().testTag("dashboardMetricLabel$index"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = metric.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            minLines = 3,
                            maxLines = 3,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                    Box(
                        modifier = Modifier.fillMaxWidth().testTag("dashboardMetricValue$index"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = metric.value,
                            style = MaterialTheme.typography.titleMedium,
                            color = colorResource(R.color.metric_value),
                            minLines = 2,
                            maxLines = 2,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dashboardMetricDelta$index")
                            .clip(RoundedCornerShape(dimensionResource(R.dimen.radius_small)))
                            .background(deltaContainer(metric.tone))
                            .padding(horizontal = dimensionResource(R.dimen.space_4), vertical = dimensionResource(R.dimen.space_4)),
                        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_2)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            painter = painterResource(metric.trendIcon),
                            contentDescription = null,
                            modifier = Modifier.size(dimensionResource(R.dimen.trend_indicator_icon_size)),
                            colorFilter = ColorFilter.tint(deltaColor(metric.tone)),
                        )
                        Text(
                            text = metric.delta,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = deltaColor(metric.tone),
                            minLines = 2,
                            maxLines = 2,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun deltaColor(tone: MetricDeltaTone) = when (tone) {
    MetricDeltaTone.Positive -> colorResource(R.color.accent_green)
    MetricDeltaTone.Negative -> colorResource(R.color.semantic_error)
    MetricDeltaTone.Neutral -> colorResource(R.color.text_muted)
}

@Composable
private fun deltaContainer(tone: MetricDeltaTone) = when (tone) {
    MetricDeltaTone.Positive -> colorResource(R.color.surface_positive_soft)
    MetricDeltaTone.Negative -> colorResource(R.color.surface_error_soft)
    MetricDeltaTone.Neutral -> colorResource(R.color.surface_secondary)
}

@Preview(showBackground = true)
@Composable
private fun DashboardMetricsPanelPreview() {
    MyFitAiTheme {
        DashboardMetricsPanel(
            metrics = listOf(
                DashboardMetricPresentation("Peso", "78,4 kg", "2,1 kg", MetricDeltaTone.Neutral, R.drawable.ic_trend_down),
                DashboardMetricPresentation("Grasso", "14,2%", "0,8%", MetricDeltaTone.Positive, R.drawable.ic_trend_down),
                DashboardMetricPresentation("Massa muscolare", "66,8 kg", "0,6 kg", MetricDeltaTone.Positive, R.drawable.ic_trend_up),
            ),
        )
    }
}
