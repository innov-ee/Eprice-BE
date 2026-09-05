package ee.innov.eprice.data.entsoe

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class EntsoeDtoTest {

    private val xmlMapper = XmlMapper().apply {
        registerKotlinModule()
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    @Test
    fun `toDomainEnergyPrices fills gaps in stepped A03 curve points`() {
        val xml = """
            <Publication_MarketDocument xmlns="urn:iec62325.351:tc57wg16:451-3:publicationdocument:7:3">
                <TimeSeries>
                    <curveType>A03</curveType>
                    <Period>
                        <timeInterval>
                            <start>2026-08-22T22:00Z</start>
                            <end>2026-08-23T22:00Z</end>
                        </timeInterval>
                        <resolution>PT15M</resolution>
                        <Point>
                            <position>1</position>
                            <price.amount>100.0</price.amount>
                        </Point>
                        <Point>
                            <position>3</position>
                            <price.amount>200.0</price.amount>
                        </Point>
                        <Point>
                            <position>6</position>
                            <price.amount>300.0</price.amount>
                        </Point>
                    </Period>
                </TimeSeries>
            </Publication_MarketDocument>
        """.trimIndent()

        val doc = xmlMapper.readValue(xml, PublicationMarketDocument::class.java)
        val prices = doc.toDomainEnergyPrices()

        // 24 hours * 4 = 96 points
        assertEquals(96, prices.size)

        // Point 1 (pos 1): 100.0 / 1000 = 0.1
        assertEquals(Instant.parse("2026-08-22T22:00:00Z"), prices[0].startTime)
        assertEquals(0.1, prices[0].pricePerKWh)

        // Gap pos 2 takes price from pos 1
        assertEquals(Instant.parse("2026-08-22T22:15:00Z"), prices[1].startTime)
        assertEquals(0.1, prices[1].pricePerKWh)

        // Point 3 (pos 3): 200.0 / 1000 = 0.2
        assertEquals(Instant.parse("2026-08-22T22:30:00Z"), prices[2].startTime)
        assertEquals(0.2, prices[2].pricePerKWh)

        // Gap pos 4 and pos 5 take price from pos 3
        assertEquals(Instant.parse("2026-08-22T22:45:00Z"), prices[3].startTime)
        assertEquals(0.2, prices[3].pricePerKWh)
        assertEquals(Instant.parse("2026-08-22T23:00:00Z"), prices[4].startTime)
        assertEquals(0.2, prices[4].pricePerKWh)

        // Point 6 (pos 6): 300.0 / 1000 = 0.3
        assertEquals(Instant.parse("2026-08-22T23:15:00Z"), prices[5].startTime)
        assertEquals(0.3, prices[5].pricePerKWh)

        // Remaining points (pos 7..96) take price from pos 6
        for (i in 5 until 96) {
            assertEquals(0.3, prices[i].pricePerKWh)
        }
        assertEquals(Instant.parse("2026-08-23T21:45:00Z"), prices[95].startTime)
    }

    @Test
    fun `toBiddingZone maps countries, subzones, and direct EIC codes correctly`() {
        assertEquals("10Y1001A1001A39I", "EE".toBiddingZone())
        assertEquals("10YFI-1--------U", "FI".toBiddingZone())
        assertEquals("10Y1001A1001A82H", "DE".toBiddingZone())
        assertEquals("10Y1001A1001A82H", "DE-LU".toBiddingZone())
        assertEquals("10Y1001A1001A82H", "DE_LU".toBiddingZone())
        assertEquals("10YFR-RTE------C", "FR".toBiddingZone())
        assertEquals("10Y1001A1001A46L", "SE3".toBiddingZone())
        assertEquals("10YNO-1--------2", "NO1".toBiddingZone())
        assertEquals("10Y1001A1001A73I", "IT-NORD".toBiddingZone())
        assertEquals("10Y1001A1001A73I", "IT_NORD".toBiddingZone())
        assertEquals("10Y1001A1001A75E", "IT_SICI".toBiddingZone())
        assertEquals("10Y1001A1001A70O", "IT_CNOR".toBiddingZone())

        // Direct 16-character EIC code pass-through
        val directEic = "10Y1001A1001A82H"
        assertEquals(directEic, directEic.toBiddingZone())

        // Unknown code returns null
        assertEquals(null, "INVALID_CODE".toBiddingZone())
    }

    @Test
    fun `toDomainEnergyPrices parses multiple time series correctly`() {
        val xml = """
            <Publication_MarketDocument xmlns="urn:iec62325.351:tc57wg16:451-3:publicationdocument:7:3">
                <TimeSeries>
                    <Period>
                        <timeInterval>
                            <start>2026-08-20T22:00Z</start>
                            <end>2026-08-21T22:00Z</end>
                        </timeInterval>
                        <resolution>PT60M</resolution>
                        <Point>
                            <position>1</position>
                            <price.amount>50.0</price.amount>
                        </Point>
                    </Period>
                </TimeSeries>
                <TimeSeries>
                    <Period>
                        <timeInterval>
                            <start>2026-08-21T22:00Z</start>
                            <end>2026-08-22T22:00Z</end>
                        </timeInterval>
                        <resolution>PT60M</resolution>
                        <Point>
                            <position>1</position>
                            <price.amount>75.0</price.amount>
                        </Point>
                    </Period>
                </TimeSeries>
            </Publication_MarketDocument>
        """.trimIndent()

        val doc = xmlMapper.readValue(xml, PublicationMarketDocument::class.java)
        val prices = doc.toDomainEnergyPrices()

        // 24 + 24 = 48 points
        assertEquals(48, prices.size)
        assertEquals(0.05, prices[0].pricePerKWh)
        assertEquals(0.075, prices[24].pricePerKWh)
    }

    @Test
    fun `toDomainEnergyPrices deduplicates overlapping time series for same interval`() {
        val xml = """
            <Publication_MarketDocument xmlns="urn:iec62325.351:tc57wg16:451-3:publicationdocument:7:3">
                <TimeSeries>
                    <mRID>1</mRID>
                    <Period>
                        <timeInterval>
                            <start>2026-08-20T22:00Z</start>
                            <end>2026-08-21T22:00Z</end>
                        </timeInterval>
                        <resolution>PT60M</resolution>
                        <Point>
                            <position>1</position>
                            <price.amount>50.0</price.amount>
                        </Point>
                    </Period>
                </TimeSeries>
                <TimeSeries>
                    <mRID>2</mRID>
                    <Period>
                        <timeInterval>
                            <start>2026-08-20T22:00Z</start>
                            <end>2026-08-21T22:00Z</end>
                        </timeInterval>
                        <resolution>PT60M</resolution>
                        <Point>
                            <position>1</position>
                            <price.amount>50.0</price.amount>
                        </Point>
                    </Period>
                </TimeSeries>
            </Publication_MarketDocument>
        """.trimIndent()

        val doc = xmlMapper.readValue(xml, PublicationMarketDocument::class.java)
        val prices = doc.toDomainEnergyPrices()

        // Should deduplicate and return 24 points, not 48
        assertEquals(24, prices.size)
    }
}
