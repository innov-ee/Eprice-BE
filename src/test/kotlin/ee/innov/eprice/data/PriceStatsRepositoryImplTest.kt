package ee.innov.eprice.data

import ee.innov.eprice.domain.EnergyPriceRepository
import ee.innov.eprice.domain.BiddingZones
import ee.innov.eprice.domain.model.DailyStatEntry
import ee.innov.eprice.domain.model.DomainEnergyPrice
import ee.innov.eprice.test.InMemoryDailyStatsCache
import ee.innov.eprice.test.dayPrices
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.Clock
import java.time.ZoneOffset
import kotlin.test.assertNull

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
        val prices = (0 until java.time.Duration.between(start, end).toHours().toInt()).map { hour ->
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
    fun `cold summary fetches the full 32 day window once and warm summary makes no requests`() = runBlocking {
        val prices = CountingPriceRepositoryFake()
        val repository = PriceStatsRepositoryImpl(prices, InMemoryDailyStatsCache())
        val first = LocalDate.of(2026, 8, 1)
        assertEquals(32, repository.getDailyStats("IE", first, first.plusDays(31)).getOrThrow().size)
        assertEquals(1, prices.callCount)
        val zone = BiddingZones.resolve("IE").zoneId
        assertEquals(listOf(first.atStartOfDay(zone).toInstant() to first.plusDays(32).atStartOfDay(zone).toInstant()), prices.requestedRanges)
        assertEquals(32, repository.getDailyStats("IE", first, first.plusDays(31)).getOrThrow().size)
        assertEquals(1, prices.callCount)
    }

    @Test
    fun `a single missing day still refreshes the full 32 day window`() = runBlocking {
        val first = LocalDate.of(2026, 8, 1)
        val dates = (0L..31L).map { first.plusDays(it) }
        val cache = InMemoryDailyStatsCache()
        val saved = DailyStatEntry(min = 0.05, max = 0.15, avg = 0.10, sum = 2.4, count = 24)
        cache.putBatch("EE", dates.filterNot { it == first.plusDays(15) }.associateWith { saved })
        val prices = CountingPriceRepositoryFake()
        val zone = BiddingZones.resolve("EE").zoneId

        PriceStatsRepositoryImpl(prices, cache).use { repository ->
            assertEquals(32, repository.getDailyStats("EE", first, dates.last()).getOrThrow().size)
            assertEquals(listOf(first.atStartOfDay(zone).toInstant() to first.plusDays(32).atStartOfDay(zone).toInstant()), prices.requestedRanges)
            repository.getDailyStats("EE", first, dates.last()).getOrThrow()
            assertEquals(1, prices.callCount)
        }
    }

    @Test
    fun `full window refresh recovers cooling gaps without extending cooldowns for unresolved gaps`() = runBlocking {
        val now = Instant.parse("2026-10-20T08:00:00Z")
        val today = LocalDate.of(2026, 10, 20)
        val dates = (0L..31L).map { today.minusDays(30).plusDays(it) }
        val gaps = dates.take(2)
        val zone = BiddingZones.resolve("EE").zoneId
        val saved = DailyStatEntry(min = 0.05, max = 0.15, avg = 0.10, sum = 2.4, count = 24)
        for (recover in listOf(false, true)) {
            val cache = InMemoryDailyStatsCache()
            cache.putBatch("EE", dates.filterNot { it in gaps || it == today }.associateWith { saved })
            val cooldowns = gaps.associateWith { StatsRefreshPolicy().missing(it, zone, now, null, MissingStatsReason.NO_DATA) }
            cache.putMissingBatch("EE", cooldowns)
            val requests = mutableListOf<Pair<Instant, Instant>>()
            val prices = object : EnergyPriceRepository {
                override suspend fun getPrices(countryCode: String, start: Instant, end: Instant, cacheResults: Boolean): Result<List<DomainEnergyPrice>> {
                    requests += start to end
                    return Result.success((if (recover) dates else listOf(today)).flatMap { dayPrices(it, zone) })
                }
            }
            PriceStatsRepositoryImpl(prices, cache, clock = Clock.fixed(now, ZoneOffset.UTC)).use { repository ->
                repeat(2) {
                    val result = repository.getDailyStats("EE", dates.first(), dates.last()).getOrThrow()
                    assertEquals(if (recover) 32 else 30, result.size)
                }
                assertEquals(listOf(dates.first().atStartOfDay(zone).toInstant() to dates.last().plusDays(1).atStartOfDay(zone).toInstant()), requests)
                gaps.forEach { gap ->
                    if (recover) assertNull(cache.getMissing("EE", gap))
                    else assertEquals(cooldowns.getValue(gap), cache.getMissing("EE", gap))
                }
                assertEquals(saved, cache.get("EE", dates.last()))
            }
        }
    }

    @Test
    fun `failed window refresh preserves previously cached days`() = runBlocking {
        val cache = InMemoryDailyStatsCache()
        val first = LocalDate.of(2026, 8, 1)
        val requested = mutableListOf<Instant>()
        var fail = true
        val failure = IllegalStateException("Provider unavailable")
        val saved = DailyStatEntry(min = 0.05, max = 0.15, avg = 0.10, sum = 2.4, count = 24)
        cache.putBatch("EE", (0L..6L).associate { first.plusDays(it) to saved })
        val prices = object : EnergyPriceRepository {
            override suspend fun getPrices(countryCode: String, start: Instant, end: Instant, cacheResults: Boolean): Result<List<DomainEnergyPrice>> {
                requested += start
                if (fail) return Result.failure(failure)
                return Result.success((0 until java.time.Duration.between(start, end).toHours().toInt()).map { DomainEnergyPrice(start.plusSeconds(it * 3600L), 0.1) })
            }
        }
        val repository = PriceStatsRepositoryImpl(prices, cache, errorCooldown = java.time.Duration.ZERO)
        assertEquals(failure, repository.getDailyStats("EE", first, first.plusDays(14)).exceptionOrNull())
        assertEquals((0L..6L).map { first.plusDays(it) }.toSet(), cache.getRange("EE", first, first.plusDays(14)).keys)
        fail = false
        assertEquals(15, repository.getDailyStats("EE", first, first.plusDays(14)).getOrThrow().size)
        assertEquals(2, requested.size)
        assertEquals(requested.first(), requested.last())
        assertEquals(saved, cache.get("EE", first))
    }

    @Test
    fun `getDailyStats populates cache on cold start and hits cache on warm start`() = runBlocking {
        val cache = InMemoryDailyStatsCache()
        val priceRepo = CountingPriceRepositoryFake()
        val statsRepo = PriceStatsRepositoryImpl(priceRepo, cache)

        val start = LocalDate.of(2026, 8, 1)
        val end = LocalDate.of(2026, 8, 3)

        // Cold start - fetches all 3 days in one range
        val firstResult = statsRepo.getDailyStats("EE", start, end)
        assertTrue(firstResult.isSuccess)
        val statsMap = firstResult.getOrThrow()
        assertEquals(3, statsMap.size)
        assertEquals(1, priceRepo.callCount)

        // Cache contains all 3 days
        assertEquals(3, cache.getRange("EE", start, end).size)

        // Warm start - no new price fetches
        val secondResult = statsRepo.getDailyStats("EE", start, end)
        assertTrue(secondResult.isSuccess)
        assertEquals(3, secondResult.getOrThrow().size)
        assertEquals(1, priceRepo.callCount)
    }

    @Test
    fun `getDailyStats refreshes the full window across cached days and preserves their values`() = runBlocking {
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
        // One full-window fetch fills both gaps without replacing the cached middle day.
        assertEquals(1, priceRepo.callCount)
        val zone = BiddingZones.resolve("EE").zoneId
        assertEquals(listOf(day1.atStartOfDay(zone).toInstant() to day3.plusDays(1).atStartOfDay(zone).toInstant()), priceRepo.requestedRanges)
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
        assertEquals(Instant.parse("2026-08-22T21:00:00Z"), summerEnd)

        // Winter date in Estonia (EET is UTC+2): 2026-01-15 start is 2026-01-14T22:00:00Z
        val winterDate = LocalDate.of(2026, 1, 15)
        statsRepo.getDailyStats("EE", winterDate, winterDate)

        assertEquals(2, priceRepo.requestedRanges.size)
        val (winterStart, winterEnd) = priceRepo.requestedRanges[1]
        assertEquals(Instant.parse("2026-01-14T22:00:00Z"), winterStart)
        assertEquals(Instant.parse("2026-01-15T22:00:00Z"), winterEnd)
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
}
