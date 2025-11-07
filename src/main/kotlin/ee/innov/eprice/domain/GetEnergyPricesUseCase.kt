package ee.innov.eprice.domain

import ee.innov.eprice.domain.model.DomainEnergyPrice
import java.time.Instant
import java.time.temporal.ChronoUnit

class GetEnergyPricesUseCase(
    private val energyPriceRepository: EnergyPriceRepository
) {
    /**
     * Executes the use case to get energy prices for yesterday, today, and tomorrow
     * for a specific country.
     *
     * @param countryCode The 2-letter country code (e.g., "EE", "FI").
     * @return A Result containing the list of prices or an error.
     */
    suspend fun execute(countryCode: String): Result<List<DomainEnergyPrice>> {
        val now = Instant.now()

        // Business logic: Set periodStart to the beginning of yesterday
        val start = now.truncatedTo(ChronoUnit.DAYS)
            .minus(1, ChronoUnit.DAYS)

        // Business logic: Set periodEnd to the end of tomorrow
        // (i.e., start of day after tomorrow, minus one minute)
        val end = now.truncatedTo(ChronoUnit.DAYS)
            .plus(2, ChronoUnit.DAYS)
            .minus(1, ChronoUnit.MINUTES)

        return energyPriceRepository.getPrices(countryCode, start, end)
    }
}