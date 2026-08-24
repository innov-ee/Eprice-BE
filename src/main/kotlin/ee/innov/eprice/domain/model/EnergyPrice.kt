package ee.innov.eprice.domain.model

import ee.innov.eprice.domain.CountryZoneProvider
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
    val pricePerKWh: Double
)

/**
 * Checks if the list of energy prices provides complete coverage for the entire local day in the given timezone.
 * Rejects partial day data (such as 1-2 hours available due to timezone offsets) and datasets with missing/duplicate intervals.
 */
fun List<DomainEnergyPrice>.isFullDay(date: LocalDate, zoneId: ZoneId): Boolean {
    if (isEmpty()) return false
    val dayStart = date.atStartOfDay(zoneId).toInstant()
    val nextDayStart = date.plusDays(1).atStartOfDay(zoneId).toInstant()
    val dayPrices = this.filter { it.startTime >= dayStart && it.startTime < nextDayStart }
    if (dayPrices.isEmpty()) return false

    val expectedHours = CountryZoneProvider.getExpectedHoursForDay(date, zoneId)
    val sorted = dayPrices.sortedBy { it.startTime }
    val count = sorted.size

    if (count < expectedHours) return false

    val totalSeconds = Duration.between(dayStart, nextDayStart).seconds
    if (totalSeconds % count != 0L) return false

    val stepSeconds = totalSeconds / count
    for (i in 0 until count) {
        val expectedTime = dayStart.plusSeconds(i * stepSeconds)
        if (sorted[i].startTime != expectedTime) {
            return false
        }
    }
    return true
}

/**
 * Checks if the list of energy prices completely and gaplessly covers every local day in the range [start, end].
 */
fun List<DomainEnergyPrice>.isCompleteRange(zoneId: ZoneId, start: Instant, end: Instant): Boolean {
    if (isEmpty()) return false
    val startDate = start.atZone(zoneId).toLocalDate()
    val endDate = end.atZone(zoneId).toLocalDate()
    val totalDays = ChronoUnit.DAYS.between(startDate, endDate) + 1
    if (totalDays <= 0) return false

    for (i in 0 until totalDays) {
        val date = startDate.plusDays(i)
        if (!this.isFullDay(date, zoneId)) {
            return false
        }
    }
    return true
}