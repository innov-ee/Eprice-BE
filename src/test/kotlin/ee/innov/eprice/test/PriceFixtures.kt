package ee.innov.eprice.test

import ee.innov.eprice.domain.EnergyPriceRepository
import ee.innov.eprice.domain.model.DomainEnergyPrice
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Generate UTC timestamps from actual local day boundaries, including DST days. */
fun dayPrices(date: LocalDate, zone: ZoneId, stepSeconds: Long = 900, price: Double = 0.1): List<DomainEnergyPrice> {
    val start = date.atStartOfDay(zone).toInstant()
    val end = date.plusDays(1).atStartOfDay(zone).toInstant()
    return (0 until Duration.between(start, end).seconds / stepSeconds).map {
        DomainEnergyPrice(start.plusSeconds(it * stepSeconds), price)
    }
}

class FixedPriceRepository(private val response: Result<List<DomainEnergyPrice>>) : EnergyPriceRepository {
    override suspend fun getPrices(
        countryCode: String,
        start: Instant,
        end: Instant,
        cacheResults: Boolean
    ): Result<List<DomainEnergyPrice>> = response
}

/** Small provider-shaped fixture; currency and unit fields must not be confused with scaling. */
fun entsoePriceXml(
    curve: String = "A01",
    resolution: String = "PT15M",
    eic: String = "10Y1001A1001A82H",
    points: List<Pair<Int, Double>> = (1..4).map { it to 100.0 },
    start: String = "2026-08-22T00:00:00Z",
    end: String = "2026-08-22T01:00:00Z"
): String = """
    <Publication_MarketDocument xmlns="urn:iec62325.351:tc57wg16:451-3:publicationdocument:7:3">
        <type>A44</type>
        <TimeSeries>
            <mRID>1</mRID>
            <businessType>A62</businessType>
            <in_Domain.mRID codingScheme="A01">$eic</in_Domain.mRID>
            <out_Domain.mRID codingScheme="A01">$eic</out_Domain.mRID>
            <contract_MarketAgreement.type>A01</contract_MarketAgreement.type>
            <currency_Unit.name>EUR</currency_Unit.name>
            <price_Measure_Unit.name>MWH</price_Measure_Unit.name>
            <curveType>$curve</curveType>
            <Period>
                <timeInterval><start>$start</start><end>$end</end></timeInterval>
                <resolution>$resolution</resolution>
                ${points.joinToString("\n") { (position, price) -> "<Point><position>$position</position><price.amount>$price</price.amount></Point>" }}
            </Period>
        </TimeSeries>
    </Publication_MarketDocument>
""".trimIndent()
