package ee.innov.eprice

import ee.innov.eprice.data.DailyAveragePriceCache
import ee.innov.eprice.data.DailyStatsCache
import ee.innov.eprice.data.PriceCache
import ee.innov.eprice.di.appModule
import ee.innov.eprice.domain.GetEnergyPricesUseCase
import ee.innov.eprice.domain.GetPriceStatisticsUseCase
import ee.innov.eprice.domain.GetPriceSummaryUseCase
import ee.innov.eprice.domain.GetRollingAveragePriceUseCase
import ee.innov.eprice.domain.EnergyPriceRepository
import ee.innov.eprice.domain.model.ApiError
import ee.innov.eprice.test.FixedPriceRepository
import ee.innov.eprice.security.KeyRotationService
import ee.innov.eprice.test.NoOpDailyAveragePriceCache
import ee.innov.eprice.test.NoOpDailyStatsCache
import ee.innov.eprice.test.NoOpPriceCache
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises real providers/repositories/use cases/serialization behind the live HTTP routes. */
class LiveRegionApiContractTest {
    private val amounts = mapOf("EE" to 100.0, "FI" to -50.0, "LT" to 0.0, "LV" to 200.0)
    private val clock = Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"), ZoneOffset.UTC)

    @AfterEach
    fun stopKoin() = GlobalContext.stopKoin()

