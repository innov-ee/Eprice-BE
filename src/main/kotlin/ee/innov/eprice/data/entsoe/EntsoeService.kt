package ee.innov.eprice.data.entsoe

import com.fasterxml.jackson.dataformat.xml.XmlMapper
import ee.innov.eprice.domain.model.EntsoeApiException
import ee.innov.eprice.domain.model.NoDataFoundException
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class EntsoeService(
    private val client: HttpClient,
    private val xmlMapper: XmlMapper,
    private val apiKey: String
) {
    private val logger = LoggerFactory.getLogger(EntsoeService::class.java)

    private val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
        .withZone(ZoneOffset.UTC)

    /**
     * Fetches the raw XML data and parses it.
     * @param biddingZone The ENTSO-E bidding zone identifier.
     * @throws EntsoeApiException if the API key is missing or the API returns an error.
     * @throws NoDataFoundException if the API returns a "no data" message.
     * @throws io.ktor.client.plugins.HttpRequestTimeoutException on timeout.
     * @throws com.fasterxml.jackson.core.JsonProcessingException on parsing error.
     */
    suspend fun fetchPrices(
        biddingZone: String,
        start: Instant,
        end: Instant
    ): PublicationMarketDocument {
        if (apiKey.isBlank()) {
            throw EntsoeApiException(500, "ENTSOE_API_KEY is not set.")
        }

        val periodStart = formatter.format(start)
        val periodEnd = formatter.format(end)

        val response: HttpResponse = client.get("https://web-api.tp.entsoe.eu/api") {
            // Explicitly request XML payload from ENTSO-E
            headers {
                append(HttpHeaders.Accept, "application/xml")
            }
            url {
                parameters.append("securityToken", apiKey)
                parameters.append("documentType", "A44")
                parameters.append("in_Domain", biddingZone)
                parameters.append("out_Domain", biddingZone)
                parameters.append("periodStart", periodStart)
                parameters.append("periodEnd", periodEnd)
            }
        }
        val rawBody = response.bodyAsText()
        val trimmed = rawBody.trim()

        logger.info("[ENTSO-E RESPONSE] Status=${response.status.value}, Length=${trimmed.length}, Preview=${trimmed.take(120).replace("\n", " ")}")

        if (!response.status.isSuccess()) {
            if (trimmed.contains("No matching data found", ignoreCase = true)) {
                throw NoDataFoundException(
                    "No matching data found for bidding zone $biddingZone in period $periodStart - $periodEnd"
                )
            }
            throw EntsoeApiException(
                response.status.value,
                "Failed to fetch data from ENTSO-E (status ${response.status.value}): $trimmed"
            )
        }

        if (!trimmed.startsWith("<")) {
            logger.warn("[ENTSO-E ERROR SCENARIO DETECTED] Response is not XML: $trimmed")
            throw EntsoeApiException(
                response.status.value,
                "Unexpected non-XML response from ENTSO-E: $trimmed"
            )
        }

        if (trimmed.contains("<Reason>")) {
            if (trimmed.contains("No matching data found", ignoreCase = true)) {
                // This is a special case, not a fatal error
                throw NoDataFoundException(
                    "No matching data found for bidding zone $biddingZone in period $periodStart - $periodEnd"
                )
            }
            // This is a real API error returned inside XML
            throw EntsoeApiException(
                response.status.value,
                "Failed to fetch data from ENTSO-E: $trimmed"
            )
        }

        return xmlMapper.readValue(
            trimmed,
            PublicationMarketDocument::class.java
        )
    }
}