package ee.innov.eprice.monitoring

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

@Serializable
data class ServiceStats(
    val uptime: String,
    val totalIncomingRequests: Long,
    val totalOutgoingRequests: Long,
    val outgoingEleringRequests: Long,
    val outgoingEntsoeRequests: Long,
    val cacheHits: Long,
    val cacheMisses: Long,
    val cacheHitRatio: Double,
    val avgIncomingPerHour: Double,
    val avgOutgoingPerHour: Double
)

class ServiceMonitor {
    private val startTime = Instant.now()
    private val incomingCounter = AtomicLong(0)
    private val outgoingCounter = AtomicLong(0)
    private val outgoingEleringCounter = AtomicLong(0)
    private val outgoingEntsoeCounter = AtomicLong(0)
    private val cacheHitCounter = AtomicLong(0)
    private val cacheMissCounter = AtomicLong(0)

    fun incrementIncoming() {
        incomingCounter.incrementAndGet()
    }

    fun incrementOutgoing(host: String? = null) {
        outgoingCounter.incrementAndGet()
        if (host != null) {
            when {
                host.contains("elering", ignoreCase = true) -> outgoingEleringCounter.incrementAndGet()
                host.contains("entsoe", ignoreCase = true) -> outgoingEntsoeCounter.incrementAndGet()
            }
        }
    }

    fun recordCacheHit(count: Long = 1) {
        if (count > 0) {
            cacheHitCounter.addAndGet(count)
        }
    }

    fun recordCacheMiss(count: Long = 1) {
        if (count > 0) {
            cacheMissCounter.addAndGet(count)
        }
    }

    fun getStats(): ServiceStats {
        val now = Instant.now()
        val uptimeDuration = Duration.between(startTime, now)

        // coercing to 1 to avoid mega averages when service is in its first minutes.
        val hoursForAvg = (uptimeDuration.seconds / 3600.0).coerceAtLeast(1.0)

        val inTotal = incomingCounter.get()
        val outTotal = outgoingCounter.get()
        val outElering = outgoingEleringCounter.get()
        val outEntsoe = outgoingEntsoeCounter.get()
        val hits = cacheHitCounter.get()
        val misses = cacheMissCounter.get()
        val totalLookups = hits + misses
        val hitRatio = if (totalLookups > 0) hits.toDouble() / totalLookups else 0.0

        return ServiceStats(
            uptime = formatDuration(uptimeDuration),
            totalIncomingRequests = inTotal,
            totalOutgoingRequests = outTotal,
            outgoingEleringRequests = outElering,
            outgoingEntsoeRequests = outEntsoe,
            cacheHits = hits,
            cacheMisses = misses,
            cacheHitRatio = hitRatio,
            avgIncomingPerHour = inTotal / hoursForAvg,
            avgOutgoingPerHour = outTotal / hoursForAvg
        )
    }

    private fun formatDuration(duration: Duration): String {
        val days = duration.toDays()
        val hours = duration.toHoursPart()
        val minutes = duration.toMinutesPart()
        val seconds = duration.toSecondsPart()
        return "${days}d ${hours}h ${minutes}m ${seconds}s"
    }
}