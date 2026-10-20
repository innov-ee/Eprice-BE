package ee.innov.eprice.domain

import ee.innov.eprice.domain.model.DailyStatEntry
import ee.innov.eprice.domain.model.PriceStatistics
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertNull

class PriceStatisticsTest {
    @Test
    fun `compute returns null for empty collection or empty map slice`() {
        val start = LocalDate.of(2026, 8, 10)
        val end = LocalDate.of(2026, 8, 12)
        assertNull(PriceStatistics.compute("EE", start, end, 3, emptyList()))

        val outOfRangeMap = mapOf(
            LocalDate.of(2026, 8, 15) to DailyStatEntry(min = 0.1, max = 0.2, avg = 0.15, sum = 3.6, count = 24)
        )
        assertNull(PriceStatistics.compute("EE", outOfRangeMap, start, end, 3))
    }
}
