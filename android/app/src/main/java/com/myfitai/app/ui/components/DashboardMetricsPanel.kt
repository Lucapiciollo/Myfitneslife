package com.myfitai.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
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

/** Three compact metric columns replacing the legacy custom View in the Home panel. */
@Composable
fun DashboardMetricsPanel(
    metrics: List<DashboardMetricPresentation>,
    modifier: Modifier = Modifier,
) {
    MyFitAiCard(
        role = MyFitAiCardRole.Metric,
        modifier = modifier.fillMaxWidth(),
        contentPadding = MyFitAiCardPadding.Compact,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_8)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            metrics.forEachIndexed { index, metric ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(dimensionResource(R.dimen.divider_thickness))
                            .background(colorResource(R.color.divider)),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_4)),
                ) {
                    Text(
                        text = metric.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = metric.value,
                        style = MaterialTheme.typography.titleSmall,
                        color = colorResource(R.color.metric_value),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_2)),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Image(
                            painter = painterResource(metric.trendIcon),
                            contentDescription = null,
                            modifier = Modifier.size(dimensionResource(R.dimen.trend_indicator_icon_size)),
                            colorFilter = ColorFilter.tint(deltaColor(metric.tone)),
                        )
                        Text(
                            text = metric.delta,
                            style = MaterialTheme.typography.labelSmall,
                            color = deltaColor(metric.tone),
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
