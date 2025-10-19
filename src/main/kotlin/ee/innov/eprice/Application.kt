package ee.innov.eprice

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// --- Data Models ---
data class PublicationMarketDocument(
    @JsonProperty("TimeSeries")
    @JacksonXmlElementWrapper(useWrapping = false)
    val timeSeries: List<TimeSeries> = emptyList()
)

data class TimeSeries(
    val mRID: String? = null,
    @JsonProperty("Period")
    @JacksonXmlElementWrapper(useWrapping = false)
    val period: List<Period> = emptyList()
)

data class Period(
    val timeInterval: TimeInterval,
    val resolution: String,
    @JsonProperty("Point")
    @JacksonXmlElementWrapper(useWrapping = false)
    val point: List<Point> = emptyList()
)

data class TimeInterval(val start: String)
data class Point(
    val position: Int,
    @JsonProperty("price.amount")
    val priceAmount: Double
)

@kotlinx.serialization.Serializable
data class PriceData(
    val startTimeUTC: String,
    val price_eur_kwh: String
)

private val client = HttpClient(CIO) {
    install(HttpTimeout) {
        requestTimeoutMillis = 15000 // Total request time: 15 seconds
        connectTimeoutMillis = 10000 // Connection establishment: 10 seconds
        socketTimeoutMillis = 10000  // Inactivity between data packets: 10 seconds
    }
}

private val xmlMapper = XmlMapper().registerKotlinModule().apply {
    configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
}

fun main() {
    // Read environment variables
    val port = System.getenv("PORT")?.toInt() ?: 8080
    val host = System.getenv("HOST") ?: "0.0.0.0"
    val apiKey = System.getenv("ENTSOE_API_KEY")

    // Bidding zone for Estonia
    val eestiBiddingZone = "10Y1001A1001A39I"

    // Set up the Ktor server
    embeddedServer(Netty, port = port, host = host) {
        install(ContentNegotiation) {
            json()
        }
        install(CORS) {
            anyHost()
            allowHeader(HttpHeaders.ContentType)
        }

        routing {
            get("/api/prices") {
                if (apiKey.isNullOrBlank()) {
                    call.application.log.error("ENTSOE_API_KEY is not set.")
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
                    val entsoeApiUrl = "https://web-api.tp.entsoe.eu/api?securityToken=$apiKey&documentType=A44&in_Domain=$eestiBiddingZone&out_Domain=$eestiBiddingZone&periodStart=$periodStart&periodEnd=$periodEnd"

                    val response: HttpResponse = client.get(entsoeApiUrl)
                    val xmlString = response.bodyAsText()

                    if (!response.status.isSuccess() || xmlString.contains("<Reason>")) {
                        call.application.log.error("ENTSO-E API Error Response: $xmlString")
                        call.respond(
                            HttpStatusCode.BadGateway,
                            mapOf(
                                "error" to "Failed to fetch data from ENTSO-E.",
                                "details" to xmlString
                            )
                        )
                        return@get
                    }

                    // 3. Parse XML response
                    val marketDocument = xmlMapper.readValue(xmlString, PublicationMarketDocument::class.java)

                    // 4. Transform data into the final format
                    val prices = marketDocument.timeSeries.firstOrNull()?.period?.firstOrNull()?.let { period ->
                        val resolutionMinutes = period.resolution.removePrefix("PT").removeSuffix("M").toLongOrNull() ?: 60L
                        val periodStartInstant = Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(period.timeInterval.start))

                        period.point.map { point ->
                            val pricePerKWh = point.priceAmount / 1000.0
                            val intervalStart = periodStartInstant.plus((point.position - 1) * resolutionMinutes, ChronoUnit.MINUTES)

                            PriceData(
                                startTimeUTC = intervalStart.toString(),
                                price_eur_kwh = "%.5f".format(pricePerKWh)
                            )
                        }
                    } ?: emptyList()

                    // 5. Send the response
                    call.respond(prices)

                } catch (e: HttpRequestTimeoutException) {
                    call.application.log.error("ENTSO-E API timed out: ${e.message}", e)
                    call.respond(
                        HttpStatusCode.GatewayTimeout,
                        mapOf(
                            "error" to "Request to energy provider timed out.",
                            "details" to e.message
                        )
                    )
                } catch (e: Exception) {
                    call.application.log.error("An error occurred: ${e.message}", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf(
                            "error" to "An internal server error occurred.",
                            "details" to e.message
                        )
                    )
                }
            }
        }
    }.start(wait = true)
}