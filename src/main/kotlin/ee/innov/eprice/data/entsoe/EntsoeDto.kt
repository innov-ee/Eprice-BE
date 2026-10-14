package ee.innov.eprice.data.entsoe

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import ee.innov.eprice.domain.model.DomainEnergyPrice
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.Duration
import ee.innov.eprice.domain.model.InvalidPriceDataException

// --- Data Models for XML Parsing ---

data class PublicationMarketDocument(
    @JsonProperty("TimeSeries")
    @JacksonXmlElementWrapper(useWrapping = false)
    val timeSeries: List<TimeSeries> = emptyList()
)

data class TimeSeries(
    val mRID: String? = null,
    val curveType: String? = null,
    @JsonProperty("currency_Unit.name")
    val currency: String? = null,
    @JsonProperty("price_Measure_Unit.name")
    val priceUnit: String? = null,
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
    val priceAmount: Double?
)

private fun checkPriceData(valid: Boolean, message: String) {
    if (!valid) throw InvalidPriceDataException(message)
}

private fun parseResolutionSeconds(resolution: String): Long {
    val duration = try { Duration.parse(resolution.trim().uppercase()) } catch (_: Exception) {
        throw InvalidPriceDataException("Invalid price resolution: $resolution")
    }
    checkPriceData(duration.seconds > 0 && duration.nano == 0 && duration.seconds % 60 == 0L,
        "Price resolution must be a positive whole number of minutes: $resolution")
    return duration.seconds
}

fun PublicationMarketDocument.toDomainEnergyPrices(): List<DomainEnergyPrice> {
    val prices = timeSeries.flatMap { series ->
        checkPriceData(series.currency == "EUR", "Expected EUR prices, received ${series.currency ?: "no currency"}")
        checkPriceData(series.priceUnit == "MWH", "Expected MWH price unit, received ${series.priceUnit ?: "no unit"}")
        val curve = series.curveType ?: "A01"
        checkPriceData(curve == "A01" || curve == "A03", "Unsupported price curve: $curve")
        series.period.flatMap periodLoop@ { period ->
            val step = parseResolutionSeconds(period.resolution)
            val start = Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(period.timeInterval.start))
            val points = period.point.sortedBy { it.position }
            if (points.isEmpty()) return@periodLoop emptyList<DomainEnergyPrice>()
            val end = period.timeInterval.end?.let { Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(it)) }
                ?: start.plusSeconds(points.last().position * step)
            val seconds = Duration.between(start, end).seconds
            checkPriceData(seconds > 0 && seconds % step == 0L, "Price period does not match its resolution")
            val count = seconds / step
            checkPriceData(points.all { it.position in 1..count }, "Price point lies outside its period")
            checkPriceData(points.all { it.priceAmount == null || it.priceAmount.isFinite() }, "Price must be finite")
            val pointMap = points.groupBy { it.position }.mapValues { (_, duplicates) ->
                checkPriceData(duplicates.map { it.priceAmount }.distinct().size == 1, "Conflicting prices for the same position")
                duplicates.first()
            }
            if (curve == "A01") {
                pointMap.values.mapNotNull { point ->
                    point.priceAmount?.let { DomainEnergyPrice(start.plusSeconds((point.position - 1) * step), it / 1000, step) }
                }
            } else {
                var amount: Double? = null
                (1..count).mapNotNull { position ->
                    pointMap[position.toInt()]?.let { amount = it.priceAmount }
                    amount?.let { DomainEnergyPrice(start.plusSeconds((position - 1) * step), it / 1000, step) }
                }
            }
        }
    }
    return prices.groupBy { it.startTime }.map { (_, duplicates) ->
        checkPriceData(duplicates.distinct().size == 1, "Conflicting price series for the same UTC interval")
        duplicates.first()
    }.sortedBy { it.startTime }
}

/** Resolves known area identifiers through the shared timezone/EIC registry. */
internal fun String.toBiddingZone(): String? =
    runCatching { ee.innov.eprice.domain.BiddingZones.resolve(this).eic }.getOrNull()
