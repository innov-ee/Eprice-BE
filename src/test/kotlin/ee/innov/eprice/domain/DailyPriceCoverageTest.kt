package ee.innov.eprice.domain

import ee.innov.eprice.domain.model.DailyStatEntry
import ee.innov.eprice.domain.model.DomainEnergyPrice
import ee.innov.eprice.domain.model.isCompleteRange
import ee.innov.eprice.domain.model.isFullDay
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

class DailyPriceCoverageTest {

    private val tallinnZone = ZoneId.of("Europe/Tallinn")

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
            DomainEnergyPrice(dayStart.plusSeconds(interval * 900L), 0.10, durationSeconds = 900)
        }
        assertTrue(fullDay15m.isFullDay(date, tallinnZone))

        // Incomplete 15m dataset (e.g. 2 hours = 8 intervals)
        val partial15m = (0 until 8).map { interval ->
            DomainEnergyPrice(dayStart.plusSeconds(interval * 900L), 0.10, durationSeconds = 900)
        }
        assertFalse(partial15m.isFullDay(date, tallinnZone))
    }

    @Test
    fun `DailyStatEntry isFullDay validates covered duration against the actual local day`() {
        val standardDate = LocalDate.of(2026, 8, 23)
        val springDst = LocalDate.of(2026, 3, 29) // 23h
        val autumnDst = LocalDate.of(2026, 10, 25) // 25h

        val full24Entry = DailyStatEntry(0.1, 0.2, 0.15, 3.6, 24)
        val partial2Entry = DailyStatEntry(0.1, 0.2, 0.15, 0.3, 2)
        val entry23 = DailyStatEntry(0.1, 0.2, 0.15, 3.45, 23)
        val entry25 = DailyStatEntry(0.1, 0.2, 0.15, 3.75, 25)

        assertTrue(full24Entry.isFullDay(standardDate, tallinnZone))
        assertFalse(partial2Entry.isFullDay(standardDate, tallinnZone))

        // On 23h spring day, 23 count is full day
        assertTrue(entry23.isFullDay(springDst, tallinnZone))
        // On 24h standard day, 23 count is not full day
        assertFalse(entry23.isFullDay(standardDate, tallinnZone))
        // On 25h autumn day, 24 count is not full day
        assertFalse(full24Entry.isFullDay(autumnDst, tallinnZone))
        assertTrue(entry25.isFullDay(autumnDst, tallinnZone))
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
