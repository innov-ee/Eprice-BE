package ee.innov.eprice.data

import ee.innov.eprice.domain.model.DailyStatEntry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay

class DailyStatsCacheTest {

    private val tempFile: Path = Files.createTempFile("daily-stats-test", ".json")

    @AfterEach
    fun tearDown() {
        Files.deleteIfExists(tempFile)
    }

    @Test
    fun `missing metadata survives restart and complete stats and clear remove it`() = runBlocking {
        // Existing v2 cache files without missing metadata still load.
        Files.writeString(tempFile, """{"data":{}}""")
        val cache = FileBackedDailyStatsCache(tempFile)
        val date = LocalDate.of(2026, 10, 21)
        val now = Instant.parse("2026-10-20T08:00:00Z")
        val partial = DailyStatEntry(0.1, 0.1, 0.1, 2.3, 23, isComplete = false)
        val missing = MissingDailyStats(MissingStatsReason.PARTIAL, now, now.plusSeconds(900), 2, partialStats = partial)
        cache.putMissingBatch("IE", mapOf(date to missing))
        withTimeout(5000) {
            while (!Files.readString(tempFile).contains("PARTIAL")) delay(10)
        }
        val restored = FileBackedDailyStatsCache(tempFile)
        assertEquals(missing, restored.getMissing("IE", date))
        assertEquals(partial, restored.getMissing("IE", date)!!.partialStats)
        assertNull(restored.get("IE", date))
        restored.putBatch("IE", mapOf(date to DailyStatEntry(0.1, 0.1, 0.1, 2.4, 24)))
        assertNull(restored.getMissing("IE", date))
        restored.putMissingBatch("GB", mapOf(date to missing))
        restored.clear()
        assertNull(restored.getMissing("GB", date))
        assertNull(restored.get("IE", date))
    }

    @Test
    fun `put and get single entry`() {
        val cache = FileBackedDailyStatsCache(tempFile)
        val date = LocalDate.of(2026, 8, 20)
        val entry = DailyStatEntry(min = 0.05, max = 0.25, avg = 0.15, sum = 3.6, count = 24)

        cache.put("EE", date, entry)

        val retrieved = cache.get("EE", date)
        assertNotNull(retrieved)
        assertEquals(0.05, retrieved!!.min)
        assertEquals(0.25, retrieved.max)
        assertEquals(0.15, retrieved.avg)
        assertEquals(3.6, retrieved.sum)
        assertEquals(24, retrieved.count)
    }

    @Test
    fun `putBatch and getRange retrieves correct slice`() {
        val cache = FileBackedDailyStatsCache(tempFile)
        val entries = mapOf(
            LocalDate.of(2026, 8, 18) to DailyStatEntry(min = 0.01, max = 0.10, avg = 0.05, sum = 1.2, count = 24),
            LocalDate.of(2026, 8, 19) to DailyStatEntry(min = 0.02, max = 0.20, avg = 0.10, sum = 2.4, count = 24),
            LocalDate.of(2026, 8, 20) to DailyStatEntry(min = 0.03, max = 0.30, avg = 0.15, sum = 3.6, count = 24),
            LocalDate.of(2026, 8, 21) to DailyStatEntry(min = 0.04, max = 0.40, avg = 0.20, sum = 4.8, count = 24)
        )

        cache.putBatch("EE", entries)

        val range = cache.getRange("EE", LocalDate.of(2026, 8, 19), LocalDate.of(2026, 8, 20))
        assertEquals(2, range.size)
        assertTrue(range.containsKey(LocalDate.of(2026, 8, 19)))
        assertTrue(range.containsKey(LocalDate.of(2026, 8, 20)))
        assertNull(range[LocalDate.of(2026, 8, 18)])
        assertNull(range[LocalDate.of(2026, 8, 21)])
    }

    @Test
    fun `clear empties cache`() {
        val cache = FileBackedDailyStatsCache(tempFile)
        val date = LocalDate.of(2026, 8, 20)
        cache.put("EE", date, DailyStatEntry(0.05, 0.25, 0.15, 3.6, 24))

        cache.clear()
        assertNull(cache.get("EE", date))
    }
}
