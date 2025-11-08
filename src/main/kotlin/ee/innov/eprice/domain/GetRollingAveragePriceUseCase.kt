package ee.innov.eprice.domain

import ee.innov.eprice.data.DailyAveragePriceCache
import ee.innov.eprice.domain.model.NoDataFoundException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class GetRollingAveragePriceUseCase(
    private val energyPriceRepository: EnergyPriceRepository,
    private val dailyAveragePriceCache: DailyAveragePriceCache
) {

    /**
     * Calculates the rolling average price for a given country over a number of days.
     *
     * It first checks a persistent daily average cache. If data for a day is missing,
     * it fetches the hourly data from the repository, calculates the day's average,
     * and stores it in the cache for future use.
     *
     * @param countryCode The 2-letter country code (e.g., "EE").
     * @param days The number of days to include in the average (e.g., 30).
     * @return A Result containing the average price, or an error if data cannot be fetched.
     */
    suspend fun execute(countryCode: String, days: Int): Result<RollingAverage> {
        if (days <= 0) {
            return Result.failure(IllegalArgumentException("Number of days must be positive."))
        }

        // We calculate up to *yesterday*. Today's data is often incomplete.
        val endDate = Instant.now().atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
        val startDate = endDate.minusDays(days.toLong() - 1)

        // 1. Get all dates in the range
        val datesInRange = (0 until days).map { startDate.plusDays(it.toLong()) }

        // 2. Get all currently cached daily averages for this range
        val cachedAverages: Map<LocalDate, Double> =
            dailyAveragePriceCache.getRange(countryCode, startDate, endDate)

        // 3. Determine which dates we are missing
        val missingDates = datesInRange.filter { !cachedAverages.containsKey(it) }

        val fetchedAverages = mutableMapOf<LocalDate, Double>()

        if (missingDates.isNotEmpty()) {
            // 4. Fetch missing dates in parallel
            try {
                coroutineScope {
                    val deferredResults = missingDates.map { date ->
                        async {
                            // Fetch data for a single day
                            val dayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant()
                            val dayEnd =
                                date.plusDays(1).atStartOfDay(ZoneOffset.UTC).minusSeconds(1)
                                    .toInstant()


                            val result = energyPriceRepository.getPrices(
                                countryCode = countryCode,
                                start = dayStart,
                                end = dayEnd,
                                // dont cache, as its just noise
                                cacheResults = false
                            )


                            result.getOrNull()?.let { prices ->
                                if (prices.isNotEmpty()) {
                                    val dailyAvg = prices.map { it.pricePerKWh }.average()
                                    // Store in our persistent daily cache
                                    dailyAveragePriceCache.put(countryCode, date, dailyAvg)
                                    date to dailyAvg // Return pair for map
                                } else {
                                    null // No data found for this day
                                }
                            }
                            // If result.getOrNull() is null (a failure), this will also return null
                            // We will filter these out later.
                        }
                    }
                    // awaitAll() will propagate exceptions from children
                    fetchedAverages.putAll(deferredResults.awaitAll().filterNotNull())
                }
            } catch (e: Exception) {
                // If any network request fails, propagate the error
                return Result.failure(e)
            }
        }

        // 5. Combine all results
        val allAverages = (cachedAverages.values + fetchedAverages.values)

        if (allAverages.isEmpty()) {
            return Result.failure(
                NoDataFoundException("No price data found for $countryCode between $startDate and $endDate.")
            )
        }

        // 6. Calculate the final average
        val finalAverage = allAverages.average()
        val daysCalculated = allAverages.size

        return Result.success(
            RollingAverage(
                countryCode = countryCode.uppercase(),
                daysRequested = days,
                daysCalculated = daysCalculated,
                startDate = startDate,
                endDate = endDate,
                averagePrice = finalAverage
            )
        )
    }

    @Serializable
    data class RollingAverage(
        val countryCode: String,
        val daysRequested: Int,
        val daysCalculated: Int,
        val startDate: String,
        val endDate: String,
        val averagePrice: Double
    ) {
        // Overloaded constructor for use case internal logic
        constructor(
            countryCode: String,
            daysRequested: Int,
            daysCalculated: Int,
            startDate: LocalDate,
            endDate: LocalDate,
            averagePrice: Double
        ) : this(
            countryCode = countryCode,
            daysRequested = daysRequested,
            daysCalculated = daysCalculated,
            startDate = startDate.toString(),
            endDate = endDate.toString(),
            averagePrice = averagePrice
        )
    }
}