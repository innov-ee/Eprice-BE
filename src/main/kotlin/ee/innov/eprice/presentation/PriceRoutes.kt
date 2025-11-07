package ee.innov.eprice.presentation

import ee.innov.eprice.data.DailyAveragePriceCache
import ee.innov.eprice.data.PriceCache
import ee.innov.eprice.domain.GetEnergyPricesUseCase
import ee.innov.eprice.domain.GetRollingAveragePriceUseCase
import ee.innov.eprice.domain.model.ApiError
import ee.innov.eprice.domain.model.NoDataFoundException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.koin.ktor.ext.inject

@kotlinx.serialization.Serializable
data class ErrorResponse(val error: String, val details: String? = null)

fun Route.priceRoutes() {
    // Inject the use case directly into the route
    val getEnergyPricesUseCase: GetEnergyPricesUseCase by inject()
    val getRollingAveragePriceUseCase: GetRollingAveragePriceUseCase by inject()
    val priceCache: PriceCache by inject()
    val dailyAveragePriceCache: DailyAveragePriceCache by inject()

    get("/health") {
        call.respond(HttpStatusCode.OK, mapOf("status" to "UP"))
    }

    get("/api") {
        call.respond("All good")
    }

    get("/api/cache/clear") { // get so i can invoke it with browser
        try {
            priceCache.clear()
            dailyAveragePriceCache.clear()
            call.application.log.info("Cache clear requested and initiated for all caches.")
            call.respond(HttpStatusCode.OK, mapOf("status" to "All caches clear initiated"))
        } catch (e: Exception) {
            call.application.log.error("Error during cache clear request", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("Failed to initiate cache clear", e.message)
            )
        }
    }

    get("/api/prices/{countryCode?}") {
        val countryCode = call.parameters["countryCode"]?.uppercase() ?: "EE"

        val result = getEnergyPricesUseCase.execute(countryCode)

        result.onSuccess { domainPrices ->
            // Map domain models to presentation DTOs
            val responseData = domainPrices.map { it.toPriceData() }
            call.respond(HttpStatusCode.OK, responseData)

        }.onFailure { error ->
            call.application.log.error("Error fetching prices for $countryCode", error)
            respondWithError(call, error) // +++ Refactored to helper
        }
    }

    get("/api/prices/{countryCode}/rolling-average-30d") {
        val countryCode = call.parameters["countryCode"]?.uppercase() ?: "EE"
        // You could also get 'days' from query param:
        // val days = call.request.queryParameters["days"]?.toIntOrNull() ?: 30
        val days = 30 // Hardcode for now as per endpoint name

        val result = getRollingAveragePriceUseCase.execute(countryCode, days)

        result.onSuccess { rollingAverage ->
            call.respond(HttpStatusCode.OK, rollingAverage)
        }.onFailure { error ->
            call.application.log.error("Error fetching 30d rolling average for $countryCode", error)
            respondWithError(call, error)
        }
    }
}

/**
 * Helper function to map domain errors to HTTP responses.
 */
private suspend fun respondWithError(
    call: io.ktor.server.application.ApplicationCall,
    error: Throwable
) {
    call.application.log.error("API Error", error) // Log all errors
    when (error) {
        is ApiError.Timeout -> call.respond(
            HttpStatusCode.GatewayTimeout,
            ErrorResponse(error.message, error.details)
        )

        is ApiError.Server -> call.respond(
            HttpStatusCode.BadGateway,
            ErrorResponse(error.message, error.details)
        )

        is ApiError.Network -> call.respond(
            HttpStatusCode.BadGateway,
            ErrorResponse(error.message, error.details)
        )

        is ApiError.Parsing -> call.respond(
            HttpStatusCode.InternalServerError,
            ErrorResponse("An internal server error occurred.", error.details)
        )

        is NoDataFoundException -> call.respond(
            HttpStatusCode.NotFound,
            ErrorResponse("No data found", error.message)
        )

        is ApiError.Unknown -> call.respond(
            HttpStatusCode.InternalServerError,
            ErrorResponse("An internal server error occurred.", error.details)
        )

        is IllegalArgumentException -> call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse("Bad request", error.message)
        )

        else -> {
            // This handles any other Throwable that wasn't mapped
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("An unexpected error occurred.", error.message)
            )
        }
    }
}