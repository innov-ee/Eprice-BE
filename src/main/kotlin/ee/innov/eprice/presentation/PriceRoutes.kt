package ee.innov.eprice.presentation

import ee.innov.eprice.data.DailyStatsCache
import ee.innov.eprice.data.PriceCache
import ee.innov.eprice.domain.GetEnergyPricesUseCase
import ee.innov.eprice.domain.GetPriceSummaryUseCase
import ee.innov.eprice.domain.BiddingZones
import ee.innov.eprice.domain.model.ApiError
import ee.innov.eprice.domain.model.NoDataFoundException
import ee.innov.eprice.monitoring.CacheOverview
import ee.innov.eprice.monitoring.ServiceMonitor
import ee.innov.eprice.monitoring.ServiceLogs
import ee.innov.eprice.security.AUTH_ADMIN_BASIC
import ee.innov.eprice.security.AUTH_BOOTSTRAP
import ee.innov.eprice.security.AUTH_OPERATIONAL
import ee.innov.eprice.security.KeyRotationService
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject

@Serializable
data class ErrorResponse(val error: String, val details: String? = null)

fun Route.priceRoutes() {
    // Inject the use cases directly into the route
    val getEnergyPricesUseCase: GetEnergyPricesUseCase by inject()
    val getPriceSummaryUseCase: GetPriceSummaryUseCase by inject()
    val priceCache: PriceCache by inject()
    val dailyStatsCache: DailyStatsCache by inject()
    val monitor: ServiceMonitor by inject()
    val serviceLogs: ServiceLogs by inject()
    val keyRotationService: KeyRotationService by inject()

    // Interceptor to count all incoming requests
    intercept(ApplicationCallPipeline.Plugins) {
        monitor.incrementIncoming()
        proceed()
    }

    // --- Public Route (Unauthenticated) ---
    get("/health") {
        call.respond(HttpStatusCode.OK, mapOf("status" to "UP"))
    }

    // --- Bootstrap-Protected Route (X-Bootstrap-Key) ---
    // ngingx is configured to have harder rate limits on /api/v*/auth paths.
    authenticate(AUTH_BOOTSTRAP) {
        get("/api/v1/auth/keys") {
            val info = keyRotationService.getKeyInfo()
            call.respond(
                HttpStatusCode.OK, mapOf(
                    "operational_key" to info.operationalKey,
                    "ttl_seconds" to info.ttlSeconds.toString(),
                    "expires_at" to info.expiresAtUtc
                )
            )
        }
    }

    // --- Admin-Only Routes (HTTP Basic Auth) ---
    authenticate(AUTH_ADMIN_BASIC) {
        get("/monitor") {
            call.respond(HttpStatusCode.OK, monitor.getStats())
        }

        get("/monitor/caches") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.OK, CacheOverview.read(dailyStatsCache, monitor))
        }

        get("/monitor/logs") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.OK, serviceLogs.snapshot())
        }

        staticResources("/monitor.html", "static", index = "monitor.html")

        post("/api/cache/clear") {
            try {
                priceCache.clear()
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
    }

    // --- Core API Routes (X-API-Key OR Admin Basic Auth) ---
    authenticate(AUTH_OPERATIONAL, AUTH_ADMIN_BASIC, strategy = AuthenticationStrategy.FirstSuccessful) {
        get("/api") {
            call.respond("All good")
        }

        get("/api/meta/routes") {
            call.respond(HttpStatusCode.OK, EndpointCatalog.endpoints)
        }

        get("/api/prices/{countryCode?}") {
            val countryCode = call.resolveRegionCode() ?: return@get

            val result = getEnergyPricesUseCase.execute(countryCode)

            result.onSuccess { domainPrices ->
                // Map domain models to presentation DTOs
                val responseData = domainPrices.map { it.toPriceData() }
                call.respond(HttpStatusCode.OK, responseData)

            }.onFailure { error ->
                respondWithError(call, error)
            }
        }

        get("/api/prices/{countryCode}/stats/summary") {
            val countryCode = call.resolveRegionCode() ?: return@get

            monitor.recordStatsRequest(countryCode)
            val result = getPriceSummaryUseCase.execute(countryCode)

            result.onSuccess { summary ->
                call.respond(HttpStatusCode.OK, summary)
            }.onFailure { error ->
                respondWithError(call, error)
            }
        }
    }
}

/**
 * Helper function to map domain errors to HTTP responses.
 */
private suspend fun respondWithError(
    call: ApplicationCall,
    error: Throwable
) {
    call.application.log.error("API error for ${call.request.path()}", error)
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
private suspend fun ApplicationCall.resolveRegionCode(): String? = try {
    BiddingZones.resolve(parameters["countryCode"] ?: "EE").id
} catch (e: IllegalArgumentException) {
    respond(HttpStatusCode.BadRequest, ErrorResponse("Unsupported region", e.message))
    null
}
