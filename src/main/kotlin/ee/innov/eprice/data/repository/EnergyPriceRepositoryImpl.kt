package ee.innov.eprice.data.repository

import ee.innov.eprice.data.remote.EntsoeRemoteDataSource
import ee.innov.eprice.data.remote.mapper.toDomainEnergyPrices
import ee.innov.eprice.domain.model.DomainEnergyPrice
import ee.innov.eprice.domain.model.NoDataFoundException
import ee.innov.eprice.domain.model.toApiError
import ee.innov.eprice.domain.repository.EnergyPriceRepository
import java.time.Instant

class EnergyPriceRepositoryImpl(
    private val remoteDataSource: EntsoeRemoteDataSource
) : EnergyPriceRepository {

    override suspend fun getPrices(
        start: Instant,
        end: Instant
    ): Result<List<DomainEnergyPrice>> {
        return try {
            val marketDocument = remoteDataSource.fetchPrices(start, end)
            val prices = marketDocument.toDomainEnergyPrices()
            Result.success(prices)
        } catch (e: NoDataFoundException) {
            // "No data" is not a failure, it's just an empty list.
            Result.success(emptyList())
        } catch (e: Exception) {
            // All other exceptions are mapped to our ApiError
            Result.failure(e.toApiError())
        }
    }
}