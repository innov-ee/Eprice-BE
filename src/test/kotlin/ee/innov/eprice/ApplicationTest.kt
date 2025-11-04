package ee.innov.eprice

import ee.innov.eprice.di.appModule
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
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

    @Test
    fun `GET prices should return 200 OK with price data on success`() {
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

        runPriceApiTest(
            engineHandler = { _ ->
                mockXmlResponse(mockXmlResponse, HttpStatusCode.OK)
            },
            testBlock = {
                val response = client.get("/api/prices")

                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()

                assertTrue(body.contains(""""startTimeUTC":"2023-01-01T00:00:00Z""""))
                assertTrue(body.contains(""""price_eur_kwh":"0.15000""""))
                assertTrue(body.contains(""""startTimeUTC":"2023-01-01T01:00:00Z""""))
                assertTrue(body.contains(""""price_eur_kwh":"0.12000""""))
            }
        )
    }

    @Test
    fun `GET prices should return 200 OK with empty list on NoDataFoundException`() {
        val mockErrorResponse = """
            <Reason>
                <text>No matching data found for the specified time interval</text>
            </Reason>
        """.trimIndent()

        runPriceApiTest(
            engineHandler = { _ ->
                mockXmlResponse(mockErrorResponse, HttpStatusCode.BadRequest)
            },
            testBlock = {
                val response = client.get("/api/prices")

                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("[]", response.bodyAsText())
            }
        )
    }

    @Test
    fun `GET prices should return 502 BadGateway on general API error`() {
        val mockErrorResponse = """
            <Reason>
                <text>Invalid security token</text>
            </Reason>
        """.trimIndent()

        runPriceApiTest(
            engineHandler = { _ ->
                mockXmlResponse(mockErrorResponse, HttpStatusCode.Unauthorized)
            },
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
     * Main test runner that sets up the Ktor application, Koin modules,
     * and a mock HttpClient for each test.
     */
    private fun runPriceApiTest(
        engineHandler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
        testBlock: suspend ApplicationTestBuilder.() -> Unit
    ) = testApplication {
        // ARRANGE:
        val mockEngine = MockEngine(engineHandler)
        val mockHttpClient = HttpClient(mockEngine)
        // Note: You might need to add ContentNegotiation here if your
        // *real* client uses it, though for this test it's not required
        // as we are just passing mock text.


        val testModule = module {
            single { mockHttpClient }
            single(qualifier = named("entsoeApiKey")) { "TEST_KEY" }
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