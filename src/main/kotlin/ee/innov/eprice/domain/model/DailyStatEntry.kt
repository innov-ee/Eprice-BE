package ee.innov.eprice.domain.model

import ee.innov.eprice.domain.CountryZoneProvider
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId

@Serializable
data class DailyStatEntry(
    val min: Double,
    val max: Double,
    val avg: Double,
    val sum: Double,
    val count: Int
) {
    /**
     * Checks if this stat entry has enough data points to represent a full day in the given timezone.
     */
    fun isFullDay(date: LocalDate, zoneId: ZoneId): Boolean {
        return count >= CountryZoneProvider.getExpectedHoursForDay(date, zoneId)
    }
}
