package ee.innov.eprice

import ee.innov.eprice.data.DailyAveragePriceCache
import ee.innov.eprice.data.DailyStatsCache
import ee.innov.eprice.data.PriceCache
import ee.innov.eprice.di.appModule
import ee.innov.eprice.test.NoOpDailyAveragePriceCache
import ee.innov.eprice.test.NoOpDailyStatsCache
import ee.innov.eprice.test.NoOpPriceCache
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest {

    @AfterEach
    fun tearDown() {
        GlobalContext.stopKoin()
    }

    private val mockEleringSuccessJson = """
        {
          "success": true,
          "data": {
            "ee": [
              {
                "timestamp": 1672531200, 
                "price": 150.0
              },
              {
                "timestamp": 1672534800, 
                "price": 120.0
              }
            ]
          }
        }
    """.trimIndent()

    private val mockEleringNoDataJson = """
        {
          "success": true,
          "data": {}
        }
    """.trimIndent()

    private val mockEntsoeSuccessXml = """
        <Publication_MarketDocument>
            <TimeSeries>
                <Period>
                    <timeInterval>
                        <start>2023-01-01T00:00:00Z</start>
                    </timeInterval>
                    <resolution>PT60M</resolution>
                    <Point>
                        <position>1</position>
                        <price.amount>150.0</price.amount>
                    </Point>
                    <Point>
                        <position>2</position>
                        <price.amount>120.0</price.amount>
                    </Point>
                </Period>
            </TimeSeries>
        </Publication_MarketDocument>
    """.trimIndent()

    private val mockEntsoeNoDataErrorXml = """
            <Reason>
                <text>No matching data found for the specified time interval</text>
            </Reason>
        """.trimIndent()

    private val mockEntsoeAuthErrorXml = """
            <Reason>
                <text>Invalid security token</text>
            </Reason>
        """.trimIndent()


    // --- Tests ---

    @Test
    fun `GET prices should return 200 OK with Elering price data`() {
        runPriceApiTest(
            engineHandler = createMockEngineHandler(
                eleringContent = mockEleringSuccessJson,
                eleringStatus = HttpStatusCode.OK,
                entsoeContent = "<Error>Entsoe should not be called</Error>",
                entsoeStatus = HttpStatusCode.InternalServerError
            ),
            testBlock = {
                val response = client.get("/api/prices")

                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()

                // Assertions checking for the Elering data
                assertTrue(body.contains(""""startTimeUTC":"2023-01-01T00:00:00Z""""))
                assertTrue(body.contains(""""price_eur_kwh":"0.15000""""))
                assertTrue(body.contains(""""startTimeUTC":"2023-01-01T01:00:00Z""""))
                assertTrue(body.contains(""""price_eur_kwh":"0.12000""""))
            }
        )
    }

    @Test
    fun `GET prices should return 200 OK with Entsoe price data on Elering failure`() {
        runPriceApiTest(
            engineHandler = createMockEngineHandler(
                eleringContent = mockEleringNoDataJson,
                eleringStatus = HttpStatusCode.OK,
                entsoeContent = mockEntsoeSuccessXml,
                entsoeStatus = HttpStatusCode.OK
            ),
            testBlock = {
                val response = client.get("/api/prices")

                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()

                // Assertions checking for the Entsoe data
                assertTrue(body.contains(""""startTimeUTC":"2023-01-01T00:00:00Z""""))
                assertTrue(body.contains(""""price_eur_kwh":"0.15000""""))
                assertTrue(body.contains(""""startTimeUTC":"2023-01-01T01:00:00Z""""))
                assertTrue(body.contains(""""price_eur_kwh":"0.12000""""))
            }
        )
    }

    @Test
    fun `GET prices should return 200 OK with empty list on NoDataFoundException from Entsoe`() {
        runPriceApiTest(
            engineHandler = createMockEngineHandler(
                eleringContent = mockEleringNoDataJson,
                eleringStatus = HttpStatusCode.OK,
                entsoeContent = mockEntsoeNoDataErrorXml,
                entsoeStatus = HttpStatusCode.BadRequest
            ),
            testBlock = {
                val response = client.get("/api/prices")

                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("[]", response.bodyAsText())
            }
        )
    }

    @Test
    fun `GET prices should return 502 BadGateway on general API error from Entsoe`() {
        runPriceApiTest(
            engineHandler = createMockEngineHandler(
                eleringContent = mockEleringNoDataJson,
                eleringStatus = HttpStatusCode.OK,
                entsoeContent = mockEntsoeAuthErrorXml,
                entsoeStatus = HttpStatusCode.Unauthorized
            ),
            testBlock = {
                val response = client.get("/api/prices")

                assertEquals(HttpStatusCode.BadGateway, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains(""""error":"Server error (code 401)""""))
                assertTrue(body.contains(""""details":"Failed to fetch data from ENTSO-E"""))
                assertTrue(body.contains("Invalid security token"))
            }
        )
    }

    @Test
    fun `GET prices should return 502 BadGateway on unexpected non-XML response from Entsoe`() {
        runPriceApiTest(
            engineHandler = createMockEngineHandler(
                eleringContent = mockEleringNoDataJson,
                eleringStatus = HttpStatusCode.OK,
                entsoeContent = """{"error": "Too Many Requests"}""",
                entsoeStatus = HttpStatusCode.OK
            ),
            testBlock = {
                val response = client.get("/api/prices")

                assertEquals(HttpStatusCode.BadGateway, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains(""""error":"Server error (code 200)""""))
                assertTrue(body.contains("Unexpected non-XML response from ENTSO-E"))
            }
        )
    }

    @Test
    fun `GET prices stats should return 200 OK with aggregated statistics`() {
        runPriceApiTest(
            engineHandler = createMockEngineHandler(
                eleringContent = mockEleringSuccessJson,
                eleringStatus = HttpStatusCode.OK,
                entsoeContent = "<Error>Entsoe should not be called</Error>",
                entsoeStatus = HttpStatusCode.InternalServerError
            ),
            testBlock = {
                val response = client.get("/api/prices/EE/stats?range=yesterday")

                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains(""""countryCode":"EE""""))
                assertTrue(body.contains(""""minPrice":0.12"""))
                assertTrue(body.contains(""""maxPrice":0.15"""))
                assertTrue(body.contains(""""averagePrice":0.135"""))
                assertTrue(body.contains(""""daysCalculated":1"""))
            }
        )
    }

    @Test
    fun `GET prices stats for today should return 200 OK with statistics`() {
        runPriceApiTest(
            engineHandler = createMockEngineHandler(
                eleringContent = mockEleringSuccessJson,
                eleringStatus = HttpStatusCode.OK,
                entsoeContent = "<Error>Entsoe should not be called</Error>",
                entsoeStatus = HttpStatusCode.InternalServerError
            ),
            testBlock = {
                val response = client.get("/api/prices/EE/stats?range=today")

                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains(""""countryCode":"EE""""))
                assertTrue(body.contains(""""daysCalculated":1"""))
            }
        )
    }

    @Test
    fun `GET prices stats for tomorrow before publication should return 404 NotFound`() {
        runPriceApiTest(
            engineHandler = createMockEngineHandler(
                eleringContent = mockEleringNoDataJson,
                eleringStatus = HttpStatusCode.OK,
                entsoeContent = mockEntsoeNoDataErrorXml,
                entsoeStatus = HttpStatusCode.OK
            ),
            testBlock = {
                val response = client.get("/api/prices/EE/stats?range=tomorrow")

                assertEquals(HttpStatusCode.NotFound, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains(""""error":"No data found""""))
            }
        )
    }

    @Test
    fun `GET prices stats with invalid days should return 400 BadRequest`() {
        runPriceApiTest(
            engineHandler = createMockEngineHandler(
                eleringContent = mockEleringSuccessJson,
                eleringStatus = HttpStatusCode.OK,
                entsoeContent = "<Error>Entsoe should not be called</Error>",
                entsoeStatus = HttpStatusCode.InternalServerError
            ),
            testBlock = {
                val response = client.get("/api/prices/EE/stats?days=-5")

                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
        )
    }

    @Test
    fun `GET prices stats with invalid range name should return 400 BadRequest`() {
        runPriceApiTest(
            engineHandler = createMockEngineHandler(
                eleringContent = mockEleringSuccessJson,
                eleringStatus = HttpStatusCode.OK,
                entsoeContent = "<Error>Entsoe should not be called</Error>",
                entsoeStatus = HttpStatusCode.InternalServerError
            ),
            testBlock = {
                val response = client.get("/api/prices/EE/stats?range=nextmonth")

                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
        )
    }

    @Test
    fun `GET prices stats summary should return 200 OK with combined summary`() {
        runPriceApiTest(
            engineHandler = createMockEngineHandler(
                eleringContent = mockEleringSuccessJson,
                eleringStatus = HttpStatusCode.OK,
                entsoeContent = "<Error>Entsoe should not be called</Error>",
                entsoeStatus = HttpStatusCode.InternalServerError
            ),
            testBlock = {
                val response = client.get("/api/prices/EE/stats/summary")

                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains(""""countryCode":"EE""""))
                assertTrue(body.contains(""""rolling":{"""))
                assertTrue(body.contains(""""yesterday":{"""))
                assertTrue(body.contains(""""today":{"""))
            }
        )
    }

    @Test
    fun `GET monitor html should return 200 OK with html content`() {
        runPriceApiTest(
            engineHandler = createMockEngineHandler(
                eleringContent = mockEleringSuccessJson,
                eleringStatus = HttpStatusCode.OK,
                entsoeContent = "<Error>Entsoe should not be called</Error>",
                entsoeStatus = HttpStatusCode.InternalServerError
            ),
            testBlock = {
                val response = client.get("/monitor.html")

                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains("<title>EPrice Service Monitor & API Explorer</title>"))
                assertTrue(body.contains("Operational Metrics"))
                assertTrue(body.contains("refreshMonitor"))
            }
        )
    }

    @Test
    fun `GET api meta routes should return 200 OK with route metadata catalog`() {
        runPriceApiTest(
            engineHandler = createMockEngineHandler(
                eleringContent = mockEleringSuccessJson,
                eleringStatus = HttpStatusCode.OK,
                entsoeContent = "<Error>Entsoe should not be called</Error>",
                entsoeStatus = HttpStatusCode.InternalServerError
            ),
            testBlock = {
                val response = client.get("/api/meta/routes")

                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains(""""path":"/monitor""""))
                assertTrue(body.contains(""""path":"/api/prices/{countryCode?}""""))
                assertTrue(body.contains(""""category":"Monitoring & Diagnostics""""))
                assertTrue(body.contains(""""samples":[{"""))
            }
        )
    }

    @Test
    fun `GET monitor should return 200 OK with service stats`() {
        runPriceApiTest(
            engineHandler = createMockEngineHandler(
                eleringContent = mockEleringSuccessJson,
                eleringStatus = HttpStatusCode.OK,
                entsoeContent = "<Error>Entsoe should not be called</Error>",
                entsoeStatus = HttpStatusCode.InternalServerError
            ),
            testBlock = {
                // First make an API call to generate some incoming and outgoing metrics
                client.get("/api/prices")

                val response = client.get("/monitor")

                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains(""""uptime":"""))
                assertTrue(body.contains(""""totalIncomingRequests":2""")) // /api/prices + /monitor
                assertTrue(body.contains(""""totalOutgoingRequests":1""")) // Elering call
                assertTrue(body.contains(""""outgoingEleringRequests":1"""))
                assertTrue(body.contains(""""outgoingEntsoeRequests":0"""))
                assertTrue(body.contains(""""cacheHits":0"""))
                assertTrue(body.contains(""""cacheMisses":1"""))
                assertTrue(body.contains(""""cacheHitRatio":0.0"""))
            }
        )
    }


    private fun generateEleringDailyJson(startInstant: java.time.Instant, count: Int = 24): String {
        val points = (0 until count).joinToString(",") { i ->
            val timestamp = startInstant.epochSecond + (i * 3600)
            val price = if (i % 2 == 0) 150.0 else 120.0
            """{"timestamp": $timestamp, "price": $price}"""
        }
        return """{"success": true, "data": {"EE": [$points]}}"""
    }

    private fun createMockEngineHandler(
        eleringContent: String,
        eleringStatus: HttpStatusCode,
        entsoeContent: String,
        entsoeStatus: HttpStatusCode
    ): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData {
        return { request ->
            if (request.url.host.contains("elering")) {
                if (eleringStatus == HttpStatusCode.OK && eleringContent == mockEleringSuccessJson) {
                    val startParam = request.url.parameters["start"]
                    val endParam = request.url.parameters["end"]
                    if (startParam != null && endParam != null) {
                        try {
                            val startInstant = java.time.Instant.parse(startParam)
                            val endInstant = java.time.Instant.parse(endParam)
                            val durationSeconds = java.time.Duration.between(startInstant, endInstant).seconds
                            if (durationSeconds <= 86400) {
                                val content = generateEleringDailyJson(startInstant)
                                mockJsonResponse(content, eleringStatus)
                            } else {
                                mockJsonResponse(eleringContent, eleringStatus)
                            }
                        } catch (_: Exception) {
                            mockJsonResponse(eleringContent, eleringStatus)
                        }
                    } else {
                        mockJsonResponse(eleringContent, eleringStatus)
                    }
                } else {
                    mockJsonResponse(eleringContent, eleringStatus)
                }
            } else {
                mockXmlResponse(entsoeContent, entsoeStatus)
            }
        }
    }

    /**
     * A helper to create a standardized XML response for the MockEngine.
     */
    private fun MockRequestHandleScope.mockXmlResponse(
        content: String,
        status: HttpStatusCode
    ): HttpResponseData = respond(
        content = ByteReadChannel(content),
        status = status,
        headers = headersOf(HttpHeaders.ContentType to listOf("application/xml"))
    )

    /**
     * A helper to create a standardized JSON response for the MockEngine.
     */
    private fun MockRequestHandleScope.mockJsonResponse(
        content: String,
        status: HttpStatusCode
    ): HttpResponseData = respond(
        content = ByteReadChannel(content),
        status = status,
        headers = headersOf(HttpHeaders.ContentType to listOf("application/json"))
    )

    /**
     * Main test runner that sets up the Ktor application, Koin modules,
     * and a mock HttpClient for each test.
     */
    private fun runPriceApiTest(
        engineHandler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
        testBlock: suspend ApplicationTestBuilder.() -> Unit
    ) = testApplication {
        // ARRANGE:
        val mockEngine = MockEngine(engineHandler)

        val testModule = module {
            single {
                val monitor = get<ee.innov.eprice.monitoring.ServiceMonitor>()
                HttpClient(mockEngine) {
                    install(createClientPlugin("OutgoingMonitor") {
                        onRequest { request, _ ->
                            monitor.incrementOutgoing(request.url.host)
                        }
                    })
                }
            } // Override the real HttpClient
            single(qualifier = named("entsoeApiKey")) { "TEST_KEY" }
            single<PriceCache> { NoOpPriceCache() }
            single<DailyStatsCache> { NoOpDailyStatsCache() }
            single<DailyAveragePriceCache> { NoOpDailyAveragePriceCache() }
        }

        application {
            module(
                koinModules = listOf(appModule, testModule),
                allowKoinOverrides = true
            )
        }

        // ACT & ASSERT: Run the specific test logic
        testBlock()
    }
}