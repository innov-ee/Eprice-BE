package ee.innov.eprice.data.remote.mapper

import ee.innov.eprice.data.remote.dto.PublicationMarketDocument
import ee.innov.eprice.domain.model.DomainEnergyPrice
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

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