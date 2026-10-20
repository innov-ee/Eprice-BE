package ee.innov.eprice.data.elering

import ee.innov.eprice.data.PriceStatsRepositoryImpl
import ee.innov.eprice.domain.model.isFullDay
import ee.innov.eprice.test.FixedPriceRepository
import ee.innov.eprice.test.InMemoryDailyStatsCache
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EleringResolutionRegressionTest {
    private val zone = ZoneId.of("Europe/Tallinn")
    private fun response(points: List<EleringPriceData>) = EleringPriceResponse(true, mapOf("ee" to points))

    @Test
    fun `hourly historical prices retain their full hour duration`() {
        val date = LocalDate.of(2025, 9, 30)
        val start = date.atStartOfDay(zone).toInstant()
        val prices = response((0 until 24).map { EleringPriceData(start.plusSeconds(it * 3600L).epochSecond, 100.0) })
            .toDomainEnergyPrices("EE")
        assertTrue(prices.isFullDay(date, zone))
        assertTrue(prices.all { it.durationSeconds == 3600L })
    }

    @Test
    fun `missing three quarters of modern prices cannot appear to be a complete hourly day`() {
        val date = LocalDate.of(2026, 10, 12)
        val start = date.atStartOfDay(zone).toInstant()
        // Only one of each four expected quarter-hour prices is available.
        val prices = response((0 until 24).map { EleringPriceData(start.plusSeconds(it * 3600L).epochSecond, 100.0) })
            .toDomainEnergyPrices("EE")
        assertEquals(24, prices.size)
        assertTrue(prices.all { it.durationSeconds == 900L })
        assertFalse(prices.isFullDay(date, zone))
    }

    @Test
    fun `resolution transition within a local day preserves complete coverage and correct average`() = runBlocking {
        val date = LocalDate.of(2025, 10, 1)
        val start = date.atStartOfDay(zone).toInstant() // 2025-09-30T21:00Z: one final hourly interval.
        val transition = Instant.parse("2025-09-30T22:00:00Z")
        val points = listOf(EleringPriceData(start.epochSecond, 100.0)) +
            (0 until 92).map { EleringPriceData(transition.plusSeconds(it * 900L).epochSecond, 300.0) }
        val prices = response(points).toDomainEnergyPrices("EE")
        assertEquals(93, prices.size)
        assertEquals(3600L, prices.first().durationSeconds)
        assertTrue(prices.drop(1).all { it.durationSeconds == 900L })
        assertTrue(prices.isFullDay(date, zone))
        val stats = PriceStatsRepositoryImpl(FixedPriceRepository(Result.success(prices)), InMemoryDailyStatsCache())
            .getDailyStats("EE", date, date).getOrThrow().getValue(date)
        assertEquals(86400L, stats.durationSeconds)
        assertEquals((0.1 + 23 * 0.3) / 24, stats.avg, 1e-12)
    }
}
