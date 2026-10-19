package ee.innov.eprice.data

import ee.innov.eprice.domain.GetPriceSummaryUseCase
import ee.innov.eprice.domain.model.PriceStatistics
import ee.innov.eprice.domain.model.DailyStatEntry
import ee.innov.eprice.domain.model.ApiError
import ee.innov.eprice.domain.model.DomainEnergyPrice
import ee.innov.eprice.domain.model.NoDataFoundException
import ee.innov.eprice.test.FixedPriceRepository
import ee.innov.eprice.test.InMemoryDailyStatsCache
import ee.innov.eprice.test.dayPrices
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DailyPriceStatisticsRegressionTest {
    private val date = LocalDate.of(2026, 8, 22)
    private val zone = ZoneId.of("Europe/Berlin")

    @Test
    fun `statistics repository excludes partial days before the summary consumes them`() = runBlocking {
        val clock = Clock.fixed(Instant.parse("2026-08-22T12:00:00Z"), ZoneOffset.UTC)
        listOf(-1L, 0L, 1L).forEach { partialDayOffset ->
            val partialDate = date.plusDays(partialDayOffset)
            val prices = (-1L..1L).flatMap { offset ->
                val day = date.plusDays(offset)
                val intervals = dayPrices(day, zone)
                if (day == partialDate) intervals.take(8) else intervals
            }
            val cache = InMemoryDailyStatsCache()
            val repository = PriceStatsRepositoryImpl(FixedPriceRepository(Result.success(prices)), cache)
            val result = GetPriceSummaryUseCase(repository, clock, rollingDays = 1).execute("DE_LU")

            if (partialDayOffset == 1L) {
                assertEquals(null, result.getOrThrow().tomorrow)
            } else {
                assertTrue(result.exceptionOrNull() is NoDataFoundException)
            }
            assertEquals(null, cache.get("DE_LU", partialDate))
        }
    }

    @Test
    fun `equal duration days have equal weight across an hourly to quarter hourly transition`() {
        // Two ordinary 24-hour days: one hourly at 0.10, one quarter hourly at 0.30.
        // Sample weighting gives 0.26; the average over elapsed time is 0.20.
        val stats = PriceStatistics.compute(
            "DE_LU", date, date.plusDays(1), 2,
            listOf(
                DailyStatEntry(0.1, 0.1, 0.1, 2.4, 24),
                DailyStatEntry(0.3, 0.3, 0.3, 28.8, 96, durationSeconds = 86400)
            )
        )!!
        assertEquals(0.2, stats.averagePrice, 1e-12)
    }

    private fun oversizedResponse(): List<DomainEnergyPrice> {
        val start = date.atStartOfDay(zone).toInstant()
        val next = date.plusDays(1).atStartOfDay(zone).toInstant()
        // Include values on both sides; midnight belongs to the next day.
        return listOf(DomainEnergyPrice(start.minusSeconds(900), -10.0, durationSeconds = 900)) +
            dayPrices(date, zone) + listOf(DomainEnergyPrice(next, 10.0))
    }

    @Test
    fun `daily statistics and cache contain only the requested local day`() = runBlocking {
        val cache = InMemoryDailyStatsCache()
        val repo = PriceStatsRepositoryImpl(FixedPriceRepository(Result.success(oversizedResponse())), cache)
        val stats = repo.getDailyStats("DE_LU", date, date).getOrThrow().getValue(date)
        assertEquals(0.1, stats.min, 1e-12)
        assertEquals(0.1, stats.max, 1e-12)
        assertEquals(0.1, stats.avg, 1e-12)
        assertEquals(9.6, stats.sum, 1e-12)
        assertEquals(96, stats.count)
        assertEquals(stats, cache.get("DE_LU", date))
    }

    @Test
    fun `typed provider errors remain failed Results in daily statistics`() = runBlocking {
        val cause = IllegalStateException("Provider unavailable")
        listOf(ApiError.Server(503, "Provider unavailable", cause), ApiError.Timeout("Timed out", cause)).forEach { error ->
            val prices = FixedPriceRepository(Result.failure(error))
            val stats = PriceStatsRepositoryImpl(prices, InMemoryDailyStatsCache()).getDailyStats("DE_LU", date, date)
            assertSame(error, stats.exceptionOrNull())
        }
    }

    @Test
    fun `daily statistics propagate provider failures and do not cache them`() = runBlocking {
        val failure = IllegalStateException("Provider unavailable")
        val cache = InMemoryDailyStatsCache()
        val repo = PriceStatsRepositoryImpl(FixedPriceRepository(Result.failure(failure)), cache)
        val result = repo.getDailyStats("DE_LU", date, date)
        assertTrue(result.isFailure, "A provider failure must not become successful missing data")
        assertSame(failure, result.exceptionOrNull())
        assertTrue(cache.store.isEmpty())
    }

    @Test
    fun `unpublished prices remain missing data and are not cached`() = runBlocking {
        val prices = FixedPriceRepository(Result.success(emptyList()))
        val statsCache = InMemoryDailyStatsCache()
        assertTrue(PriceStatsRepositoryImpl(prices, statsCache).getDailyStats("DE_LU", date, date).getOrThrow().isEmpty())
        assertTrue(statsCache.store.isEmpty())
    }

    @Test
    fun `mixed interval durations within one day use elapsed time rather than sample counts`() = runBlocking {
        val start = date.atStartOfDay(zone).toInstant()
        val prices = (0 until 12).map { DomainEnergyPrice(start.plusSeconds(it * 3600L), 0.1, 3600) } +
            (0 until 48).map { DomainEnergyPrice(start.plusSeconds(43200 + it * 900L), 0.3, 900) }
        val repo = PriceStatsRepositoryImpl(FixedPriceRepository(Result.success(prices)), InMemoryDailyStatsCache())
        val stats = repo.getDailyStats("DE_LU", date, date).getOrThrow().getValue(date)
        assertEquals(60, stats.count)
        assertEquals(86400L, stats.durationSeconds)
        assertEquals(0.2, stats.avg, 1e-12)
    }

    @Test
    fun `statistics averages give a 25 hour day its actual elapsed weight and reuse cached days`() = runBlocking {
        val dstDate = LocalDate.of(2026, 10, 25)
        val previous = dstDate.minusDays(1)
        val prices = dayPrices(previous, zone, stepSeconds = 3600, price = 0.1) +
            dayPrices(dstDate, zone, price = 0.3)
        val priceRepo = FixedPriceRepository(Result.success(prices))
        val cache = InMemoryDailyStatsCache()
        val daily = PriceStatsRepositoryImpl(priceRepo, cache)
            .getDailyStats("DE_LU", previous, dstDate).getOrThrow()
        val statistics = PriceStatistics.compute("DE_LU", daily, previous, dstDate, 2)!!
        val expected = (24 * 0.1 + 25 * 0.3) / 49
        assertEquals(expected, statistics.averagePrice, 1e-12)
        val cached = PriceStatsRepositoryImpl(
            FixedPriceRepository(Result.failure(IllegalStateException("Should not fetch cached days"))), cache
        ).getDailyStats("DE_LU", previous, dstDate).getOrThrow()
        assertEquals(expected, PriceStatistics.compute("DE_LU", cached, previous, dstDate, 2)!!.averagePrice, 1e-12)
    }

    @Test
    fun `daily statistics accept complete quarter hourly DST days`() = runBlocking {
        listOf(LocalDate.of(2026, 3, 29) to 92, LocalDate.of(2026, 10, 25) to 100).forEach { (day, count) ->
            val prices = dayPrices(day, zone, price = -0.05)
            val cache = InMemoryDailyStatsCache()
            val repo = PriceStatsRepositoryImpl(FixedPriceRepository(Result.success(prices)), cache)
            val stats = repo.getDailyStats("DE_LU", day, day).getOrThrow().getValue(day)
            assertEquals(count, stats.count)
            assertEquals(-0.05, stats.avg, 1e-12)
            assertEquals(stats, cache.get("DE_LU", day))
        }
    }
}
