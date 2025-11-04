package ee.innov.eprice.presentation

import ee.innov.eprice.di.appModule
import ee.innov.eprice.module
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PriceRoutesTest {

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `GET prices should return 200 OK with price data on success`() = testApplication {
        // 1. ARRANGE: Define the mock XML response from the network
        val mockXmlResponse = """
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

        // 2. ARRANGE: Set up the MockEngine and HttpClient
        val mockEngine = MockEngine { request ->
            // You could add assertions here about the request URL if needed
            // e.g., assertTrue(request.url.parameters.contains("securityToken", "TEST_KEY"))
            respond(
                content = ByteReadChannel(mockXmlResponse),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf("application/xml"))
            )
        }
        val mockHttpClient = HttpClient(mockEngine)

        // 3. ARRANGE: Define the Koin test module to override the HttpClient and API Key
        val testModule = module {
            single { mockHttpClient } // Replaces the real HttpClient(CIO)
            single(qualifier = named("entsoeApiKey")) { "TEST_KEY" } // Provide a dummy key
        }

        // 4. ARRANGE: Set up the application
        application {
            module(
                koinModules = listOf(appModule, testModule),
                allowKoinOverrides = true
            )
        }

        // 5. ACT: Make the HTTP request
        val response = client.get("/api/prices")

        // 6. ASSERT
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()

        // This now tests the entire chain:
        // MockEngine -> HttpClient -> EntsoeRemoteDataSource -> XmlMapper ->
        // toDomainEnergyPrices -> EnergyPriceRepositoryImpl ->
        // GetEnergyPricesUseCase -> PriceRoutes -> toPriceData
        assertTrue(body.contains(""""startTimeUTC":"2023-01-01T00:00:00Z""""))
        assertTrue(body.contains(""""price_eur_kwh":"0.15000""""))
        assertTrue(body.contains(""""startTimeUTC":"2023-01-01T01:00:00Z""""))
        assertTrue(body.contains(""""price_eur_kwh":"0.12000""""))
    }

    @Test
    fun `GET prices should return 200 OK with empty list on NoDataFoundException`() =
        testApplication {
            // 1. ARRANGE: Define the "No data" error response from the network
            val mockErrorXml = """
                <Reason>
                    <text>No matching data found for the specified time interval</text>
                </Reason>
            """.trimIndent()

            // 2. ARRANGE: Set up the MockEngine to return a non-success status
            val mockEngine = MockEngine {
                respond(
                    content = ByteReadChannel(mockErrorXml),
                    status = HttpStatusCode.BadRequest, // Any non-200 status
                    headers = headersOf(HttpHeaders.ContentType to listOf("application/xml"))
                )
            }
            val mockHttpClient = HttpClient(mockEngine)

            // 3. ARRANGE: Koin test module
            val testModule = module {
                single { mockHttpClient }
                single(qualifier = named("entsoeApiKey")) { "TEST_KEY" }
            }

            // 4. ARRANGE: Set up the application
            application {
                module(
                    koinModules = listOf(appModule, testModule),
                    allowKoinOverrides = true
                )
            }

            // 5. ACT
            val response = client.get("/api/prices")

            // 6. ASSERT
            // This now tests that EntsoeRemoteDataSource correctly throws
            // NoDataFoundException, and EnergyPriceRepositoryImpl correctly
            // catches it and returns Result.success(emptyList())
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("[]", response.bodyAsText())
        }

    @Test
    fun `GET prices should return 502 BadGateway on general API error`() =
        testApplication {
            // 1. ARRANGE: Define a generic error response from the network
            val mockErrorXml = """
                <Reason>
                    <text>Invalid security token</text>
                </Reason>
            """.trimIndent()

            // 2. ARRANGE: Set up the MockEngine to return an error status
            val mockEngine = MockEngine {
                respond(
                    content = ByteReadChannel(mockErrorXml),
                    status = HttpStatusCode.Unauthorized, // e.g., 401
                    headers = headersOf(HttpHeaders.ContentType to listOf("application/xml")) // <-- CORRECTED
                )
            }
            val mockHttpClient = HttpClient(mockEngine)

            // 3. ARRANGE: Koin test module
            val testModule = module {
                single { mockHttpClient }
                single(qualifier = named("entsoeApiKey")) { "TEST_KEY" }
            }

            // 4. ARRANGE: Set up the application
            application {
                module(
                    koinModules = listOf(appModule, testModule),
                    allowKoinOverrides = true
                )
            }

            // 5. ACT
            val response = client.get("/api/prices")

            // 6. ASSERT
            // This tests the full error path:
            // EntsoeRemoteDataSource -> EntsoeApiException
            // EnergyPriceRepositoryImpl -> Result.failure(ApiError.Server)
            // PriceRoutes -> responds with BadGateway
            assertEquals(HttpStatusCode.BadGateway, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains(""""error":"Server error (code 401)""""))
            assertTrue(body.contains(""""details":"Failed to fetch data from ENTSO-E"""))
            assertTrue(body.contains("Invalid security token"))
        }
}