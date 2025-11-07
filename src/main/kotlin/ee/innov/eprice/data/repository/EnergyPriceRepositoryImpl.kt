package ee.innov.eprice.data.repository

import ee.innov.eprice.data.remote.EleringService
import ee.innov.eprice.data.remote.EntsoeService
import ee.innov.eprice.data.remote.dto.toDomainEnergyPrices
import ee.innov.eprice.domain.model.DomainEnergyPrice
import ee.innov.eprice.domain.model.NoDataFoundException
import ee.innov.eprice.domain.model.toApiError
import ee.innov.eprice.domain.repository.EnergyPriceRepository
import java.time.Instant

class EnergyPriceRepositoryImpl(
    private val entsoeService: EntsoeService,
    private val eleringService: EleringService
) : EnergyPriceRepository {

    override suspend fun getPrices(
        start: Instant,
        end: Instant
    ): Result<List<DomainEnergyPrice>> {

        // Strategy: Try Elering first.
        try {
            val eleringMarketDocument = eleringService.fetchPrices(start, end)
            val prices = eleringMarketDocument.toDomainEnergyPrices()
            if (prices.isNotEmpty()) {
                return Result.success(prices)
            }
        } catch (e: NoDataFoundException) {
            println(e)
        } catch (e: Exception) {
            println(e)
        }

        // elering failed, try entso-e
        return try {
            val marketDocument = entsoeService.fetchPrices(start, end)
            val prices = marketDocument.toDomainEnergyPrices()
            Result.success(prices)
        } catch (_: NoDataFoundException) {
            // "No data" from ENTSO-E is not a failure, it's just an empty list.
            Result.success(emptyList())
        } catch (e: Exception) {
            // All other exceptions are mapped to our ApiError
            Result.failure(e.toApiError())
        }
    }
}