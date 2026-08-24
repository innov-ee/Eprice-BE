package ee.innov.eprice.test

import ee.innov.eprice.data.DailyAveragePriceCache
import ee.innov.eprice.data.DailyStatsCache
import ee.innov.eprice.data.PriceCache
import ee.innov.eprice.domain.model.DailyStatEntry
import ee.innov.eprice.domain.model.DomainEnergyPrice
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

class NoOpPriceCache : PriceCache {
    override fun get(key: String): List<DomainEnergyPrice>? = null
    override fun put(key: String, prices: List<DomainEnergyPrice>, isComplete: Boolean) {}
    override fun clear() {}
}

class NoOpDailyStatsCache : DailyStatsCache {
    override fun get(countryCode: String, date: LocalDate): DailyStatEntry? = null
    override fun put(countryCode: String, date: LocalDate, stats: DailyStatEntry) {}
    override fun putBatch(countryCode: String, entries: Map<LocalDate, DailyStatEntry>) {}
    override fun getRange(
        countryCode: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Map<LocalDate, DailyStatEntry> = emptyMap()

    override fun clear() {}
}

class NoOpDailyAveragePriceCache : DailyAveragePriceCache {
    override fun get(countryCode: String, date: LocalDate): Double? = null
    override fun put(countryCode: String, date: LocalDate, averagePrice: Double) {}
    override fun getRange(
        countryCode: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Map<LocalDate, Double> = emptyMap()

    override fun clear() {}
}

class InMemoryDailyStatsCache : DailyStatsCache {
    val store = ConcurrentHashMap<String, ConcurrentHashMap<LocalDate, DailyStatEntry>>()

    override fun get(countryCode: String, date: LocalDate): DailyStatEntry? =
        store[countryCode.uppercase()]?.get(date)

    override fun put(countryCode: String, date: LocalDate, stats: DailyStatEntry) {
        store.getOrPut(countryCode.uppercase()) { ConcurrentHashMap() }[date] = stats
    }

    override fun putBatch(countryCode: String, entries: Map<LocalDate, DailyStatEntry>) {
        val countryMap = store.getOrPut(countryCode.uppercase()) { ConcurrentHashMap() }
        countryMap.putAll(entries)
    }

    override fun getRange(
        countryCode: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Map<LocalDate, DailyStatEntry> {
        val countryMap = store[countryCode.uppercase()] ?: return emptyMap()
        return countryMap.filterKeys { !it.isBefore(startDate) && !it.isAfter(endDate) }
    }

    override fun clear() {
        store.clear()
    }
}

class InMemoryDailyAveragePriceCache : DailyAveragePriceCache {
    val store = ConcurrentHashMap<String, ConcurrentHashMap<LocalDate, Double>>()

    override fun get(countryCode: String, date: LocalDate): Double? =
        store[countryCode.uppercase()]?.get(date)

    override fun put(countryCode: String, date: LocalDate, averagePrice: Double) {
        store.getOrPut(countryCode.uppercase()) { ConcurrentHashMap() }[date] = averagePrice
    }

    override fun getRange(
        countryCode: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Map<LocalDate, Double> {
        val countryMap = store[countryCode.uppercase()] ?: return emptyMap()
        return countryMap.filterKeys { !it.isBefore(startDate) && !it.isAfter(endDate) }
    }

    override fun clear() {
        store.clear()
    }
}
