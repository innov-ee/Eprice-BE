package ee.innov.eprice.monitoring

import ee.innov.eprice.data.MissingDailyStats
import ee.innov.eprice.data.MissingStatsReason
import ee.innov.eprice.domain.BiddingZones
import ee.innov.eprice.domain.model.DailyStatEntry
import ee.innov.eprice.test.InMemoryDailyStatsCache
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals

class CacheOverviewTest {
    @Test
    fun `overview reads complete partial and missing days without changing monitoring or cache`() {
        val now = Instant.parse("2026-10-20T12:00:00Z")
        val today = LocalDate.parse("2026-10-20")
        val cache = InMemoryDailyStatsCache()
        val stats = DailyStatEntry(1.0, 1.0, 1.0, 24.0, 24)
        cache.put("EE", today, stats)
        val partial = MissingDailyStats(MissingStatsReason.PARTIAL, now, now.plusSeconds(900), 1)
        cache.putMissingBatch("EE", mapOf(today.minusDays(1) to partial,
            today.minusDays(2) to partial.copy(reason = MissingStatsReason.NO_DATA)))
        val monitor = ServiceMonitor()
        monitor.recordStatsRequest("EE")
        val overview = CacheOverview.read(cache, monitor, Clock.fixed(now, ZoneOffset.UTC))
        assertEquals(BiddingZones.all.map { it.id }, overview.regions.map { it.region })
        val ee = overview.regions.first { it.region == "EE" }
        assertEquals(1L, ee.statsRequests)
        assertEquals((1 downTo -30).toList(), ee.days.map { it.offset })
        assertEquals("full", ee.days.first { it.offset == 0 }.state)
        assertEquals("partial", ee.days.first { it.offset == -1 }.state)
        assertEquals("missing", ee.days.first { it.offset == -2 }.state)
        assertEquals("missing", ee.days.first { it.offset == 1 }.state)
        assertEquals(partial, cache.getMissing("EE", today.minusDays(1)))
        assertEquals(stats, cache.get("EE", today))
        assertEquals(0L, monitor.getStats().cacheHits)
        assertEquals(0L, monitor.getStats().cacheMisses)
        assertEquals(0L, monitor.getStats().totalOutgoingRequests)
    }

    @Test
    fun `day offsets follow each region at midnight and counts remain independent`() {
        val monitor = ServiceMonitor()
        java.util.stream.IntStream.range(0, 1000).parallel().forEach { monitor.recordStatsRequest("EE") }
        monitor.recordStatsRequest("FI")
        val overview = CacheOverview.read(InMemoryDailyStatsCache(), monitor,
            Clock.fixed(Instant.parse("2026-10-20T21:30:00Z"), ZoneOffset.UTC))
        val ee = overview.regions.first { it.region == "EE" }
        val gb = overview.regions.first { it.region == "GB" }
        assertEquals("2026-10-21", ee.days.first { it.offset == 0 }.date)
        assertEquals("2026-10-20", gb.days.first { it.offset == 0 }.date)
        assertEquals(1000L, ee.statsRequests)
        assertEquals(1L, monitor.statsRequestCount("FI"))
        assertEquals(0L, gb.statsRequests)
        assertEquals(0L, ServiceMonitor().statsRequestCount("EE"))
    }
}
