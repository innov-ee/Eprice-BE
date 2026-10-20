package ee.innov.eprice.data

import ee.innov.eprice.domain.EnergyPriceRepository
import ee.innov.eprice.domain.BiddingZones
import ee.innov.eprice.domain.model.DailyStatEntry
import ee.innov.eprice.domain.model.DomainEnergyPrice
import ee.innov.eprice.test.InMemoryDailyStatsCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Clock
import java.time.ZoneOffset
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConcurrentStatsTest {
    private val first = LocalDate.of(2026, 8, 1)

    @Test
    fun `cached and cooling subsets return without waiting for a failing larger fill`() = runBlocking {
        withTimeout(5000) {
            val now = Instant.parse("2026-10-20T08:00:00Z")
            val cache = InMemoryDailyStatsCache()
            val saved = DailyStatEntry(0.1, 0.1, 0.1, 2.4, 24)
            cache.put("IE", first, saved)
            val coolingDay = first.plusDays(1)
            cache.putMissingBatch("IE", mapOf(coolingDay to StatsRefreshPolicy().missing(
                coolingDay, BiddingZones.resolve("IE").zoneId, now, null, MissingStatsReason.NO_DATA
            )))
            val prices = GatedPrices().apply { failure = IllegalStateException("Provider failed") }
            PriceStatsRepositoryImpl(prices, cache, clock = Clock.fixed(now, ZoneOffset.UTC)).use { repository ->
                val larger = async { repository.getDailyStats("IE", first, first.plusDays(2)) }
                prices.entered.await()
                try {
                    assertEquals(mapOf(first to saved), repository.getDailyStats("IE", first, first).getOrThrow())
                    assertEquals(mapOf(first to saved), repository.getDailyStats("IE", first, coolingDay).getOrThrow())
                    assertTrue(repository.getDailyStats("IE", coolingDay, coolingDay).getOrThrow().isEmpty())
                    assertFalse(larger.isCompleted)
                } finally {
                    prices.release.complete(Unit)
                }
                assertSame(prices.failure, larger.await().exceptionOrNull())
                assertEquals(mapOf(first to saved), repository.getDailyStats("IE", first, coolingDay).getOrThrow())
                assertEquals(1, prices.requests.size)
            }
        }
    }

    private class GatedPrices : EnergyPriceRepository {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val requests = CopyOnWriteArrayList<Triple<String, Instant, Instant>>()
        var empty = false
        var failure: Exception? = null

        override suspend fun getPrices(countryCode: String, start: Instant, end: Instant, cacheResults: Boolean): Result<List<DomainEnergyPrice>> {
            requests += Triple(countryCode, start, end)
            if (countryCode == "IE" && requests.size == 1) {
                entered.complete(Unit)
                release.await()
            }
            failure?.let { return Result.failure(it) }
            return Result.success(if (empty) emptyList() else
                (0 until Duration.between(start, end).toHours().toInt()).map {
                    DomainEnergyPrice(start.plusSeconds(it * 3600L), 0.1)
                })
        }
    }

    @Test
    fun `ten simultaneous cold summaries share one full window fetch`() = runBlocking {
        withTimeout(5000) {
            val prices = GatedPrices()
            PriceStatsRepositoryImpl(prices, InMemoryDailyStatsCache()).use { repository ->
                val callers = List(10) {
                    async(start = CoroutineStart.UNDISPATCHED) { repository.getDailyStats("IE", first, first.plusDays(31)).getOrThrow() }
                }
                prices.entered.await()
                assertEquals(1, prices.requests.size)
                prices.release.complete(Unit)
                assertTrue(callers.awaitAll().all { it.size == 32 })
                assertEquals(1, prices.requests.size)
            }
        }
    }

    @Test
    fun `overlapping callers recheck cache then refresh their full window and return their own ranges`() = runBlocking {
        withTimeout(5000) {
            val prices = GatedPrices()
            PriceStatsRepositoryImpl(prices, InMemoryDailyStatsCache()).use { repository ->
                val initial = async(start = CoroutineStart.UNDISPATCHED) { repository.getDailyStats("IE", first, first.plusDays(6)).getOrThrow() }
                prices.entered.await()
                val overlap = async(start = CoroutineStart.UNDISPATCHED) { repository.getDailyStats("IE", first.plusDays(3), first.plusDays(9)).getOrThrow() }
                val subset = async(start = CoroutineStart.UNDISPATCHED) { repository.getDailyStats("IE", first.plusDays(1), first.plusDays(2)).getOrThrow() }
                prices.release.complete(Unit)
                assertEquals(7, initial.await().size)
                assertEquals((3L..9L).map { first.plusDays(it) }.toSet(), overlap.await().keys)
                assertEquals(setOf(first.plusDays(1), first.plusDays(2)), subset.await().keys)
                assertEquals(2, prices.requests.size)
                assertEquals(first.plusDays(3).atStartOfDay(BiddingZones.resolve("IE").zoneId).toInstant(), prices.requests[1].second)
                assertEquals(first.plusDays(10).atStartOfDay(BiddingZones.resolve("IE").zoneId).toInstant(), prices.requests[1].third)
            }
        }
    }

    @Test
    fun `caller cancellation does not cancel shared work and other regions do not wait`() = runBlocking {
        withTimeout(5000) {
            val prices = GatedPrices()
            PriceStatsRepositoryImpl(prices, InMemoryDailyStatsCache()).use { repository ->
                val original = async(start = CoroutineStart.UNDISPATCHED) { repository.getDailyStats("IE", first, first) }
                prices.entered.await()
                val follower = async(start = CoroutineStart.UNDISPATCHED) { repository.getDailyStats("IE", first, first).getOrThrow() }
                original.cancelAndJoin()
                assertEquals(1, repository.getDailyStats("GB", first, first).getOrThrow().size)
                assertFalse(follower.isCompleted)
                prices.release.complete(Unit)
                assertEquals(1, follower.await().size)
                assertEquals(1, prices.requests.count { it.first == "IE" })
            }
        }
    }

    @Test
    fun `missing data and failures are shared and subsequent calls respect cooldowns`() = runBlocking {
        withTimeout(5000) {
            for (fail in listOf(false, true)) {
                val prices = GatedPrices().apply {
                    empty = true
                    if (fail) failure = IllegalStateException("Provider failed")
                }
                PriceStatsRepositoryImpl(prices, InMemoryDailyStatsCache()).use { repository ->
                    val callers = List(10) {
                        async(start = CoroutineStart.UNDISPATCHED) { repository.getDailyStats("IE", first, first) }
                    }
                    prices.entered.await()
                    prices.release.complete(Unit)
                    callers.awaitAll().forEach {
                        if (fail) assertSame(prices.failure, it.exceptionOrNull()) else assertTrue(it.getOrThrow().isEmpty())
                    }
                    assertEquals(1, prices.requests.size)
                    prices.empty = false
                    prices.failure = null
                    val next = repository.getDailyStats("IE", first, first)
                    if (fail) assertTrue(next.isFailure) else assertTrue(next.getOrThrow().isEmpty())
                    assertEquals(1, prices.requests.size)
                }
            }
        }
    }

    @Test
    fun `worker cancellation preserves cached days and a new repository can retry the full window`() = runBlocking {
        withTimeout(5000) {
            val prices = GatedPrices()
            val cache = InMemoryDailyStatsCache()
            cache.putBatch("IE", (0L..6L).associate { first.plusDays(it) to DailyStatEntry(0.1, 0.1, 0.1, 2.4, 24) })
            val repository = PriceStatsRepositoryImpl(prices, cache)
            val caller = async(start = CoroutineStart.UNDISPATCHED) { repository.getDailyStats("IE", first, first.plusDays(13)) }
            prices.entered.await()
            repository.close()
            caller.join()
            assertTrue(caller.isCancelled)
            assertEquals(7, cache.getRange("IE", first, first.plusDays(13)).size)
            prices.release.complete(Unit)
            PriceStatsRepositoryImpl(prices, cache).use { retry ->
                assertEquals(14, retry.getDailyStats("IE", first, first.plusDays(13)).getOrThrow().size)
            }
            assertEquals(2, prices.requests.size)
            assertEquals(prices.requests[0], prices.requests[1])
        }
    }

    @Test
    fun `closing repository cancels shared provider work`() = runBlocking {
        withTimeout(5000) {
            val prices = GatedPrices()
            val repository = PriceStatsRepositoryImpl(prices, InMemoryDailyStatsCache())
            val caller = async(start = CoroutineStart.UNDISPATCHED) { repository.getDailyStats("IE", first, first) }
            prices.entered.await()
            repository.close()
            caller.join()
            assertTrue(caller.isCancelled)
        }
    }
}
