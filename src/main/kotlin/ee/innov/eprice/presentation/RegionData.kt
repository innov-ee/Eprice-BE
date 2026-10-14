package ee.innov.eprice.presentation

import ee.innov.eprice.domain.BiddingZones
import kotlinx.serialization.Serializable

/** Configured areas; publication availability depends on the provider and date. */
@Serializable
data class RegionData(val code: String, val timezone: String, val providers: List<String>)

fun configuredRegions(): List<RegionData> = BiddingZones.all.map { zone ->
    RegionData(zone.id, zone.zoneId.id, if (zone.eleringKey != null) listOf("elering", "entsoe") else listOf("entsoe"))
}
