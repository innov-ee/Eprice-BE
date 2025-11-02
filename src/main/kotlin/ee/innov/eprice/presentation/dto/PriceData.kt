package ee.innov.eprice.presentation.dto

import ee.innov.eprice.domain.model.DomainEnergyPrice
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PriceData(
    @SerialName("startTimeUTC")
    val startTimeUTC: String,
    @SerialName("price_eur_kwh")
    val price_eur_kwh: String
)

fun DomainEnergyPrice.toPriceData() = PriceData(
    startTimeUTC = this.startTime.toString(),
    price_eur_kwh = "%.5f".format(this.pricePerKWh)
)