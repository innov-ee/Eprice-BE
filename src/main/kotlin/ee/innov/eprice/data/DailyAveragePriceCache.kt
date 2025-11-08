package ee.innov.eprice.data

import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

interface DailyAveragePriceCache {

    fun get(countryCode: String, date: LocalDate): Double?

    fun put(countryCode: String, date: LocalDate, averagePrice: Double)

    fun getRange(
        countryCode: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Map<LocalDate, Double>

    fun clear()
}

@Serializable
private data class DailyCacheFile(
    val data: Map<String, Map<String, Double>> = emptyMap()
)

class FileBackedDailyAveragePriceCache(
    cacheFile: Path = Paths.get("daily-average-cache.json")
) : BaseFileCache(cacheFile), DailyAveragePriceCache {


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