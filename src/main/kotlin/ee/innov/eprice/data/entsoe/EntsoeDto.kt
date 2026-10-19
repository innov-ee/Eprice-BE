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
    return timeSeries.flatMap { series ->
        series.validateCurrencyAndUnit()
        val curve = series.validatedCurveType()
        series.period.flatMap { it.toDomainEnergyPrices(curve) }
    }.mergeIdenticalIntervals().sortedBy { it.startTime }
}

private fun TimeSeries.validateCurrencyAndUnit() {
    checkPriceData(currency == "EUR", "Expected EUR prices, received ${currency ?: "no currency"}")
    checkPriceData(priceUnit == "MWH", "Expected MWH price unit, received ${priceUnit ?: "no unit"}")
}

private fun TimeSeries.validatedCurveType(): String {
    val curve = curveType ?: "A01"
    checkPriceData(curve == "A01" || curve == "A03", "Unsupported price curve: $curve")
    return curve
}

private fun Period.toDomainEnergyPrices(curve: String): List<DomainEnergyPrice> {
    val step = parseResolutionSeconds(resolution)
    val start = Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(timeInterval.start))
    val points = point.sortedBy { it.position }
    if (points.isEmpty()) return emptyList()

    val end = timeInterval.end?.let { Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(it)) }
        ?: start.plusSeconds(points.last().position * step)
    val count = validatedIntervalCount(start, end, step)
    points.validatePositions(count)
    points.validateFinitePrices()
    val pointMap = points.mergeIdenticalPositions()

    return if (curve == "A01") {
        pointMap.values.mapExplicitPrices(start, step)
    } else {
        pointMap.expandSteppedPrices(start, step, count)
    }
}

private fun validatedIntervalCount(start: Instant, end: Instant, step: Long): Long {
    val seconds = Duration.between(start, end).seconds
    checkPriceData(seconds > 0 && seconds % step == 0L, "Price period does not match its resolution")
    return seconds / step
}

private fun List<Point>.validatePositions(count: Long) {
    checkPriceData(all { it.position in 1..count }, "Price point lies outside its period")
}

private fun List<Point>.validateFinitePrices() {
    checkPriceData(all { it.priceAmount == null || it.priceAmount.isFinite() }, "Price must be finite")
}

private fun List<Point>.mergeIdenticalPositions(): Map<Int, Point> =
    groupBy { it.position }.mapValues { (_, duplicates) ->
        checkPriceData(duplicates.map { it.priceAmount }.distinct().size == 1, "Conflicting prices for the same position")
        duplicates.first()
    }

/** A01 supplies individual prices; missing positions remain gaps. */
private fun Collection<Point>.mapExplicitPrices(start: Instant, step: Long): List<DomainEnergyPrice> =
    mapNotNull { point ->
        point.priceAmount?.let { DomainEnergyPrice(start.plusSeconds((point.position - 1) * step), it / 1000, step) }
    }

/** A03 prices carry forward until the next point; a null price clears the current value. */
private fun Map<Int, Point>.expandSteppedPrices(start: Instant, step: Long, count: Long): List<DomainEnergyPrice> {
    var amount: Double? = null
    return (1..count).mapNotNull { position ->
        this[position.toInt()]?.let { amount = it.priceAmount }
        amount?.let { DomainEnergyPrice(start.plusSeconds((position - 1) * step), it / 1000, step) }
    }
}

private fun List<DomainEnergyPrice>.mergeIdenticalIntervals(): List<DomainEnergyPrice> =
    groupBy { it.startTime }.map { (_, duplicates) ->
        checkPriceData(duplicates.distinct().size == 1, "Conflicting price series for the same UTC interval")
        duplicates.first()
    }

/** Resolves known area identifiers through the shared timezone/EIC registry. */
internal fun String.toBiddingZone(): String? =
    runCatching { ee.innov.eprice.domain.BiddingZones.resolve(this).eic }.getOrNull()
