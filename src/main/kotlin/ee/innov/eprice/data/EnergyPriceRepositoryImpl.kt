package ee.innov.eprice.data

import ee.innov.eprice.data.elering.EleringService
import ee.innov.eprice.data.entsoe.EntsoeService
import ee.innov.eprice.domain.BiddingZones
import ee.innov.eprice.domain.BiddingZone
import ee.innov.eprice.domain.EnergyPriceRepository
import ee.innov.eprice.domain.model.DomainEnergyPrice
import ee.innov.eprice.domain.model.NoDataFoundException
import ee.innov.eprice.domain.model.isCompleteRange
import ee.innov.eprice.domain.model.inRange
import ee.innov.eprice.domain.model.toApiError
import ee.innov.eprice.monitoring.ServiceMonitor
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlinx.coroutines.CancellationException

class EnergyPriceRepositoryImpl(
    private val entsoeService: EntsoeService,
    private val eleringService: EleringService,
    private val cache: PriceCache,
    private val monitor: ServiceMonitor? = null
) : EnergyPriceRepository {

    private val logger = LoggerFactory.getLogger(EnergyPriceRepositoryImpl::class.java)

    override suspend fun getPrices(
        countryCode: String,
        start: Instant,
        end: Instant,
        cacheResults: Boolean,
    ): Result<List<DomainEnergyPrice>> {
        if (end <= start) return Result.failure(IllegalArgumentException("Price range end must be after its start"))
        val region = runCatching { BiddingZones.resolve(countryCode) }.getOrElse { return Result.failure(it) }
        val cacheKey = "${region.id}_${start}_$end"

        val cachedPrices = cache.get(cacheKey)
        if (cachedPrices != null) {
            if (cacheResults) {
                monitor?.recordCacheHit()
            }
            return Result.success(cachedPrices)
        }

        if (cacheResults) {
            monitor?.recordCacheMiss()
        }

        // Stats fills bypass price-cache writes and need fallback for each empty day.
        // Price requests can serve and cache a partial Elering response as before.
        val networkResult = fetchFromNetwork(region, start, end, fillDayGaps = !cacheResults)

        if (cacheResults) {
            networkResult.onSuccess { prices ->
                if (prices.isNotEmpty()) {
                    val zoneId = region.zoneId
                    val isComplete = prices.isCompleteRange(zoneId, start, end)
                    cache.put(cacheKey, prices, isComplete = isComplete)
                }
            }
        }

        return networkResult
    }

    /**
     * Contains the network-fetching logic.
     * Strategy: Try Elering first for Baltic/Nordic supported countries, otherwise or on failure query ENTSO-E.
     */
    private suspend fun fetchFromNetwork(
        region: BiddingZone,
        start: Instant,
        end: Instant,
        fillDayGaps: Boolean
    ): Result<List<DomainEnergyPrice>> {
        // Strategy: Try Elering first if supported.
        if (region.eleringKey != null) {
            val prices = try {
                eleringService.fetchPrices(region.eleringKey, start, end)
            } catch (e: NoDataFoundException) {
                logger.info("Elering reported no data for ${region.id} ($start to $end), attempting ENTSO-E fallback")
                emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Elering fetch failed for ${region.id} ($start to $end): ${e.message}, attempting ENTSO-E fallback")
                emptyList()
            }
            if (prices.isNotEmpty()) {
                return if (fillDayGaps) fillEmptyDays(region, start, end, prices) else Result.success(prices)
            }
        }

        return fetchEntsoe(region, start, end)
    }

    /** Preserve the per-day fallback when a batched Elering response omits entire days. */
    private suspend fun fillEmptyDays(
        region: BiddingZone,
        start: Instant,
        end: Instant,
        prices: List<DomainEnergyPrice>
    ): Result<List<DomainEnergyPrice>> {
        val combined = prices.toMutableList()
        var date = start.atZone(region.zoneId).toLocalDate()
        var gapStart: Instant? = null
        while (date.atStartOfDay(region.zoneId).toInstant() < end) {
            val dayStart = maxOf(start, date.atStartOfDay(region.zoneId).toInstant())
            val dayEnd = minOf(end, date.plusDays(1).atStartOfDay(region.zoneId).toInstant())
            val empty = prices.inRange(dayStart, dayEnd).isEmpty()
            if (empty && gapStart == null) gapStart = dayStart
            if (gapStart != null && (!empty || dayEnd == end)) {
                val gapEnd = if (empty) dayEnd else dayStart
                val fallback = fetchEntsoe(region, gapStart, gapEnd).getOrElse { return Result.failure(it) }
                combined.addAll(fallback)
                gapStart = null
            }
            date = date.plusDays(1)
        }
        return Result.success(combined.sortedBy { it.startTime })
    }

    private suspend fun fetchEntsoe(region: BiddingZone, start: Instant, end: Instant): Result<List<DomainEnergyPrice>> {
        return try {
            Result.success(entsoeService.fetchPrices(region.eic, start, end))
        } catch (_: NoDataFoundException) {
            // "No data" from ENTSO-E is not a failure, it's just an empty list.
            Result.success(emptyList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // All other exceptions are mapped to our ApiError
            Result.failure(e.toApiError())
        }
    }
}
