package ee.innov.eprice

import ee.innov.eprice.data.DailyAveragePriceCache
import ee.innov.eprice.data.DailyStatsCache
import ee.innov.eprice.data.PriceCache
import ee.innov.eprice.di.appModule
import ee.innov.eprice.security.KeyRotationService
import ee.innov.eprice.test.NoOpDailyAveragePriceCache
import ee.innov.eprice.test.NoOpDailyStatsCache
import ee.innov.eprice.test.NoOpPriceCache
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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
            testBlock = { client ->
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
            testBlock = { client ->
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
            testBlock = { client ->
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
            testBlock = { client ->
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
            testBlock = { client ->
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
            testBlock = { client ->
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
    fun `GET prices stats normalizes lowercase country code at route boundary`() {
        runPriceApiTest(
            engineHandler = createMockEngineHandler(
                eleringContent = mockEleringSuccessJson,
                eleringStatus = HttpStatusCode.OK,
                entsoeContent = "<Error>Entsoe should not be called</Error>",
                entsoeStatus = HttpStatusCode.InternalServerError
            ),
            testBlock = { client ->
                val response = client.get("/api/prices/ee/stats?range=yesterday")

                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains(""""countryCode":"EE""""))
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
            testBlock = { client ->
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
            testBlock = { client ->
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
            testBlock = { client ->
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
            testBlock = { client ->
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
            testBlock = { client ->
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
            testBlock = { client ->
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
            testBlock = { client ->
                val response = client.get("/api/meta/routes")

                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains(""""path":"/monitor""""))
                assertTrue(body.contains(""""path":"/api/prices/{countryCode?}""""))
                assertTrue(body.contains(""""path":"/api/v1/auth/keys""""))
                assertTrue(body.contains(""""category":"Monitoring & Diagnostics""""))
                assertTrue(body.contains(""""category":"Authentication & Keys""""))
                assertTrue(body.contains(""""headers":{"X-Bootstrap-Key":"test-bootstrap-key"}"""))
                assertTrue(body.contains(""""X-Bootstrap-Key":"invalid-bootstrap-key""""))
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
            testBlock = { client ->
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

    // --- Security Integration Tests ---

    @Test
    fun `GET health should return 200 OK without any authentication`() = testApplication {
        application {
            module(allowKoinOverrides = true)
        }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains(""""status":"UP""""))
    }

    @Test
    fun `GET keys should return 401 Unauthorized when X-Bootstrap-Key is missing`() = testApplication {
        application {
            module(allowKoinOverrides = true)
        }

        val response = client.get("/api/v1/auth/keys")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains(""""error":"Unauthorized""""))
    }

    @Test
    fun `GET keys should return 401 Unauthorized when X-Bootstrap-Key is invalid`() = testApplication {
        application {
            module(allowKoinOverrides = true)
        }

        val response = client.get("/api/v1/auth/keys") {
            header("X-Bootstrap-Key", "wrong-bootstrap-key")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains(""""error":"Unauthorized""""))
    }

    @Test
    fun `GET keys should return 200 OK with operational key metadata when X-Bootstrap-Key is valid`() = testApplication {
        application {
            module(allowKoinOverrides = true)
        }

        val response = client.get("/api/v1/auth/keys") {
            header("X-Bootstrap-Key", "test-bootstrap-key")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains(""""operational_key":"""))
        assertTrue(body.contains(""""ttl_seconds":"""))
        assertTrue(body.contains(""""expires_at":"""))
    }

    @Test
    fun `GET prices without credentials should return 401 Unauthorized`() = testApplication {
        application {
            module(allowKoinOverrides = true)
        }

        val response = client.get("/api/prices")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET prices with invalid X-API-Key should return 401 Unauthorized`() = testApplication {
        application {
            module(allowKoinOverrides = true)
        }

        val response = client.get("/api/prices") {
            header("X-API-Key", "invalid-key")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET prices with valid X-API-Key should return 200 OK`() {
        runPriceApiTest(
            engineHandler = createMockEngineHandler(
                eleringContent = mockEleringSuccessJson,
                eleringStatus = HttpStatusCode.OK,
                entsoeContent = "<Error>Entsoe should not be called</Error>",
                entsoeStatus = HttpStatusCode.InternalServerError
            ),
            testBlock = {
                val validKey = KeyRotationService().getKeyInfo().operationalKey
                val response = client.get("/api/prices") {
                    header("X-API-Key", validKey)
                }
                assertEquals(HttpStatusCode.OK, response.status)
            }
        )
    }

    @Test
    fun `GET prices with valid Admin Basic Auth should return 200 OK`() {
        runPriceApiTest(
            engineHandler = createMockEngineHandler(
                eleringContent = mockEleringSuccessJson,
                eleringStatus = HttpStatusCode.OK,
                entsoeContent = "<Error>Entsoe should not be called</Error>",
                entsoeStatus = HttpStatusCode.InternalServerError
            ),
            testBlock = {
                val adminAuth = "Basic " + java.util.Base64.getEncoder().encodeToString("admin:password".toByteArray())
                val response = client.get("/api/prices") {
                    header("Authorization", adminAuth)
                }
                assertEquals(HttpStatusCode.OK, response.status)
            }
        )
    }

    @Test
    fun `GET monitor should return 401 Unauthorized when unauthenticated`() = testApplication {
        application {
            module(allowKoinOverrides = true)
        }

        val response = client.get("/monitor")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET monitor html should return 401 Unauthorized when unauthenticated`() = testApplication {
        application {
            module(allowKoinOverrides = true)
        }

        val response = client.get("/monitor.html")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET cache clear should fail or not be routed as GET`() = testApplication {
        application {
            module(allowKoinOverrides = true)
        }

        val response = client.get("/api/cache/clear")
        // Since /api/cache/clear is now POST only, GET returns 404/405 or 401
        assertTrue(response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.MethodNotAllowed)
    }

    @Test
    fun `POST cache clear without credentials should return 401 Unauthorized`() = testApplication {
        application {
            module(allowKoinOverrides = true)
        }

        val response = client.post("/api/cache/clear")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST cache clear with X-API-Key only should return 401 Unauthorized (Admin Basic required)`() = testApplication {
        application {
            module(allowKoinOverrides = true)
        }

        val validApiKey = KeyRotationService().getKeyInfo().operationalKey
        val response = client.post("/api/cache/clear") {
            header("X-API-Key", validApiKey)
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST cache clear with valid Admin Basic Auth should return 200 OK`() = testApplication {
        application {
            module(allowKoinOverrides = true)
        }

        val adminAuthHeader = "Basic " + java.util.Base64.getEncoder().encodeToString("admin:password".toByteArray())
        val response = client.post("/api/cache/clear") {
            header("Authorization", adminAuthHeader)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("All caches clear initiated"))
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
        testBlock: suspend ApplicationTestBuilder.(HttpClient) -> Unit
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

        val validApiKey = KeyRotationService().getKeyInfo().operationalKey
        val adminAuthHeader = "Basic " + java.util.Base64.getEncoder().encodeToString("admin:password".toByteArray())

        // Create an authenticated client that sends X-API-Key and Admin Basic Auth by default
        val authClient = createClient {
            defaultRequest {
                header("X-API-Key", validApiKey)
                header("Authorization", adminAuthHeader)
            }
        }

        // Run the specific test logic passing authClient
        testBlock(authClient)
    }
}