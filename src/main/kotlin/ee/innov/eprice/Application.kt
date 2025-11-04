package ee.innov.eprice

import ee.innov.eprice.di.appModule
import ee.innov.eprice.presentation.priceRoutes
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.routing.routing
import org.koin.core.module.Module
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    val host = System.getenv("HOST") ?: "0.0.0.0"

    embeddedServer(
        Netty,
        port = port,
        host = host,
        module = Application::module
    ).start(wait = true)
}


/**
 * 'allowKoinOverrides' to conditionally enable
 * Koin's allowOverride(true) for testing.
 */
fun Application.module(
    koinModules: List<Module> = listOf(appModule),
    allowKoinOverrides: Boolean = false
) {
    install(Koin) {
        slf4jLogger()
        modules(koinModules)

        if (allowKoinOverrides) {
            // This is the new, correct way to allow overrides
            allowOverride(true)
        }
    }

    install(ContentNegotiation) {
        json() // Use kotlinx.serialization for JSON
    }
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
    }

    routing {
        priceRoutes() // Use the modularized routes
    }
}