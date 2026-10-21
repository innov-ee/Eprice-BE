package ee.innov.eprice.data

import ee.innov.eprice.data.elering.EleringService
import ee.innov.eprice.data.entsoe.EntsoeService
import ee.innov.eprice.domain.BiddingZones
import ee.innov.eprice.domain.model.NoDataFoundException
import ee.innov.eprice.test.InMemoryDailyStatsCache
import ee.innov.eprice.test.NoOpPriceCache
import ee.innov.eprice.test.dayPrices
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatsProviderFallbackTest {
    private val region = BiddingZones.resolve("EE")
    private val first = LocalDate.of(2026, 10, 23)
    private val dates = (0L..6L).map { first.plusDays(it) }
    private val start = first.atStartOfDay(region.zoneId).toInstant()
    private val end = first.plusDays(7).atStartOfDay(region.zoneId).toInstant()

    @Test
    fun `batched stats fall back only for consecutive empty days including DST`() = runBlocking {
        // Exercise gaps at either boundary and in the middle, including the 25-hour DST day.
        for (gap in listOf(dates.take(2), dates.subList(2, 4), dates.takeLast(2))) {
            val elering = mockk<EleringService>()
            val entsoe = mockk<EntsoeService>()
            val cache = InMemoryDailyStatsCache()
            val gapStart = gap.first().atStartOfDay(region.zoneId).toInstant()
            val gapEnd = gap.last().plusDays(1).atStartOfDay(region.zoneId).toInstant()
            coEvery { elering.fetchPrices("EE", start, end) } returns
                dates.filterNot { it in gap }.flatMap { dayPrices(it, region.zoneId, price = 0.1) }
            coEvery { entsoe.fetchPrices(region.eic, gapStart, gapEnd) } returns
                gap.flatMap { dayPrices(it, region.zoneId, price = 0.2) }
            val prices = EnergyPriceRepositoryImpl(entsoe, elering, NoOpPriceCache())

            PriceStatsRepositoryImpl(prices, cache).use { stats ->
                val result = stats.getDailyStats("EE", first, dates.last()).getOrThrow()
                assertEquals(dates.toSet(), result.keys)
                gap.forEach {
                    assertEquals(0.2, result.getValue(it).min)
                    assertNull(cache.getMissing("EE", it))
                }
                assertEquals(25 * 3600L, result.getValue(LocalDate.of(2026, 10, 25)).durationSeconds)
                stats.getDailyStats("EE", first, dates.last()).getOrThrow()
            }
            coVerify(exactly = 1) { elering.fetchPrices(any(), any(), any()) }
            coVerify(exactly = 1) { entsoe.fetchPrices(region.eic, gapStart, gapEnd) }
            coVerify(exactly = 1) { entsoe.fetchPrices(any(), any(), any()) }
        }
    }

    @Test
    fun `empty fallback creates cooldown and does not repeat provider calls`() = runBlocking {
        val elering = mockk<EleringService>()
        val entsoe = mockk<EntsoeService>()
        val cache = InMemoryDailyStatsCache()
        val gap = dates.last()
        val gapStart = gap.atStartOfDay(region.zoneId).toInstant()
        coEvery { elering.fetchPrices("EE", start, end) } returns
            dates.dropLast(1).flatMap { dayPrices(it, region.zoneId) }
        coEvery { entsoe.fetchPrices(region.eic, gapStart, end) } throws NoDataFoundException("Not published")
        val prices = EnergyPriceRepositoryImpl(entsoe, elering, NoOpPriceCache())

        PriceStatsRepositoryImpl(prices, cache).use { stats ->
            repeat(2) {
                assertEquals(dates.dropLast(1).toSet(), stats.getDailyStats("EE", first, gap).getOrThrow().keys)
            }
            assertEquals(MissingStatsReason.NO_DATA, cache.getMissing("EE", gap)?.reason)
        }
        coVerify(exactly = 1) { elering.fetchPrices(any(), any(), any()) }
        coVerify(exactly = 1) { entsoe.fetchPrices(any(), any(), any()) }
    }

    @Test
    fun `fallback failure remains a failure without recording a missing day`() = runBlocking {
        val elering = mockk<EleringService>()
        val entsoe = mockk<EntsoeService>()
        val cache = InMemoryDailyStatsCache()
        coEvery { elering.fetchPrices("EE", start, end) } returns
            dates.dropLast(1).flatMap { dayPrices(it, region.zoneId) }
        coEvery { entsoe.fetchPrices(any(), any(), any()) } throws IllegalStateException("Provider unavailable")
        val prices = EnergyPriceRepositoryImpl(entsoe, elering, NoOpPriceCache())

        PriceStatsRepositoryImpl(prices, cache).use { stats ->
            assertTrue(stats.getDailyStats("EE", first, dates.last()).isFailure)
            assertNull(cache.getMissing("EE", dates.last()))
        }
        coVerify(exactly = 1) { entsoe.fetchPrices(any(), any(), any()) }
    }
}
