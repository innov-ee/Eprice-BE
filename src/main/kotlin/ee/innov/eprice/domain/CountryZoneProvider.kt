package ee.innov.eprice.domain

import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

object CountryZoneProvider {
    private val defaultZoneId = ZoneId.of("Europe/Tallinn")

    private val countryZoneMap: Map<String, ZoneId> = mapOf(
        "EE" to ZoneId.of("Europe/Tallinn"),
        "FI" to ZoneId.of("Europe/Helsinki"),
        "LV" to ZoneId.of("Europe/Riga"),
        "LT" to ZoneId.of("Europe/Vilnius")
    )

    fun getZoneId(countryCode: String): ZoneId =
        countryZoneMap[countryCode.uppercase()] ?: defaultZoneId

    /**
     * Calculates the expected number of hours for a specific date in the given timezone,
     * correctly accounting for standard days (24h), DST spring transitions (23h),
     * and DST autumn transitions (25h).
     */
    fun getExpectedHoursForDay(date: LocalDate, zoneId: ZoneId): Int {
        val start = date.atStartOfDay(zoneId)
        val nextStart = date.plusDays(1).atStartOfDay(zoneId)
        return Duration.between(start, nextStart).toHours().toInt()
    }
}
