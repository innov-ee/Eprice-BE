package ee.innov.eprice.data

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import ee.innov.eprice.data.elering.EleringService
import ee.innov.eprice.data.entsoe.EntsoeService
import ee.innov.eprice.domain.model.ApiError
import ee.innov.eprice.domain.model.DomainEnergyPrice
import ee.innov.eprice.test.NoOpPriceCache
import ee.innov.eprice.test.entsoePriceXml
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderRoutingRegressionTest {
    private val start = Instant.parse("2026-08-22T00:00:00Z")
    private val end = Instant.parse("2026-08-22T01:00:00Z")

    @Test
    fun `provider services return domain prices clipped to the requested range`() = runBlocking {
        val rows = (-1..4).joinToString(",") { i ->
            """{"timestamp":${start.plusSeconds(i * 900L).epochSecond},"price":100.0}"""
        }
        val engine = MockEngine { request ->
            if (request.url.host == "dashboard.elering.ee") {
                respond("""{"success":true,"data":{"ee":[$rows]}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respond(entsoePriceXml(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/xml"))
            }
        }
        val client = HttpClient(engine)
        try {
            val mapper = XmlMapper().apply { registerKotlinModule(); configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false) }
            val rangeStart = start.plusSeconds(300)
            val rangeEnd = end.minusSeconds(300)
            val expected = listOf(
                DomainEnergyPrice(rangeStart, 0.1, 600),
                DomainEnergyPrice(start.plusSeconds(900), 0.1, 900),
                DomainEnergyPrice(start.plusSeconds(1800), 0.1, 900),
                DomainEnergyPrice(start.plusSeconds(2700), 0.1, 600)
            )
            assertEquals(expected, EleringService(client).fetchPrices("EE", rangeStart, rangeEnd))
            assertEquals(expected, EntsoeService(client, mapper, "test-token").fetchPrices("10Y1001A1001A82H", rangeStart, rangeEnd))
        } finally { client.close() }
    }

    @Test
    fun `Elering validation failures still trigger ENTSO-E fallback`() = runBlocking {
        val hosts = mutableListOf<String>()
        val row = """{"timestamp":${start.epochSecond},"price":100.0}"""
        val engine = MockEngine { request ->
            hosts.add(request.url.host)
            if (request.url.host == "dashboard.elering.ee") {
                respond("""{"success":true,"data":{"ee":[$row,$row]}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respond(entsoePriceXml(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/xml"))
            }
        }
        withRepository(engine) { repo ->
            assertEquals(4, repo.getPrices("EE", start, end, false).getOrThrow().size)
            assertEquals(listOf("dashboard.elering.ee", "web-api.tp.entsoe.eu"), hosts)
        }
    }

    private suspend fun withRepository(engine: MockEngine, block: suspend (EnergyPriceRepositoryImpl) -> Unit) {
        val client = HttpClient(engine)
        try {
            val mapper = XmlMapper().apply {
                registerKotlinModule()
                configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            }
            block(EnergyPriceRepositoryImpl(EntsoeService(client, mapper, "test-token"), EleringService(client), NoOpPriceCache()))
        } finally {
            client.close()
        }
    }

    @Test
    fun `repository returns and caches only requested intervals across region aliases`() = runBlocking {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond(
                entsoePriceXml(points = (1..8).map { it to 100.0 }, start = "2026-08-21T23:30:00Z", end = "2026-08-22T01:30:00Z"),
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/xml")
            )
        }
        val entries = mutableMapOf<String, List<DomainEnergyPrice>>()
        val cache = object : PriceCache {
            override fun get(key: String) = entries[key]
            override fun put(key: String, prices: List<DomainEnergyPrice>, isComplete: Boolean) { entries[key] = prices }
            override fun clear() = entries.clear()
        }
        val client = HttpClient(engine)
        try {
            val mapper = XmlMapper().apply { registerKotlinModule(); configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false) }
            val repo = EnergyPriceRepositoryImpl(EntsoeService(client, mapper, "test-token"), EleringService(client), cache)
            val prices = repo.getPrices("DE", start, end, true).getOrThrow()
            assertEquals(4, prices.size)
            assertEquals(start, prices.first().startTime)
            assertEquals(start.plusSeconds(2700), prices.last().startTime)
            assertEquals(prices, repo.getPrices("DE_LU", start, end, true).getOrThrow())
            assertEquals(prices, repo.getPrices("10Y1001A1001A82H", start, end, true).getOrThrow())
            assertEquals(1, calls)
            assertEquals(listOf(prices), entries.values.toList())
        } finally { client.close() }
    }

    @Test
    fun `all four Elering countries select their own prices and preserve quarter hourly timestamps`() = runBlocking {
        val amounts = mapOf("EE" to 100.0, "FI" to 200.0, "LV" to -50.0, "LT" to 0.0)
        val data = amounts.entries.joinToString(",") { (code, price) ->
            val rows = (0..3).joinToString(",") { i ->
                """{"timestamp":${start.plusSeconds(i * 900L).epochSecond},"price":$price}"""
            }
            """"${code.lowercase()}":[$rows]"""
        }
        val engine = MockEngine { request ->
            assertEquals("dashboard.elering.ee", request.url.host)
            assertEquals(start.toString(), request.url.parameters["start"])
            assertEquals(end.toString(), request.url.parameters["end"])
            respond("""{"success":true,"data":{$data}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        withRepository(engine) { repo ->
            amounts.forEach { (code, amount) ->
                val prices = repo.getPrices(code, start, end, cacheResults = false).getOrThrow()
                assertEquals(4, prices.size)
                prices.forEachIndexed { i, price ->
                    assertEquals(start.plusSeconds(i * 900L), price.startTime)
                    assertEquals(amount / 1000, price.pricePerKWh, 1e-12)
                }
            }
        }
    }

    @Test
    fun `Elering server errors fall back to ENTSO-E with the correct area`() = runBlocking {
        val hosts = mutableListOf<String>()
        val engine = MockEngine { request ->
            hosts.add(request.url.host)
            if (request.url.host == "dashboard.elering.ee") {
                respond("Unavailable", HttpStatusCode.ServiceUnavailable)
            } else {
                assertEquals("A44", request.url.parameters["documentType"])
                assertEquals("10YFI-1--------U", request.url.parameters["in_Domain"])
                assertEquals("10YFI-1--------U", request.url.parameters["out_Domain"])
                assertEquals("202608220000", request.url.parameters["periodStart"])
                assertEquals("202608220100", request.url.parameters["periodEnd"])
                respond(entsoePriceXml(eic = "10YFI-1--------U"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/xml"))
            }
        }
        withRepository(engine) { repo ->
            assertEquals(4, repo.getPrices("FI", start, end, false).getOrThrow().size)
            assertEquals(listOf("dashboard.elering.ee", "web-api.tp.entsoe.eu"), hosts)
        }
    }

    @Test
    fun `empty Elering country series triggers fallback instead of successful empty prices`() = runBlocking {
        val hosts = mutableListOf<String>()
        val engine = MockEngine { request ->
            hosts.add(request.url.host)
            if (request.url.host == "dashboard.elering.ee") {
                respond("""{"success":true,"data":{"ee":[]}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respond(entsoePriceXml(eic = "10Y1001A1001A39I"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/xml"))
            }
        }
        withRepository(engine) { repo ->
            assertEquals(4, repo.getPrices("EE", start, end, false).getOrThrow().size)
            assertEquals(listOf("dashboard.elering.ee", "web-api.tp.entsoe.eu"), hosts)
        }
    }

    @Test
    fun `ENTSO-E no matching data acknowledgement remains successful empty prices`() = runBlocking {
        val engine = MockEngine {
            respond(
                "<Acknowledgement_MarketDocument><Reason><code>999</code><text>No matching data found</text></Reason></Acknowledgement_MarketDocument>",
                HttpStatusCode.BadRequest,
                headersOf(HttpHeaders.ContentType, "application/xml")
            )
        }
        withRepository(engine) { repo ->
            assertTrue(repo.getPrices("DE", start, end, false).getOrThrow().isEmpty())
        }
    }

    @Test
    fun `ENTSO-E throttling is a provider failure rather than missing prices`() = runBlocking {
        val engine = MockEngine {
            respond("Rate limit exceeded", HttpStatusCode.TooManyRequests)
        }
        withRepository(engine) { repo ->
            val error = repo.getPrices("DE", start, end, false).exceptionOrNull()
            assertTrue(error is ApiError.Server)
            assertEquals(429, error.code)
        }
    }
}
