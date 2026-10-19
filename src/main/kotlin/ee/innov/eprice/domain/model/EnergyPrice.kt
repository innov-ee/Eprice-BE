package ee.innov.eprice.domain.model

import ee.innov.eprice.util.InstantSerializer
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@Serializable
data class DomainEnergyPrice(
    @Serializable(with = InstantSerializer::class)
    val startTime: Instant,
    val pricePerKWh: Double,
    val durationSeconds: Long = 3600
)

/** Restrict actual intervals to [start, end), including an interval spanning a boundary. */
fun List<DomainEnergyPrice>.inRange(start: Instant, end: Instant): List<DomainEnergyPrice> = mapNotNull { price ->
    val intervalStart = maxOf(start, price.startTime)
    val intervalEnd = minOf(end, price.startTime.plusSeconds(price.durationSeconds))
    if (intervalStart >= intervalEnd) null
    else price.copy(startTime = intervalStart, durationSeconds = Duration.between(intervalStart, intervalEnd).seconds)
}.sortedBy { it.startTime }

/** Complete coverage requires contiguous, non-overlapping intervals, not a sample count. */
fun List<DomainEnergyPrice>.isFullDay(date: LocalDate, zoneId: ZoneId): Boolean {
    val start = date.atStartOfDay(zoneId).toInstant()
    val end = date.plusDays(1).atStartOfDay(zoneId).toInstant()
    val prices = inRange(start, end)
    var cursor = start
    for (price in prices) {
        if (price.startTime != cursor) return false
        cursor = price.startTime.plusSeconds(price.durationSeconds)
    }
    return cursor == end
}

/** The range end is exclusive, so midnight belongs to the following day. */
fun List<DomainEnergyPrice>.isCompleteRange(zoneId: ZoneId, start: Instant, end: Instant): Boolean {
    if (isEmpty() || end <= start) return false
    val startDate = start.atZone(zoneId).toLocalDate()
    val endDate = end.minusNanos(1).atZone(zoneId).toLocalDate()
    val days = ChronoUnit.DAYS.between(startDate, endDate) + 1
    return (0 until days).all { isFullDay(startDate.plusDays(it), zoneId) }
}