    private fun withApi(tomorrowPublished: Boolean = true, failure: ApiError? = null, block: suspend (HttpClient) -> Unit) = testApplication {
        val engine = MockEngine { request ->
            if (request.url.host == "web-api.tp.entsoe.eu") {
                assertFalse(tomorrowPublished, "Fallback should only be used for unpublished prices")
                return@MockEngine respond(
                    "<Acknowledgement_MarketDocument><Reason><code>999</code><text>No matching data found</text></Reason></Acknowledgement_MarketDocument>",
                    HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/xml")
                )
            }
            assertEquals("dashboard.elering.ee", request.url.host, "Live regions should use Elering")
            val start = Instant.parse(request.url.parameters["start"])
            val end = Instant.parse(request.url.parameters["end"])
            val data = amounts.entries.joinToString(",") { (code, amount) ->
                val times = generateSequence(start) { it.plusSeconds(900) }.takeWhile {
                    it <= end && (tomorrowPublished || it < Instant.parse("2026-10-25T22:00:00Z"))
                }
                val rows = times.joinToString(",") { """{"timestamp":${it.epochSecond},"price":$amount}""" }
                """"${code.lowercase()}":[$rows]"""
            }
            respond("""{"success":true,"data":{$data}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val overrides = module {
            single { HttpClient(engine) }
            single(named("entsoeApiKey")) { "test-token" }
            single<PriceCache> { NoOpPriceCache() }
            single<DailyStatsCache> { NoOpDailyStatsCache() }
            single<DailyAveragePriceCache> { NoOpDailyAveragePriceCache() }
            if (failure != null) single<EnergyPriceRepository> { FixedPriceRepository(Result.failure(failure)) }
            factory { GetEnergyPricesUseCase(get(), clock) }
            factory { GetPriceStatisticsUseCase(get(), clock) }
            factory { GetPriceSummaryUseCase(get(), clock) }
            factory { GetRollingAveragePriceUseCase(get(), get(), clock) }
        }
        application { module(koinModules = listOf(appModule, overrides), allowKoinOverrides = true) }
        val authenticated = createClient {
            defaultRequest { header("X-API-Key", KeyRotationService().getKeyInfo().operationalKey) }
        }
        block(authenticated)
    }

    @Test
    fun `all live regions preserve array shape UTC string timestamps and five decimal EUR prices`() = withApi { client ->
        val formatted = mapOf("EE" to "0.10000", "FI" to "-0.05000", "LT" to "0.00000", "LV" to "0.20000")
        formatted.forEach { (code, price) ->
            val response = client.get("/api/prices/${code.lowercase()}")
            assertEquals(HttpStatusCode.OK, response.status)
            val rows = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            assertEquals(292, rows.size) // yesterday 24h + today 25h + tomorrow 24h
            assertEquals("2026-10-23T21:00:00Z", rows.first().jsonObject.getValue("startTimeUTC").jsonPrimitive.content)
            assertEquals("2026-10-26T21:45:00Z", rows.last().jsonObject.getValue("startTimeUTC").jsonPrimitive.content)
            rows.forEach { row ->
                val fields = row.jsonObject
                assertEquals(setOf("startTimeUTC", "price_eur_kwh"), fields.keys)
                assertTrue(fields.getValue("startTimeUTC").jsonPrimitive.isString)
                assertTrue(fields.getValue("price_eur_kwh").jsonPrimitive.isString)
                assertEquals(price, fields.getValue("price_eur_kwh").jsonPrimitive.content)
            }
        }
        assertEquals(client.get("/api/prices/EE").bodyAsText(), client.get("/api/prices").bodyAsText())
    }

    private fun assertStatistics(fields: JsonObject, code: String, price: Double) {
        assertEquals(setOf("countryCode", "startDate", "endDate", "daysRequested", "daysCalculated", "minPrice", "maxPrice", "averagePrice"), fields.keys)
        listOf("countryCode", "startDate", "endDate").forEach { assertTrue(fields.getValue(it).jsonPrimitive.isString) }
        assertEquals(code, fields.getValue("countryCode").jsonPrimitive.content)
        listOf("minPrice", "maxPrice", "averagePrice").forEach {
            assertFalse(fields.getValue(it).jsonPrimitive.isString)
            assertEquals(price, fields.getValue(it).jsonPrimitive.double, 1e-12)
        }
        assertFalse(fields.getValue("daysRequested").jsonPrimitive.isString)
        assertFalse(fields.getValue("daysCalculated").jsonPrimitive.isString)
    }

    @Test
    fun `provider failures retain 502 responses through price and statistics routes`() = withApi(
        failure = ApiError.Server(503, "Provider unavailable", IllegalStateException("Provider unavailable"))
    ) { client ->
        amounts.keys.forEach { code ->
            listOf("", "/avg", "/stats?range=today", "/stats/summary").forEach { suffix ->
                assertEquals(HttpStatusCode.BadGateway, client.get("/api/prices/$code$suffix").status)
            }
        }
    }

    @Test
    fun `provider timeouts retain 504 responses through price and statistics routes`() = withApi(
        failure = ApiError.Timeout("Timed out", IllegalStateException("Timed out"))
    ) { client ->
        listOf("", "/avg", "/stats?range=today", "/stats/summary").forEach { suffix ->
            assertEquals(HttpStatusCode.GatewayTimeout, client.get("/api/prices/EE$suffix").status)
        }
    }

    @Test
    fun `unknown and ambiguous regions return 400 before contacting a provider`() = withApi { client ->
        listOf("UNKNOWN", "NO", "SE", "IT", "10X1001A1001A82H").forEach { code ->
            listOf("", "/avg", "/stats", "/stats/summary").forEach { suffix ->
                assertEquals(HttpStatusCode.BadRequest, client.get("/api/prices/$code$suffix").status)
            }
        }
    }

    @Test
    fun `live statistics retain fields types and local dates on the DST day`() = withApi { client ->
        amounts.forEach { (code, amount) ->
            val response = client.get("/api/prices/${code.lowercase()}/stats?range=today")
            assertEquals(HttpStatusCode.OK, response.status)
            val fields = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertStatistics(fields, code, amount / 1000)
            assertEquals("2026-10-25", fields.getValue("startDate").jsonPrimitive.content)
            assertEquals("2026-10-25", fields.getValue("endDate").jsonPrimitive.content)
            assertEquals(1, fields.getValue("daysCalculated").jsonPrimitive.int)
        }
    }

    @Test
    fun `live rolling average retains five day window and response types`() = withApi { client ->
        amounts.forEach { (code, amount) ->
            val response = client.get("/api/prices/$code/avg")
            assertEquals(HttpStatusCode.OK, response.status)
            val fields = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(setOf("countryCode", "daysRequested", "daysCalculated", "startDate", "endDate", "averagePrice"), fields.keys)
            assertEquals(code, fields.getValue("countryCode").jsonPrimitive.content)
            assertEquals(5, fields.getValue("daysRequested").jsonPrimitive.int)
            assertEquals(5, fields.getValue("daysCalculated").jsonPrimitive.int)
            assertEquals("2026-10-20", fields.getValue("startDate").jsonPrimitive.content)
            assertEquals("2026-10-24", fields.getValue("endDate").jsonPrimitive.content)
            assertFalse(fields.getValue("averagePrice").jsonPrimitive.isString)
            assertEquals(amount / 1000, fields.getValue("averagePrice").jsonPrimitive.double, 1e-12)
        }
    }

    @Test
    fun `live summary retains nested statistics and null unpublished tomorrow`() = withApi(tomorrowPublished = false) { client ->
        amounts.forEach { (code, amount) ->
            val response = client.get("/api/prices/$code/stats/summary")
            assertEquals(HttpStatusCode.OK, response.status)
            val fields = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(setOf("countryCode", "rolling", "yesterday", "today", "tomorrow"), fields.keys)
            assertEquals(JsonNull, fields.getValue("tomorrow"))
            listOf("rolling", "yesterday", "today").forEach { assertStatistics(fields.getValue(it).jsonObject, code, amount / 1000) }
        }
    }
}
