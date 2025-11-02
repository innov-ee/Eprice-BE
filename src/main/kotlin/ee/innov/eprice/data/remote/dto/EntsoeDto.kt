package ee.innov.eprice.data.remote.dto

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

data class TimeInterval(val start: String)
data class Point(
    val position: Int,
    @JsonProperty("price.amount")
    val priceAmount: Double
)

fun PublicationMarketDocument.toDomainEnergyPrices(): List<DomainEnergyPrice> {
    return this.timeSeries.flatMap { timeSeries ->
        timeSeries.period.flatMap { period ->
            val resolutionMinutes = period.resolution.removePrefix("PT")
                .removeSuffix("M").toLongOrNull() ?: 60L
            val periodStartInstant = Instant.from(
                DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(period.timeInterval.start)
            )

            period.point.map { point ->
                val pricePerKWh = point.priceAmount / 1000.0
                val intervalStart = periodStartInstant.plus(
                    (point.position - 1) * resolutionMinutes,
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