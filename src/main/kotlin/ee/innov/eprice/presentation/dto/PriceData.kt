package ee.innov.eprice.presentation.dto

@kotlinx.serialization.Serializable
data class PriceData(
    val startTimeUTC: String,
    val price_eur_kwh: String
)