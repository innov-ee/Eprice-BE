package ee.innov.eprice.data.entsoe

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import ee.innov.eprice.domain.model.DomainEnergyPrice
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// --- Data Models for XML Parsing ---

data class PublicationMarketDocument(
    @JsonProperty("TimeSeries")
    @JacksonXmlElementWrapper(useWrapping = false)
    val timeSeries: List<TimeSeries> = emptyList()
)

data class TimeSeries(
    val mRID: String? = null,
    val curveType: String? = null,
    @JsonProperty("Period")
    @JacksonXmlElementWrapper(useWrapping = false)
    val period: List<Period> = emptyList()
)

data class Period(
    val timeInterval: TimeInterval,
    val resolution: String,
    @JsonProperty("Point")
    @JacksonXmlElementWrapper(useWrapping = false)
    val point: List<Point> = emptyList()
)

data class TimeInterval(
    val start: String,
    val end: String? = null
)
data class Point(
    val position: Int,
    @JsonProperty("price.amount")
    val priceAmount: Double
)

private fun parseResolutionMinutes(resolution: String): Long {
    val trimmed = resolution.trim().uppercase()
    return when {
        trimmed == "PT1H" || trimmed == "PT60M" -> 60L
        trimmed.startsWith("PT") && trimmed.endsWith("M") ->
            trimmed.removePrefix("PT").removeSuffix("M").toLongOrNull() ?: 60L
        trimmed.startsWith("PT") && trimmed.endsWith("H") ->
            (trimmed.removePrefix("PT").removeSuffix("H").toLongOrNull() ?: 1L) * 60L
        else -> 60L
    }
}

fun PublicationMarketDocument.toDomainEnergyPrices(): List<DomainEnergyPrice> {
    return this.timeSeries.flatMap { timeSeries ->
        timeSeries.period.flatMap { period ->
            val resolutionMinutes = parseResolutionMinutes(period.resolution)
            val periodStartInstant = Instant.from(
                DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(period.timeInterval.start)
            )

            val sortedPoints = period.point.sortedBy { it.position }
            if (sortedPoints.isEmpty()) {
                emptyList()
            } else {
                val totalPositions = if (!period.timeInterval.end.isNullOrBlank()) {
                    try {
                        val periodEndInstant = Instant.from(
                            DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(period.timeInterval.end)
                        )
                        val durationMinutes = java.time.Duration.between(periodStartInstant, periodEndInstant).toMinutes()
                        (durationMinutes / resolutionMinutes).toInt()
                    } catch (_: Exception) {
                        sortedPoints.last().position
                    }
                } else {
                    sortedPoints.last().position
                }

                val endPosition = maxOf(totalPositions, sortedPoints.last().position)
                val pointMap = sortedPoints.associateBy { it.position }

                var currentPriceAmount = sortedPoints.first().priceAmount
                (1..endPosition).mapNotNull { pos ->
                    pointMap[pos]?.let { point ->
                        currentPriceAmount = point.priceAmount
                    }

                    if (pos < sortedPoints.first().position) {
                        null
                    } else {
                        val pricePerKWh = currentPriceAmount / 1000.0
                        val intervalStart = periodStartInstant.plus(
                            (pos - 1) * resolutionMinutes,
                            ChronoUnit.MINUTES
                        )

                        DomainEnergyPrice(
                            startTime = intervalStart,
                            pricePerKWh = pricePerKWh
                        )
                    }
                }
            }
        }
    }.distinctBy { it.startTime }.sortedBy { it.startTime }
}

private val biddingZoneMap: Map<String, String> = mapOf(
    // Baltics
    "EE" to "10Y1001A1001A39I",
    "FI" to "10YFI-1--------U",
    "LT" to "10YLT-1001A0008Q",
    "LV" to "10YLV-1001A00074",

    // Nordics (Bidding Zones)
    "SE1" to "10Y1001A1001A44P",
    "SE2" to "10Y1001A1001A45N",
    "SE3" to "10Y1001A1001A46L",
    "SE4" to "10Y1001A1001A47J",
    "NO1" to "10YNO-1--------2",
    "NO2" to "10YNO-2--------T",
    "NO3" to "10YNO-3--------J",
    "NO4" to "10YNO-4--------9",
    "NO5" to "10Y1001A1001A48H",
    "DK1" to "10YDK-1--------W",
    "DK2" to "10YDK-2--------M",

    // Western & Central Europe
    "DE" to "10Y1001A1001A82H",
    "DE-LU" to "10Y1001A1001A82H",
    "DE_LU" to "10Y1001A1001A82H",
    "AT" to "10YAT-APG------L",
    "BE" to "10YBE----------2",
    "FR" to "10YFR-RTE------C",
    "NL" to "10YNL----------L",
    "CH" to "10YCH-SWISSGRIDZ",
    "PL" to "10YPL-AREA-----S",
    "CZ" to "10YCZ-CEPS-----N",
    "SK" to "10YSK-SEPS-----K",
    "HU" to "10YHU-MAVIR----U",
    "SI" to "10YSI-ELES-----O",
    "HR" to "10YHR-HEP------M",
    "RO" to "10YRO-TEL------6",
    "BG" to "10YCA-BULGARIA-R",
    "GR" to "10YGR-HTSO-----1",
    "LU" to "10Y1001A1001A82H",

    // Iberia
    "ES" to "10YES-REE------0",
    "PT" to "10YPT-REN------W",

    // British Isles
    "GB" to "10YGB----------A",
    "UK" to "10YGB----------A",
    "IE" to "10Y1001A1001A59C",

    // Italy (Bidding Zones)
    "IT-NORD" to "10Y1001A1001A73P",
    "IT_NORD" to "10Y1001A1001A73P",
    "IT-CNOR" to "10Y1001A1001A70V",
    "IT_CNOR" to "10Y1001A1001A70V",
    "IT-CSUD" to "10Y1001A1001A71T",
    "IT_CSUD" to "10Y1001A1001A71T",
    "IT-SUD" to "10Y1001A1001A78F",
    "IT_SUD" to "10Y1001A1001A78F",
    "IT-SICI" to "10Y1001A1001A75L",
    "IT_SICI" to "10Y1001A1001A75L",
    "IT-SARD" to "10Y1001A1001A74N",
    "IT_SARD" to "10Y1001A1001A74N",
    "IT-CALA" to "10Y1001C--00096J",
    "IT_CALA" to "10Y1001C--00096J",

    // Southeastern Europe & Non-EU
    "RS" to "10YCS-SERBIATSOV",
    "ME" to "10YCS-CG-TSO---S",
    "MK" to "10YMK-MEPSO----8",
    "AL" to "10YAL-KESH-----5",
    "BA" to "10YBA-JPCC-----D",
    "XK" to "10Y1001C--00100H",
    "UA" to "10Y1001C--00003F",
    "MD" to "10Y1001A1001A990"
)

/**
 * Maps a 2-letter country code or bidding zone name to its corresponding ENTSO-E Bidding Zone EIC code.
 * If the input is already a valid 16-character EIC code, it is returned directly.
 */
internal fun String.toBiddingZone(): String? {
    val normalized = this.trim().uppercase()
    if (normalized.length == 16 && (normalized.startsWith("10Y") || normalized.startsWith("10X") || normalized.startsWith("10Z"))) {
        return normalized
    }
    return biddingZoneMap[normalized.replace('_', '-')] ?: biddingZoneMap[normalized]
}