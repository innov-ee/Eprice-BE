package ee.innov.eprice.data.entsoe

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import ee.innov.eprice.test.entsoePriceXml
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PriceParsingRegressionTest {
    private val mapper = XmlMapper().apply {
        registerKotlinModule()
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private fun parse(xml: String) = mapper.readValue(xml, PublicationMarketDocument::class.java).toDomainEnergyPrices()

    @Test
    fun `hourly half hourly and quarter hourly prices preserve timestamps and negative values`() {
        listOf("PT60M" to 3600L, "PT30M" to 1800L, "PT15M" to 900L).forEach { (resolution, step) ->
            val count = (3600 / step).toInt()
            val prices = parse(entsoePriceXml(resolution = resolution, points = (1..count).map { it to -50.0 }))
            assertEquals(count, prices.size, resolution)
            prices.forEachIndexed { index, price ->
                assertEquals(Instant.parse("2026-08-22T00:00:00Z").plusSeconds(index * step), price.startTime)
                assertEquals(-0.05, price.pricePerKWh, 1e-12)
                assertEquals(step, price.durationSeconds)
            }
        }
    }

    @Test
    fun `A03 repeats prices up to the next point and stops at the exclusive period end`() {
        val prices = parse(entsoePriceXml(curve = "A03", points = listOf(3 to 200.0, 1 to 100.0)))
        assertEquals(listOf(0.1, 0.1, 0.2, 0.2), prices.map { it.pricePerKWh })
        assertEquals(Instant.parse("2026-08-22T00:45:00Z"), prices.last().startTime)
        assertTrue(prices.none { it.startTime >= Instant.parse("2026-08-22T01:00:00Z") })
    }

    @Test
    fun `offset timestamps are converted to UTC`() {
        val prices = parse(entsoePriceXml(start = "2026-08-22T02:00:00+02:00", end = "2026-08-22T03:00:00+02:00"))
        assertEquals(Instant.parse("2026-08-22T00:00:00Z"), prices.first().startTime)
    }

    @Test
    fun `A01 missing positions remain missing rather than carrying forward a price`() {
        val prices = parse(entsoePriceXml(points = listOf(1 to 100.0, 3 to 200.0)))
        assertEquals(
            listOf(Instant.parse("2026-08-22T00:00:00Z"), Instant.parse("2026-08-22T00:30:00Z")),
            prices.map { it.startTime }
        )
        assertEquals(listOf(0.1, 0.2), prices.map { it.pricePerKWh })
    }

    @Test
    fun `unknown resolution is rejected instead of silently treated as hourly`() {
        assertTrue(runCatching { parse(entsoePriceXml(resolution = "invalid")) }.isFailure)
    }

    @Test
    fun `non EUR currency and unexpected units are rejected instead of mislabeled`() {
        listOf(
            entsoePriceXml().replace("<currency_Unit.name>EUR", "<currency_Unit.name>GBP"),
            entsoePriceXml().replace("<price_Measure_Unit.name>MWH", "<price_Measure_Unit.name>KWH"),
            entsoePriceXml().replace("<currency_Unit.name>EUR</currency_Unit.name>", "")
        ).forEach { xml -> assertTrue(runCatching { parse(xml) }.isFailure) }
    }

    @Test
    fun `A03 explicitly missing amounts stop price carry forward`() {
        val xml = entsoePriceXml(curve = "A03", points = listOf(1 to 100.0, 2 to 0.0, 4 to 200.0))
            .replace("<price.amount>0.0</price.amount>", "")
        val prices = parse(xml)
        assertEquals(listOf(0.1, 0.2), prices.map { it.pricePerKWh })
        assertEquals(listOf(Instant.parse("2026-08-22T00:00:00Z"), Instant.parse("2026-08-22T00:45:00Z")), prices.map { it.startTime })
    }

    @Test
    fun `invalid positions and conflicting duplicate prices are rejected`() {
        listOf(listOf(0 to 100.0), listOf(5 to 100.0), listOf(1 to 100.0, 1 to 200.0)).forEach { points ->
            assertTrue(runCatching { parse(entsoePriceXml(points = points)) }.isFailure)
        }
    }
}
