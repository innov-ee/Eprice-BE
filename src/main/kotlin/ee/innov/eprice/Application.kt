package ee.innov.eprice

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// --- Data Models for Parsing ENTSO-E XML Response ---
data class PublicationMarketDocument(
    @JsonProperty("TimeSeries")
    val timeSeries: List<TimeSeries> = emptyList()
)

data class TimeSeries(
    @JsonProperty("Period")
    val period: List<Period> = emptyList()
)

data class Period(
    val timeInterval: TimeInterval,
    val resolution: String,
    @JsonProperty("Point")
    val point: List<Point> = emptyList()
)

data class TimeInterval(val start: String)
data class Point(
    val position: Int,
    @JsonProperty("price.amount") // This annotation maps the XML tag to the property
    val priceAmount: Double // The property name is now a valid Kotlin identifier
)

// --- Data Model for the final JSON API Response ---
@kotlinx.serialization.Serializable
data class PriceData(
    val startTimeUTC: String,
    val price_eur_kwh: String
)

fun main() {
    // Read environment variables
    val port = System.getenv("PORT")?.toInt() ?: 8080
    val host = System.getenv("HOST") ?: "0.0.0.0"
    val apiKey = System.getenv("ENTSOE_API_KEY")

    // Bidding zone for Estonia
    val eestiBiddingZone = "10Y1001A1001A39I"

    // Set up the Ktor server
    embeddedServer(Netty, port = port, host = host) {
        // Install CORS to allow requests from your app's domain
        install(CORS) {
            anyHost() // For simplicity, allows any host. Can be configured to be more restrictive.
            allowHeader(HttpHeaders.ContentType)
        }

        // Configure routing
        routing {
            get("/api/prices") {
                if (apiKey.isNullOrBlank()) {
                    call.application.log.error("ENTSOE_API_KEY is not set in environment variables.")
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Server configuration error."))
                    return@get
                }

                try {
                    // 1. Calculate time period for today in UTC
                    val now = Instant.now()
                    val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC)
                    val periodStart = formatter.format(now.truncatedTo(ChronoUnit.DAYS))
                    val periodEnd = formatter.format(now.truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS).minus(1, ChronoUnit.MINUTES))

                    // 2. Build URL and make request to ENTSO-E
                    val client = HttpClient(CIO)
                    val entsoeApiUrl = "https://web-api.tp.entsoe.eu/api?securityToken=$apiKey&documentType=A44&in_Domain=$eestiBiddingZone&out_Domain=$eestiBiddingZone&periodStart=$periodStart&periodEnd=$periodEnd"
                    val response: HttpResponse = client.get(entsoeApiUrl)
                    val xmlString = response.bodyAsText()
                    client.close()

                    if (!response.status.isSuccess() || xmlString.contains("<Reason>")) {
                        call.application.log.error("ENTSO-E API Error Response: $xmlString")
                        call.respond(HttpStatusCode.BadGateway, mapOf("error" to "Failed to fetch data from ENTSO-E."))
                        return@get
                    }

                    // 3. Parse XML response
                    val xmlMapper = XmlMapper().registerKotlinModule()
                    val marketDocument = xmlMapper.readValue(xmlString, PublicationMarketDocument::class.java)

                    // 4. Transform data into the final format
                    val prices = marketDocument.timeSeries.firstOrNull()?.period?.firstOrNull()?.let { period ->
                        val resolutionMinutes = period.resolution.removePrefix("PT").removeSuffix("M").toLong()
                        val periodStartInstant = Instant.parse(period.timeInterval.start)

                        period.point.map { point ->
                            val pricePerKWh = point.priceAmount / 1000.0 // Use the corrected property name
                            val intervalStart = periodStartInstant.plus((point.position - 1) * resolutionMinutes, ChronoUnit.MINUTES)

                            PriceData(
                                startTimeUTC = intervalStart.toString(),
                                price_eur_kwh = "%.5f".format(pricePerKWh)
                            )
                        }
                    } ?: emptyList()

                    // 5. Send the response
                    call.respond(prices)

                } catch (e: Exception) {
                    call.application.log.error("An error occurred: ${e.message}", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "An internal server error occurred."))
                }
            }
        }
    }.start(wait = true)
}

