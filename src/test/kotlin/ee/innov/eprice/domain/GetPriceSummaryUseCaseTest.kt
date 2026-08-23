package ee.innov.eprice.domain

import ee.innov.eprice.domain.model.DailyStatEntry
import ee.innov.eprice.domain.model.NoDataFoundException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

private class FakePriceStatsRepositoryForSummary : PriceStatsRepository {
    var callCount = 0
    val entries = mutableMapOf<LocalDate, DailyStatEntry>()

    var defaultEntryProvider: (LocalDate) -> DailyStatEntry? = { _ ->
        DailyStatEntry(
            min = 0.10,
            max = 0.30,
            avg = 0.20,
            sum = 4.80,
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

class GetPriceSummaryUseCaseTest {

    // 2026-08-22 10:00 UTC -> in Estonia (UTC+3) today is 2026-08-22
    private val fixedInstant = Instant.parse("2026-08-22T10:00:00Z")
    private val fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
    private lateinit var repo: FakePriceStatsRepositoryForSummary
    private lateinit var useCase: GetPriceSummaryUseCase

    @BeforeEach
    fun setUp() {
        repo = FakePriceStatsRepositoryForSummary()
        useCase = GetPriceSummaryUseCase(repo, fixedClock, rollingDays = 5)
    }

    @Test
    fun `execute when tomorrow prices are available includes tomorrow and shifts rolling window to tomorrow`() = runBlocking {
        // Today is 2026-08-22, tomorrow is 2026-08-23
        repo.entries[LocalDate.of(2026, 8, 19)] = DailyStatEntry(min = 0.10, max = 0.20, avg = 0.15, sum = 3.6, count = 24)
        repo.entries[LocalDate.of(2026, 8, 20)] = DailyStatEntry(min = 0.12, max = 0.22, avg = 0.17, sum = 4.08, count = 24)
        repo.entries[LocalDate.of(2026, 8, 21)] = DailyStatEntry(min = 0.14, max = 0.24, avg = 0.19, sum = 4.56, count = 24) // yesterday
        repo.entries[LocalDate.of(2026, 8, 22)] = DailyStatEntry(min = 0.16, max = 0.26, avg = 0.21, sum = 5.04, count = 24) // today
        repo.entries[LocalDate.of(2026, 8, 23)] = DailyStatEntry(min = 0.18, max = 0.28, avg = 0.23, sum = 5.52, count = 24) // tomorrow
        repo.defaultEntryProvider = { null }

        val result = useCase.execute("EE")
        assertTrue(result.isSuccess)

        val summary = result.getOrThrow()
        assertEquals("EE", summary.countryCode)
        assertEquals(1, repo.callCount)

        // Yesterday
        assertEquals("2026-08-21", summary.yesterday.startDate)
        assertEquals("2026-08-21", summary.yesterday.endDate)
        assertEquals(0.14, summary.yesterday.minPrice, 0.0001)
        assertEquals(0.24, summary.yesterday.maxPrice, 0.0001)
        assertEquals(0.19, summary.yesterday.averagePrice, 0.0001)

        // Today
        assertEquals("2026-08-22", summary.today.startDate)
        assertEquals("2026-08-22", summary.today.endDate)
        assertEquals(0.16, summary.today.minPrice, 0.0001)
        assertEquals(0.26, summary.today.maxPrice, 0.0001)
        assertEquals(0.21, summary.today.averagePrice, 0.0001)

        // Tomorrow (available)
        assertNotNull(summary.tomorrow)
        val tomorrow = summary.tomorrow!!
        assertEquals("2026-08-23", tomorrow.startDate)
        assertEquals("2026-08-23", tomorrow.endDate)
        assertEquals(0.18, tomorrow.minPrice, 0.0001)
        assertEquals(0.28, tomorrow.maxPrice, 0.0001)
        assertEquals(0.23, tomorrow.averagePrice, 0.0001)

        // Rolling 5-day window ends on tomorrow (2026-08-19 to 2026-08-23)
        assertEquals("2026-08-19", summary.rolling.startDate)
        assertEquals("2026-08-23", summary.rolling.endDate)
        assertEquals(5, summary.rolling.daysRequested)
        assertEquals(5, summary.rolling.daysCalculated)
        assertEquals(0.10, summary.rolling.minPrice, 0.0001)
        assertEquals(0.28, summary.rolling.maxPrice, 0.0001)
        // total sum: 3.6 + 4.08 + 4.56 + 5.04 + 5.52 = 22.8, total count: 120 -> 22.8 / 120 = 0.19
        assertEquals(0.19, summary.rolling.averagePrice, 0.0001)
    }

    @Test
    fun `execute when tomorrow prices not published returns null tomorrow and ends rolling on today`() = runBlocking {
        // Today is 2026-08-22, tomorrow 2026-08-23 has no data
        repo.entries[LocalDate.of(2026, 8, 18)] = DailyStatEntry(min = 0.08, max = 0.18, avg = 0.13, sum = 3.12, count = 24)
        repo.entries[LocalDate.of(2026, 8, 19)] = DailyStatEntry(min = 0.10, max = 0.20, avg = 0.15, sum = 3.6, count = 24)
        repo.entries[LocalDate.of(2026, 8, 20)] = DailyStatEntry(min = 0.12, max = 0.22, avg = 0.17, sum = 4.08, count = 24)
        repo.entries[LocalDate.of(2026, 8, 21)] = DailyStatEntry(min = 0.14, max = 0.24, avg = 0.19, sum = 4.56, count = 24) // yesterday
        repo.entries[LocalDate.of(2026, 8, 22)] = DailyStatEntry(min = 0.16, max = 0.26, avg = 0.21, sum = 5.04, count = 24) // today
        repo.defaultEntryProvider = { null }

        val result = useCase.execute("EE")
        assertTrue(result.isSuccess)

        val summary = result.getOrThrow()
        assertEquals("EE", summary.countryCode)
        assertEquals(1, repo.callCount)

        // Tomorrow is null
        assertNull(summary.tomorrow)

        // Yesterday
        assertEquals("2026-08-21", summary.yesterday.startDate)

        // Today
        assertEquals("2026-08-22", summary.today.startDate)

        // Rolling 5-day window ends on today (2026-08-18 to 2026-08-22)
        assertEquals("2026-08-18", summary.rolling.startDate)
        assertEquals("2026-08-22", summary.rolling.endDate)
        assertEquals(5, summary.rolling.daysRequested)
        assertEquals(5, summary.rolling.daysCalculated)
        assertEquals(0.08, summary.rolling.minPrice, 0.0001)
        assertEquals(0.26, summary.rolling.maxPrice, 0.0001)
        // total sum: 3.12 + 3.6 + 4.08 + 4.56 + 5.04 = 20.4, count = 120 -> 20.4 / 120 = 0.17
        assertEquals(0.17, summary.rolling.averagePrice, 0.0001)
    }

    @Test
    fun `execute with configurable rollingDays parameter computes correct window`() = runBlocking {
        val useCase30d = GetPriceSummaryUseCase(repo, fixedClock, rollingDays = 30)

        // Default entry provider supplies data for all dates
        val result = useCase30d.execute("EE")
        assertTrue(result.isSuccess)

        val summary = result.getOrThrow()
        // Tomorrow 2026-08-23 is supplied by defaultEntryProvider -> window is 30 days ending 2026-08-23
        assertEquals("2026-07-25", summary.rolling.startDate)
        assertEquals("2026-08-23", summary.rolling.endDate)
        assertEquals(30, summary.rolling.daysRequested)
        assertEquals(30, summary.rolling.daysCalculated)
    }

    @Test
    fun `execute returns failure when required today or yesterday data is missing`() = runBlocking {
        // No data available at all
        repo.defaultEntryProvider = { null }

        val result = useCase.execute("EE")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is NoDataFoundException)
    }

    @Test
    fun `execute respects country timezone boundaries`() = runBlocking {
        // 22:30 UTC on 2026-08-22 is 01:30 on 2026-08-23 in Estonia (UTC+3)
        val lateUtcInstant = Instant.parse("2026-08-22T22:30:00Z")
        val lateClock = Clock.fixed(lateUtcInstant, ZoneOffset.UTC)
        val tzUseCase = GetPriceSummaryUseCase(repo, lateClock, rollingDays = 5)

        val result = tzUseCase.execute("EE")
        assertTrue(result.isSuccess)

        val summary = result.getOrThrow()
        // Today in EE is 2026-08-23, yesterday is 2026-08-22
        assertEquals("2026-08-22", summary.yesterday.startDate)
        assertEquals("2026-08-23", summary.today.startDate)
    }
}
