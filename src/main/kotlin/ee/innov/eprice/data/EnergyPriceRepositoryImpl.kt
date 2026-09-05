package ee.innov.eprice.data

import ee.innov.eprice.data.elering.EleringService
import ee.innov.eprice.data.elering.toDomainEnergyPrices
import ee.innov.eprice.data.entsoe.EntsoeService
import ee.innov.eprice.data.entsoe.toBiddingZone
import ee.innov.eprice.data.entsoe.toDomainEnergyPrices
import ee.innov.eprice.domain.CountryZoneProvider
import ee.innov.eprice.domain.EnergyPriceRepository
import ee.innov.eprice.domain.model.ApiError
import ee.innov.eprice.domain.model.DomainEnergyPrice
import ee.innov.eprice.domain.model.NoDataFoundException
import ee.innov.eprice.domain.model.isCompleteRange
import ee.innov.eprice.domain.model.toApiError
import ee.innov.eprice.monitoring.ServiceMonitor
import org.slf4j.LoggerFactory
import java.time.Instant

class EnergyPriceRepositoryImpl(
    private val entsoeService: EntsoeService,
    private val eleringService: EleringService,
    private val cache: PriceCache,
    private val monitor: ServiceMonitor? = null
) : EnergyPriceRepository {

    private val logger = LoggerFactory.getLogger(EnergyPriceRepositoryImpl::class.java)

    companion object {
        private val ELERING_SUPPORTED_COUNTRIES = setOf("EE", "FI", "LV", "LT")
    }

    override suspend fun getPrices(
        countryCode: String,
        start: Instant,
        end: Instant,
        cacheResults: Boolean,
    ): Result<List<DomainEnergyPrice>> {
        val cacheKey = "${countryCode}_${start}_$end"

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

        val networkResult = fetchFromNetwork(countryCode, start, end)

        if (cacheResults) {
            networkResult.onSuccess { prices ->
                if (prices.isNotEmpty()) {
                    val zoneId = CountryZoneProvider.getZoneId(countryCode)
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
        normalizedCode: String,
        start: Instant,
        end: Instant
    ): Result<List<DomainEnergyPrice>> {
        // Strategy: Try Elering first if supported.
        if (normalizedCode in ELERING_SUPPORTED_COUNTRIES) {
            try {
                val eleringMarketDocument = eleringService.fetchPrices(normalizedCode, start, end)
                val prices = eleringMarketDocument.toDomainEnergyPrices(normalizedCode)
                if (prices.isNotEmpty()) {
                    return Result.success(prices)
                }
            } catch (e: NoDataFoundException) {
                logger.info("Elering reported no data for $normalizedCode ($start to $end), attempting ENTSO-E fallback")
            } catch (e: Exception) {
                logger.warn("Elering fetch failed for $normalizedCode ($start to $end): ${e.message}, attempting ENTSO-E fallback")
            }
        }

        // Try ENTSO-E
        val biddingZone = normalizedCode.toBiddingZone()
            ?: return Result.failure(
                ApiError.Unknown(
                    "Unsupported country or bidding zone code: $normalizedCode",
                    IllegalArgumentException("No ENTSO-E bidding zone mapping for $normalizedCode")
                )
            )

        return try {
            val marketDocument = entsoeService.fetchPrices(biddingZone, start, end)
            val prices = marketDocument.toDomainEnergyPrices()
            Result.success(prices)
        } catch (_: NoDataFoundException) {
            // "No data" from ENTSO-E is not a failure, it's just an empty list.
            Result.success(emptyList())
        } catch (e: Exception) {
            // All other exceptions are mapped to our ApiError
            Result.failure(e.toApiError())
        }
    }
}