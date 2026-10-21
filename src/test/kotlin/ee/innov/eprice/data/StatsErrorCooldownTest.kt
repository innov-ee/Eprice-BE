package ee.innov.eprice.data

import ee.innov.eprice.domain.BiddingZones
import ee.innov.eprice.domain.EnergyPriceRepository
import ee.innov.eprice.domain.model.DomainEnergyPrice
import ee.innov.eprice.test.InMemoryDailyStatsCache
import ee.innov.eprice.test.dayPrices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Test
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class StatsErrorCooldownTest {
    private val date = LocalDate.of(2026, 10, 20)

    private class MutableClock(var now: Instant = Instant.parse("2026-10-20T10:00:00Z")) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    }

    @Test
    fun `returned and thrown errors are reused until expiry without extending cooldown`() = runBlocking {
        for (failure in listOf(IllegalStateException("Upstream failed"), SerializationException("Invalid JSON"), SocketTimeoutException("Timed out"))) {
            val clock = MutableClock()
            val cache = InMemoryDailyStatsCache()
            var calls = 0
            var fail = true
            val prices = object : EnergyPriceRepository {
                override suspend fun getPrices(countryCode: String, start: Instant, end: Instant, cacheResults: Boolean): Result<List<DomainEnergyPrice>> {
                    calls++
                    if (fail) {
                        if (failure is IllegalStateException) return Result.failure(failure)
                        throw failure
                    }
                    return Result.success(dayPrices(date, BiddingZones.resolve(countryCode).zoneId))
                }
            }
            PriceStatsRepositoryImpl(prices, cache, clock = clock).use { repository ->
                repeat(10) { assertSame(failure, repository.getDailyStats("EE", date, date).exceptionOrNull()) }
                clock.now = clock.now.plusSeconds(29)
                assertSame(failure, repository.getDailyStats("EE", date, date).exceptionOrNull())
                assertEquals(1, calls)
                assertNull(cache.getMissing("EE", date))

                fail = false
                clock.now = clock.now.plusSeconds(1)
                assertEquals(setOf(date), repository.getDailyStats("EE", date, date).getOrThrow().keys)
                repository.getDailyStats("EE", date, date).getOrThrow()
                assertEquals(2, calls)
            }
        }
    }

    @Test
    fun `error cooldown covers other ranges in the region but allows cached days and other regions`() = runBlocking {
        val cache = InMemoryDailyStatsCache()
        val failure = IllegalStateException("Provider failed")
        var calls = 0
        val prices = object : EnergyPriceRepository {
            override suspend fun getPrices(countryCode: String, start: Instant, end: Instant, cacheResults: Boolean): Result<List<DomainEnergyPrice>> {
                calls++
                if (countryCode == "EE" && calls > 1) return Result.failure(failure)
                return Result.success(dayPrices(date, BiddingZones.resolve(countryCode).zoneId))
            }
        }
        PriceStatsRepositoryImpl(prices, cache, clock = MutableClock()).use { repository ->
            repository.getDailyStats("EE", date, date).getOrThrow()
            assertSame(failure, repository.getDailyStats("EE", date.plusDays(1), date.plusDays(1)).exceptionOrNull())
            assertSame(failure, repository.getDailyStats("EE", date.plusDays(5), date.plusDays(10)).exceptionOrNull())
            assertEquals(setOf(date), repository.getDailyStats("EE", date, date).getOrThrow().keys)
            assertEquals(setOf(date), repository.getDailyStats("FI", date, date).getOrThrow().keys)
            assertEquals(3, calls)
            assertNull(cache.getMissing("EE", date.plusDays(1)))
        }
    }

    @Test
    fun `cancelled provider work does not start an error cooldown`() = runBlocking {
        var calls = 0
        val prices = object : EnergyPriceRepository {
            override suspend fun getPrices(countryCode: String, start: Instant, end: Instant, cacheResults: Boolean): Result<List<DomainEnergyPrice>> {
                if (++calls == 1) throw CancellationException("Cancelled")
                return Result.success(dayPrices(date, BiddingZones.resolve(countryCode).zoneId))
            }
        }
        PriceStatsRepositoryImpl(prices, InMemoryDailyStatsCache(), clock = MutableClock()).use { repository ->
            assertFailsWith<CancellationException> { repository.getDailyStats("EE", date, date) }
            assertEquals(setOf(date), repository.getDailyStats("EE", date, date).getOrThrow().keys)
            assertEquals(2, calls)
        }
    }
}
