package ee.innov.eprice.data

import ee.innov.eprice.domain.CountryZoneProvider
import ee.innov.eprice.domain.EnergyPriceRepository
import ee.innov.eprice.domain.PriceStatsRepository
import ee.innov.eprice.domain.model.DailyStatEntry
import ee.innov.eprice.domain.model.isFullDay
import ee.innov.eprice.monitoring.ServiceMonitor
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class PriceStatsRepositoryImpl(
    private val energyPriceRepository: EnergyPriceRepository,
    private val dailyStatsCache: DailyStatsCache,
    private val monitor: ServiceMonitor? = null
) : PriceStatsRepository {

    override suspend fun getDailyStats(
        countryCode: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Result<Map<LocalDate, DailyStatEntry>> {
        val zoneId = CountryZoneProvider.getZoneId(countryCode)
        val totalDays = (ChronoUnit.DAYS.between(startDate, endDate) + 1).toInt()
        val datesInRange = (0 until totalDays).map { startDate.plusDays(it.toLong()) }

        val cachedStats = dailyStatsCache.getRange(countryCode, startDate, endDate)
            .filter { (date, entry) -> entry.isFullDay(date, zoneId) }
        val missingDates = datesInRange.filter { !cachedStats.containsKey(it) }

        monitor?.recordCacheHit(cachedStats.size.toLong())
        monitor?.recordCacheMiss(missingDates.size.toLong())

        if (missingDates.isEmpty()) {
            return Result.success(cachedStats)
        }

        val fetchedStats = try {
            missingDates.mapNotNull { date -> fetchDailyStat(countryCode, date) }.toMap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        }

        if (fetchedStats.isNotEmpty()) {
            dailyStatsCache.putBatch(countryCode, fetchedStats)
        }

        return Result.success(cachedStats + fetchedStats)
    }

    private suspend fun fetchDailyStat(countryCode: String, date: LocalDate): Pair<LocalDate, DailyStatEntry>? {
        val zoneId = CountryZoneProvider.getZoneId(countryCode)
        val dayStart = date.atStartOfDay(zoneId).toInstant()
        val dayEnd = date.plusDays(1).atStartOfDay(zoneId).toInstant()

        val prices = energyPriceRepository.getPrices(
            countryCode = countryCode,
            start = dayStart,
            end = dayEnd,
            cacheResults = false
        ).getOrThrow()
        if (prices.isEmpty()) return null

        if (!prices.isFullDay(date, zoneId)) {
            return null
        }

        val pricesList = prices.map { it.pricePerKWh }
        val entry = DailyStatEntry(
            min = pricesList.min(),
            max = pricesList.max(),
            avg = prices.sumOf { it.pricePerKWh * it.durationSeconds } / prices.sumOf { it.durationSeconds },
            sum = pricesList.sum(),
            count = pricesList.size,
            durationSeconds = prices.sumOf { it.durationSeconds }
        )
        return date to entry
    }
}
