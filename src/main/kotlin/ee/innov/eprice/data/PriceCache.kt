package ee.innov.eprice.data

import ee.innov.eprice.domain.model.DomainEnergyPrice
import ee.innov.eprice.util.InstantSerializer
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

interface PriceCache {

    fun get(key: String): List<DomainEnergyPrice>?

    fun put(key: String, prices: List<DomainEnergyPrice>)

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

class InMemoryPriceCache(
    cacheFile: Path = Paths.get("eprice-cache.json")
) : BaseFileCache(cacheFile), PriceCache {

    private val cache = ConcurrentHashMap<String, CacheEntry>()

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
        cache.clear()
        println("In-memory cache cleared.")
        clearFileAsync()
    }

    /**
     * Loads the cache from disk and filters out expired entries.
     */
    private fun loadCache() {
        val deserializedMap = loadFromFile<PriceCacheDto>() ?: return

        // Filter out expired entries before loading into memory
        val now = Instant.now()
        val validEntries = deserializedMap.filterValues { it.expiryTime.isAfter(now) }

        cache.putAll(validEntries)
        println("Loaded ${validEntries.size} valid cache entries from $cacheFile.")
    }
}