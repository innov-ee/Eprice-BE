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
    val avgIncomingPerHour: Double,
    val avgOutgoingPerHour: Double
)

class ServiceMonitor {
    private val startTime = Instant.now()
    private val incomingCounter = AtomicLong(0)
    private val outgoingCounter = AtomicLong(0)

    fun incrementIncoming() {
        incomingCounter.incrementAndGet()
    }

    fun incrementOutgoing() {
        outgoingCounter.incrementAndGet()
    }

    fun getStats(): ServiceStats {
        val now = Instant.now()
        val uptimeDuration = Duration.between(startTime, now)

        // coercing to 1 to avoid mega averages when service is in its first minutes.
        val hoursForAvg = (uptimeDuration.seconds / 3600.0).coerceAtLeast(1.0)

        val inTotal = incomingCounter.get()
        val outTotal = outgoingCounter.get()

        return ServiceStats(
            uptime = formatDuration(uptimeDuration),
            totalIncomingRequests = inTotal,
            totalOutgoingRequests = outTotal,
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