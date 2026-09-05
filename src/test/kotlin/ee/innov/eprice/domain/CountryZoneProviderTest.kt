package ee.innov.eprice.domain

import ee.innov.eprice.domain.model.DailyStatEntry
import ee.innov.eprice.domain.model.DomainEnergyPrice
import ee.innov.eprice.domain.model.isCompleteRange
import ee.innov.eprice.domain.model.isFullDay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

class CountryZoneProviderTest {

    private val tallinnZone = ZoneId.of("Europe/Tallinn")

    @Test
    fun `normalizeCountryCode handles whitespace case and hyphens`() {
        assertEquals("EE", "ee".normalizeCountryCode())
        assertEquals("EE", "  EE  ".normalizeCountryCode())
        assertEquals("DE_LU", "de-lu".normalizeCountryCode())
        assertEquals("DE_LU", "DE-LU".normalizeCountryCode())
        assertEquals("IT_NORD", "it-nord".normalizeCountryCode())
        assertEquals("NO_1", "no-1".normalizeCountryCode())
        assertEquals("SE_1", "se_1".normalizeCountryCode())
    }

    @Test
    fun `getZoneId maps known countries correctly and defaults to Europe Tallinn`() {
        assertEquals(ZoneId.of("Europe/Tallinn"), CountryZoneProvider.getZoneId("EE"))
        assertEquals(ZoneId.of("Europe/Helsinki"), CountryZoneProvider.getZoneId("FI"))
        assertEquals(ZoneId.of("Europe/Riga"), CountryZoneProvider.getZoneId("LV"))
        assertEquals(ZoneId.of("Europe/Vilnius"), CountryZoneProvider.getZoneId("LT"))
        assertEquals(ZoneId.of("Europe/Berlin"), CountryZoneProvider.getZoneId("DE"))
        assertEquals(ZoneId.of("Europe/Berlin"), CountryZoneProvider.getZoneId("DE_LU"))
        assertEquals(ZoneId.of("Europe/Paris"), CountryZoneProvider.getZoneId("FR"))
        assertEquals(ZoneId.of("Europe/Stockholm"), CountryZoneProvider.getZoneId("SE3"))
        assertEquals(ZoneId.of("Europe/Oslo"), CountryZoneProvider.getZoneId("NO1"))
        assertEquals(ZoneId.of("Europe/Lisbon"), CountryZoneProvider.getZoneId("PT"))
        assertEquals(ZoneId.of("Europe/London"), CountryZoneProvider.getZoneId("UK"))
        assertEquals(ZoneId.of("Europe/Dublin"), CountryZoneProvider.getZoneId("IE"))
        assertEquals(ZoneId.of("Europe/Rome"), CountryZoneProvider.getZoneId("IT_NORD"))
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
    fun `isFullDay accepts complete 24-hour data and rejects partial 1-2 hours data`() {
        val date = LocalDate.of(2026, 8, 23)
        val dayStart = date.atStartOfDay(tallinnZone).toInstant()

        // Complete 24-hour dataset
        val fullDayPrices = (0 until 24).map { hour ->
            DomainEnergyPrice(dayStart.plusSeconds(hour * 3600L), 0.10)
        }
        assertTrue(fullDayPrices.isFullDay(date, tallinnZone))

        // Partial 2-hour dataset (e.g. from tomorrow with only UTC offset available)
        val partialPrices = (0 until 2).map { hour ->
            DomainEnergyPrice(dayStart.plusSeconds(hour * 3600L), 0.10)
        }
        assertFalse(partialPrices.isFullDay(date, tallinnZone))

        // Empty list
        assertFalse(emptyList<DomainEnergyPrice>().isFullDay(date, tallinnZone))
    }

    @Test
    fun `isFullDay works with 15-minute resolution data`() {
        val date = LocalDate.of(2026, 8, 23)
        val dayStart = date.atStartOfDay(tallinnZone).toInstant()

        // Complete 96-interval dataset (15-minute intervals)
        val fullDay15m = (0 until 96).map { interval ->
            DomainEnergyPrice(dayStart.plusSeconds(interval * 900L), 0.10)
        }
        assertTrue(fullDay15m.isFullDay(date, tallinnZone))

        // Incomplete 15m dataset (e.g. 2 hours = 8 intervals)
        val partial15m = (0 until 8).map { interval ->
            DomainEnergyPrice(dayStart.plusSeconds(interval * 900L), 0.10)
        }
        assertFalse(partial15m.isFullDay(date, tallinnZone))
    }

    @Test
    fun `DailyStatEntry isFullDay validates daily stat entry counts against expected hours`() {
        val standardDate = LocalDate.of(2026, 8, 23)
        val springDst = LocalDate.of(2026, 3, 29) // 23h
        val autumnDst = LocalDate.of(2026, 10, 25) // 25h

        val full24Entry = DailyStatEntry(0.1, 0.2, 0.15, 3.6, 24)
        val partial2Entry = DailyStatEntry(0.1, 0.2, 0.15, 0.3, 2)
        val entry23 = DailyStatEntry(0.1, 0.2, 0.15, 3.45, 23)

        assertTrue(full24Entry.isFullDay(standardDate, tallinnZone))
        assertFalse(partial2Entry.isFullDay(standardDate, tallinnZone))

        // On 23h spring day, 23 count is full day
        assertTrue(entry23.isFullDay(springDst, tallinnZone))
        // On 24h standard day, 23 count is not full day
        assertFalse(entry23.isFullDay(standardDate, tallinnZone))
        // On 25h autumn day, 24 count is not full day
        assertFalse(full24Entry.isFullDay(autumnDst, tallinnZone))
    }

    @Test
    fun `isFullDay rejects datasets with gaps or duplicate timestamps`() {
        val date = LocalDate.of(2026, 8, 23)
        val dayStart = date.atStartOfDay(tallinnZone).toInstant()

        // 24 items, but hour 10 is duplicated and hour 11 is missing (gap!)
        val gappedPrices = (0 until 24).map { hour ->
            val actualHour = if (hour == 11) 10 else hour
            DomainEnergyPrice(dayStart.plusSeconds(actualHour * 3600L), 0.10)
        }
        assertFalse(gappedPrices.isFullDay(date, tallinnZone))

        // Starts shifted by 1 hour (01:00 to 00:00 next day)
        val shiftedPrices = (1..24).map { hour ->
            DomainEnergyPrice(dayStart.plusSeconds(hour * 3600L), 0.10)
        }
        assertFalse(shiftedPrices.isFullDay(date, tallinnZone))
    }

    @Test
    fun `isCompleteRange validates all days in multi-day range`() {
        val day1 = LocalDate.of(2026, 8, 22)
        val day2 = LocalDate.of(2026, 8, 23)
        val day3 = LocalDate.of(2026, 8, 24)

        val start = day1.atStartOfDay(tallinnZone).toInstant()
        val end = day3.plusDays(1).atStartOfDay(tallinnZone).minusSeconds(1).toInstant()

        fun makeDayPrices(d: LocalDate) = (0 until 24).map { hour ->
            DomainEnergyPrice(d.atStartOfDay(tallinnZone).toInstant().plusSeconds(hour * 3600L), 0.10)
        }

        val full3Days = makeDayPrices(day1) + makeDayPrices(day2) + makeDayPrices(day3)
        assertTrue(full3Days.isCompleteRange(tallinnZone, start, end))

        // If day 3 (tomorrow) is incomplete (only 2 hours available)
        val partialDay3 = (0 until 2).map { hour ->
            DomainEnergyPrice(day3.atStartOfDay(tallinnZone).toInstant().plusSeconds(hour * 3600L), 0.10)
        }
        val incomplete3Days = makeDayPrices(day1) + makeDayPrices(day2) + partialDay3
        assertFalse(incomplete3Days.isCompleteRange(tallinnZone, start, end))

        // Empty list
        assertFalse(emptyList<DomainEnergyPrice>().isCompleteRange(tallinnZone, start, end))
    }
}
