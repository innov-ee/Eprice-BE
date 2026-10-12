package ee.innov.eprice.data

import ee.innov.eprice.domain.GetRollingAveragePriceUseCase
import ee.innov.eprice.domain.GetPriceStatisticsUseCase.PriceStatistics
import ee.innov.eprice.domain.model.DailyStatEntry
import ee.innov.eprice.domain.model.DomainEnergyPrice
import ee.innov.eprice.domain.model.NoDataFoundException
import ee.innov.eprice.test.FixedPriceRepository
import ee.innov.eprice.test.InMemoryDailyAveragePriceCache
import ee.innov.eprice.test.InMemoryDailyStatsCache
import ee.innov.eprice.test.dayPrices
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DailyPriceStatisticsRegressionTest {
    private val date = LocalDate.of(2026, 8, 22)
    private val zone = ZoneId.of("Europe/Berlin")
    private val clock = Clock.fixed(Instant.parse("2026-08-23T12:00:00Z"), ZoneOffset.UTC)

    @Test
    @Tag("known-bug")
    fun `equal duration days have equal weight across an hourly to quarter hourly transition`() {
        // Two ordinary 24-hour days: one hourly at 0.10, one quarter hourly at 0.30.
        // Sample weighting gives 0.26; the average over elapsed time is 0.20.
        val stats = PriceStatistics.compute(
            "DE", date, date.plusDays(1), 2,
            listOf(
                DailyStatEntry(0.1, 0.1, 0.1, 2.4, 24),
                DailyStatEntry(0.3, 0.3, 0.3, 28.8, 96)
            )
        )!!
        assertEquals(0.2, stats.averagePrice, 1e-12)
    }

    private fun oversizedResponse(): List<DomainEnergyPrice> {
        val start = date.atStartOfDay(zone).toInstant()
        val next = date.plusDays(1).atStartOfDay(zone).toInstant()
        // Include values on both sides; midnight belongs to the next day.
        return listOf(DomainEnergyPrice(start.minusSeconds(900), -10.0)) +
            dayPrices(date, zone) + listOf(DomainEnergyPrice(next, 10.0))
    }

    @Test
    @Tag("known-bug")
    fun `daily statistics and cache contain only the requested local day`() = runBlocking {
        val cache = InMemoryDailyStatsCache()
        val repo = PriceStatsRepositoryImpl(FixedPriceRepository(Result.success(oversizedResponse())), cache)
        val stats = repo.getDailyStats("DE", date, date).getOrThrow().getValue(date)
        assertEquals(0.1, stats.min, 1e-12)
        assertEquals(0.1, stats.max, 1e-12)
        assertEquals(0.1, stats.avg, 1e-12)
        assertEquals(9.6, stats.sum, 1e-12)
        assertEquals(96, stats.count)
        assertEquals(stats, cache.get("DE", date))
    }

    @Test
    @Tag("known-bug")
    fun `rolling average and cache exclude prices outside the requested local day`() = runBlocking {
        val cache = InMemoryDailyAveragePriceCache()
        val useCase = GetRollingAveragePriceUseCase(FixedPriceRepository(Result.success(oversizedResponse())), cache, clock)
        val average = useCase.execute("DE", 1).getOrThrow()
        assertEquals(0.1, average.averagePrice, 1e-12)
        assertEquals(1, average.daysCalculated)
        assertEquals(0.1, cache.get("DE", date)!!, 1e-12)
    }

    @Test
    @Tag("known-bug")
    fun `daily statistics propagate provider failures and do not cache them`() = runBlocking {
        val failure = IllegalStateException("Provider unavailable")
        val cache = InMemoryDailyStatsCache()
        val repo = PriceStatsRepositoryImpl(FixedPriceRepository(Result.failure(failure)), cache)
        val result = repo.getDailyStats("DE", date, date)
        assertTrue(result.isFailure, "A provider failure must not become successful missing data")
        assertSame(failure, result.exceptionOrNull())
        assertTrue(cache.store.isEmpty())
    }

    @Test
    @Tag("known-bug")
    fun `rolling average preserves provider failure rather than reporting no data`() = runBlocking {
        val failure = IllegalStateException("Provider unavailable")
        val cache = InMemoryDailyAveragePriceCache()
        val useCase = GetRollingAveragePriceUseCase(FixedPriceRepository(Result.failure(failure)), cache, clock)
        val result = useCase.execute("DE", 1)
        assertSame(failure, result.exceptionOrNull())
        assertTrue(cache.store.isEmpty())
    }

    @Test
    fun `unpublished prices remain missing data and are not cached`() = runBlocking {
        val prices = FixedPriceRepository(Result.success(emptyList()))
        val statsCache = InMemoryDailyStatsCache()
        assertTrue(PriceStatsRepositoryImpl(prices, statsCache).getDailyStats("DE", date, date).getOrThrow().isEmpty())
        val averageCache = InMemoryDailyAveragePriceCache()
        assertTrue(GetRollingAveragePriceUseCase(prices, averageCache, clock).execute("DE", 1).exceptionOrNull() is NoDataFoundException)
        assertTrue(statsCache.store.isEmpty())
        assertTrue(averageCache.store.isEmpty())
    }

    @Test
    fun `daily statistics accept complete quarter hourly DST days`() = runBlocking {
        listOf(LocalDate.of(2026, 3, 29) to 92, LocalDate.of(2026, 10, 25) to 100).forEach { (day, count) ->
            val prices = dayPrices(day, zone, price = -0.05)
            val cache = InMemoryDailyStatsCache()
            val repo = PriceStatsRepositoryImpl(FixedPriceRepository(Result.success(prices)), cache)
            val stats = repo.getDailyStats("DE", day, day).getOrThrow().getValue(day)
            assertEquals(count, stats.count)
            assertEquals(-0.05, stats.avg, 1e-12)
            assertEquals(stats, cache.get("DE", day))
        }
    }
}
