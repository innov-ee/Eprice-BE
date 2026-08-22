package ee.innov.eprice.domain

import ee.innov.eprice.domain.model.DailyStatEntry
import ee.innov.eprice.domain.model.NoDataFoundException
import ee.innov.eprice.domain.model.PriceStatsQuery
import ee.innov.eprice.domain.model.PriceStatsQuery.NamedRange
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Fakes the stats repository contract for use case testing.
 */
private class FakePriceStatsRepository : PriceStatsRepository {
    var callCount = 0
    val entries = mutableMapOf<LocalDate, DailyStatEntry>()

    var defaultEntryProvider: (LocalDate) -> DailyStatEntry? = { _ ->
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
        val result = (0 until totalDays)
            .map { startDate.plusDays(it.toLong()) }
            .mapNotNull { date ->
                val entry = entries[date] ?: defaultEntryProvider(date)
                entry?.let { date to it }
            }
            .toMap()
        return Result.success(result)
    }
}

class GetPriceStatisticsUseCaseTest {

    private val fixedInstant = Instant.parse("2026-08-22T10:00:00Z")
    private val fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
    private lateinit var repo: FakePriceStatsRepository
    private lateinit var useCase: GetPriceStatisticsUseCase

    @BeforeEach
    fun setUp() {
        repo = FakePriceStatsRepository()
        useCase = GetPriceStatisticsUseCase(repo, fixedClock)
    }

    @Test
    fun `execute named yesterday calculates single day stats accurately`() = runBlocking {
        val result = useCase.execute("EE", PriceStatsQuery.Named(NamedRange.YESTERDAY))
        assertTrue(result.isSuccess)

        val stats = result.getOrThrow()
        assertEquals("EE", stats.countryCode)
        assertEquals("2026-08-21", stats.startDate)
        assertEquals("2026-08-21", stats.endDate)
        assertEquals(1, stats.daysRequested)
        assertEquals(1, stats.daysCalculated)
        assertEquals(0.10, stats.minPrice, 0.0001)
        assertEquals(0.33, stats.maxPrice, 0.0001)
        assertEquals(0.215, stats.averagePrice, 0.0001)
        assertEquals(1, repo.callCount)
    }

    @Test
    fun `execute named today calculates stats for current date`() = runBlocking {
        val result = useCase.execute("EE", PriceStatsQuery.Named(NamedRange.TODAY))
        assertTrue(result.isSuccess)

        val stats = result.getOrThrow()
        assertEquals("2026-08-22", stats.startDate)
        assertEquals("2026-08-22", stats.endDate)
        assertEquals(1, stats.daysRequested)
        assertEquals(1, stats.daysCalculated)
    }

    @Test
    fun `execute named tomorrow succeeds when tomorrow prices are available`() = runBlocking {
        val result = useCase.execute("EE", PriceStatsQuery.Named(NamedRange.TOMORROW))
        assertTrue(result.isSuccess)

        val stats = result.getOrThrow()
        assertEquals("2026-08-23", stats.startDate)
        assertEquals("2026-08-23", stats.endDate)
        assertEquals(1, stats.daysRequested)
        assertEquals(1, stats.daysCalculated)
    }

    @Test
    fun `execute named tomorrow returns NoDataFoundException when not published yet`() = runBlocking {
        // Provider returns null (no data yet for tomorrow)
        repo.defaultEntryProvider = { null }

        val result = useCase.execute("EE", PriceStatsQuery.Named(NamedRange.TOMORROW))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is NoDataFoundException)
    }

    @Test
    fun `execute with custom range spanning into unpublished tomorrow calculates available days`() = runBlocking {
        val start = LocalDate.of(2026, 8, 20)
        val end = LocalDate.of(2026, 8, 23) // 4 days requested

        // 2026-08-23 has no data
        repo.entries[LocalDate.of(2026, 8, 20)] = DailyStatEntry(min = 0.10, max = 0.20, avg = 0.15, sum = 3.6, count = 24)
        repo.entries[LocalDate.of(2026, 8, 21)] = DailyStatEntry(min = 0.15, max = 0.25, avg = 0.20, sum = 4.8, count = 24)
        repo.entries[LocalDate.of(2026, 8, 22)] = DailyStatEntry(min = 0.05, max = 0.15, avg = 0.10, sum = 2.4, count = 24)
        repo.defaultEntryProvider = { null }

        val result = useCase.execute("EE", PriceStatsQuery.CustomRange(start, end))
        assertTrue(result.isSuccess)

        val stats = result.getOrThrow()
        assertEquals("2026-08-20", stats.startDate)
        assertEquals("2026-08-23", stats.endDate)
        assertEquals(4, stats.daysRequested)
        assertEquals(3, stats.daysCalculated)
        assertEquals(0.05, stats.minPrice, 0.0001)
        assertEquals(0.25, stats.maxPrice, 0.0001)
        // sum = 3.6 + 4.8 + 2.4 = 10.8, count = 72 -> avg = 0.15
        assertEquals(0.15, stats.averagePrice, 0.0001)
    }

    @Test
    fun `execute with 5 days delegates range to stats repository`() = runBlocking {
        val result = useCase.execute("EE", days = 5)
        assertTrue(result.isSuccess)

        val stats = result.getOrThrow()
        assertEquals("2026-08-17", stats.startDate)
        assertEquals("2026-08-21", stats.endDate)
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

    @Test
    fun `execute with days exceeding max range is rejected`() = runBlocking {
        val result = useCase.execute("EE", days = 1000)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        // Guards against fan-out of 1000 concurrent upstream fetches (repository was never called)
        assertEquals(0, repo.callCount)
    }
}
