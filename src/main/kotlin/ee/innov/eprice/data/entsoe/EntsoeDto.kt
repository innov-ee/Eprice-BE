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
    }
}

/**
 * Maps a 2-letter country code to its corresponding ENTSO-E Bidding Zone.
 * This mapping must be expanded to support new countries.
 */
internal fun String.toBiddingZone(): String? = when (this.uppercase()) {
    "EE" -> "10Y1001A1001A39I"
    "FI" -> "10YFI-1--------U"
    "LT" -> "10YLT-1001A0008Q"
    "LV" -> "10YLV-1001A00074"
    else -> null
}