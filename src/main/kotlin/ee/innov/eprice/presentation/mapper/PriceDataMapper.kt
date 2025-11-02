package ee.innov.eprice.presentation.mapper

import ee.innov.eprice.domain.model.DomainEnergyPrice
import ee.innov.eprice.presentation.dto.PriceData

fun DomainEnergyPrice.toPriceData(): PriceData {
    return PriceData(
        startTimeUTC = this.startTime.toString(),
        price_eur_kwh = "%.5f".format(this.pricePerKWh)
    )
}