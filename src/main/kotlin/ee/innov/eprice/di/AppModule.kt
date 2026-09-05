package ee.innov.eprice.di

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import ee.innov.eprice.data.DailyAveragePriceCache
import ee.innov.eprice.data.DailyStatsCache
import ee.innov.eprice.data.EnergyPriceRepositoryImpl
import ee.innov.eprice.data.FileBackedDailyAveragePriceCache
import ee.innov.eprice.data.FileBackedDailyStatsCache
import ee.innov.eprice.data.InMemoryPriceCache
import ee.innov.eprice.data.PriceCache
import ee.innov.eprice.data.PriceStatsRepositoryImpl
import ee.innov.eprice.data.elering.EleringService
import ee.innov.eprice.data.entsoe.EntsoeService
import ee.innov.eprice.domain.EnergyPriceRepository
import ee.innov.eprice.domain.GetEnergyPricesUseCase
import ee.innov.eprice.domain.GetPriceStatisticsUseCase
import ee.innov.eprice.domain.GetPriceSummaryUseCase
import ee.innov.eprice.domain.GetRollingAveragePriceUseCase
import ee.innov.eprice.domain.PriceStatsRepository
import ee.innov.eprice.monitoring.ServiceMonitor
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.slf4j.LoggerFactory

val appModule = module {

    single { ServiceMonitor() }

    single {
        val clientLogger = LoggerFactory.getLogger("ee.innov.eprice.httpclient")
        val monitor = get<ServiceMonitor>()

        HttpClient(CIO) {
            engine {
                requestTimeout = 60_000
                maxConnectionsCount = 1000
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 30_000
            }

            install(HttpRequestRetry) {
                retryOnServerErrors(maxRetries = 3)
                retryOnException(maxRetries = 3, retryOnTimeout = true)
                exponentialDelay()
            }

            install(createClientPlugin("OutgoingMonitor") {
                onRequest { request, _ ->
                    monitor.incrementOutgoing(request.url.host)
                }
            })

            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) {
                        clientLogger.info(message)
                    }
                }
                level = LogLevel.INFO
            }
        }
    }

    single {
        XmlMapper().apply {
            registerKotlinModule()
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
    }

    single(qualifier = named("entsoeApiKey")) {
        System.getenv("ENTSOE_API_KEY")
            ?: throw IllegalStateException("ENTSOE_API_KEY environment variable is not set.")
    }

    single {
        EntsoeService(
            client = get(),
            xmlMapper = get(),
            apiKey = get(qualifier = named("entsoeApiKey"))
        )
    }

    single {
        EleringService(baseClient = get())
    }

    single<PriceCache> {
        InMemoryPriceCache()
    }

    single<DailyAveragePriceCache> {
        FileBackedDailyAveragePriceCache()
    }

    single<DailyStatsCache> {
        FileBackedDailyStatsCache()
    }

    single<EnergyPriceRepository> {
        EnergyPriceRepositoryImpl(
            entsoeService = get(),
            eleringService = get(),
            cache = get(),
            monitor = get()
        )
    }

    single<PriceStatsRepository> {
        PriceStatsRepositoryImpl(
            energyPriceRepository = get(),
            dailyStatsCache = get(),
            monitor = get()
        )
    }

    factory { GetEnergyPricesUseCase(energyPriceRepository = get()) }

    factory {
        GetRollingAveragePriceUseCase(
            energyPriceRepository = get(),
            dailyAveragePriceCache = get(),
            monitor = get()
        )
    }

    factory {
        GetPriceStatisticsUseCase(
            priceStatsRepository = get()
        )
    }

    factory {
        GetPriceSummaryUseCase(
            priceStatsRepository = get()
        )
    }
}