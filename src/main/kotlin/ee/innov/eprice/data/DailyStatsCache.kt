package ee.innov.eprice.data

import ee.innov.eprice.domain.model.DailyStatEntry
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

interface DailyStatsCache {

    fun get(countryCode: String, date: LocalDate): DailyStatEntry?

    fun put(countryCode: String, date: LocalDate, stats: DailyStatEntry)

    fun putBatch(countryCode: String, entries: Map<LocalDate, DailyStatEntry>)

    fun getRange(
        countryCode: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Map<LocalDate, DailyStatEntry>

    fun clear()
}

@Serializable
private data class DailyStatsCacheFile(
    val data: Map<String, Map<String, DailyStatEntry>> = emptyMap()
)

class FileBackedDailyStatsCache(
    cacheFile: Path = Paths.get("daily-stats-cache.json")
) : BaseFileCache(cacheFile), DailyStatsCache {

    // In-memory thread-safe cache
    private val cache: ConcurrentHashMap<String, ConcurrentHashMap<String, DailyStatEntry>>

    init {
        cache = loadCacheFromFile()
    }

    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE // "yyyy-MM-dd"

    override fun get(countryCode: String, date: LocalDate): DailyStatEntry? {
        val dateString = date.format(dateFormatter)
        return cache[countryCode.uppercase()]?.get(dateString)
    }

    // For multiple entries use putBatch instead - this writes the whole cache to disk on every call.
    override fun put(countryCode: String, date: LocalDate, stats: DailyStatEntry) {
        val ucCountryCode = countryCode.uppercase()
        val dateString = date.format(dateFormatter)

        val countryCache = cache.getOrPut(ucCountryCode) { ConcurrentHashMap() }
        countryCache[dateString] = stats

        writeCacheToFile()
    }

    override fun putBatch(countryCode: String, entries: Map<LocalDate, DailyStatEntry>) {
        if (entries.isEmpty()) return
        val ucCountryCode = countryCode.uppercase()
        val countryCache = cache.getOrPut(ucCountryCode) { ConcurrentHashMap() }

        entries.forEach { (date, stats) ->
            val dateString = date.format(dateFormatter)
            countryCache[dateString] = stats
        }

        writeCacheToFile()
    }

    private fun writeCacheToFile() = saveToFileAsync(DailyStatsCacheFile(snapshot()))

    override fun getRange(
        countryCode: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Map<LocalDate, DailyStatEntry> {
        val countryCache = cache[countryCode.uppercase()] ?: return emptyMap()

        return countryCache
            .mapKeysNotNull { LocalDate.parse(it.key, dateFormatter) }
            .filterKeys { !it.isBefore(startDate) && !it.isAfter(endDate) }
    }

    override fun clear() {
        cache.clear()
        println("In-memory daily stats cache cleared.")
        clearFileAsync()
    }

    private fun <K, V, R> Map<K, V>.mapKeysNotNull(transform: (Map.Entry<K, V>) -> R?): Map<R, V> {
        val result = mutableMapOf<R, V>()
        for (entry in this) {
            transform(entry)?.let {
                result[it] = entry.value
            }
        }
        return result
    }

    // Defensive copy so serialization isn't reading a map that's still being mutated concurrently.
    private fun snapshot(): Map<String, Map<String, DailyStatEntry>> =
        cache.mapValues { it.value.toMap() }

    private fun loadCacheFromFile(): ConcurrentHashMap<String, ConcurrentHashMap<String, DailyStatEntry>> {
        val deserialized = loadFromFile<DailyStatsCacheFile>()
        if (deserialized == null) {
            println("No daily stats cache file found. Starting with an empty cache.")
            return ConcurrentHashMap()
        }

        println("Loaded ${deserialized.data.values.sumOf { it.size }} daily stats entries from $cacheFile.")

        val concurrentMap = ConcurrentHashMap<String, ConcurrentHashMap<String, DailyStatEntry>>()
        deserialized.data.forEach { (country, dateMap) ->
            concurrentMap[country] = ConcurrentHashMap(dateMap)
        }
        return concurrentMap
    }
}
