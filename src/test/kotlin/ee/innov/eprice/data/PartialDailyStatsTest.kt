package ee.innov.eprice.data

import ee.innov.eprice.domain.BiddingZones
import ee.innov.eprice.domain.EnergyPriceRepository
import ee.innov.eprice.domain.GetPriceSummaryUseCase
import ee.innov.eprice.domain.model.DomainEnergyPrice
import ee.innov.eprice.domain.model.NoDataFoundException
import ee.innov.eprice.test.InMemoryDailyStatsCache
import ee.innov.eprice.test.dayPrices
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartialDailyStatsTest {
    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
        override fun instant(): Instant = now
    }

    private class PriceSource(var prices: List<DomainEnergyPrice>) : EnergyPriceRepository {
        var calls = 0
        override suspend fun getPrices(
            countryCode: String, start: Instant, end: Instant, cacheResults: Boolean
        ): Result<List<DomainEnergyPrice>> {
            calls++
            return Result.success(prices)
        }
    }

    @Test
    fun `PT and IE summaries reuse partial today then replace it with the complete publication`() = runBlocking {
        val today = LocalDate.of(2026, 10, 21)
        for (country in listOf("PT", "IE")) {
            val zone = BiddingZones.resolve(country).zoneId
            val clock = MutableClock(Instant.parse("2026-10-21T06:00:00Z"))
            val complete = dayPrices(today, zone, stepSeconds = 3600).mapIndexed { index, price ->
                price.copy(pricePerKWh = when (index) { 22 -> 0.3; 23 -> 0.9; else -> 0.1 })
            }
            val yesterday = dayPrices(today.minusDays(1), zone)
            val source = PriceSource(yesterday + complete.dropLast(1))
            val cache = InMemoryDailyStatsCache()
            PriceStatsRepositoryImpl(source, cache, clock = clock).use { repository ->
                val useCase = GetPriceSummaryUseCase(repository, clock, rollingDays = 1)
                val initial = useCase.execute(country).getOrThrow()
                assertFalse(initial.today.isComplete)
                assertEquals(23 * 3600L, initial.today.coverageSeconds)
                assertEquals((22 * 0.1 + 0.3) / 23, initial.today.averagePrice, 1e-12)
                assertEquals(0.3, initial.today.maxPrice)
                assertTrue(initial.yesterday.isComplete)
                assertTrue(initial.rolling.isComplete)
                assertNull(initial.tomorrow)
                assertNull(cache.get(country, today))
                assertEquals(MissingStatsReason.PARTIAL, cache.getMissing(country, today)!!.reason)

                // Metadata must be present even with the default JSON serializer.
                val json = Json.parseToJsonElement(Json.encodeToString(initial)).jsonObject
                assertEquals("false", json.getValue("today").jsonObject.getValue("isComplete").jsonPrimitive.content)
                assertEquals("82800", json.getValue("today").jsonObject.getValue("coverageSeconds").jsonPrimitive.content)
                assertEquals("true", json.getValue("yesterday").jsonObject.getValue("isComplete").jsonPrimitive.content)

                source.prices = yesterday + complete
                clock.now = clock.now.plusSeconds(899)
                assertEquals(initial, useCase.execute(country).getOrThrow())
                assertEquals(1, source.calls)
                PriceStatsRepositoryImpl(source, cache, clock = clock).use { restarted ->
                    assertEquals(initial, GetPriceSummaryUseCase(restarted, clock, rollingDays = 1).execute(country).getOrThrow())
                    assertEquals(1, source.calls)
                }

                clock.now = clock.now.plusSeconds(1)
                val updated = useCase.execute(country).getOrThrow()
                assertTrue(updated.today.isComplete)
                assertEquals(86400L, updated.today.coverageSeconds)
                assertEquals(0.9, updated.today.maxPrice)
                assertEquals((22 * 0.1 + 0.3 + 0.9) / 24, updated.today.averagePrice, 1e-12)
                assertTrue(cache.get(country, today)!!.isComplete)
                assertNull(cache.getMissing(country, today))
                assertEquals(2, source.calls)

                useCase.execute(country).getOrThrow()
                assertEquals(2, source.calls)
            }
        }
    }

    @Test
    fun `partial tomorrow survives rollover to today and becomes complete after refresh`() = runBlocking {
        val date = LocalDate.of(2026, 10, 21)
        for (country in listOf("PT", "IE")) {
            val zone = BiddingZones.resolve(country).zoneId
            val midnight = date.atStartOfDay(zone).toInstant()
            val clock = MutableClock(midnight.minusSeconds(60))
            val complete = dayPrices(date, zone)
            val source = PriceSource(dayPrices(date.minusDays(2), zone) + dayPrices(date.minusDays(1), zone) + complete.dropLast(4))
            val cache = InMemoryDailyStatsCache()
            PriceStatsRepositoryImpl(source, cache, clock = clock).use { repository ->
                val useCase = GetPriceSummaryUseCase(repository, clock, rollingDays = 1)
                val tomorrow = useCase.execute(country).getOrThrow().tomorrow!!
                assertFalse(tomorrow.isComplete)
                assertEquals(82800L, tomorrow.coverageSeconds)
                assertNull(cache.get(country, date))
                val json = Json.parseToJsonElement(Json.encodeToString(tomorrow)).jsonObject
                assertEquals("false", json.getValue("isComplete").jsonPrimitive.content)

                clock.now = midnight
                val today = repository.getDailyStats(country, date, date).getOrThrow().getValue(date)
                assertFalse(today.isComplete)
                assertEquals(tomorrow.coverageSeconds, today.durationSeconds)
                assertEquals(1, source.calls)

                source.prices = source.prices + complete.takeLast(4) + dayPrices(date.plusDays(1), zone).dropLast(4)
                clock.now = midnight.plusSeconds(840) // Original 15-minute cooldown has expired.
                val updated = useCase.execute(country).getOrThrow()
                assertTrue(updated.today.isComplete)
                assertEquals(86400L, updated.today.coverageSeconds)
                assertFalse(updated.tomorrow!!.isComplete)
                assertEquals(82800L, updated.tomorrow!!.coverageSeconds)
                assertNull(cache.getMissing(country, date))
                assertEquals(2, source.calls)
            }
        }
    }

    @Test
    fun `partial dates beyond tomorrow remain unavailable`() = runBlocking {
        val date = LocalDate.of(2026, 10, 21)
        val zone = BiddingZones.resolve("PT").zoneId
        val clock = Clock.fixed(Instant.parse("2026-10-21T06:00:00Z"), ZoneOffset.UTC)
        val dates = (-2L..2L).map { date.plusDays(it) }
        val source = PriceSource(dates.flatMap { dayPrices(it, zone).dropLast(4) })
        val cache = InMemoryDailyStatsCache()
        PriceStatsRepositoryImpl(source, cache, clock = clock).use { repository ->
            val stats = repository.getDailyStats("PT", dates.first(), dates.last()).getOrThrow()
            assertEquals(setOf(date, date.plusDays(1)), stats.keys)
            assertTrue(stats.values.all { !it.isComplete })
            assertTrue(cache.getRange("PT", dates.first(), dates.last()).isEmpty())
        }
    }

    @Test
    fun `partial today is never served as yesterday after local midnight even during cooldown`() = runBlocking {
        val date = LocalDate.of(2026, 10, 21)
        for (country in listOf("PT", "IE", "EE")) {
            val zone = BiddingZones.resolve(country).zoneId
            val midnight = date.plusDays(1).atStartOfDay(zone).toInstant()
            val clock = MutableClock(midnight.minusSeconds(60))
            val source = PriceSource(dayPrices(date, zone).dropLast(4))
            val cache = InMemoryDailyStatsCache()
            PriceStatsRepositoryImpl(source, cache, clock = clock).use { repository ->
                assertFalse(repository.getDailyStats(country, date, date).getOrThrow().getValue(date).isComplete)
                clock.now = midnight
                assertTrue(repository.getDailyStats(country, date, date).getOrThrow().isEmpty())
                assertEquals(1, source.calls)
                // Complete rolling data exists, so the summary must fail specifically on yesterday.
                source.prices = dayPrices(date.minusDays(1), zone) + source.prices + dayPrices(date.plusDays(1), zone)
                val result = GetPriceSummaryUseCase(repository, clock, rollingDays = 2).execute(country)
                assertTrue(result.exceptionOrNull() is NoDataFoundException)
                assertTrue(result.exceptionOrNull()!!.message!!.contains("yesterday"))
                assertNull(cache.get(country, date))
            }
        }
    }

    @Test
    fun `completeness follows DST day duration rather than a fixed 24 hour count`() = runBlocking {
        val zone = BiddingZones.resolve("IE").zoneId
        for ((date, hours) in listOf(LocalDate.of(2026, 3, 29) to 23, LocalDate.of(2026, 10, 25) to 25)) {
            val clock = MutableClock(date.atTime(9, 0).atZone(zone).toInstant())
            val complete = dayPrices(date, zone, stepSeconds = 1800)
            val source = PriceSource(complete.dropLast(2))
            val cache = InMemoryDailyStatsCache()
            PriceStatsRepositoryImpl(source, cache, clock = clock).use { repository ->
                val partial = repository.getDailyStats("IE", date, date).getOrThrow().getValue(date)
                assertFalse(partial.isComplete)
                assertEquals((hours - 1) * 3600L, partial.durationSeconds)
                source.prices = complete
                clock.now = clock.now.plusSeconds(900)
                val full = repository.getDailyStats("IE", date, date).getOrThrow().getValue(date)
                assertTrue(full.isComplete)
                assertEquals(hours * 3600L, full.durationSeconds)
            }
        }
    }

    @Test
    fun `empty and overlapping prices cannot become provisional statistics`() = runBlocking {
        val date = LocalDate.of(2026, 10, 21)
        val zone = BiddingZones.resolve("IE").zoneId
        val intervals = dayPrices(date, zone).take(8)
        for (prices in listOf(emptyList(), intervals + intervals.first())) {
            val clock = Clock.fixed(Instant.parse("2026-10-21T06:00:00Z"), ZoneOffset.UTC)
            val cache = InMemoryDailyStatsCache()
            PriceStatsRepositoryImpl(PriceSource(prices), cache, clock = clock).use { repository ->
                assertTrue(repository.getDailyStats("IE", date, date).getOrThrow().isEmpty())
                assertNull(cache.getMissing("IE", date)!!.partialStats)
            }
        }
    }
}
