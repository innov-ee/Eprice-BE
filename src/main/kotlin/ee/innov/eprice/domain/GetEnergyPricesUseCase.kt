package ee.innov.eprice.domain

import ee.innov.eprice.domain.model.DomainEnergyPrice
import java.time.Clock
import java.time.LocalDate

class GetEnergyPricesUseCase(
    private val energyPriceRepository: EnergyPriceRepository,
    private val clock: Clock = Clock.systemUTC()
) {
    /**
     * Executes the use case to get energy prices for yesterday, today, and tomorrow
     * for a specific country.
     *
     * @param countryCode The 2-letter country code (e.g., "EE", "FI").
     * @return A Result containing the list of prices or an error.
     */
    suspend fun execute(countryCode: String): Result<List<DomainEnergyPrice>> {
        val zoneId = CountryZoneProvider.getZoneId(countryCode)
        val today = LocalDate.now(clock.withZone(zoneId))

        // Business logic: Set periodStart to the beginning of yesterday in the country's local timezone
        val start = today.minusDays(1).atStartOfDay(zoneId).toInstant()

        // Business logic: Set periodEnd to the end of tomorrow in the country's local timezone
        val end = today.plusDays(2).atStartOfDay(zoneId).minusSeconds(1).toInstant()

        return energyPriceRepository.getPrices(countryCode, start, end)
    }
}