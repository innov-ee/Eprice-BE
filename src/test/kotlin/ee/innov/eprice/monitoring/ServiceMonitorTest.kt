package ee.innov.eprice.monitoring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServiceMonitorTest {

    @Test
    fun `initial stats should be zero`() {
        val monitor = ServiceMonitor()
        val stats = monitor.getStats()

        assertEquals(0L, stats.totalIncomingRequests)
        assertEquals(0L, stats.totalOutgoingRequests)
        assertEquals(0L, stats.outgoingEleringRequests)
        assertEquals(0L, stats.outgoingEntsoeRequests)
        assertEquals(0L, stats.cacheHits)
        assertEquals(0L, stats.cacheMisses)
        assertEquals(0.0, stats.cacheHitRatio)
        assertTrue(stats.uptime.isNotBlank())
    }

    @Test
    fun `incoming request counter increments properly`() {
        val monitor = ServiceMonitor()
        monitor.incrementIncoming()
        monitor.incrementIncoming()

        val stats = monitor.getStats()
        assertEquals(2L, stats.totalIncomingRequests)
    }

    @Test
    fun `outgoing request counting handles hosts and specific domains`() {
        val monitor = ServiceMonitor()

        // Elering calls
        monitor.incrementOutgoing("dashboard.elering.ee")
        monitor.incrementOutgoing("https://dashboard.elering.ee/api/nps/price")

        // ENTSO-E calls
        monitor.incrementOutgoing("web-api.tp.entsoe.eu")

        // Generic / other outgoing call
        monitor.incrementOutgoing("other.service.com")
        monitor.incrementOutgoing(null)

        val stats = monitor.getStats()
        assertEquals(5L, stats.totalOutgoingRequests)
        assertEquals(2L, stats.outgoingEleringRequests)
        assertEquals(1L, stats.outgoingEntsoeRequests)
    }

    @Test
    fun `cache hit and miss tracking calculates correct hit ratio`() {
        val monitor = ServiceMonitor()

        monitor.recordCacheHit(8)
        monitor.recordCacheMiss(2)

        val stats = monitor.getStats()
        assertEquals(8L, stats.cacheHits)
        assertEquals(2L, stats.cacheMisses)
        assertEquals(0.8, stats.cacheHitRatio, 0.0001)
    }

    @Test
    fun `single hit and miss increments work as default count of 1`() {
        val monitor = ServiceMonitor()

        monitor.recordCacheHit()
        monitor.recordCacheHit()
        monitor.recordCacheMiss()

        val stats = monitor.getStats()
        assertEquals(2L, stats.cacheHits)
        assertEquals(1L, stats.cacheMisses)
        assertEquals(2.0 / 3.0, stats.cacheHitRatio, 0.0001)
    }
}
