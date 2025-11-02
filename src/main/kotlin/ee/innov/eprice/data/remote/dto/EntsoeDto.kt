package ee.innov.eprice.data.remote.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper

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