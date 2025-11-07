package ee.innov.eprice.di

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import ee.innov.eprice.data.EnergyPriceRepositoryImpl
import ee.innov.eprice.data.elering.EleringService
import ee.innov.eprice.data.entsoe.EntsoeService
import ee.innov.eprice.domain.EnergyPriceRepository
import ee.innov.eprice.domain.GetEnergyPricesUseCase
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import org.koin.core.qualifier.named
import org.koin.dsl.module

val appModule = module {

    single {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 15000
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 10000
            }
            // Add JSON support for Ktor client (for Elering)
            install(ContentNegotiation) {
                json()
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

    single<EnergyPriceRepository> {
        EnergyPriceRepositoryImpl(
            entsoeService = get(),
            eleringService = get(),
        )
    }

    factory { GetEnergyPricesUseCase(energyPriceRepository = get()) }
}