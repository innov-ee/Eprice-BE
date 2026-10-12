package ee.innov.eprice.domain

import ee.innov.eprice.domain.model.isFullDay
import ee.innov.eprice.test.dayPrices
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PriceCoverageRegressionTest {
    @Test
    fun `15 and 30 minute coverage accepts short and long DST days in multiple regions`() {
        listOf("Europe/Tallinn", "Europe/Berlin", "Europe/London").forEach { name ->
            val zone = ZoneId.of(name)
            listOf(LocalDate.of(2026, 3, 29) to 23, LocalDate.of(2026, 10, 25) to 25).forEach { (date, hours) ->
                listOf(900L, 1800L).forEach { step ->
                    val prices = dayPrices(date, zone, step)
                    assertEquals(hours * 3600 / step, prices.size.toLong())
                    assertTrue(prices.isFullDay(date, zone), "$name $date $step")
                }
            }
        }
    }

    @Test
    fun `quarter hourly coverage rejects a missing or duplicate interval even with enough samples`() {
        val zone = ZoneId.of("Europe/Berlin")
        val date = LocalDate.of(2026, 8, 22)
        val prices = dayPrices(date, zone)
        assertFalse(prices.filterIndexed { i, _ -> i != 40 }.isFullDay(date, zone))
        assertFalse(prices.mapIndexed { i, value -> if (i == 40) prices[39] else value }.isFullDay(date, zone))
        assertFalse((prices + prices.first()).isFullDay(date, zone))
    }

    @Test
    fun `prices from neighbouring days cannot make an incomplete local day complete`() {
        val zone = ZoneId.of("Europe/Lisbon")
        val date = LocalDate.of(2026, 8, 22)
        val previous = dayPrices(date.minusDays(1), zone)
        val next = dayPrices(date.plusDays(1), zone)
        val today = dayPrices(date, zone)
        assertTrue((previous + today + next).isFullDay(date, zone))
        assertFalse((previous + today.dropLast(1) + next).isFullDay(date, zone))
    }
}
