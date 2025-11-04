package ee.innov.eprice.presentation

import ee.innov.eprice.domain.model.ApiError
import ee.innov.eprice.domain.usecase.GetEnergyPricesUseCase
import ee.innov.eprice.presentation.dto.toPriceData
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

    get("/health") {
        call.respond(HttpStatusCode.OK, mapOf("status" to "UP"))
    }

    get("/api") {
        call.respond("All good")
    }

    get("/api/prices") {
        val result = getEnergyPricesUseCase.execute()

        result.onSuccess { domainPrices ->
            // Map domain models to presentation DTOs
            val responseData = domainPrices.map { it.toPriceData() }
            call.respond(HttpStatusCode.OK, responseData)

        }.onFailure { error ->
            call.application.log.error("Error fetching prices", error)

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

                is ApiError.Unknown -> call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse("An internal server error occurred.", error.details)
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
    }
}