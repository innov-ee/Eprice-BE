package ee.innov.eprice.data

import ee.innov.eprice.domain.model.DomainEnergyPrice
import ee.innov.eprice.util.InstantSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isReadable

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
}

/**
 * An in-memory implementation of [PriceCache] using [ConcurrentHashMap]
 * and a simple Time-to-Live (TTL) expiration.
 *
 * This implementation persists the cache to a JSON file.
 * - **On Init:** Loads the cache from the file.
 * - **On Put:** Saves the entire cache to the file asynchronously.
 */
class InMemoryPriceCache(
    private val cacheFile: Path = Paths.get("eprice-cache.json")
) : PriceCache {

    @Serializable
    private data class CacheEntry(
        val data: List<DomainEnergyPrice>,
        @Serializable(with = InstantSerializer::class)
        val expiryTime: Instant
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    // Cache data for 1 hour.
    private val cacheDuration = Duration.ofMinutes(60)

    // A dedicated scope for file I/O
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // A self-contained serializer
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

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
        // Save to disk asynchronously
        saveCacheAsync()
    }

    private fun loadCache() {
        if (!cacheFile.exists() || !cacheFile.isReadable()) {
            println("No cache file found or readable at $cacheFile. Starting with an empty cache.")
            return
        }

        try {
            val content = Files.readString(cacheFile)
            val deserializedMap: Map<String, CacheEntry> = json.decodeFromString(content)

            // Filter out expired entries before loading
            val now = Instant.now()
            val validEntries = deserializedMap.filterValues { it.expiryTime.isAfter(now) }

            cache.putAll(validEntries)
            println("Loaded ${validEntries.size} valid cache entries from $cacheFile.")
        } catch (e: Exception) {
            println("Failed to load or parse cache file $cacheFile. Starting with an empty cache. Error: ${e.message}")
            // If file is corrupt, delete it to start fresh next time
            try {
                cacheFile.deleteIfExists()
            } catch (_: Exception) {
            }
        }
    }

    private fun saveCacheAsync() {
        scope.launch {
            persistCache()
        }
    }

    private fun persistCache() {
        // Create a stable snapshot of the cache for serialization
        val cacheSnapshot = cache.toMap()
        if (cacheSnapshot.isEmpty()) return // Nothing to save

        try {
            val jsonString = json.encodeToString(cacheSnapshot)

            // Atomic write: Write to temp file, then rename
            val tempFile = cacheFile.resolveSibling("${cacheFile.fileName}.tmp")

            Files.newBufferedWriter(tempFile).use { writer ->
                writer.write(jsonString)
            }

            Files.move(
                tempFile,
                cacheFile,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (e: Exception) {
            // Log the error, but don't crash the application
            println("Failed to persist cache to $cacheFile. Error: ${e.message}")
            e.printStackTrace()
        }
    }
}