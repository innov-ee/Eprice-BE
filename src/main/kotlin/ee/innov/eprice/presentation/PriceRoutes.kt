package ee.innov.eprice.presentation

import ee.innov.eprice.data.DailyAveragePriceCache
import ee.innov.eprice.data.DailyStatsCache
import ee.innov.eprice.data.PriceCache
import ee.innov.eprice.domain.GetEnergyPricesUseCase
import ee.innov.eprice.domain.GetPriceStatisticsUseCase
import ee.innov.eprice.domain.GetRollingAveragePriceUseCase
import ee.innov.eprice.domain.model.ApiError
import ee.innov.eprice.domain.model.NoDataFoundException
import ee.innov.eprice.domain.model.PriceStatsQuery
import ee.innov.eprice.domain.model.PriceStatsQuery.NamedRange
import ee.innov.eprice.monitoring.ServiceMonitor
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import java.time.LocalDate
import java.time.format.DateTimeParseException

@Serializable
data class ErrorResponse(val error: String, val details: String? = null)

fun Route.priceRoutes() {
    // Inject the use cases directly into the route
    val getEnergyPricesUseCase: GetEnergyPricesUseCase by inject()
    val getRollingAveragePriceUseCase: GetRollingAveragePriceUseCase by inject()
    val getPriceStatisticsUseCase: GetPriceStatisticsUseCase by inject()
    val priceCache: PriceCache by inject()
    val dailyAveragePriceCache: DailyAveragePriceCache by inject()
    val dailyStatsCache: DailyStatsCache by inject()
    val monitor: ServiceMonitor by inject()

    // Interceptor to count all incoming requests
    intercept(ApplicationCallPipeline.Plugins) {
        monitor.incrementIncoming()
        proceed()
    }

    get("/health") {
        call.respond(HttpStatusCode.OK, mapOf("status" to "UP"))
    }

    get("/monitor") {
        call.respond(HttpStatusCode.OK, monitor.getStats())
    }

    get("/api") {
        call.respond("All good")
    }

    get("/api/cache/clear") { // get so i can invoke it with browser
        try {
            priceCache.clear()
            dailyAveragePriceCache.clear()
            dailyStatsCache.clear()
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
            respondWithError(call, error)
        }
    }

    get("/api/prices/{countryCode}/avg") {
        val countryCode = call.parameters["countryCode"]?.uppercase() ?: "EE"
        val days = 5
        val result = getRollingAveragePriceUseCase.execute(countryCode, days)

        result.onSuccess { rollingAverage ->
            call.respond(HttpStatusCode.OK, rollingAverage)
        }.onFailure { error ->
            call.application.log.error("Error fetching rolling average for $countryCode", error)
            respondWithError(call, error)
        }
    }

    get("/api/prices/{countryCode}/stats") {
        val countryCode = call.parameters["countryCode"]?.uppercase() ?: "EE"

        val query = try {
            parsePriceStatsQuery(call.request.queryParameters)
        } catch (e: IllegalArgumentException) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Invalid query parameters", e.message)
            )
        }

        val result = getPriceStatisticsUseCase.execute(countryCode, query)

        result.onSuccess { stats: GetPriceStatisticsUseCase.PriceStatistics ->
            call.respond(HttpStatusCode.OK, stats)
        }.onFailure { error ->
            call.application.log.error("Error fetching price stats for $countryCode", error)
            respondWithError(call, error)
        }
    }
}

private fun parsePriceStatsQuery(params: Parameters): PriceStatsQuery {
    val rangeParam = params["range"]?.lowercase()
    val daysParam = params["days"]
    val startDateParam = params["startDate"]
    val endDateParam = params["endDate"]

    return when {
        rangeParam != null -> {
            val named = NamedRange.fromStringOrNull(rangeParam)
                ?: throw IllegalArgumentException("Invalid 'range' parameter: '$rangeParam'. Must be 'yesterday', 'today', or 'tomorrow'.")
            PriceStatsQuery.Named(named)
        }
        startDateParam != null || endDateParam != null -> {
            if (startDateParam == null || endDateParam == null) {
                throw IllegalArgumentException("Both 'startDate' and 'endDate' parameters must be provided.")
            }
            try {
                val start = LocalDate.parse(startDateParam)
                val end = LocalDate.parse(endDateParam)
                PriceStatsQuery.CustomRange(start, end)
            } catch (e: DateTimeParseException) {
                throw IllegalArgumentException("Dates must be in ISO-8601 YYYY-MM-DD format.")
            }
        }
        daysParam != null -> {
            val days = daysParam.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid 'days' parameter. Must be a positive integer.")
            PriceStatsQuery.Days(days)
        }
        else -> PriceStatsQuery.Default
    }
}

/**
 * Helper function to map domain errors to HTTP responses.
 */
private suspend fun respondWithError(
    call: ApplicationCall,
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