package ee.innov.eprice.domain

import ee.innov.eprice.domain.model.DomainEnergyPrice
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private class EnergyPriceRepositorySpy : EnergyPriceRepository {
    var capturedCountryCode: String? = null
    var capturedStart: Instant? = null
    var capturedEnd: Instant? = null

    override suspend fun getPrices(
        countryCode: String,
        start: Instant,
        end: Instant,
        cacheResults: Boolean
    ): Result<List<DomainEnergyPrice>> {
        capturedCountryCode = countryCode
        capturedStart = start
        capturedEnd = end
        return Result.success(emptyList())
    }
}

class GetEnergyPricesUseCaseTest {

    @Test
    fun `execute queries 3-day window using country local timezone boundaries`() = runBlocking {
        val spyRepo = EnergyPriceRepositorySpy()
        // Summer instant: 2026-08-22 10:00 UTC -> in Estonia (UTC+3) today is 2026-08-22
        // Yesterday: 2026-08-21 start -> 2026-08-20T21:00:00Z
        // Tomorrow: 2026-08-23 end -> 2026-08-23T20:59:59Z
        val fixedInstant = Instant.parse("2026-08-22T10:00:00Z")
        val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
        val useCase = GetEnergyPricesUseCase(spyRepo, clock)

        val result = useCase.execute("EE")
        assertTrue(result.isSuccess)
        assertEquals("EE", spyRepo.capturedCountryCode)
        assertEquals(Instant.parse("2026-08-20T21:00:00Z"), spyRepo.capturedStart)
        assertEquals(Instant.parse("2026-08-23T20:59:59Z"), spyRepo.capturedEnd)
    }
}
