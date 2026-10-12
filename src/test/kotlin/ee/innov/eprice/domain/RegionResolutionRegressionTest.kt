package ee.innov.eprice.domain

import ee.innov.eprice.data.entsoe.toBiddingZone
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RegionResolutionRegressionTest {
    @Test
    fun `existing country aliases resolve to the same price area`() {
        listOf("DE", "LU", "de-lu", " DE_LU ").forEach { alias ->
            assertEquals("10Y1001A1001A82H", alias.normalizeCountryCode().toBiddingZone(), alias)
        }
        assertEquals("GB".toBiddingZone(), "UK".toBiddingZone())
        assertEquals("10Y1001A1001A73I", " it-nord ".normalizeCountryCode().toBiddingZone())
    }

    @Test
    fun `countries with multiple price zones require a zone selection`() {
        listOf("NO", "SE", "DK", "IT").forEach { assertNull(it.toBiddingZone(), it) }
    }

    @Test
    @Tag("known-bug")
    fun `Nordic separator aliases resolve to the canonical area and timezone`() {
        listOf(
            Triple("no-1", "10YNO-1--------2", "Europe/Oslo"),
            Triple("se_3", "10Y1001A1001A46L", "Europe/Stockholm"),
            Triple("dk-2", "10YDK-2--------M", "Europe/Copenhagen")
        ).forEach { (alias, eic, timezone) ->
            val code = alias.normalizeCountryCode()
            assertEquals(eic, code.toBiddingZone(), alias)
            assertEquals(ZoneId.of(timezone), CountryZoneProvider.getZoneId(code), alias)
        }
    }

    @Test
    @Tag("known-bug")
    fun `normalization preserves hyphens inside raw EIC identifiers`() {
        val eic = "10YFI-1--------U"
        val code = "  ${eic.lowercase()}  ".normalizeCountryCode()
        assertEquals(eic, code)
        assertEquals(eic, code.toBiddingZone())
    }

    @Test
    @Tag("known-bug")
    fun `known raw EIC identifiers retain the area timezone`() {
        assertEquals(
            ZoneId.of("Europe/Berlin"),
            CountryZoneProvider.getZoneId("10Y1001A1001A82H")
        )
    }
}
