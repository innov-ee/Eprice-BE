package ee.innov.eprice.presentation

import ee.innov.eprice.data.remote.EntsoeRemoteDataSource
import ee.innov.eprice.data.remote.dto.Period
import ee.innov.eprice.data.remote.dto.Point
import ee.innov.eprice.data.remote.dto.PublicationMarketDocument
import ee.innov.eprice.data.remote.dto.TimeInterval
import ee.innov.eprice.data.remote.dto.TimeSeries
import ee.innov.eprice.di.appModule
import ee.innov.eprice.domain.model.NoDataFoundException
import ee.innov.eprice.module
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext.stopKoin
import org.koin.dsl.module
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PriceRoutesTest {

    private val mockRemoteDataSource: EntsoeRemoteDataSource = mockk()

    private val testModule = module {
        single { mockRemoteDataSource }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `GET prices should return 200 OK with price data on success`() = testApplication {

        // 3. Set up the application using the REAL module() function
        application {
            module(
                koinModules = listOf(appModule, testModule),
                allowKoinOverrides = true
            )
        }

        // 4. ARRANGE: Define the mock's behavior
        val mockXmlResponse = PublicationMarketDocument(
            timeSeries = listOf(
                TimeSeries(
                    period = listOf(
                        Period(
                            timeInterval = TimeInterval(start = "2023-01-01T00:00:00Z"),
                            resolution = "PT60M",
                            point = listOf(
                                Point(position = 1, priceAmount = 150.0), // 150 EUR/MWh
                                Point(position = 2, priceAmount = 120.0)  // 120 EUR/MWh
                            )
                        )
                    )
                )
            )
        )
        coEvery { mockRemoteDataSource.fetchPrices(any(), any()) } returns mockXmlResponse

        // 5. ACT: Make the HTTP request
        val response = client.get("/api/prices")

        // 6. ASSERT
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()

        assertTrue(body.contains(""""startTimeUTC":"2023-01-01T00:00:00Z""""))
        assertTrue(body.contains(""""price_eur_kwh":"0.15000""""))
        assertTrue(body.contains(""""startTimeUTC":"2023-01-01T01:00:00Z""""))
        assertTrue(body.contains(""""price_eur_kwh":"0.12000""""))
    }

    @Test
    fun `GET prices should return 200 OK with empty list on NoDataFoundException`() =
        testApplication {
            application {
                module(
                    koinModules = listOf(appModule, testModule),
                    allowKoinOverrides = true
                )
            }

            // ARRANGE:
            coEvery {
                mockRemoteDataSource.fetchPrices(any(), any())
            } throws NoDataFoundException("No data for period")

            // ACT
            val response = client.get("/api/prices")

            // ASSERT
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("[]", response.bodyAsText())
        }
}