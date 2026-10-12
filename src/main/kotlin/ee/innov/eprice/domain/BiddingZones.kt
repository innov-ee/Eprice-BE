package ee.innov.eprice.domain

import java.time.ZoneId

/** One price area, with the timezone used for this API's local dates. */
data class BiddingZone(
    val id: String,
    val eic: String,
    val zoneId: ZoneId,
    val eleringKey: String? = null
)

object BiddingZones {
    val all: List<BiddingZone> = listOf(
        BiddingZone("EE", "10Y1001A1001A39I", ZoneId.of("Europe/Tallinn"), eleringKey = "EE"),
        BiddingZone("FI", "10YFI-1--------U", ZoneId.of("Europe/Helsinki"), eleringKey = "FI"),
        BiddingZone("LT", "10YLT-1001A0008Q", ZoneId.of("Europe/Vilnius"), eleringKey = "LT"),
        BiddingZone("LV", "10YLV-1001A00074", ZoneId.of("Europe/Riga"), eleringKey = "LV"),
        BiddingZone("SE1", "10Y1001A1001A44P", ZoneId.of("Europe/Stockholm")),
        BiddingZone("SE2", "10Y1001A1001A45N", ZoneId.of("Europe/Stockholm")),
        BiddingZone("SE3", "10Y1001A1001A46L", ZoneId.of("Europe/Stockholm")),
        BiddingZone("SE4", "10Y1001A1001A47J", ZoneId.of("Europe/Stockholm")),
        BiddingZone("NO1", "10YNO-1--------2", ZoneId.of("Europe/Oslo")),
        BiddingZone("NO2", "10YNO-2--------T", ZoneId.of("Europe/Oslo")),
        BiddingZone("NO3", "10YNO-3--------J", ZoneId.of("Europe/Oslo")),
        BiddingZone("NO4", "10YNO-4--------9", ZoneId.of("Europe/Oslo")),
        BiddingZone("NO5", "10Y1001A1001A48H", ZoneId.of("Europe/Oslo")),
        BiddingZone("DK1", "10YDK-1--------W", ZoneId.of("Europe/Copenhagen")),
        BiddingZone("DK2", "10YDK-2--------M", ZoneId.of("Europe/Copenhagen")),
        BiddingZone("DE_LU", "10Y1001A1001A82H", ZoneId.of("Europe/Berlin")),
        BiddingZone("AT", "10YAT-APG------L", ZoneId.of("Europe/Vienna")),
        BiddingZone("BE", "10YBE----------2", ZoneId.of("Europe/Brussels")),
        BiddingZone("FR", "10YFR-RTE------C", ZoneId.of("Europe/Paris")),
        BiddingZone("NL", "10YNL----------L", ZoneId.of("Europe/Amsterdam")),
        BiddingZone("CH", "10YCH-SWISSGRIDZ", ZoneId.of("Europe/Zurich")),
        BiddingZone("PL", "10YPL-AREA-----S", ZoneId.of("Europe/Warsaw")),
        BiddingZone("CZ", "10YCZ-CEPS-----N", ZoneId.of("Europe/Prague")),
        BiddingZone("SK", "10YSK-SEPS-----K", ZoneId.of("Europe/Bratislava")),
        BiddingZone("HU", "10YHU-MAVIR----U", ZoneId.of("Europe/Budapest")),
        BiddingZone("SI", "10YSI-ELES-----O", ZoneId.of("Europe/Ljubljana")),
        BiddingZone("HR", "10YHR-HEP------M", ZoneId.of("Europe/Zagreb")),
        BiddingZone("RO", "10YRO-TEL------P", ZoneId.of("Europe/Bucharest")),
        BiddingZone("BG", "10YCA-BULGARIA-R", ZoneId.of("Europe/Sofia")),
        BiddingZone("GR", "10YGR-HTSO-----Y", ZoneId.of("Europe/Athens")),
        BiddingZone("ES", "10YES-REE------0", ZoneId.of("Europe/Madrid")),
        BiddingZone("PT", "10YPT-REN------W", ZoneId.of("Europe/Lisbon")),
        BiddingZone("GB", "10YGB----------A", ZoneId.of("Europe/London")),
        BiddingZone("IE", "10Y1001A1001A59C", ZoneId.of("Europe/Dublin")),
        BiddingZone("IT_NORD", "10Y1001A1001A73I", ZoneId.of("Europe/Rome")),
        BiddingZone("IT_CNOR", "10Y1001A1001A70O", ZoneId.of("Europe/Rome")),
        BiddingZone("IT_CSUD", "10Y1001A1001A71M", ZoneId.of("Europe/Rome")),
        BiddingZone("IT_SUD", "10Y1001A1001A788", ZoneId.of("Europe/Rome")),
        BiddingZone("IT_SICI", "10Y1001A1001A75E", ZoneId.of("Europe/Rome")),
        BiddingZone("IT_SARD", "10Y1001A1001A74G", ZoneId.of("Europe/Rome")),
        BiddingZone("IT_CALA", "10Y1001C--00096J", ZoneId.of("Europe/Rome")),
        BiddingZone("RS", "10YCS-SERBIATSOV", ZoneId.of("Europe/Belgrade")),
        BiddingZone("ME", "10YCS-CG-TSO---S", ZoneId.of("Europe/Podgorica")),
        BiddingZone("MK", "10YMK-MEPSO----8", ZoneId.of("Europe/Skopje")),
        BiddingZone("AL", "10YAL-KESH-----5", ZoneId.of("Europe/Tirane")),
        BiddingZone("BA", "10YBA-JPCC-----D", ZoneId.of("Europe/Sarajevo")),
        BiddingZone("XK", "10Y1001C--00100H", ZoneId.of("Europe/Belgrade")),
        BiddingZone("UA", "10Y1001C--00003F", ZoneId.of("Europe/Kyiv")),
        BiddingZone("MD", "10Y1001A1001A990", ZoneId.of("Europe/Chisinau")),
    )
    private val byId = all.associateBy { it.id }
    private val byEic = all.associateBy { it.eic }
    private val aliases = mapOf("DE" to "DE_LU", "LU" to "DE_LU", "UK" to "GB")

    fun resolve(code: String): BiddingZone {
        val normalized = code.normalizeCountryCode()
        return byEic[normalized] ?: byId[aliases[normalized] ?: normalized]
            ?: throw IllegalArgumentException("Unsupported country or bidding zone code: $code")
    }
}
