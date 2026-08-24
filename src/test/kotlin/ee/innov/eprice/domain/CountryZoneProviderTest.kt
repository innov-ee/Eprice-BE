package ee.innov.eprice.domain

import ee.innov.eprice.domain.model.DailyStatEntry
import ee.innov.eprice.domain.model.DomainEnergyPrice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

class CountryZoneProviderTest {

    private val tallinnZone = ZoneId.of("Europe/Tallinn")

    @Test
    fun `getZoneId maps known countries correctly and defaults to Europe Tallinn`() {
        assertEquals(ZoneId.of("Europe/Tallinn"), CountryZoneProvider.getZoneId("EE"))
        assertEquals(ZoneId.of("Europe/Helsinki"), CountryZoneProvider.getZoneId("FI"))
        assertEquals(ZoneId.of("Europe/Riga"), CountryZoneProvider.getZoneId("LV"))
        assertEquals(ZoneId.of("Europe/Vilnius"), CountryZoneProvider.getZoneId("LT"))
        assertEquals(ZoneId.of("Europe/Tallinn"), CountryZoneProvider.getZoneId("UNKNOWN"))
    }

    @Test
    fun `getExpectedHoursForDay correctly calculates standard days and DST transitions`() {
        // Standard summer day (24 hours)
        val summerDate = LocalDate.of(2026, 8, 22)
        assertEquals(24, CountryZoneProvider.getExpectedHoursForDay(summerDate, tallinnZone))

        // Standard winter day (24 hours)
        val winterDate = LocalDate.of(2026, 1, 15)
        assertEquals(24, CountryZoneProvider.getExpectedHoursForDay(winterDate, tallinnZone))

        // Spring DST transition in 2026 (Sunday, March 29, 2026 - clocks move ahead, 23 hours)
        val springDst = LocalDate.of(2026, 3, 29)
        assertEquals(23, CountryZoneProvider.getExpectedHoursForDay(springDst, tallinnZone))

        // Autumn DST transition in 2026 (Sunday, October 25, 2026 - clocks move back, 25 hours)
        val autumnDst = LocalDate.of(2026, 10, 25)
        assertEquals(25, CountryZoneProvider.getExpectedHoursForDay(autumnDst, tallinnZone))
    }

    @Test
    fun `isFullDayData accepts complete 24-hour data and rejects partial 1-2 hours data`() {
        val date = LocalDate.of(2026, 8, 23)
        val dayStart = date.atStartOfDay(tallinnZone).toInstant()

        // Complete 24-hour dataset
        val fullDayPrices = (0 until 24).map { hour ->
            DomainEnergyPrice(dayStart.plusSeconds(hour * 3600L), 0.10)
        }
        assertTrue(CountryZoneProvider.isFullDayData(fullDayPrices, date, tallinnZone))

        // Partial 2-hour dataset (e.g. from tomorrow with only UTC offset available)
        val partialPrices = (0 until 2).map { hour ->
            DomainEnergyPrice(dayStart.plusSeconds(hour * 3600L), 0.10)
        }
        assertFalse(CountryZoneProvider.isFullDayData(partialPrices, date, tallinnZone))

        // Empty list
        assertFalse(CountryZoneProvider.isFullDayData(emptyList(), date, tallinnZone))
    }

    @Test
    fun `isFullDayData works with 15-minute resolution data`() {
        val date = LocalDate.of(2026, 8, 23)
        val dayStart = date.atStartOfDay(tallinnZone).toInstant()

        // Complete 96-interval dataset (15-minute intervals)
        val fullDay15m = (0 until 96).map { interval ->
            DomainEnergyPrice(dayStart.plusSeconds(interval * 900L), 0.10)
        }
        assertTrue(CountryZoneProvider.isFullDayData(fullDay15m, date, tallinnZone))

        // Incomplete 15m dataset (e.g. 2 hours = 8 intervals)
        val partial15m = (0 until 8).map { interval ->
            DomainEnergyPrice(dayStart.plusSeconds(interval * 900L), 0.10)
        }
        assertFalse(CountryZoneProvider.isFullDayData(partial15m, date, tallinnZone))
    }

    @Test
    fun `isFullDayEntry validates daily stat entry counts against expected hours`() {
        val standardDate = LocalDate.of(2026, 8, 23)
        val springDst = LocalDate.of(2026, 3, 29) // 23h
        val autumnDst = LocalDate.of(2026, 10, 25) // 25h

        val full24Entry = DailyStatEntry(0.1, 0.2, 0.15, 3.6, 24)
        val partial2Entry = DailyStatEntry(0.1, 0.2, 0.15, 0.3, 2)
        val entry23 = DailyStatEntry(0.1, 0.2, 0.15, 3.45, 23)

        assertTrue(CountryZoneProvider.isFullDayEntry(full24Entry, standardDate, tallinnZone))
        assertFalse(CountryZoneProvider.isFullDayEntry(partial2Entry, standardDate, tallinnZone))

        // On 23h spring day, 23 count is full day
        assertTrue(CountryZoneProvider.isFullDayEntry(entry23, springDst, tallinnZone))
        // On 24h standard day, 23 count is not full day
        assertFalse(CountryZoneProvider.isFullDayEntry(entry23, standardDate, tallinnZone))
        // On 25h autumn day, 24 count is not full day
        assertFalse(CountryZoneProvider.isFullDayEntry(full24Entry, autumnDst, tallinnZone))
    }
}
