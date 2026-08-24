package ee.innov.eprice.data

import ee.innov.eprice.domain.model.DomainEnergyPrice
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class PriceCacheTest {

    private val tempFile: Path = Files.createTempFile("price-cache-test", ".json")

    @AfterEach
    fun tearDown() {
        Files.deleteIfExists(tempFile)
    }

    @Test
    fun `complete data is cached indefinitely and never expires`() {
        val cache = InMemoryPriceCache(cacheFile = tempFile, cacheDuration = Duration.ofMillis(50))
        val prices = listOf(
            DomainEnergyPrice(Instant.parse("2026-08-23T00:00:00Z"), 0.15)
        )

        cache.put("EE_complete", prices, isComplete = true)

        // Wait longer than cacheDuration
        Thread.sleep(80)

        val retrieved = cache.get("EE_complete")
        assertNotNull(retrieved)
        assertEquals(1, retrieved!!.size)
        assertEquals(0.15, retrieved[0].pricePerKWh)
    }

    @Test
    fun `incomplete data expires after cache duration`() {
        val cache = InMemoryPriceCache(cacheFile = tempFile, cacheDuration = Duration.ofMillis(50))
        val prices = listOf(
            DomainEnergyPrice(Instant.parse("2026-08-23T00:00:00Z"), 0.15)
        )

        cache.put("EE_incomplete", prices, isComplete = false)

        // Immediately available
        assertNotNull(cache.get("EE_incomplete"))

        // Wait for TTL expiration
        Thread.sleep(80)

        // Expired
        assertNull(cache.get("EE_incomplete"))
    }

    @Test
    fun `reloading cache from file restores indefinite entries and drops expired ones`() {
        val cache1 = InMemoryPriceCache(cacheFile = tempFile, cacheDuration = Duration.ofMillis(50))
        val completePrices = listOf(DomainEnergyPrice(Instant.parse("2026-08-23T00:00:00Z"), 0.15))
        val incompletePrices = listOf(DomainEnergyPrice(Instant.parse("2026-08-23T01:00:00Z"), 0.20))

        cache1.put("EE_complete", completePrices, isComplete = true)
        cache1.put("EE_incomplete", incompletePrices, isComplete = false)

        // Wait for disk async write and incomplete TTL expiration
        Thread.sleep(100)

        // Load into new cache instance
        val cache2 = InMemoryPriceCache(cacheFile = tempFile)

        assertNotNull(cache2.get("EE_complete"))
        assertNull(cache2.get("EE_incomplete"))
    }

    @Test
    fun `clear removes all entries from memory and disk`() {
        val cache = InMemoryPriceCache(cacheFile = tempFile)
        val prices = listOf(DomainEnergyPrice(Instant.parse("2026-08-23T00:00:00Z"), 0.15))

        cache.put("EE_key", prices, isComplete = true)
        assertNotNull(cache.get("EE_key"))

        cache.clear()
        assertNull(cache.get("EE_key"))
    }
}
