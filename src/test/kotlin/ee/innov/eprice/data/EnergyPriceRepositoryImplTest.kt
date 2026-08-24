package ee.innov.eprice.data

import com.fasterxml.jackson.dataformat.xml.XmlMapper
import ee.innov.eprice.data.elering.EleringService
import ee.innov.eprice.data.entsoe.EntsoeService
import ee.innov.eprice.domain.CountryZoneProvider
import ee.innov.eprice.domain.model.DomainEnergyPrice
import ee.innov.eprice.monitoring.ServiceMonitor
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

class EnergyPriceRepositoryImplTest {

    private val tempFiles = mutableListOf<Path>()

    @AfterEach
    fun tearDown() {
        tempFiles.forEach { Files.deleteIfExists(it) }
        tempFiles.clear()
    }

    private fun createTempCacheFile(prefix: String): Path {
        val file = Files.createTempFile(prefix, ".json")
        tempFiles.add(file)
        return file
    }

    private class RecordingPriceCache(private val delegate: PriceCache) : PriceCache {
        val putInvocations = mutableListOf<PutCall>()

        data class PutCall(val key: String, val prices: List<DomainEnergyPrice>, val isComplete: Boolean)

        override fun get(key: String): List<DomainEnergyPrice>? = delegate.get(key)

        override fun put(key: String, prices: List<DomainEnergyPrice>, isComplete: Boolean) {
            putInvocations.add(PutCall(key, prices, isComplete))
            delegate.put(key, prices, isComplete)
        }

        override fun clear() = delegate.clear()
    }

    private fun createEleringResponseJson(prices: List<Pair<Long, Double>>): String {
        val rows = prices.joinToString(",") { (timestamp, price) ->
            """{"timestamp": $timestamp, "price": $price}"""
        }
        return """{"success": true, "data": {"ee": [$rows]}}"""
    }

    @Test
    fun `getPrices caches complete dataset indefinitely`() = runBlocking<Unit> {
        val networkCallCount = AtomicInteger(0)
        val zoneId = CountryZoneProvider.getZoneId("EE")
        val d1 = LocalDate.of(2026, 8, 22)
        val d2 = LocalDate.of(2026, 8, 23)
        val d3 = LocalDate.of(2026, 8, 24)

        val start = d1.atStartOfDay(zoneId).toInstant()
        val end = d3.plusDays(1).atStartOfDay(zoneId).minusSeconds(1).toInstant()

        // Generate complete 24h data for each day (72 hours total)
        val allPoints = mutableListOf<Pair<Long, Double>>()
        listOf(d1, d2, d3).forEach { d ->
            val dayStart = d.atStartOfDay(zoneId).toInstant()
            (0 until 24).forEach { h ->
                val epochSec = dayStart.plusSeconds(h * 3600L).epochSecond
                allPoints.add(epochSec to 100.0)
            }
        }

        val eleringJson = createEleringResponseJson(allPoints)

        val mockEngine = MockEngine {
            networkCallCount.incrementAndGet()
            respond(
                content = eleringJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val httpClient = HttpClient(mockEngine)
        val eleringService = EleringService(httpClient)
        val entsoeService = EntsoeService(httpClient, XmlMapper(), "DUMMY_KEY")

        val tempFile = createTempCacheFile("repo-cache-test")
        val inMemoryCache = InMemoryPriceCache(cacheFile = tempFile)
        val recordingCache = RecordingPriceCache(inMemoryCache)
        val monitor = ServiceMonitor()

        val repository = EnergyPriceRepositoryImpl(
            entsoeService = entsoeService,
            eleringService = eleringService,
            cache = recordingCache,
            monitor = monitor
        )

        // First call - cold cache, fetches from network
        val firstResult = repository.getPrices("EE", start, end, cacheResults = true)
        assertTrue(firstResult.isSuccess)
        assertEquals(72, firstResult.getOrThrow().size)
        assertEquals(1, networkCallCount.get())
        assertEquals(1, recordingCache.putInvocations.size)
        assertTrue(recordingCache.putInvocations[0].isComplete, "Complete 3-day data should be marked isComplete=true")

        // Second call - warm cache, served from cache with zero network calls
        val secondResult = repository.getPrices("EE", start, end, cacheResults = true)
        assertTrue(secondResult.isSuccess)
        assertEquals(72, secondResult.getOrThrow().size)
        assertEquals(1, networkCallCount.get(), "Network should not be called again on cache hit")
    }

    @Test
    fun `getPrices caches incomplete dataset with temporary TTL to throttle upstream requests`() = runBlocking<Unit> {
        val networkCallCount = AtomicInteger(0)
        val zoneId = CountryZoneProvider.getZoneId("EE")
        val d1 = LocalDate.of(2026, 8, 22)
        val d2 = LocalDate.of(2026, 8, 23)
        val d3 = LocalDate.of(2026, 8, 24)

        val start = d1.atStartOfDay(zoneId).toInstant()
        val end = d3.plusDays(1).atStartOfDay(zoneId).minusSeconds(1).toInstant()

        // Day 1 & 2 complete, Day 3 only has 2 hours (incomplete tomorrow)
        val points = mutableListOf<Pair<Long, Double>>()
        listOf(d1, d2).forEach { d ->
            val dayStart = d.atStartOfDay(zoneId).toInstant()
            (0 until 24).forEach { h ->
                points.add(dayStart.plusSeconds(h * 3600L).epochSecond to 100.0)
            }
        }
        val day3Start = d3.atStartOfDay(zoneId).toInstant()
        (0 until 2).forEach { h ->
            points.add(day3Start.plusSeconds(h * 3600L).epochSecond to 100.0)
        }

        val eleringJson = createEleringResponseJson(points)

        val mockEngine = MockEngine {
            networkCallCount.incrementAndGet()
            respond(
                content = eleringJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val httpClient = HttpClient(mockEngine)
        val eleringService = EleringService(httpClient)
        val entsoeService = EntsoeService(httpClient, XmlMapper(), "DUMMY_KEY")

        val tempFile = createTempCacheFile("repo-cache-test-incomplete")
        val inMemoryCache = InMemoryPriceCache(cacheFile = tempFile, cacheDuration = Duration.ofMillis(100))
        val recordingCache = RecordingPriceCache(inMemoryCache)

        val repository = EnergyPriceRepositoryImpl(
            entsoeService = entsoeService,
            eleringService = eleringService,
            cache = recordingCache
        )

        // First call - cold cache
        val firstResult = repository.getPrices("EE", start, end, cacheResults = true)
        assertTrue(firstResult.isSuccess)
        assertEquals(50, firstResult.getOrThrow().size)
        assertEquals(1, networkCallCount.get())
        assertEquals(1, recordingCache.putInvocations.size)
        assertFalse(recordingCache.putInvocations[0].isComplete, "Incomplete data should be marked isComplete=false")

        // Second call immediately - should hit cache within TTL window (throttled, no upstream spam)
        val secondResult = repository.getPrices("EE", start, end, cacheResults = true)
        assertTrue(secondResult.isSuccess)
        assertEquals(50, secondResult.getOrThrow().size)
        assertEquals(1, networkCallCount.get(), "Should hit cache and not spam upstream while within TTL")

        // Wait for TTL to expire
        Thread.sleep(150)

        // Third call after expiration - refetches from upstream
        val thirdResult = repository.getPrices("EE", start, end, cacheResults = true)
        assertTrue(thirdResult.isSuccess)
        assertEquals(2, networkCallCount.get(), "Should refetch after TTL expires")
    }
}
