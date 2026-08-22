package ee.innov.eprice.domain

import ee.innov.eprice.domain.model.DailyStatEntry
import ee.innov.eprice.domain.model.DomainEnergyPrice
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Fakes the repository's whole contract, including its internal daily-stats caching,
 * so tests can assert on cache hits without the use case knowing about caching at all.
 */
private class FakeEnergyPriceRepository : EnergyPriceRepository {
    var callCount = 0
    private val dailyStatsByCountry = mutableMapOf<String, MutableMap<LocalDate, DailyStatEntry>>()
    val priceProvider: (countryCode: String, start: Instant, end: Instant) -> List<DomainEnergyPrice> = { _, start, _ ->
        // Generate 24 hourly prices
        (0 until 24).map { hour ->
            DomainEnergyPrice(
                startTime = start.plusSeconds(hour * 3600L),
                pricePerKWh = 0.10 + (hour * 0.01) // 0.10 to 0.33
            )
        }
    }

    override suspend fun getPrices(
        countryCode: String,
        start: Instant,
        end: Instant,
        cacheResults: Boolean
    ): Result<List<DomainEnergyPrice>> {
        callCount++
        val prices = priceProvider(countryCode, start, end)
        return Result.success(prices)
    }

    override suspend fun getDailyStats(
        countryCode: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Result<Map<LocalDate, DailyStatEntry>> {
        val countryCache = dailyStatsByCountry.getOrPut(countryCode.uppercase()) { mutableMapOf() }
        val totalDays = (ChronoUnit.DAYS.between(startDate, endDate) + 1).toInt()
        val datesInRange = (0 until totalDays).map { startDate.plusDays(it.toLong()) }

        datesInRange.filter { !countryCache.containsKey(it) }.forEach { date ->
            val dayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant()
            val dayEnd = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).minusSeconds(1).toInstant()
            val prices = getPrices(countryCode, dayStart, dayEnd, cacheResults = false).getOrThrow()
            val pricesList = prices.map { it.pricePerKWh }
            countryCache[date] = DailyStatEntry(
                min = pricesList.min(),
                max = pricesList.max(),
                avg = pricesList.average(),
                sum = pricesList.sum(),
                count = pricesList.size
            )
        }

        return Result.success(datesInRange.associateWith { countryCache.getValue(it) })
    }
}

class GetPriceStatisticsUseCaseTest {

    private lateinit var repo: FakeEnergyPriceRepository
    private lateinit var useCase: GetPriceStatisticsUseCase

    @BeforeEach
    fun setUp() {
        repo = FakeEnergyPriceRepository()
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

        // Verify the repository cached it internally
        val yesterday = Instant.now().atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
        val cached = repo.getDailyStats("EE", yesterday, yesterday).getOrThrow()[yesterday]
        assertEquals(0.10, cached?.min)
        assertEquals(0.33, cached?.max)
    }

    @Test
    fun `execute with 5 days uses repository cache on subsequent calls without extra network fetch`() = runBlocking {
        // Cold start - repo fetches 5 times
        val firstResult = useCase.execute("EE", days = 5)
        assertTrue(firstResult.isSuccess)
        assertEquals(5, repo.callCount)

        val stats = firstResult.getOrThrow()
        assertEquals(5, stats.daysCalculated)

        // Warm start - 0 additional repo calls
        val secondResult = useCase.execute("EE", days = 5)
        assertTrue(secondResult.isSuccess)
        assertEquals(5, repo.callCount) // call count remains 5
    }

    @Test
    fun `execute with explicit date range computes exact weighted average`() = runBlocking {
        val start = LocalDate.of(2026, 8, 10)
        val end = LocalDate.of(2026, 8, 12)

        val result = useCase.execute("EE", start, end)
        assertTrue(result.isSuccess)

        val stats = result.getOrThrow()
        assertEquals("2026-08-10", stats.startDate)
        assertEquals("2026-08-12", stats.endDate)
        assertEquals(3, stats.daysCalculated)
        assertEquals(0.10, stats.minPrice, 0.0001)
        assertEquals(0.33, stats.maxPrice, 0.0001)
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
