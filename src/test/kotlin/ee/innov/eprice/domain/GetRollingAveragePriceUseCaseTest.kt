package ee.innov.eprice.domain

import ee.innov.eprice.data.DailyAveragePriceCache
import ee.innov.eprice.domain.model.DomainEnergyPrice
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private class InMemoryDailyAveragePriceCacheFake : DailyAveragePriceCache {
    val store = mutableMapOf<String, MutableMap<LocalDate, Double>>()

    override fun get(countryCode: String, date: LocalDate): Double? =
        store[countryCode.uppercase()]?.get(date)

    override fun put(countryCode: String, date: LocalDate, averagePrice: Double) {
        store.getOrPut(countryCode.uppercase()) { mutableMapOf() }[date] = averagePrice
    }

    override fun getRange(
        countryCode: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Map<LocalDate, Double> {
        val countryMap = store[countryCode.uppercase()] ?: return emptyMap()
        return countryMap.filterKeys { !it.isBefore(startDate) && !it.isAfter(endDate) }
    }

    override fun clear() {
        store.clear()
    }
}

private class RollingEnergyPriceRepoSpy : EnergyPriceRepository {
    val requestedRanges = mutableListOf<Pair<Instant, Instant>>()

    override suspend fun getPrices(
        countryCode: String,
        start: Instant,
        end: Instant,
        cacheResults: Boolean
    ): Result<List<DomainEnergyPrice>> {
        requestedRanges.add(start to end)
        val prices = (0 until 24).map { hour ->
            DomainEnergyPrice(
                startTime = start.plusSeconds(hour * 3600L),
                pricePerKWh = 0.20
            )
        }
        return Result.success(prices)
    }
}

class GetRollingAveragePriceUseCaseTest {

    @Test
    fun `execute uses country local timezone for end date and fetch boundaries`() = runBlocking {
        val cache = InMemoryDailyAveragePriceCacheFake()
        val repoSpy = RollingEnergyPriceRepoSpy()

        // At 2026-08-22 10:00 UTC (Estonia UTC+3), today is 2026-08-22
        // Rolling average for 1 day -> endDate = yesterday (2026-08-21), startDate = 2026-08-21
        // Fetch boundary for 2026-08-21 in EE: 2026-08-20T21:00:00Z to 2026-08-21T20:59:59Z
        val clock = Clock.fixed(Instant.parse("2026-08-22T10:00:00Z"), ZoneOffset.UTC)
        val useCase = GetRollingAveragePriceUseCase(repoSpy, cache, clock)

        val result = useCase.execute("EE", days = 1)
        assertTrue(result.isSuccess)
        val avg = result.getOrThrow()
        assertEquals("2026-08-21", avg.startDate)
        assertEquals("2026-08-21", avg.endDate)
        assertEquals(1, repoSpy.requestedRanges.size)
        assertEquals(Instant.parse("2026-08-20T21:00:00Z"), repoSpy.requestedRanges[0].first)
        assertEquals(Instant.parse("2026-08-21T20:59:59Z"), repoSpy.requestedRanges[0].second)
    }
}
