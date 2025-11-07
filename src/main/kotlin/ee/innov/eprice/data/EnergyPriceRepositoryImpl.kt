package ee.innov.eprice.data

import ee.innov.eprice.data.elering.EleringService
import ee.innov.eprice.data.elering.toDomainEnergyPrices
import ee.innov.eprice.data.entsoe.EntsoeService
import ee.innov.eprice.data.entsoe.toBiddingZone
import ee.innov.eprice.data.entsoe.toDomainEnergyPrices
import ee.innov.eprice.domain.EnergyPriceRepository
import ee.innov.eprice.domain.model.ApiError
import ee.innov.eprice.domain.model.DomainEnergyPrice
import ee.innov.eprice.domain.model.NoDataFoundException
import ee.innov.eprice.domain.model.toApiError
import java.time.Instant

class EnergyPriceRepositoryImpl(
    private val entsoeService: EntsoeService,
    private val eleringService: EleringService
) : EnergyPriceRepository {

    override suspend fun getPrices(
        countryCode: String,
        start: Instant,
        end: Instant
    ): Result<List<DomainEnergyPrice>> {

        // Strategy: Try Elering first.
        try {
            println("Hitting Elering: $countryCode")
            val eleringMarketDocument = eleringService.fetchPrices(countryCode, start, end)
            val prices = eleringMarketDocument.toDomainEnergyPrices()
            if (prices.isNotEmpty()) {
                return Result.success(prices)
            }
        } catch (e: NoDataFoundException) {
            println(e)
        } catch (e: Exception) {
            println(e)
        }

        // Elering failed, try entso-e
        val biddingZone = countryCode.toBiddingZone()
            ?: return Result.failure( // Return a specific error if mapping fails
                ApiError.Unknown(
                    "Unsupported country code for ENTSO-E fallback: $countryCode",
                    IllegalArgumentException("No bidding zone mapping for $countryCode")
                )
            )

        return try {
            println("Hitting Entsoe: $countryCode")
            val marketDocument = entsoeService.fetchPrices(biddingZone, start, end)
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