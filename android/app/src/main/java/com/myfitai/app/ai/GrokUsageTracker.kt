package com.myfitai.app.ai

import android.content.Context
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.AiUsageRecordEntity
import com.myfitai.app.data.repository.AiUsageRepository
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class GrokUsageTracker(context: Context) {
    private val appContext = context.applicationContext
    private val repository = AiUsageRepository(MyFitAiDatabase.getInstance(appContext))
    private val pricingStore = GrokPricingStore(appContext)

    suspend fun record(response: AiRawResponse) {
        if (response.provider != AiProviderType.GROK) return
        val usage = response.usage ?: return
        val pricing = pricingStore.pricingFor(response.model)
        val calculated = GrokCostCalculator.calculate(usage, pricing)
        repository.insert(
            AiUsageRecordEntity(
                timestampEpochMillis = System.currentTimeMillis(),
                provider = PROVIDER,
                model = response.model,
                inputTokens = calculated.inputTokens,
                outputTokens = calculated.outputTokens,
                thoughtsTokens = calculated.reasoningTokens,
                cachedTokens = calculated.cachedTokens,
                totalTokens = usage.totalTokens,
                inputUsdPerMillion = pricing.inputUsdPerMillion.stripTrailingZeros().toPlainString(),
                outputUsdPerMillion = pricing.outputUsdPerMillion.stripTrailingZeros().toPlainString(),
                cachedInputUsdPerMillion = pricing.cachedInputUsdPerMillion.stripTrailingZeros().toPlainString(),
                costUsdNanos = calculated.costUsdNanos,
                // Base (<200k) rates are stored; the x2 long-context tier is flagged here so the cost stays explainable.
                pricingSource = if (calculated.longContext) pricing.source + GrokPricingStore.SOURCE_LONG_CONTEXT_SUFFIX else pricing.source,
                pricingEffectiveDate = pricing.effectiveDate,
            )
        )
    }

    suspend fun summary(nowEpochMillis: Long = System.currentTimeMillis(), zoneId: ZoneId = ZoneId.systemDefault()): GrokCostSummary {
        val today = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId).toLocalDate()
        val tomorrow = today.plusDays(1)
        val monthStart = today.withDayOfMonth(1)
        val total = repository.totalCostNanos(PROVIDER)
        val todayCost = repository.costNanosBetween(PROVIDER, startOfDay(today, zoneId), startOfDay(tomorrow, zoneId))
        val monthCost = repository.costNanosBetween(PROVIDER, startOfDay(monthStart, zoneId), startOfDay(monthStart.plusMonths(1), zoneId))
        return GrokCostSummary(
            todayUsd = nanosToUsd(todayCost),
            monthUsd = nanosToUsd(monthCost),
            totalUsd = nanosToUsd(total),
            requestCount = repository.count(PROVIDER),
            latestAtEpochMillis = repository.latest(PROVIDER)?.timestampEpochMillis,
        )
    }

    private fun startOfDay(date: LocalDate, zoneId: ZoneId): Long = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
    private fun nanosToUsd(value: Long): BigDecimal = BigDecimal(value).movePointLeft(9)

    companion object {
        private const val PROVIDER = "GROK"
    }
}

data class GrokCostSummary(
    val todayUsd: BigDecimal,
    val monthUsd: BigDecimal,
    val totalUsd: BigDecimal,
    val requestCount: Long,
    val latestAtEpochMillis: Long?,
)

/** Records every successful Grok transport response, including responses later rejected by validators. */
class GrokUsageTrackingProvider(
    private val delegate: AiProvider,
    private val tracker: GrokUsageTracker,
) : AiProvider {
    override val type: AiProviderType = delegate.type

    override suspend fun generateStructured(request: AiStructuredRequest): AiRawResponse {
        val response = delegate.generateStructured(request)
        try {
            tracker.record(response)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Usage tracking is best effort and must never fail a paid response.
        }
        return response
    }
}

