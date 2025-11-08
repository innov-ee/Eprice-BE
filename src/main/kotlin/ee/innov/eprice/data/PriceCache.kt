package ee.innov.eprice.data

import ee.innov.eprice.domain.model.DomainEnergyPrice
import ee.innov.eprice.util.InstantSerializer
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * A simple in-memory cache interface for storing energy prices.
 */
interface PriceCache {
    /**
     * Retrieves prices from the cache.
     * @param key A unique key identifying the request (e.g., "EE_2023-01-01T00:00Z_2023-01-03T23:59Z")
     * @return The list of prices if found and not expired, otherwise null.
     */
    fun get(key: String): List<DomainEnergyPrice>?

    /**
     * Stores prices in the cache.
     * @param key A unique key identifying the request.
     * @param prices The list of prices to store.
     */
    fun put(key: String, prices: List<DomainEnergyPrice>)

    /**
     * Clears all entries from the cache (memory and persistent storage).
     */
    fun clear()
}

@Serializable
private data class CacheEntry(
    val data: List<DomainEnergyPrice>,
    @Serializable(with = InstantSerializer::class)
    val expiryTime: Instant
)

/**
 * The type alias for the data structure that gets serialized to disk.
 * We serialize a simple Map, not the ConcurrentHashMap.
 */
private typealias PriceCacheDto = Map<String, CacheEntry>

/**
 * An in-memory implementation of [PriceCache] using [ConcurrentHashMap]
 * and a simple Time-to-Live (TTL) expiration.
 *
 * This implementation persists the cache to a JSON file by inheriting from [BaseFileCache].
 * - **On Init:** Loads and filters the cache from the file.
 * - **On Put:** Saves the entire cache to the file asynchronously.
 */
class InMemoryPriceCache(
    cacheFile: Path = Paths.get("eprice-cache.json")
) : BaseFileCache(cacheFile), PriceCache {

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    // Cache data for 1 hour.
    private val cacheDuration = Duration.ofMinutes(60)

    init {
        loadCache()
    }

    override fun get(key: String): List<DomainEnergyPrice>? {
        val entry = cache[key] ?: return null
        return if (Instant.now().isBefore(entry.expiryTime)) {
            entry.data // Cache is valid
        } else {
            cache.remove(key) // Cache expired
            null
        }
    }

    override fun put(key: String, prices: List<DomainEnergyPrice>) {
        val expiry = Instant.now().plus(cacheDuration)
        cache[key] = CacheEntry(prices, expiry)
        // Save to disk asynchronously. Pass a snapshot (.toMap()) for thread safety.
        saveToFileAsync<PriceCacheDto>(cache.toMap())
    }

    override fun clear() {
        // 1. Clear in-memory map
        cache.clear()
        println("In-memory cache cleared.")

        // 2. Clear backing file (asynchronously)
        clearFileAsync()
    }

    /**
     * Loads the cache from disk and filters out expired entries.
     */
    private fun loadCache() {
        // 1. Load the DTO from file using the base class method
        val deserializedMap = loadFromFile<PriceCacheDto>() ?: return

        // 2. Filter out expired entries before loading into memory
        val now = Instant.now()
        val validEntries = deserializedMap.filterValues { it.expiryTime.isAfter(now) }

        // 3. Populate the in-memory cache
        cache.putAll(validEntries)
        println("Loaded ${validEntries.size} valid cache entries from $cacheFile.")
    }
}