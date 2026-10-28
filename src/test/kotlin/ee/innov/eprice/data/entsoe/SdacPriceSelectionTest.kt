package ee.innov.eprice.data.entsoe

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import ee.innov.eprice.domain.BiddingZones
import ee.innov.eprice.domain.model.InvalidPriceDataException
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SdacPriceSelectionTest {
    private val mapper = XmlMapper().apply {
        registerKotlinModule()
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
    private val start = Instant.parse("2026-10-20T00:00:00Z")
    private val end = start.plusSeconds(3600)

    private fun series(sequence: Int?, price: Double, contract: String? = "A01") = """
        <TimeSeries>
            ${sequence?.let { "<classificationSequence_AttributeInstanceComponent.position>$it</classificationSequence_AttributeInstanceComponent.position>" } ?: ""}
            ${contract?.let { "<contract_MarketAgreement.type>$it</contract_MarketAgreement.type>" } ?: ""}
            <currency_Unit.name>EUR</currency_Unit.name>
            <price_Measure_Unit.name>MWH</price_Measure_Unit.name>
            <curveType>A03</curveType>
            <Period>
                <timeInterval><start>$start</start><end>$end</end></timeInterval>
                <resolution>PT15M</resolution>
                <Point><position>1</position><price.amount>$price</price.amount></Point>
            </Period>
        </TimeSeries>
    """.trimIndent()

    private fun document(vararg series: String) = """
        <Publication_MarketDocument xmlns="urn:iec62325.351:tc57wg16:451-3:publicationdocument:7:3">
            ${series.joinToString("\n")}
        </Publication_MarketDocument>
    """.trimIndent()

    private fun parse(xml: String) = mapper.readValue(xml, PublicationMarketDocument::class.java).toDomainEnergyPrices()

    @Test
    fun `every ENTSO-E region requests daily prices and selects SDAC without requiring optional sequence tags`() = runBlocking {
        var requests = 0
        val engine = MockEngine { request ->
            val region = BiddingZones.all[requests++]
            assertEquals("web-api.tp.entsoe.eu", request.url.host)
            assertEquals("A44", request.url.parameters["documentType"])
            assertEquals("A01", request.url.parameters["contract_MarketAgreement.type"])
            assertNull(request.url.parameters["classificationSequence_AttributeInstanceComponent.position"])
            assertEquals(region.eic, request.url.parameters["in_Domain"])
            assertEquals(region.eic, request.url.parameters["out_Domain"])
            val sequence = if (region.id in setOf("DE_LU", "AT")) 1 else null
            respond(document(series(2, 200.0), series(sequence, 100.0)), HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/xml"))
        }
        HttpClient(engine).use { client ->
            val service = EntsoeService(client, mapper, "test-token")
            BiddingZones.all.forEach { region ->
                assertEquals(List(4) { 0.1 }, service.fetchPrices(region.eic, start, end).map { it.pricePerKWh }, region.id)
            }
        }
        assertEquals(BiddingZones.all.size, requests)
    }

    @Test
    fun `EXAA and intraday prices are never substituted for missing SDAC prices`() {
        listOf(series(2, 200.0), series(1, 200.0, "A07"), series(null, 200.0, "A07")).forEach {
            assertTrue(parse(document(it)).isEmpty())
        }
    }

    @Test
    fun `legacy daily responses without optional auction metadata remain supported`() {
        for (contract in listOf(null, "A01")) {
            assertEquals(List(4) { 0.1 }, parse(document(series(null, 100.0, contract))).map { it.pricePerKWh })
        }
    }

    @Test
    fun `conflicting SDAC prices still fail validation`() {
        assertFailsWith<InvalidPriceDataException> {
            parse(document(series(1, 100.0), series(1, 200.0)))
        }
    }
}
