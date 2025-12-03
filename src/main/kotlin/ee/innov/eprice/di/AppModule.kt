// file: src/main/kotlin/ee/innov/eprice/di/AppModule.kt
package ee.innov.eprice.di

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import ee.innov.eprice.data.DailyAveragePriceCache
import ee.innov.eprice.data.EnergyPriceRepositoryImpl
import ee.innov.eprice.data.FileBackedDailyAveragePriceCache
import ee.innov.eprice.data.InMemoryPriceCache
import ee.innov.eprice.data.PriceCache
import ee.innov.eprice.data.elering.EleringService
import ee.innov.eprice.data.entsoe.EntsoeService
import ee.innov.eprice.domain.EnergyPriceRepository
import ee.innov.eprice.domain.GetEnergyPricesUseCase
import ee.innov.eprice.domain.GetRollingAveragePriceUseCase
import ee.innov.eprice.monitoring.ServiceMonitor
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.slf4j.LoggerFactory

val appModule = module {

    single { ServiceMonitor() }

    single {
        val clientLogger = LoggerFactory.getLogger("ee.innov.eprice.httpclient")
        val monitor = get<ServiceMonitor>()

        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 15000
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 10000
            }


            install(createClientPlugin("OutgoingMonitor") {
                onRequest { _, _ ->
                    monitor.incrementOutgoing()
                }
            })

            // Add JSON support for Ktor client (for Elering)
            install(ContentNegotiation) {
                json()
            }

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
        EleringService(client = get())
    }

    single<PriceCache> {
        InMemoryPriceCache()
    }

    single<DailyAveragePriceCache> {
        FileBackedDailyAveragePriceCache()
    }

    single<EnergyPriceRepository> {
        EnergyPriceRepositoryImpl(
            entsoeService = get(),
            eleringService = get(),
            cache = get()
        )
    }

    factory { GetEnergyPricesUseCase(energyPriceRepository = get()) }

    factory {
        GetRollingAveragePriceUseCase(
            energyPriceRepository = get(),
            dailyAveragePriceCache = get()
        )
    }
}