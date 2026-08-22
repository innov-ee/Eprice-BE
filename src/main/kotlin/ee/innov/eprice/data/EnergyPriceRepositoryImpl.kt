package ee.innov.eprice.data

import ee.innov.eprice.data.elering.EleringService
import ee.innov.eprice.data.elering.toDomainEnergyPrices
import ee.innov.eprice.data.entsoe.EntsoeService
import ee.innov.eprice.data.entsoe.toBiddingZone
import ee.innov.eprice.data.entsoe.toDomainEnergyPrices
import ee.innov.eprice.domain.EnergyPriceRepository
import ee.innov.eprice.domain.model.ApiError
import ee.innov.eprice.domain.model.DailyStatEntry
import ee.innov.eprice.domain.model.DomainEnergyPrice
import ee.innov.eprice.domain.model.NoDataFoundException
import ee.innov.eprice.domain.model.toApiError
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class EnergyPriceRepositoryImpl(
    private val entsoeService: EntsoeService,
    private val eleringService: EleringService,
    private val cache: PriceCache,
    private val dailyStatsCache: DailyStatsCache
) : EnergyPriceRepository {

    override suspend fun getPrices(
        countryCode: String,
        start: Instant,
        end: Instant,
        cacheResults: Boolean,
    ): Result<List<DomainEnergyPrice>> {

        val cacheKey = "${countryCode}_${start}_$end"

        val cachedPrices = cache.get(cacheKey)
        if (cachedPrices != null) {
            return Result.success(cachedPrices)
        }

        val networkResult = fetchFromNetwork(countryCode, start, end)

        if (cacheResults) {
            networkResult.onSuccess { prices ->
                if (prices.isNotEmpty()) {
                    cache.put(cacheKey, prices)
                }
            }
        }

        return networkResult
    }

    override suspend fun getDailyStats(
        countryCode: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Result<Map<LocalDate, DailyStatEntry>> {
        val totalDays = (ChronoUnit.DAYS.between(startDate, endDate) + 1).toInt()
        val datesInRange = (0 until totalDays).map { startDate.plusDays(it.toLong()) }

        val cachedStats = dailyStatsCache.getRange(countryCode, startDate, endDate)
        val missingDates = datesInRange.filter { !cachedStats.containsKey(it) }

        if (missingDates.isEmpty()) {
            return Result.success(cachedStats)
        }

        val fetchedStats = try {
            coroutineScope {
                val deferredResults = missingDates.map { date ->
                    async { fetchDailyStat(countryCode, date) }
                }
                deferredResults.awaitAll().filterNotNull().toMap()
            }
        } catch (e: Exception) {
            return Result.failure(e)
        }

        if (fetchedStats.isNotEmpty()) {
            dailyStatsCache.putBatch(countryCode, fetchedStats)
        }

        return Result.success(cachedStats + fetchedStats)
    }

    private suspend fun fetchDailyStat(countryCode: String, date: LocalDate): Pair<LocalDate, DailyStatEntry>? {
        val dayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant()
        val dayEnd = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).minusSeconds(1).toInstant()

        val prices = getPrices(
            countryCode = countryCode,
            start = dayStart,
            end = dayEnd,
            cacheResults = false
        ).getOrNull()?.takeIf { it.isNotEmpty() } ?: return null

        val pricesList = prices.map { it.pricePerKWh }
        val entry = DailyStatEntry(
            min = pricesList.min(),
            max = pricesList.max(),
            avg = pricesList.average(),
            sum = pricesList.sum(),
            count = pricesList.size
        )
        return date to entry
    }

    /**
     * Contains the original network-fetching logic.
     */
    private suspend fun fetchFromNetwork(
        countryCode: String,
        start: Instant,
        end: Instant
    ): Result<List<DomainEnergyPrice>> {

        // Strategy: Try Elering first.
        try {
            val eleringMarketDocument = eleringService.fetchPrices(countryCode, start, end)
            val prices = eleringMarketDocument.toDomainEnergyPrices(countryCode)
            if (prices.isNotEmpty()) {
                return Result.success(prices)
            }
        } catch (e: NoDataFoundException) {
            println(e)
        } catch (e: Exception) {
            println(e)
        }

        // Elering failed, try entso-e
        val biddingZone = countryCode.toBiddingZone()
            ?: return Result.failure( // Return a specific error if mapping fails
                ApiError.Unknown(
                    "Unsupported country code for ENTSO-E fallback: $countryCode",
                    IllegalArgumentException("No bidding zone mapping for $countryCode")
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