package ee.innov.eprice.domain

import ee.innov.eprice.domain.model.DailyStatEntry
import ee.innov.eprice.domain.model.DomainEnergyPrice
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

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

    /**
     * Checks if the list of energy prices provides complete coverage for the entire local day.
     * Rejects partial day data (such as 1-2 hours available due to timezone offsets).
     */
    fun isFullDayData(prices: List<DomainEnergyPrice>, date: LocalDate, zoneId: ZoneId): Boolean {
        if (prices.isEmpty()) return false
        val dayStart = date.atStartOfDay(zoneId).toInstant()
        val nextDayStart = date.plusDays(1).atStartOfDay(zoneId)
        val dayEnd = nextDayStart.minusSeconds(1).toInstant()
        val expectedHours = getExpectedHoursForDay(date, zoneId)

        if (prices.size < expectedHours) return false

        val minStartTime = prices.minOf { it.startTime }
        val maxStartTime = prices.maxOf { it.startTime }
        val lastHourThreshold = dayEnd.minus(1, ChronoUnit.HOURS)

        return minStartTime <= dayStart && maxStartTime >= lastHourThreshold
    }

    /**
     * Checks if a [DailyStatEntry] contains enough data points to represent a full day.
     */
    fun isFullDayEntry(entry: DailyStatEntry, date: LocalDate, zoneId: ZoneId): Boolean {
        return entry.count >= getExpectedHoursForDay(date, zoneId)
    }
}
