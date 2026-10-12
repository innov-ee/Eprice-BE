package ee.innov.eprice.domain

import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/**
 * Normalizes country and bidding zone codes (e.g., " ee " -> "EE", "no-1" -> "NO_1").
 */
fun String.normalizeCountryCode(): String {
    val code = trim().uppercase(java.util.Locale.ROOT)
    if (Regex("[0-9]{2}Y[A-Z0-9-]{13}").matches(code)) return code
    return code.replace('-', '_').replace(Regex("^(NO|SE|DK)_([0-9])$"), "$1$2")
}

object CountryZoneProvider {
    fun getZoneId(countryCode: String): ZoneId = BiddingZones.resolve(countryCode).zoneId

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
