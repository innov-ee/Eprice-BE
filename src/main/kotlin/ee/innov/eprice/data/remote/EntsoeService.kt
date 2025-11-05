package ee.innov.eprice.data.remote

import com.fasterxml.jackson.dataformat.xml.XmlMapper
import ee.innov.eprice.data.remote.dto.PublicationMarketDocument
import ee.innov.eprice.domain.model.EntsoeApiException
import ee.innov.eprice.domain.model.NoDataFoundException
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class EntsoeService(
    private val client: HttpClient,
    private val xmlMapper: XmlMapper,
    private val apiKey: String,
    private val biddingZone: String
) {
    private val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
        .withZone(ZoneOffset.UTC)

    /**
     * Fetches the raw XML data and parses it.
     * @throws EntsoeApiException if the API key is missing or the API returns an error.
     * @throws NoDataFoundException if the API returns a "no data" message.
     * @throws io.ktor.client.plugins.HttpRequestTimeoutException on timeout.
     * @throws com.fasterxml.jackson.core.JsonProcessingException on parsing error.
     */
    suspend fun fetchPrices(start: Instant, end: Instant): PublicationMarketDocument {
        if (apiKey.isBlank()) {
            throw EntsoeApiException(500, "ENTSOE_API_KEY is not set.")
        }

        val periodStart = formatter.format(start)
        val periodEnd = formatter.format(end)

        val response: HttpResponse = client.get("https://web-api.tp.entsoe.eu/api") {
            url {
                parameters.append("securityToken", apiKey)
                parameters.append("documentType", "A44")
                parameters.append("in_Domain", biddingZone)
                parameters.append("out_Domain", biddingZone)
                parameters.append("periodStart", periodStart)
                parameters.append("periodEnd", periodEnd)
            }
        }
        val xmlString = response.bodyAsText()

        if (!response.status.isSuccess() || xmlString.contains("<Reason>")) {
            if (xmlString.contains("No matching data found", ignoreCase = true)) {
                // This is a special case, not a fatal error
                throw NoDataFoundException(
                    "No matching data found for period $periodStart - $periodEnd"
                )
            }
            // This is a real API error
            throw EntsoeApiException(
                response.status.value,
                "Failed to fetch data from ENTSO-E: $xmlString"
            )
        }

        return xmlMapper.readValue(
            xmlString,
            PublicationMarketDocument::class.java
        )
    }
}