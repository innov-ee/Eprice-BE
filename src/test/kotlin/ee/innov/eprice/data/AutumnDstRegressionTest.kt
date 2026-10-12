package ee.innov.eprice.data

import ee.innov.eprice.domain.model.isFullDay
import ee.innov.eprice.test.FixedPriceRepository
import ee.innov.eprice.test.InMemoryDailyStatsCache
import ee.innov.eprice.test.dayPrices
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutumnDstRegressionTest {
    private val date = LocalDate.of(2026, 10, 25)
    private val zone = ZoneId.of("Europe/Tallinn")

    @Test
    fun `autumn clock change retains both occurrences of the repeated local hour`() {
        val prices = dayPrices(date, zone)
        assertEquals(100, prices.size)
        assertEquals(Instant.parse("2026-10-24T21:00:00Z"), prices.first().startTime)
        assertEquals(Instant.parse("2026-10-25T21:45:00Z"), prices.last().startTime)
        assertEquals(100, prices.map { it.startTime }.distinct().size)

        val repeatedHour = prices.filter { it.startTime.atZone(zone).hour == 3 }
        assertEquals(8, repeatedHour.size)
        val repeatedThree = repeatedHour.filter { it.startTime.atZone(zone).toLocalTime() == LocalTime.of(3, 0) }
        assertEquals(
            listOf(Instant.parse("2026-10-25T00:00:00Z"), Instant.parse("2026-10-25T01:00:00Z")),
            repeatedThree.map { it.startTime }
        )
        assertEquals(listOf(10800, 7200), repeatedThree.map { it.startTime.atZone(zone).offset.totalSeconds })
        assertTrue(prices.isFullDay(date, zone))
        // Losing the second 03:00-03:45 leaves 96 points, but this is not a full DST day.
        val withoutSecondOccurrence = prices.filterNot {
            val local = it.startTime.atZone(zone)
            local.hour == 3 && local.offset.totalSeconds == 7200
        }
        assertEquals(96, withoutSecondOccurrence.size)
        assertFalse(withoutSecondOccurrence.isFullDay(date, zone))
    }

    @Test
    fun `daily statistics include prices from both repeated hours and cache all 100 intervals`() = runBlocking {
        val prices = dayPrices(date, zone).map {
            val local = it.startTime.atZone(zone)
            // Give the second occurrence a different price to expose accidental deduplication.
            if (local.hour == 3 && local.offset.totalSeconds == 7200) it.copy(pricePerKWh = 0.3) else it
        }
        val cache = InMemoryDailyStatsCache()
        val repo = PriceStatsRepositoryImpl(FixedPriceRepository(Result.success(prices)), cache)
        val stats = repo.getDailyStats("EE", date, date).getOrThrow().getValue(date)
        assertEquals(100, stats.count)
        assertEquals(0.1, stats.min, 1e-12)
        assertEquals(0.3, stats.max, 1e-12)
        assertEquals(10.8, stats.sum, 1e-12)
        assertEquals(0.108, stats.avg, 1e-12)
        assertEquals(stats, cache.get("EE", date))
    }
}
