package ee.innov.eprice.domain.model

import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.serialization.SerializationException

/**
 * A sealed class hierarchy for domain-specific errors,
 * extending Throwable so it can be used in Result.failure()
 */
sealed class ApiError(
    override val message: String,
    open val details: String? = null,
    override val cause: Throwable? = null
) : Throwable(message, cause) {

    data class Network(
        override val details: String,
        override val cause: Throwable
    ) : ApiError("Network error", details, cause)

    data class Server(
        val code: Int,
        override val details: String,
        override val cause: Throwable
    ) : ApiError("Server error (code $code)", details, cause)

    data class Timeout(
        override val details: String,
        override val cause: Throwable
    ) : ApiError("Outgoing Request timed out", details, cause)

    data class Parsing(
        override val details: String,
        override val cause: Throwable
    ) : ApiError("Data parsing error", details, cause)

    data class Unknown(
        override val details: String,
        override val cause: Throwable
    ) : ApiError("Unknown error", details, cause)
}

// --- Custom exceptions (still useful for the data layer to throw) ---
class EntsoeApiException(val code: Int, val details: String) :
    Exception("ENTSO-E API Error: $details")

class EleringApiException(val code: Int, val details: String) :
    Exception("Elering API Error: $details")

class NoDataFoundException(details: String) : Exception(details)

// Helper function to map generic Throwables to our specific ApiError
fun Throwable.toApiError(): ApiError {
    // If it's already an ApiError, just return it.
    if (this is ApiError) return this

    val details = this.message ?: "No details"

    return when (this) {
        is HttpRequestTimeoutException -> ApiError.Timeout(details, this)
        is EntsoeApiException -> ApiError.Server(this.code, this.details, this)
        is EleringApiException -> ApiError.Server(this.code, this.details, this)
        is com.fasterxml.jackson.core.JsonProcessingException -> ApiError.Parsing(details, this)
        is SerializationException -> ApiError.Parsing(details, this)
        // You could add more specific network checks here, e.g.:
        // is java.net.UnknownHostException -> ApiError.Network(details, this)
        else -> ApiError.Unknown(details, this)
    }
}