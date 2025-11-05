package ee.innov.eprice.data.remote

import ee.innov.eprice.data.remote.dto.EleringPriceResponse
import ee.innov.eprice.domain.model.EleringApiException
import ee.innov.eprice.domain.model.NoDataFoundException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class EleringService(
    private val client: HttpClient,
    private val countryCode: String
) {
    // Elering API uses ISO 8601 format (UTC)
    private val formatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)

    /**
     * Fetches prices from Elering.
     * @throws EleringApiException if the API returns an error.
     * @throws NoDataFoundException if the API returns no data.
     * @throws io.ktor.client.plugins.HttpRequestTimeoutException on timeout.
     * @throws kotlinx.serialization.SerializationException on parsing error.
     */
    suspend fun fetchPrices(start: Instant, end: Instant): EleringPriceResponse {
        val periodStart = formatter.format(start)
        val periodEnd = formatter.format(end)

        val response: HttpResponse = client.get("https://dashboard.elering.ee/api/nps/price") {
            url {
                parameters.append("start", periodStart)
                parameters.append("end", periodEnd)
                // Elering uses country codes like 'EE', 'LT', 'LV', 'FI'
                parameters.append("country_code", countryCode)
            }
        }

        if (!response.status.isSuccess()) {
            throw EleringApiException(
                response.status.value,
                "Failed to fetch data from Elering: ${response.status.description}"
            )
        }

        val priceResponse = response.body<EleringPriceResponse>()

        if (!priceResponse.success || priceResponse.data.isEmpty()) {
            throw NoDataFoundException(
                "Elering reported no data for period $periodStart - $periodEnd"
            )
        }

        return priceResponse
    }
}