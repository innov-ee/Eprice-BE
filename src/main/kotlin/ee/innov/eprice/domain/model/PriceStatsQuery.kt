package ee.innov.eprice.domain.model

import java.time.LocalDate

sealed interface PriceStatsQuery {
    enum class NamedRange {
        YESTERDAY,
        TODAY,
        TOMORROW;

        companion object {
            fun fromStringOrNull(value: String): NamedRange? =
                entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        }
    }

    data class Named(val range: NamedRange) : PriceStatsQuery
    data class Days(val days: Int) : PriceStatsQuery
    data class CustomRange(val startDate: LocalDate, val endDate: LocalDate) : PriceStatsQuery
    data object Default : PriceStatsQuery
}
