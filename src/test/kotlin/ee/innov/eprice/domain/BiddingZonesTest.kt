package ee.innov.eprice.domain

import org.junit.jupiter.api.Test
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BiddingZonesTest {
    @Test
    fun `canonical identifiers resolve to their provider code and local timezone`() {
        listOf(
            Triple("EE", "10Y1001A1001A39I", "Europe/Tallinn"),
            Triple("FI", "10YFI-1--------U", "Europe/Helsinki"),
            Triple("LV", "10YLV-1001A00074", "Europe/Riga"),
            Triple("LT", "10YLT-1001A0008Q", "Europe/Vilnius"),
            Triple("DE_LU", "10Y1001A1001A82H", "Europe/Berlin"),
            Triple("GB", "10YGB----------A", "Europe/London"),
            Triple("NO1", "10YNO-1--------2", "Europe/Oslo"),
            Triple("SE3", "10Y1001A1001A46L", "Europe/Stockholm"),
            Triple("DK2", "10YDK-2--------M", "Europe/Copenhagen"),
            Triple("IT_NORD", "10Y1001A1001A73I", "Europe/Rome")
        ).forEach { (code, eic, timezone) ->
            val zone = BiddingZones.resolve(code)
            assertEquals(code, zone.id)
            assertEquals(eic, zone.eic)
            assertEquals(ZoneId.of(timezone), zone.zoneId)
        }
    }

    @Test
    fun `alternate spellings aliases and provider identifiers are rejected`() {
        listOf("ee", "Ee", " EE ", "DE", "LU", "UK", "DE-LU", "NO-1", "se_3", "IT-NORD", "10YFI-1--------U").forEach { code ->
            assertFailsWith<IllegalArgumentException>(code) { BiddingZones.resolve(code) }
        }
    }

    @Test
    fun `unknown and ambiguous countries are rejected`() {
        listOf("UNKNOWN", "NO", "SE", "DK", "IT").forEach { code ->
            assertFailsWith<IllegalArgumentException>(code) { BiddingZones.resolve(code) }
        }
    }
}
