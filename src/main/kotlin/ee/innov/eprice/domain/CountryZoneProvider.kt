package ee.innov.eprice.domain

import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

private val biddingZoneCodeAliases = mapOf(
    "DE-LU" to "DE_LU",
    "IT-NORD" to "IT_NORD",
    "IT-CNOR" to "IT_CNOR",
    "IT-CSUD" to "IT_CSUD",
    "IT-SUD" to "IT_SUD",
    "IT-SICI" to "IT_SICI",
    "IT-SARD" to "IT_SARD",
    "IT-CALA" to "IT_CALA",
    "NO-1" to "NO1", "NO_1" to "NO1",
    "NO-2" to "NO2", "NO_2" to "NO2",
    "NO-3" to "NO3", "NO_3" to "NO3",
    "NO-4" to "NO4", "NO_4" to "NO4",
    "NO-5" to "NO5", "NO_5" to "NO5",
    "SE-1" to "SE1", "SE_1" to "SE1",
    "SE-2" to "SE2", "SE_2" to "SE2",
    "SE-3" to "SE3", "SE_3" to "SE3",
    "SE-4" to "SE4", "SE_4" to "SE4",
    "DK-1" to "DK1", "DK_1" to "DK1",
    "DK-2" to "DK2", "DK_2" to "DK2"
)

/**
 * Normalizes case, whitespace and supported aliases (e.g., "no-1" -> "NO1").
 * EIC identifiers retain their hyphens; supported areas are validated by BiddingZones.
 */
fun String.normalizeCountryCode(): String {
    val code = trim().uppercase(Locale.ROOT)
    return biddingZoneCodeAliases[code] ?: code
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
