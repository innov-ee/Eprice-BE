package ee.innov.eprice.di

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import ee.innov.eprice.data.remote.EntsoeRemoteDataSource
import ee.innov.eprice.data.repository.EnergyPriceRepositoryImpl
import ee.innov.eprice.domain.repository.EnergyPriceRepository
import ee.innov.eprice.domain.usecase.GetEnergyPricesUseCase
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import org.koin.core.qualifier.named
import org.koin.dsl.module

val appModule = module {
    // --- Singletons ---

    // Ktor HTTP Client
    single {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 15000
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 10000
            }
        }
    }

    // Jackson XML Mapper
    single {
        XmlMapper().registerKotlinModule().apply {
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
    }

    // Environment-provided values
    single(qualifier = named("entsoeApiKey")) {
        System.getenv("ENTSOE_API_KEY") ?: ""
    }
    single(qualifier = named("eestiBiddingZone")) {
        "10Y1001A1001A39I"
    }

    // --- Data Layer ---
    single {
        EntsoeRemoteDataSource(
            client = get(),
            xmlMapper = get(),
            apiKey = get(qualifier = named("entsoeApiKey")),
            biddingZone = get(qualifier = named("eestiBiddingZone"))
        )
    }

    // Bind the implementation to the interface
    single<EnergyPriceRepository> {
        EnergyPriceRepositoryImpl(
            remoteDataSource = get()
        )
    }

    // --- Domain Layer ---

    // Use 'factory' for use cases as they are typically lightweight and stateless
    factory {
        GetEnergyPricesUseCase(
            energyPriceRepository = get()
        )
    }
}