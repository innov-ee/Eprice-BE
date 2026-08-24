package ee.innov.eprice.data

import ee.innov.eprice.domain.EnergyPriceRepository
import ee.innov.eprice.domain.model.DailyStatEntry
import ee.innov.eprice.domain.model.DomainEnergyPrice
import ee.innov.eprice.test.InMemoryDailyStatsCache
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

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
        val cache = InMemoryDailyStatsCache()
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
        val cache = InMemoryDailyStatsCache()
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
        val cache = InMemoryDailyStatsCache()
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

    @Test
    fun `fetchDailyStat ignores partial day with only 1-2 hours and does not cache it`() = runBlocking {
        val cache = InMemoryDailyStatsCache()
        val customRepo = object : EnergyPriceRepository {
            override suspend fun getPrices(
                countryCode: String,
                start: Instant,
                end: Instant,
                cacheResults: Boolean
            ): Result<List<DomainEnergyPrice>> {
                // Return only 2 hours
                val partialPrices = listOf(
                    DomainEnergyPrice(start, 0.10),
                    DomainEnergyPrice(start.plusSeconds(3600), 0.12)
                )
                return Result.success(partialPrices)
            }
        }

        val statsRepo = PriceStatsRepositoryImpl(customRepo, cache)
        val testDate = LocalDate.of(2026, 8, 23)

        val result = statsRepo.getDailyStats("EE", testDate, testDate)
        assertTrue(result.isSuccess)
        val map = result.getOrThrow()
        // Map should be empty because partial day is rejected
        assertTrue(map.isEmpty())
        // Cache should not have cached the partial day
        assertTrue(cache.getRange("EE", testDate, testDate).isEmpty())
    }

    @Test
    fun `getDailyStats filters out invalid partial entries from cache and refetches`() = runBlocking {
        val cache = InMemoryDailyStatsCache()
        val priceRepo = CountingPriceRepositoryFake()
        val statsRepo = PriceStatsRepositoryImpl(priceRepo, cache)

        val testDate = LocalDate.of(2026, 8, 23)
        // Corrupt cache with a partial 2-hour entry
        cache.put("EE", testDate, DailyStatEntry(min = 0.10, max = 0.12, avg = 0.11, sum = 0.22, count = 2))

        val result = statsRepo.getDailyStats("EE", testDate, testDate)
        assertTrue(result.isSuccess)
        val map = result.getOrThrow()
        assertEquals(1, map.size)
        // Repositories fetched full 24-hour data
        assertEquals(24, map[testDate]?.count)
        assertEquals(1, priceRepo.callCount)
    }
}
