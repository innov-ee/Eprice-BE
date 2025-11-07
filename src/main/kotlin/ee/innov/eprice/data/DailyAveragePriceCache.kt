package ee.innov.eprice.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isReadable

/**
 * Interface for a cache that stores calculated average daily prices.
 */
interface DailyAveragePriceCache {
    /**
     * Retrieves the average price for a specific country and date.
     * @param countryCode The 2-letter country code.
     * @param date The specific date.
     * @return The average price if it exists in the cache, otherwise null.
     */
    fun get(countryCode: String, date: LocalDate): Double?

    /**
     * Stores the average price for a specific country and date.
     * @param countryCode The 2-letter country code.
     * @param date The specific date.
     * @param averagePrice The calculated average price for that day.
     */
    fun put(countryCode: String, date: LocalDate, averagePrice: Double)

    /**
     * Gets all cached prices for a given country within a date range.
     * @param countryCode The 2-letter country code.
     * @param startDate The start of the period (inclusive).
     * @param endDate The end of the period (inclusive).
     * @return A map of [LocalDate] to [Double] for all cached entries found.
     */
    fun getRange(
        countryCode: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Map<LocalDate, Double>
}

/**
 * A file-backed implementation of [DailyAveragePriceCache].
 *
 * This cache stores calculated daily averages and persists them to "daily-average-cache.json".
 * It does not use TTL/expiry, as a historical day's average price is considered final.
 *
 * The in-memory structure is: `Map<CountryCode, Map<DateString, Price>>`
 * e.g., "EE" -> {"2023-10-01" -> 0.123, "2023-10-02" -> 0.456}
 */
class FileBackedDailyAveragePriceCache(
    private val cacheFile: Path = Paths.get("daily-average-cache.json")
) : DailyAveragePriceCache {

    @Serializable
    private data class DailyCacheFile(
        // *** FIX: Use standard Map for serialization ***
        val data: Map<String, Map<String, Double>> = emptyMap()
    )

    // The *in-memory* cache remains a ConcurrentHashMap for thread-safety
    private val cache: ConcurrentHashMap<String, ConcurrentHashMap<String, Double>>

    // A dedicated scope for file I/O
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistMutex = Mutex()

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    init {
        cache = loadCache()
    }

    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE // "yyyy-MM-dd"

    override fun get(countryCode: String, date: LocalDate): Double? {
        val dateString = date.format(dateFormatter)
        return cache[countryCode.uppercase()]?.get(dateString)
    }

    override fun put(countryCode: String, date: LocalDate, averagePrice: Double) {
        val ucCountryCode = countryCode.uppercase()
        val dateString = date.format(dateFormatter)

        val countryCache = cache.getOrPut(ucCountryCode) { ConcurrentHashMap() }
        countryCache[dateString] = averagePrice

        // Save to disk asynchronously
        saveCacheAsync()
    }

    override fun getRange(
        countryCode: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Map<LocalDate, Double> {
        val countryCache = cache[countryCode.uppercase()] ?: return emptyMap()

        return countryCache
            .mapKeysNotNull { LocalDate.parse(it.key, dateFormatter) }
            .filterKeys { !it.isBefore(startDate) && !it.isAfter(endDate) }
    }

    // Helper to allow safe transformation of map keys
    private fun <K, V, R> Map<K, V>.mapKeysNotNull(transform: (Map.Entry<K, V>) -> R?): Map<R, V> {
        val result = mutableMapOf<R, V>()
        for (entry in this) {
            transform(entry)?.let {
                result[it] = entry.value
            }
        }
        return result
    }

    private fun loadCache(): ConcurrentHashMap<String, ConcurrentHashMap<String, Double>> {
        if (!cacheFile.exists() || !cacheFile.isReadable()) {
            println("No daily average cache file found or readable at $cacheFile. Starting with an empty cache.")
            return ConcurrentHashMap()
        }

        return try {
            val content = Files.readString(cacheFile)
            // 1. Deserialize into the simple DTO
            val deserialized: DailyCacheFile = json.decodeFromString(content)
            println("Loaded ${deserialized.data.values.sumOf { it.size }} daily average entries from $cacheFile.")

            // *** FIX: Convert from Map to ConcurrentHashMap for the in-memory instance ***
            val concurrentMap = ConcurrentHashMap<String, ConcurrentHashMap<String, Double>>()
            deserialized.data.forEach { (country, dateMap) ->
                concurrentMap[country] = ConcurrentHashMap(dateMap)
            }
            concurrentMap

        } catch (e: Exception) {
            println("Failed to load or parse daily average cache file $cacheFile. Starting with an empty cache. Error: ${e.message}")
            // If file is corrupt, delete it to start fresh next time
            try {
                cacheFile.deleteIfExists()
            } catch (_: Exception) {
            }
            ConcurrentHashMap()
        }
    }

    private fun saveCacheAsync() {
        scope.launch {
            persistMutex.withLock {
                persistCache()
            }
        }
    }

    private fun persistCache() {
        // We pass the in-memory 'cache' (a ConcurrentHashMap) to the DTO
        // which expects a standard 'Map', and serialization works.
        val cacheSnapshot = DailyCacheFile(cache)
        if (cacheSnapshot.data.isEmpty()) return // Nothing to save

        try {
            val jsonString = json.encodeToString(cacheSnapshot)

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
            println("Failed to persist daily average cache to $cacheFile. Error: ${e.message}")
            e.printStackTrace()
        }
    }
}