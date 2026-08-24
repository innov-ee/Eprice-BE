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

    fun put(key: String, prices: List<DomainEnergyPrice>, isComplete: Boolean = false)

    fun clear()
}

@Serializable
private data class CacheEntry(
    val data: List<DomainEnergyPrice>,
    @Serializable(with = InstantSerializer::class)
    val expiryTime: Instant? = null
)

/**
 * The type alias for the data structure that gets serialized to disk.
 * We serialize a simple Map, not the ConcurrentHashMap.
 */
private typealias PriceCacheDto = Map<String, CacheEntry>

class InMemoryPriceCache(
    cacheFile: Path = Paths.get("eprice-cache.json"),
    private val cacheDuration: Duration = Duration.ofMinutes(60)
) : BaseFileCache(cacheFile), PriceCache {

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    init {
        loadCache()
    }

    override fun get(key: String): List<DomainEnergyPrice>? {
        val entry = cache[key] ?: return null
        val expiry = entry.expiryTime
        return if (expiry == null || Instant.now().isBefore(expiry)) {
            entry.data // Cache is valid (indefinite if null, or not yet expired)
        } else {
            cache.remove(key) // Cache expired
            null
        }
    }

    override fun put(key: String, prices: List<DomainEnergyPrice>, isComplete: Boolean) {
        val expiry = if (isComplete) null else Instant.now().plus(cacheDuration)
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

        // Keep indefinite entries (null expiryTime) and entries not yet expired
        val now = Instant.now()
        val validEntries = deserializedMap.filterValues { it.expiryTime == null || it.expiryTime.isAfter(now) }

        cache.putAll(validEntries)
        println("Loaded ${validEntries.size} valid cache entries from $cacheFile.")
    }
}