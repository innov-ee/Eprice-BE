package ee.innov.eprice.domain

import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

object CountryZoneProvider {
    private val defaultZoneId = ZoneId.of("Europe/Tallinn")

    private val countryZoneMap: Map<String, ZoneId> = mapOf(
        // Baltics
        "EE" to ZoneId.of("Europe/Tallinn"),
        "FI" to ZoneId.of("Europe/Helsinki"),
        "LV" to ZoneId.of("Europe/Riga"),
        "LT" to ZoneId.of("Europe/Vilnius"),

        // Nordics
        "SE" to ZoneId.of("Europe/Stockholm"),
        "SE1" to ZoneId.of("Europe/Stockholm"),
        "SE2" to ZoneId.of("Europe/Stockholm"),
        "SE3" to ZoneId.of("Europe/Stockholm"),
        "SE4" to ZoneId.of("Europe/Stockholm"),
        "NO" to ZoneId.of("Europe/Oslo"),
        "NO1" to ZoneId.of("Europe/Oslo"),
        "NO2" to ZoneId.of("Europe/Oslo"),
        "NO3" to ZoneId.of("Europe/Oslo"),
        "NO4" to ZoneId.of("Europe/Oslo"),
        "NO5" to ZoneId.of("Europe/Oslo"),
        "DK" to ZoneId.of("Europe/Copenhagen"),
        "DK1" to ZoneId.of("Europe/Copenhagen"),
        "DK2" to ZoneId.of("Europe/Copenhagen"),

        // Western & Central Europe
        "DE" to ZoneId.of("Europe/Berlin"),
        "DE-LU" to ZoneId.of("Europe/Berlin"),
        "DE_LU" to ZoneId.of("Europe/Berlin"),
        "AT" to ZoneId.of("Europe/Vienna"),
        "BE" to ZoneId.of("Europe/Brussels"),
        "FR" to ZoneId.of("Europe/Paris"),
        "NL" to ZoneId.of("Europe/Amsterdam"),
        "CH" to ZoneId.of("Europe/Zurich"),
        "PL" to ZoneId.of("Europe/Warsaw"),
        "CZ" to ZoneId.of("Europe/Prague"),
        "SK" to ZoneId.of("Europe/Bratislava"),
        "HU" to ZoneId.of("Europe/Budapest"),
        "SI" to ZoneId.of("Europe/Ljubljana"),
        "HR" to ZoneId.of("Europe/Zagreb"),
        "RO" to ZoneId.of("Europe/Bucharest"),
        "BG" to ZoneId.of("Europe/Sofia"),
        "GR" to ZoneId.of("Europe/Athens"),
        "LU" to ZoneId.of("Europe/Luxembourg"),

        // Iberia
        "ES" to ZoneId.of("Europe/Madrid"),
        "PT" to ZoneId.of("Europe/Lisbon"),

        // British Isles
        "GB" to ZoneId.of("Europe/London"),
        "UK" to ZoneId.of("Europe/London"),
        "IE" to ZoneId.of("Europe/Dublin"),
        "NIR" to ZoneId.of("Europe/Belfast"),

        // Italy & Bidding Zones
        "IT" to ZoneId.of("Europe/Rome"),
        "IT-NORD" to ZoneId.of("Europe/Rome"),
        "IT_NORD" to ZoneId.of("Europe/Rome"),
        "IT-CNOR" to ZoneId.of("Europe/Rome"),
        "IT_CNOR" to ZoneId.of("Europe/Rome"),
        "IT-CSUD" to ZoneId.of("Europe/Rome"),
        "IT_CSUD" to ZoneId.of("Europe/Rome"),
        "IT-SUD" to ZoneId.of("Europe/Rome"),
        "IT_SUD" to ZoneId.of("Europe/Rome"),
        "IT-SICI" to ZoneId.of("Europe/Rome"),
        "IT_SICI" to ZoneId.of("Europe/Rome"),
        "IT-SARD" to ZoneId.of("Europe/Rome"),
        "IT_SARD" to ZoneId.of("Europe/Rome"),
        "IT-CALA" to ZoneId.of("Europe/Rome"),
        "IT_CALA" to ZoneId.of("Europe/Rome"),

        // Southeastern Europe & Non-EU
        "RS" to ZoneId.of("Europe/Belgrade"),
        "ME" to ZoneId.of("Europe/Podgorica"),
        "MK" to ZoneId.of("Europe/Skopje"),
        "AL" to ZoneId.of("Europe/Tirane"),
        "BA" to ZoneId.of("Europe/Sarajevo"),
        "XK" to ZoneId.of("Europe/Belgrade"),
        "UA" to ZoneId.of("Europe/Kyiv"),
        "UA-DOB" to ZoneId.of("Europe/Kyiv"),
        "UA_DOB" to ZoneId.of("Europe/Kyiv"),
        "UA-BEI" to ZoneId.of("Europe/Kyiv"),
        "UA_BEI" to ZoneId.of("Europe/Kyiv"),
        "UA-IPS" to ZoneId.of("Europe/Kyiv"),
        "UA_IPS" to ZoneId.of("Europe/Kyiv"),
        "MD" to ZoneId.of("Europe/Chisinau"),
        "GE" to ZoneId.of("Asia/Tbilisi"),
        "TR" to ZoneId.of("Europe/Istanbul"),
        "CY" to ZoneId.of("Asia/Nicosia"),
        "MT" to ZoneId.of("Europe/Malta")
    )

    fun getZoneId(countryCode: String): ZoneId =
        countryZoneMap[countryCode.uppercase().replace('_', '-')]
            ?: countryZoneMap[countryCode.uppercase()]
            ?: defaultZoneId

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
