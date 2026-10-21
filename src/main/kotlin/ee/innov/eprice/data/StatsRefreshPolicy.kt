package ee.innov.eprice.data

import ee.innov.eprice.domain.model.DailyStatEntry
import ee.innov.eprice.util.InstantSerializer
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Serializable
enum class MissingStatsReason { NO_DATA, PARTIAL }

@Serializable
data class MissingDailyStats(
    val reason: MissingStatsReason,
    @Serializable(with = InstantSerializer::class) val checkedAt: Instant,
    @Serializable(with = InstantSerializer::class) val nextCheckAt: Instant,
    val consecutiveMisses: Int,
    // Provisional statistics are served only for region-local today and tomorrow.
    val partialStats: DailyStatEntry? = null
)

/** Refresh choices, not guarantees about upstream publication times. All dates are region-local. */
class StatsRefreshPolicy(
    private val tomorrowRecheckTime: LocalTime = LocalTime.NOON,
    private val recentDelay: Duration = Duration.ofMinutes(15),
    private val historicalDelay: Duration = Duration.ofHours(6),
    private val maxHistoricalDelay: Duration = Duration.ofDays(7)
) {
    fun missing(date: LocalDate, zone: ZoneId, now: Instant, previous: MissingDailyStats?, reason: MissingStatsReason): MissingDailyStats {
        val today = now.atZone(zone).toLocalDate()
        // Frequent checks while a day is recent must not inflate its historical backoff.
        val enteredHistoricalWindow = date < today.minusDays(1) && previous != null &&
            date >= previous.checkedAt.atZone(zone).toLocalDate().minusDays(1)
        val misses = if (enteredHistoricalWindow) 1 else
            ((previous?.consecutiveMisses ?: 0) + 1).coerceAtMost(30)
        val next = when {
            date > today -> maxOf(now.plus(recentDelay), date.minusDays(1).atTime(tomorrowRecheckTime).atZone(zone).toInstant())
            date >= today.minusDays(1) -> now.plus(recentDelay)
            else -> now.plus(minOf(historicalDelay.multipliedBy(1L shl (misses - 1)), maxHistoricalDelay))
        }
        return MissingDailyStats(reason, now, next, misses)
    }
}
