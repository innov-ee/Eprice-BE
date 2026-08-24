package ee.innov.eprice.domain.model

import ee.innov.eprice.domain.CountryZoneProvider
import ee.innov.eprice.util.InstantSerializer
import kotlinx.serialization.Serializable
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
 * Rejects partial day data (such as 1-2 hours available due to timezone offsets).
 */
fun List<DomainEnergyPrice>.isFullDay(date: LocalDate, zoneId: ZoneId): Boolean {
    if (isEmpty()) return false
    val dayStart = date.atStartOfDay(zoneId).toInstant()
    val nextDayStart = date.plusDays(1).atStartOfDay(zoneId)
    val dayEnd = nextDayStart.minusSeconds(1).toInstant()
    val expectedHours = CountryZoneProvider.getExpectedHoursForDay(date, zoneId)

    if (size < expectedHours) return false

    val minStartTime = minOf { it.startTime }
    val maxStartTime = maxOf { it.startTime }
    val lastHourThreshold = dayEnd.minus(1, ChronoUnit.HOURS)

    return minStartTime <= dayStart && maxStartTime >= lastHourThreshold
}