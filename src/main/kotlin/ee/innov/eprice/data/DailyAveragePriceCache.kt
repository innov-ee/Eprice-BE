package ee.innov.eprice.data

import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

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

    /**
     * Clears all entries from the cache (memory and persistent storage).
     */
    fun clear()
}

/**
 * A file-backed implementation of [DailyAveragePriceCache].
 *
 * This cache stores calculated daily averages and persists them by inheriting from [BaseFileCache].
 * It does not use TTL/expiry, as a historical day's average price is considered final.
 *
 * The in-memory structure is: `Map<CountryCode, Map<DateString, Price>>`
 * e.g., "EE" -> {"2023-10-01" -> 0.123, "2023-10-02" -> 0.456}
 */
class FileBackedDailyAveragePriceCache(
    cacheFile: Path = Paths.get("daily-average-cache.json")
) : BaseFileCache(cacheFile), DailyAveragePriceCache {

    /**
     * This is the DTO (Data Transfer Object) that will be serialized to JSON.
     * It uses standard Maps, which are easily serializable.
     */
    @Serializable
    private data class DailyCacheFile(
        val data: Map<String, Map<String, Double>> = emptyMap()
    )

    // The *in-memory* cache is a ConcurrentHashMap for thread-safety.
    // This is different from the DTO.
    private val cache: ConcurrentHashMap<String, ConcurrentHashMap<String, Double>>

    init {
        // Load the cache from the file and transform it into the in-memory structure
        cache = loadCacheFromFile()
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

        // Save to disk asynchronously.
        // We pass a new DTO instance containing the current cache state.
        // TODO this is called in a loop, every time requiring write, its ineficcient
        saveToFileAsync(DailyCacheFile(cache))
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

    override fun clear() {
        cache.clear()
        println("In-memory daily average cache cleared.")

        // Clear the backing file asynchronously
        clearFileAsync()
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

    /**
     * Loads the cache from disk and transforms the DTO into the
     * in-memory ConcurrentHashMap structure.
     */
    private fun loadCacheFromFile(): ConcurrentHashMap<String, ConcurrentHashMap<String, Double>> {
        // 1. Load the DTO from file using the base class method
        val deserialized = loadFromFile<DailyCacheFile>()
        if (deserialized == null) {
            println("No daily average cache file found. Starting with an empty cache.")
            return ConcurrentHashMap()
        }

        println("Loaded ${deserialized.data.values.sumOf { it.size }} daily average entries from $cacheFile.")

        // 2. Transform the deserialized Map into the in-memory ConcurrentHashMap structure
        val concurrentMap = ConcurrentHashMap<String, ConcurrentHashMap<String, Double>>()
        deserialized.data.forEach { (country, dateMap) ->
            concurrentMap[country] = ConcurrentHashMap(dateMap)
        }
        return concurrentMap
    }
}