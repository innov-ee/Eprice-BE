package ee.innov.eprice.di

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import ee.innov.eprice.data.remote.EntsoeService
import ee.innov.eprice.data.repository.EnergyPriceRepositoryImpl
import ee.innov.eprice.domain.repository.EnergyPriceRepository
import ee.innov.eprice.domain.usecase.GetEnergyPricesUseCase
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
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

    single(qualifier = named("biddingZone")) { "10Y1001A1001A39I" }

    single {
        EntsoeService(
            client = get(),
            xmlMapper = get(),
            apiKey = get(qualifier = named("entsoeApiKey")),
            biddingZone = get(qualifier = named("biddingZone"))
        )
    }

    single<EnergyPriceRepository> { EnergyPriceRepositoryImpl(entsoeService = get()) }

    factory { GetEnergyPricesUseCase(energyPriceRepository = get()) }
}