package ee.innov.eprice.domain.model

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

@Serializable
data class DailyStatEntry(
    val min: Double,
    val max: Double,
    val avg: Double,
    val sum: Double,
    val count: Int,
    val durationSeconds: Long = count * 3600L,
    val isComplete: Boolean = true
) {
    /**
     * Checks whether the entry covers the actual local day, including DST transitions.
     */
    fun isFullDay(date: LocalDate, zoneId: ZoneId): Boolean {
        val start = date.atStartOfDay(zoneId)
        val end = date.plusDays(1).atStartOfDay(zoneId)
        return isComplete && durationSeconds == Duration.between(start, end).seconds
    }
}
