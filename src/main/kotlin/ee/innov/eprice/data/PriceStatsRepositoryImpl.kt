package ee.innov.eprice.data

import ee.innov.eprice.domain.EnergyPriceRepository
import ee.innov.eprice.domain.PriceStatsRepository
import ee.innov.eprice.domain.model.DailyStatEntry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class PriceStatsRepositoryImpl(
    private val energyPriceRepository: EnergyPriceRepository,
    private val dailyStatsCache: DailyStatsCache
) : PriceStatsRepository {

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

        val prices = energyPriceRepository.getPrices(
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
}
