package ee.innov.eprice.domain

import ee.innov.eprice.domain.model.DomainEnergyPrice
import ee.innov.eprice.test.NoOpDailyAveragePriceCache
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

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
        val cache = NoOpDailyAveragePriceCache()
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

    @Test
    fun `execute ignores days with partial data when computing rolling average`() = runBlocking {
        val cache = ee.innov.eprice.test.InMemoryDailyAveragePriceCache()
        val partialRepo = object : EnergyPriceRepository {
            override suspend fun getPrices(
                countryCode: String,
                start: Instant,
                end: Instant,
                cacheResults: Boolean
            ): Result<List<DomainEnergyPrice>> {
                // Return only 2 hours
                return Result.success(
                    listOf(
                        DomainEnergyPrice(start, 0.10),
                        DomainEnergyPrice(start.plusSeconds(3600), 0.20)
                    )
                )
            }
        }

        val clock = Clock.fixed(Instant.parse("2026-08-22T10:00:00Z"), ZoneOffset.UTC)
        val useCase = GetRollingAveragePriceUseCase(partialRepo, cache, clock)

        val result = useCase.execute("EE", days = 1)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ee.innov.eprice.domain.model.NoDataFoundException)
    }
}
