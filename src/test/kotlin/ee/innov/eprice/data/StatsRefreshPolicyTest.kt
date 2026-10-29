package ee.innov.eprice.data

import ee.innov.eprice.domain.EnergyPriceRepository
import ee.innov.eprice.domain.model.DomainEnergyPrice
import ee.innov.eprice.test.InMemoryDailyStatsCache
import ee.innov.eprice.test.dayPrices
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StatsRefreshPolicyTest {
    private val zone = ZoneId.of("Europe/Dublin")
    private val now = Instant.parse("2026-10-20T08:00:00Z")
    private val tomorrow = LocalDate.of(2026, 10, 21)

    @Test
    fun `tomorrow is checked again at local noon and then every fifteen minutes`() {
        val policy = StatsRefreshPolicy()
        val first = policy.missing(tomorrow, zone, now, null, MissingStatsReason.NO_DATA)
        assertEquals(Instant.parse("2026-10-20T11:00:00Z"), first.nextCheckAt)
        val second = policy.missing(tomorrow, zone, first.nextCheckAt, first, MissingStatsReason.PARTIAL)
        assertEquals(first.nextCheckAt.plusSeconds(900), second.nextCheckAt)
        // After DST the same local noon corresponds to 12:00 UTC.
        assertEquals(Instant.parse("2026-10-26T12:00:00Z"), policy.missing(
            LocalDate.of(2026, 10, 27), zone, Instant.parse("2026-10-26T08:00:00Z"), null, MissingStatsReason.NO_DATA
        ).nextCheckAt)
    }

    @Test
    fun `historical gaps back off from six hours to a capped seven days`() {
        val policy = StatsRefreshPolicy()
        var entry: MissingDailyStats? = null
        repeat(12) { attempt ->
            entry = policy.missing(tomorrow.minusDays(10), zone, now, entry, MissingStatsReason.NO_DATA)
            val expected = minOf(Duration.ofHours(6).multipliedBy(1L shl attempt), Duration.ofDays(7))
            assertEquals(now.plus(expected), entry!!.nextCheckAt)
        }
    }

    @Test
    fun `recent misses reset when a day becomes historical at regional midnight`() {
        val policy = StatsRefreshPolicy()
        val date = LocalDate.of(2026, 10, 24)
        for (regionZone in listOf(zone, ZoneId.of("Europe/Tallinn"))) {
            val lateYesterday = date.plusDays(1).atTime(22, 0).atZone(regionZone).toInstant()
            var entry: MissingDailyStats? = null
            repeat(8) { attempt ->
                val checkedAt = lateYesterday.plusSeconds(attempt * 900L)
                entry = policy.missing(date, regionZone, checkedAt, entry, MissingStatsReason.PARTIAL)
                assertEquals(checkedAt.plusSeconds(900), entry!!.nextCheckAt)
            }
            assertEquals(8, entry!!.consecutiveMisses)

            // Tallinn reaches the historical window before UTC midnight, across the DST weekend.
            val historicalStart = date.plusDays(2).atStartOfDay(regionZone).toInstant()
            val historical = policy.missing(date, regionZone, historicalStart, entry, MissingStatsReason.NO_DATA)
            assertEquals(1, historical.consecutiveMisses)
            assertEquals(historicalStart.plus(Duration.ofHours(6)), historical.nextCheckAt)

            val retry = policy.missing(date, regionZone, historical.nextCheckAt, historical, MissingStatsReason.NO_DATA)
            assertEquals(2, retry.consecutiveMisses)
            assertEquals(historical.nextCheckAt.plus(Duration.ofHours(12)), retry.nextCheckAt)
        }
    }

    @Test
    fun `empty and partial days share cooldown across repository instances and recover when due`() = runBlocking {
        for (partial in listOf(false, true)) {
            val cache = InMemoryDailyStatsCache()
            var calls = 0
            var published = false
            val prices = object : EnergyPriceRepository {
                override suspend fun getPrices(countryCode: String, start: Instant, end: Instant, cacheResults: Boolean): Result<List<DomainEnergyPrice>> {
                    calls++
                    val complete = dayPrices(tomorrow, zone)
                    return Result.success(if (published) complete else if (partial) complete.take(2) else emptyList())
                }
            }
            fun repository(at: Instant) = PriceStatsRepositoryImpl(prices, cache, clock = Clock.fixed(at, ZoneOffset.UTC))
            val initial = repository(now).getDailyStats("IE", tomorrow, tomorrow).getOrThrow()
            assertTrue(initial.isEmpty())
            assertNull(cache.getMissing("IE", tomorrow)!!.partialStats)
            val missing = cache.getMissing("IE", tomorrow)!!
            assertEquals(if (partial) MissingStatsReason.PARTIAL else MissingStatsReason.NO_DATA, missing.reason)
            assertEquals(now, missing.checkedAt)
            repeat(10) {
                assertEquals(initial, repository(missing.nextCheckAt.minusSeconds(1)).getDailyStats("IE", tomorrow, tomorrow).getOrThrow())
            }
            assertEquals(1, calls)
            published = true
            assertEquals(setOf(tomorrow), repository(missing.nextCheckAt).getDailyStats("IE", tomorrow, tomorrow).getOrThrow().keys)
            assertEquals(2, calls)
            assertNull(cache.getMissing("IE", tomorrow))
            repository(missing.nextCheckAt.plusSeconds(3600)).getDailyStats("IE", tomorrow, tomorrow).getOrThrow()
            assertEquals(2, calls)
        }
    }

    @Test
    fun `provider errors are failures and do not create availability cooldowns`() = runBlocking {
        val cache = InMemoryDailyStatsCache()
        val failure = IllegalStateException("Upstream failed")
        var calls = 0
        val prices = object : EnergyPriceRepository {
            override suspend fun getPrices(countryCode: String, start: Instant, end: Instant, cacheResults: Boolean): Result<List<DomainEnergyPrice>> {
                calls++
                return Result.failure(failure)
            }
        }
        val repository = PriceStatsRepositoryImpl(prices, cache, clock = Clock.fixed(now, ZoneOffset.UTC))
        repeat(2) { assertSame(failure, repository.getDailyStats("IE", tomorrow, tomorrow).exceptionOrNull()) }
        assertNull(cache.getMissing("IE", tomorrow))
        assertEquals(1, calls)
    }

    @Test
    fun `cooldowns skip only matching region and dates and cache clear permits another check`() = runBlocking {
        val cache = InMemoryDailyStatsCache()
        var calls = 0
        val prices = object : EnergyPriceRepository {
            override suspend fun getPrices(countryCode: String, start: Instant, end: Instant, cacheResults: Boolean): Result<List<DomainEnergyPrice>> {
                calls++
                return Result.success(emptyList())
            }
        }
        val repository = PriceStatsRepositoryImpl(prices, cache, clock = Clock.fixed(now, ZoneOffset.UTC))
        repository.getDailyStats("IE", tomorrow, tomorrow).getOrThrow()
        repository.getDailyStats("IE", tomorrow.minusDays(1), tomorrow).getOrThrow()
        repository.getDailyStats("GB", tomorrow, tomorrow).getOrThrow()
        assertEquals(3, calls)
        cache.clear()
        repository.getDailyStats("IE", tomorrow, tomorrow).getOrThrow()
        assertEquals(4, calls)
    }
}
