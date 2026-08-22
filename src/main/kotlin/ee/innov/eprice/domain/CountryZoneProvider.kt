package ee.innov.eprice.domain

import java.time.ZoneId

object CountryZoneProvider {
    private val defaultZoneId = ZoneId.of("Europe/Tallinn")

    private val countryZoneMap: Map<String, ZoneId> = mapOf(
        "EE" to ZoneId.of("Europe/Tallinn"),
        "FI" to ZoneId.of("Europe/Helsinki"),
        "LV" to ZoneId.of("Europe/Riga"),
        "LT" to ZoneId.of("Europe/Vilnius")
    )

    fun getZoneId(countryCode: String): ZoneId =
        countryZoneMap[countryCode.uppercase()] ?: defaultZoneId
}
