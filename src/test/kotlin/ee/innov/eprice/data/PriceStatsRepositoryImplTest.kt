package ee.innov.eprice.data

import ee.innov.eprice.domain.EnergyPriceRepository
import ee.innov.eprice.domain.model.DailyStatEntry
import ee.innov.eprice.domain.model.DomainEnergyPrice
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

private class InMemoryDailyStatsCacheFake : DailyStatsCache {
    val store = mutableMapOf<String, MutableMap<LocalDate, DailyStatEntry>>()

    override fun get(countryCode: String, date: LocalDate): DailyStatEntry? =
        store[countryCode.uppercase()]?.get(date)

    override fun put(countryCode: String, date: LocalDate, stats: DailyStatEntry) {
        store.getOrPut(countryCode.uppercase()) { mutableMapOf() }[date] = stats
    }

    override fun putBatch(countryCode: String, entries: Map<LocalDate, DailyStatEntry>) {
        store.getOrPut(countryCode.uppercase()) { mutableMapOf() }.putAll(entries)
    }

    override fun getRange(
        countryCode: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Map<LocalDate, DailyStatEntry> {
        val countryMap = store[countryCode.uppercase()] ?: return emptyMap()
        return countryMap.filterKeys { !it.isBefore(startDate) && !it.isAfter(endDate) }
    }

    override fun clear() {
        store.clear()
    }
}

private class CountingPriceRepositoryFake : EnergyPriceRepository {
    var callCount = 0
    val requestedRanges = mutableListOf<Pair<Instant, Instant>>()

    override suspend fun getPrices(
        countryCode: String,
        start: Instant,
        end: Instant,
        cacheResults: Boolean
    ): Result<List<DomainEnergyPrice>> {
        callCount++
        requestedRanges.add(start to end)
        val prices = (0 until 24).map { hour ->
            DomainEnergyPrice(
                startTime = start.plusSeconds(hour * 3600L),
                pricePerKWh = 0.10 + (hour * 0.01) // 0.10 to 0.33
            )
        }
        return Result.success(prices)
    }
}

class PriceStatsRepositoryImplTest {

    @Test
    fun `getDailyStats populates cache on cold start and hits cache on warm start`() = runBlocking {
        val cache = InMemoryDailyStatsCacheFake()
        val priceRepo = CountingPriceRepositoryFake()
        val statsRepo = PriceStatsRepositoryImpl(priceRepo, cache)

        val start = LocalDate.of(2026, 8, 1)
        val end = LocalDate.of(2026, 8, 3)

        // Cold start - fetches 3 days
        val firstResult = statsRepo.getDailyStats("EE", start, end)
        assertTrue(firstResult.isSuccess)
        val statsMap = firstResult.getOrThrow()
        assertEquals(3, statsMap.size)
        assertEquals(3, priceRepo.callCount)

        // Cache contains all 3 days
        assertEquals(3, cache.getRange("EE", start, end).size)

        // Warm start - no new price fetches
        val secondResult = statsRepo.getDailyStats("EE", start, end)
        assertTrue(secondResult.isSuccess)
        assertEquals(3, secondResult.getOrThrow().size)
        assertEquals(3, priceRepo.callCount)
    }

    @Test
    fun `getDailyStats only fetches missing dates when partial cache exists`() = runBlocking {
        val cache = InMemoryDailyStatsCacheFake()
        val priceRepo = CountingPriceRepositoryFake()
        val statsRepo = PriceStatsRepositoryImpl(priceRepo, cache)

        val day1 = LocalDate.of(2026, 8, 1)
        val day2 = LocalDate.of(2026, 8, 2)
        val day3 = LocalDate.of(2026, 8, 3)

        // Seed day 2 in cache
        cache.put("EE", day2, DailyStatEntry(min = 0.05, max = 0.15, avg = 0.10, sum = 2.4, count = 24))

        val result = statsRepo.getDailyStats("EE", day1, day3)
        assertTrue(result.isSuccess)
        val map = result.getOrThrow()
        assertEquals(3, map.size)
        // Fetched only day 1 and day 3 -> 2 calls
        assertEquals(2, priceRepo.callCount)
        assertEquals(0.05, map[day2]?.min)
    }

    @Test
    fun `fetchDailyStat uses country local timezone boundaries during summer and winter`() = runBlocking {
        val cache = InMemoryDailyStatsCacheFake()
        val priceRepo = CountingPriceRepositoryFake()
        val statsRepo = PriceStatsRepositoryImpl(priceRepo, cache)

        // Summer date in Estonia (EEST is UTC+3): 2026-08-22 start is 2026-08-21T21:00:00Z
        val summerDate = LocalDate.of(2026, 8, 22)
        statsRepo.getDailyStats("EE", summerDate, summerDate)

        assertEquals(1, priceRepo.requestedRanges.size)
        val (summerStart, summerEnd) = priceRepo.requestedRanges[0]
        assertEquals(Instant.parse("2026-08-21T21:00:00Z"), summerStart)
        assertEquals(Instant.parse("2026-08-22T20:59:59Z"), summerEnd)

        // Winter date in Estonia (EET is UTC+2): 2026-01-15 start is 2026-01-14T22:00:00Z
        val winterDate = LocalDate.of(2026, 1, 15)
        statsRepo.getDailyStats("EE", winterDate, winterDate)

        assertEquals(2, priceRepo.requestedRanges.size)
        val (winterStart, winterEnd) = priceRepo.requestedRanges[1]
        assertEquals(Instant.parse("2026-01-14T22:00:00Z"), winterStart)
        assertEquals(Instant.parse("2026-01-15T21:59:59Z"), winterEnd)
    }
}
