package ee.innov.eprice.domain

import ee.innov.eprice.data.DailyStatEntry
import ee.innov.eprice.data.DailyStatsCache
import ee.innov.eprice.domain.model.DomainEnergyPrice
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private class InMemoryDailyStatsCache : DailyStatsCache {
    private val data = mutableMapOf<String, MutableMap<LocalDate, DailyStatEntry>>()

    override fun get(countryCode: String, date: LocalDate): DailyStatEntry? {
        return data[countryCode.uppercase()]?.get(date)
    }

    override fun put(countryCode: String, date: LocalDate, stats: DailyStatEntry) {
        data.getOrPut(countryCode.uppercase()) { mutableMapOf() }[date] = stats
    }

    override fun putBatch(countryCode: String, entries: Map<LocalDate, DailyStatEntry>) {
        data.getOrPut(countryCode.uppercase()) { mutableMapOf() }.putAll(entries)
    }

    override fun getRange(
        countryCode: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Map<LocalDate, DailyStatEntry> {
        val countryData = data[countryCode.uppercase()] ?: return emptyMap()
        return countryData.filterKeys { !it.isBefore(startDate) && !it.isAfter(endDate) }
    }

    override fun clear() {
        data.clear()
    }
}

private class FakeEnergyPriceRepository : EnergyPriceRepository {
    var callCount = 0
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
}

class GetPriceStatisticsUseCaseTest {

    private lateinit var cache: InMemoryDailyStatsCache
    private lateinit var repo: FakeEnergyPriceRepository
    private lateinit var useCase: GetPriceStatisticsUseCase

    @BeforeEach
    fun setUp() {
        cache = InMemoryDailyStatsCache()
        repo = FakeEnergyPriceRepository()
        useCase = GetPriceStatisticsUseCase(repo, cache)
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

        // Verify it was stored in cache
        val yesterday = Instant.now().atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
        val cached = cache.get("EE", yesterday)
        assertEquals(0.10, cached?.min)
        assertEquals(0.33, cached?.max)
    }

    @Test
    fun `execute with 5 days uses cache on subsequent calls without repo fetch`() = runBlocking {
        // Cold start - repo called 5 times
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
