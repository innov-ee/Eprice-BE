package ee.innov.eprice.domain.model

import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
data class PriceStatistics(
    val countryCode: String,
    val startDate: String,
    val endDate: String,
    val daysRequested: Int,
    val daysCalculated: Int,
    val minPrice: Double,
    val maxPrice: Double,
    val averagePrice: Double
) {
    companion object {
        /**
         * Aggregates a collection of [DailyStatEntry] into [PriceStatistics].
         * Returns null if [dailyStats] is empty.
         */
        fun compute(
            countryCode: String,
            startDate: LocalDate,
            endDate: LocalDate,
            daysRequested: Int,
            dailyStats: Collection<DailyStatEntry>
        ): PriceStatistics? {
            if (dailyStats.isEmpty()) return null

            val minPrice = dailyStats.minOf { it.min }
            val maxPrice = dailyStats.maxOf { it.max }
            val seconds = dailyStats.sumOf { it.durationSeconds }
            val averagePrice = if (seconds > 0) dailyStats.sumOf { it.avg * it.durationSeconds } / seconds else 0.0

            return PriceStatistics(
                countryCode = countryCode,
                startDate = startDate.toString(),
                endDate = endDate.toString(),
                daysRequested = daysRequested,
                daysCalculated = dailyStats.size,
                minPrice = minPrice,
                maxPrice = maxPrice,
                averagePrice = averagePrice
            )
        }

        /**
         * Aggregates a date-keyed map of [DailyStatEntry] for the given [startDate]..[endDate] slice.
         * Returns null if no entries fall within the date range.
         */
        fun compute(
            countryCode: String,
            statsMap: Map<LocalDate, DailyStatEntry>,
            startDate: LocalDate,
            endDate: LocalDate,
            daysRequested: Int
        ): PriceStatistics? {
            val slice = statsMap.filterKeys { !it.isBefore(startDate) && !it.isAfter(endDate) }.values
            return compute(
                countryCode = countryCode,
                startDate = startDate,
                endDate = endDate,
                daysRequested = daysRequested,
                dailyStats = slice
            )
        }
    }
}
