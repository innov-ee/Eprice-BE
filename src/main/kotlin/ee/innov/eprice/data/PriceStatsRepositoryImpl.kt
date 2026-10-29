package ee.innov.eprice.data

import ee.innov.eprice.domain.BiddingZones
import ee.innov.eprice.domain.EnergyPriceRepository
import ee.innov.eprice.domain.PriceStatsRepository
import ee.innov.eprice.domain.model.DailyStatEntry
import ee.innov.eprice.domain.model.isFullDay
import ee.innov.eprice.domain.model.inRange
import ee.innov.eprice.domain.model.DomainEnergyPrice
import ee.innov.eprice.monitoring.ServiceMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

class PriceStatsRepositoryImpl(
    private val energyPriceRepository: EnergyPriceRepository,
    private val dailyStatsCache: DailyStatsCache,
    private val monitor: ServiceMonitor? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val refreshPolicy: StatsRefreshPolicy = StatsRefreshPolicy(),
    private val errorCooldown: Duration = Duration.ofSeconds(30)
) : PriceStatsRepository, AutoCloseable {

    private companion object {
        const val MIN_PARTIAL_COVERAGE_SECONDS = 20 * 3600L
    }

    init {
        require(!errorCooldown.isNegative) { "Error cooldown must not be negative" }
    }

    private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class PendingStats(
        val start: LocalDate,
        val end: LocalDate,
        val result: Deferred<Result<Map<LocalDate, DailyStatEntry>>>
    )
    private val inFlight = mutableMapOf<String, PendingStats>()
    private data class FailedFetch(val error: Exception, val retryAt: Instant)
    private val failedFetches = ConcurrentHashMap<String, FailedFetch>()

    override suspend fun getDailyStats(
        countryCode: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Result<Map<LocalDate, DailyStatEntry>> {
        while (true) {
            // Cache-only requests must not wait for, or inherit failures from, another range's fill.
            cachedStatsIfNotDue(countryCode, startDate, endDate)?.let {
                return Result.success(withPartialStats(countryCode, startDate, endDate, it))
            }
            // One fill per region. Other regions remain independent. Never suspend under this lock.
            val pending = synchronized(inFlight) {
                // Completion is visible before its cleanup handler necessarily runs.
                // Only share work still running; retries must recheck the error cooldown.
                inFlight[countryCode]?.takeUnless { it.result.isCompleted } ?: run {
                    val result = fetchScope.async(start = CoroutineStart.LAZY) {
                        loadDailyStats(countryCode, startDate, endDate)
                    }
                    val fetch = PendingStats(startDate, endDate, result)
                    inFlight[countryCode] = fetch
                    result.invokeOnCompletion {
                        synchronized(inFlight) {
                            if (inFlight[countryCode] === fetch) inFlight.remove(countryCode)
                        }
                    }
                    result.start()
                    fetch
                }
            }
            // Cancelling an HTTP caller cancels only its wait, not the shared fill.
            val result = pending.result.await()
            if (startDate >= pending.start && endDate <= pending.end) {
                return result.map { stats ->
                    withPartialStats(countryCode, startDate, endDate, stats.filterKeys { it >= startDate && it <= endDate })
                }
            }
            // An overlapping range finished: recheck complete days and cooldowns before extending it.
        }
    }

    override fun close() {
        fetchScope.cancel()
    }

    private fun withPartialStats(
        countryCode: String,
        startDate: LocalDate,
        endDate: LocalDate,
        completeStats: Map<LocalDate, DailyStatEntry>
    ): Map<LocalDate, DailyStatEntry> {
        val today = LocalDate.now(clock.withZone(BiddingZones.resolve(countryCode).zoneId))
        val partialStats = listOf(today, today.plusDays(1)).mapNotNull { date ->
            if (date < startDate || date > endDate || date in completeStats) return@mapNotNull null
            dailyStatsCache.getMissing(countryCode, date)?.partialStats
                ?.takeIf { it.durationSeconds >= MIN_PARTIAL_COVERAGE_SECONDS }
                ?.let { date to it }
        }.toMap()
        return completeStats + partialStats
    }

    private fun cachedStatsIfNotDue(
        countryCode: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Map<LocalDate, DailyStatEntry>? {
        val cachedStats = dailyStatsCache.getRange(countryCode, startDate, endDate)
        val totalDays = (ChronoUnit.DAYS.between(startDate, endDate) + 1).toInt()
        val now = clock.instant()
        val needsRefresh = (0 until totalDays).any { offset ->
            val date = startDate.plusDays(offset.toLong())
            date !in cachedStats && dailyStatsCache.getMissing(countryCode, date)?.nextCheckAt?.isAfter(now) != true
        }
        if (needsRefresh) return null

        monitor?.recordCacheHit(cachedStats.size.toLong())
        monitor?.recordCacheMiss((totalDays - cachedStats.size).toLong())
        return cachedStats
    }

    private suspend fun loadDailyStats(
        countryCode: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Result<Map<LocalDate, DailyStatEntry>> {
        val totalDays = (ChronoUnit.DAYS.between(startDate, endDate) + 1).toInt()
        val datesInRange = (0 until totalDays).map { startDate.plusDays(it.toLong()) }

        val cachedStats = dailyStatsCache.getRange(countryCode, startDate, endDate)
        val missingDates = datesInRange.filter { !cachedStats.containsKey(it) }

        monitor?.recordCacheHit(cachedStats.size.toLong())
        monitor?.recordCacheMiss(missingDates.size.toLong())

        val dueDates = missingDates.filter { date ->
            dailyStatsCache.getMissing(countryCode, date)?.nextCheckAt?.isAfter(clock.instant()) != true
        }.toSet()
        if (dueDates.isEmpty()) return Result.success(cachedStats)

        // Serve the cache during cooldowns; otherwise refresh the entire requested window once.
        failedFetches[countryCode]?.let { failed ->
            if (failed.retryAt.isAfter(clock.instant())) return Result.failure(failed.error)
            failedFetches.remove(countryCode, failed)
        }
        return try {
            val zoneId = BiddingZones.resolve(countryCode).zoneId
            val start = startDate.atStartOfDay(zoneId).toInstant()
            val end = endDate.plusDays(1).atStartOfDay(zoneId).toInstant()
            val prices = energyPriceRepository.getPrices(countryCode, start, end, cacheResults = false).getOrThrow()

            val now = clock.instant()
            val today = now.atZone(zoneId).toLocalDate()
            val missing = mutableMapOf<LocalDate, MissingDailyStats>()
            val fetchedStats = missingDates.mapNotNull { date ->
                val dayStart = date.atStartOfDay(zoneId).toInstant()
                val dayEnd = date.plusDays(1).atStartOfDay(zoneId).toInstant()
                val dayPrices = prices.inRange(dayStart, dayEnd)
                if (dayPrices.isFullDay(date, zoneId)) {
                    date to dailyStat(dayPrices)
                } else {
                    val previous = dailyStatsCache.getMissing(countryCode, date)
                    // Provisional days need at least 20 hours of coverage, regardless of interval resolution.
                    // Overlapping intervals cannot count towards that coverage.
                    val partial = if (date in today..today.plusDays(1) &&
                        dayPrices.sumOf { it.durationSeconds } >= MIN_PARTIAL_COVERAGE_SECONDS &&
                        dayPrices.zipWithNext().all { (a, b) ->
                        a.startTime.plusSeconds(a.durationSeconds) <= b.startTime
                    }) dailyStat(dayPrices, isComplete = false) else null
                    // Incidental checks can recover a gap, but must not extend its active cooldown.
                    if (date in dueDates) {
                        val reason = if (dayPrices.isEmpty()) MissingStatsReason.NO_DATA else MissingStatsReason.PARTIAL
                        missing[date] = refreshPolicy.missing(date, zoneId, now, previous, reason).copy(partialStats = partial)
                    } else if (previous != null && previous.partialStats != partial) {
                        missing[date] = previous.copy(partialStats = partial)
                    }
                    null
                }
            }.toMap()
            dailyStatsCache.putMissingBatch(countryCode, missing)
            dailyStatsCache.putBatch(countryCode, fetchedStats)
            Result.success(cachedStats + fetchedStats)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failedFetches[countryCode] = FailedFetch(e, clock.instant().plus(errorCooldown))
            Result.failure(e)
        }
    }

    private fun dailyStat(prices: List<DomainEnergyPrice>, isComplete: Boolean = true): DailyStatEntry {
        val pricesList = prices.map { it.pricePerKWh }
        return DailyStatEntry(
            min = pricesList.min(),
            max = pricesList.max(),
            avg = prices.sumOf { it.pricePerKWh * it.durationSeconds } / prices.sumOf { it.durationSeconds },
            sum = pricesList.sum(),
            count = pricesList.size,
            durationSeconds = prices.sumOf { it.durationSeconds },
            isComplete = isComplete
        )
    }
}
