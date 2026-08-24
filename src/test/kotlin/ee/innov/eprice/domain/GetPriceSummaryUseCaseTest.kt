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
    fun `execute when tomorrow prices are available includes tomorrow and keeps rolling window ending on yesterday`() = runBlocking {
        // Today is 2026-08-22, yesterday is 2026-08-21, tomorrow is 2026-08-23
        repo.entries[LocalDate.of(2026, 8, 17)] = DailyStatEntry(min = 0.06, max = 0.16, avg = 0.11, sum = 2.64, count = 24)
        repo.entries[LocalDate.of(2026, 8, 18)] = DailyStatEntry(min = 0.08, max = 0.18, avg = 0.13, sum = 3.12, count = 24)
        repo.entries[LocalDate.of(2026, 8, 19)] = DailyStatEntry(min = 0.10, max = 0.20, avg = 0.15, sum = 3.60, count = 24)
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

        // Rolling 5-day window ends on yesterday (2026-08-17 to 2026-08-21)
        assertEquals("2026-08-17", summary.rolling.startDate)
        assertEquals("2026-08-21", summary.rolling.endDate)
        assertEquals(5, summary.rolling.daysRequested)
        assertEquals(5, summary.rolling.daysCalculated)
        assertEquals(0.06, summary.rolling.minPrice, 0.0001)
        assertEquals(0.24, summary.rolling.maxPrice, 0.0001)
        // total sum: 2.64 + 3.12 + 3.60 + 4.08 + 4.56 = 18.0, total count: 120 -> 18.0 / 120 = 0.15
        assertEquals(0.15, summary.rolling.averagePrice, 0.0001)
    }

    @Test
    fun `execute when tomorrow prices not published returns null for tomorrow`() = runBlocking {
        // Today is 2026-08-22, tomorrow 2026-08-23 is missing
        repo.defaultEntryProvider = { date ->
            if (date == LocalDate.of(2026, 8, 23)) null
            else DailyStatEntry(min = 0.10, max = 0.30, avg = 0.20, sum = 4.80, count = 24)
        }

        val result = useCase.execute("EE")
        assertTrue(result.isSuccess)

        val summary = result.getOrThrow()
        assertNull(summary.tomorrow)
        assertEquals("2026-08-21", summary.yesterday.startDate)
        assertEquals("2026-08-22", summary.today.startDate)
        assertEquals("2026-08-17", summary.rolling.startDate)
        assertEquals("2026-08-21", summary.rolling.endDate)
    }

    @Test
    fun `execute with configurable rollingDays parameter computes correct window`() = runBlocking {
        val useCase30d = GetPriceSummaryUseCase(repo, fixedClock, rollingDays = 30)

        // Default entry provider supplies data for all dates
        val result = useCase30d.execute("EE")
        assertTrue(result.isSuccess)

        val summary = result.getOrThrow()
        // Window is 30 days ending yesterday (2026-08-21)
        assertEquals("2026-07-23", summary.rolling.startDate)
        assertEquals("2026-08-21", summary.rolling.endDate)
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
        assertEquals("2026-08-18", summary.rolling.startDate)
        assertEquals("2026-08-22", summary.rolling.endDate)
    }

    @Test
    fun `execute when tomorrow has only partial 1-2 hours data returns null for tomorrow`() = runBlocking {
        // Today is 2026-08-22, tomorrow 2026-08-23 only has 2 hours of data (e.g. count = 2)
        repo.defaultEntryProvider = { date ->
            if (date == LocalDate.of(2026, 8, 23)) {
                DailyStatEntry(min = 0.10, max = 0.12, avg = 0.11, sum = 0.22, count = 2)
            } else {
                DailyStatEntry(min = 0.10, max = 0.30, avg = 0.20, sum = 4.80, count = 24)
            }
        }

        val result = useCase.execute("EE")
        assertTrue(result.isSuccess)

        val summary = result.getOrThrow()
        // Tomorrow must be null because it only had 2 hours instead of 24
        assertNull(summary.tomorrow)
        assertEquals("2026-08-21", summary.yesterday.startDate)
        assertEquals("2026-08-22", summary.today.startDate)
    }

    @Test
    fun `execute when today has only partial data returns failure`() = runBlocking {
        // Today has only 3 hours of data
        repo.defaultEntryProvider = { date ->
            if (date == LocalDate.of(2026, 8, 22)) {
                DailyStatEntry(min = 0.10, max = 0.15, avg = 0.12, sum = 0.36, count = 3)
            } else {
                DailyStatEntry(min = 0.10, max = 0.30, avg = 0.20, sum = 4.80, count = 24)
            }
        }

        val result = useCase.execute("EE")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is NoDataFoundException)
    }

    @Test
    fun `execute when yesterday has only partial data returns failure`() = runBlocking {
        // Yesterday has only 1 hour of data
        repo.defaultEntryProvider = { date ->
            if (date == LocalDate.of(2026, 8, 21)) {
                DailyStatEntry(min = 0.10, max = 0.10, avg = 0.10, sum = 0.10, count = 1)
            } else {
                DailyStatEntry(min = 0.10, max = 0.30, avg = 0.20, sum = 4.80, count = 24)
            }
        }

        val result = useCase.execute("EE")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is NoDataFoundException)
    }
}
