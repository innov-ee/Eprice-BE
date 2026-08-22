package ee.innov.eprice.domain

import ee.innov.eprice.domain.model.DailyStatEntry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Fakes the stats repository contract for use case testing.
 */
private class FakePriceStatsRepository : PriceStatsRepository {
    var callCount = 0
    val entries = mutableMapOf<LocalDate, DailyStatEntry>()

    var defaultEntryProvider: (LocalDate) -> DailyStatEntry = { _ ->
        DailyStatEntry(
            min = 0.10,
            max = 0.33,
            avg = 0.215,
            sum = 5.16,
            count = 24
        )
    }

    override suspend fun getDailyStats(
        countryCode: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Result<Map<LocalDate, DailyStatEntry>> {
        callCount++
        val totalDays = (ChronoUnit.DAYS.between(startDate, endDate) + 1).toInt()
        val result = (0 until totalDays).map { startDate.plusDays(it.toLong()) }.associateWith { date ->
            entries.getOrPut(date) { defaultEntryProvider(date) }
        }
        return Result.success(result)
    }
}

class GetPriceStatisticsUseCaseTest {

    private lateinit var repo: FakePriceStatsRepository
    private lateinit var useCase: GetPriceStatisticsUseCase

    @BeforeEach
    fun setUp() {
        repo = FakePriceStatsRepository()
        useCase = GetPriceStatisticsUseCase(repo)
    }

    @Test
    fun `executeYesterday calculates single day stats accurately`() = runBlocking {
        val result = useCase.executeYesterday("EE")
        assertTrue(result.isSuccess)

        val stats = result.getOrThrow()
        assertEquals("EE", stats.countryCode)
        assertEquals(1, stats.daysRequested)
        assertEquals(1, stats.daysCalculated)
        assertEquals(0.10, stats.minPrice, 0.0001)
        assertEquals(0.33, stats.maxPrice, 0.0001)
        assertEquals(0.215, stats.averagePrice, 0.0001)
        assertEquals(1, repo.callCount)
    }

    @Test
    fun `execute with 5 days delegates range to stats repository`() = runBlocking {
        val result = useCase.execute("EE", days = 5)
        assertTrue(result.isSuccess)

        val stats = result.getOrThrow()
        assertEquals(5, stats.daysCalculated)
        assertEquals(1, repo.callCount)
    }

    @Test
    fun `execute with explicit date range computes exact weighted average`() = runBlocking {
        val start = LocalDate.of(2026, 8, 10)
        val end = LocalDate.of(2026, 8, 12)

        repo.entries[start] = DailyStatEntry(min = 0.10, max = 0.20, avg = 0.15, sum = 3.6, count = 24)
        repo.entries[start.plusDays(1)] = DailyStatEntry(min = 0.15, max = 0.30, avg = 0.225, sum = 5.4, count = 24)
        repo.entries[end] = DailyStatEntry(min = 0.05, max = 0.25, avg = 0.15, sum = 3.6, count = 24)

        val result = useCase.execute("EE", start, end)
        assertTrue(result.isSuccess)

        val stats = result.getOrThrow()
        assertEquals("2026-08-10", stats.startDate)
        assertEquals("2026-08-12", stats.endDate)
        assertEquals(3, stats.daysCalculated)
        assertEquals(0.05, stats.minPrice, 0.0001)
        assertEquals(0.30, stats.maxPrice, 0.0001)
        // total sum: 3.6 + 5.4 + 3.6 = 12.6, total count = 72 -> avg = 12.6 / 72 = 0.175
        assertEquals(0.175, stats.averagePrice, 0.0001)
    }

    @Test
    fun `invalid inputs return failures`() = runBlocking {
        val negativeDaysResult = useCase.execute("EE", days = -1)
        assertTrue(negativeDaysResult.isFailure)

        val invalidRangeResult = useCase.execute(
            "EE",
            startDate = LocalDate.of(2026, 8, 15),
            endDate = LocalDate.of(2026, 8, 10)
        )
        assertTrue(invalidRangeResult.isFailure)
    }
}
