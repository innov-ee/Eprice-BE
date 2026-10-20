package ee.innov.eprice.data

import ee.innov.eprice.domain.model.DailyStatEntry
import kotlinx.serialization.Serializable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

interface DailyStatsCache {

    // Callers write only complete daily statistics; cached entries are trusted on read.

    fun get(countryCode: String, date: LocalDate): DailyStatEntry?

    fun put(countryCode: String, date: LocalDate, stats: DailyStatEntry)

    fun putBatch(countryCode: String, entries: Map<LocalDate, DailyStatEntry>)

    fun getRange(
        countryCode: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Map<LocalDate, DailyStatEntry>

    fun getMissing(countryCode: String, date: LocalDate): MissingDailyStats?

    fun putMissingBatch(countryCode: String, entries: Map<LocalDate, MissingDailyStats>)

    fun clear()
}

@Serializable
private data class DailyStatsCacheFile(
    val data: Map<String, Map<String, DailyStatEntry>> = emptyMap(),
    val missing: Map<String, Map<String, MissingDailyStats>> = emptyMap()
)

class FileBackedDailyStatsCache(
    cacheFile: Path = Paths.get(FILE_NAME)
) : BaseFileCache(cacheFile), DailyStatsCache {

    companion object {
        const val FILE_NAME = "daily-stats-cache-v3-sdac.json"
    }

    // In-memory thread-safe cache
    private val cache: ConcurrentHashMap<String, ConcurrentHashMap<String, DailyStatEntry>>

    private val missing = ConcurrentHashMap<String, ConcurrentHashMap<String, MissingDailyStats>>()

    init {
        val saved = loadFromFile<DailyStatsCacheFile>() ?: DailyStatsCacheFile()
        cache = ConcurrentHashMap(saved.data.mapValues { ConcurrentHashMap(it.value) })
        missing.putAll(saved.missing.mapValues { ConcurrentHashMap(it.value) })
    }

    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE // "yyyy-MM-dd"

    override fun get(countryCode: String, date: LocalDate): DailyStatEntry? {
        val dateString = date.format(dateFormatter)
        return cache[countryCode]?.get(dateString)
    }

    // For multiple entries use putBatch instead - this writes the whole cache to disk on every call.
    override fun put(countryCode: String, date: LocalDate, stats: DailyStatEntry) {
        val dateString = date.format(dateFormatter)

        val countryCache = cache.getOrPut(countryCode) { ConcurrentHashMap() }
        countryCache[dateString] = stats
        missing[countryCode]?.remove(dateString)

        writeCacheToFile()
    }

    override fun putBatch(countryCode: String, entries: Map<LocalDate, DailyStatEntry>) {
        if (entries.isEmpty()) return
        val countryCache = cache.getOrPut(countryCode) { ConcurrentHashMap() }

        entries.forEach { (date, stats) ->
            val dateString = date.format(dateFormatter)
            countryCache[dateString] = stats
            missing[countryCode]?.remove(dateString)
        }

        writeCacheToFile()
    }

    override fun getMissing(countryCode: String, date: LocalDate): MissingDailyStats? =
        missing[countryCode]?.get(date.format(dateFormatter))

    override fun putMissingBatch(countryCode: String, entries: Map<LocalDate, MissingDailyStats>) {
        if (entries.isEmpty()) return
        val countryMissing = missing.getOrPut(countryCode) { ConcurrentHashMap() }
        entries.forEach { (date, entry) -> countryMissing[date.format(dateFormatter)] = entry }
        writeCacheToFile()
    }

    private fun writeCacheToFile() = scope.launch {
        persistMutex.withLock {
            // Snapshot when writing so queued writes cannot restore older cooldowns or data.
            persistCache(DailyStatsCacheFile(snapshot(), missing.mapValues { it.value.toMap() }))
        }
    }

    override fun getRange(
        countryCode: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Map<LocalDate, DailyStatEntry> {
        val countryCache = cache[countryCode] ?: return emptyMap()

        return countryCache
            .mapKeysNotNull { LocalDate.parse(it.key, dateFormatter) }
            .filterKeys { !it.isBefore(startDate) && !it.isAfter(endDate) }
    }

    override fun clear() {
        cache.clear()
        missing.clear()
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
}
