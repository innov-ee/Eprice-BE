package ee.innov.eprice.monitoring

import ee.innov.eprice.data.DailyStatsCache
import ee.innov.eprice.data.MissingStatsReason
import ee.innov.eprice.domain.BiddingZones
import ee.innov.eprice.domain.GetPriceSummaryUseCase
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.LocalDate

@Serializable
data class CacheDay(val offset: Int, val date: String, val state: String, val detail: String)

@Serializable
data class RegionCache(val region: String, val timezone: String, val statsRequests: Long, val days: List<CacheDay>)

@Serializable
data class CacheOverview(val regions: List<RegionCache>) {
    companion object {
        /** Inspect the same local-date window as summary requests, without fetching or recording lookups. */
        fun read(cache: DailyStatsCache, monitor: ServiceMonitor, clock: Clock = Clock.systemUTC()): CacheOverview =
            CacheOverview(BiddingZones.all.map { region ->
                val today = LocalDate.now(clock.withZone(region.zoneId))
                val days = (1 downTo -GetPriceSummaryUseCase.DEFAULT_ROLLING_DAYS).map { offset ->
                    val date = today.plusDays(offset.toLong())
                    val stats = cache.get(region.id, date)
                    val missing = cache.getMissing(region.id, date)
                    val state = when {
                        stats != null -> "full"
                        missing?.reason == MissingStatsReason.PARTIAL -> "partial"
                        else -> "missing"
                    }
                    val detail = when {
                        stats != null -> "Complete daily statistics cached (${stats.count} intervals)"
                        missing != null -> "${missing.reason}; checked ${missing.checkedAt}; next check ${missing.nextCheckAt}"
                        else -> "No cached daily statistics"
                    }
                    CacheDay(offset, date.toString(), state, detail)
                }
                RegionCache(region.id, region.zoneId.id, monitor.statsRequestCount(region.id), days)
            })
    }
}
