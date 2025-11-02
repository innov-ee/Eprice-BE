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
import org.koin.core.context.startKoin
import org.koin.logger.slf4jLogger

fun main() {
    // Read environment variables
    val port = System.getenv("PORT")?.toInt() ?: 8080
    val host = System.getenv("HOST") ?: "0.0.0.0"

    // Start Koin
    startKoin {
        slf4jLogger() // Use SLF4J for Koin logging
        modules(appModule)
    }

    // Set up and start the Ktor server
    embeddedServer(Netty, port = port, host = host, module = Application::module)
        .start(wait = true)
}

/**
 * Ktor application module.
 * This function configures Ktor plugins and routing.
 */
fun Application.module() {
    install(ContentNegotiation) {
        json() // Use kotlinx.serialization for JSON
    }
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
    }

    // Configure routing
    routing {
        priceRoutes() // Use the modularized routes
    }
}