package ee.innov.eprice.monitoring

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OutgoingMonitorTest {
    @Test
    fun `HTTP errors count once after retries including provider client copies`() = runBlocking {
        val monitor = ServiceMonitor()
        var attempts = 0
        HttpClient(MockEngine {
            attempts++
            respond("", HttpStatusCode.ServiceUnavailable)
        }) {
            install(outgoingMonitor(monitor))
            install(HttpRequestRetry) {
                retryOnServerErrors(maxRetries = 3)
                delayMillis { 0 }
            }
        }.use { client ->
            client.config {}.use { providerClient ->
                assertEquals(HttpStatusCode.ServiceUnavailable, providerClient.get("https://dashboard.elering.ee/").status)
            }
        }

        assertEquals(4, attempts)
        val stats = monitor.getStats()
        assertEquals(1L, stats.totalOutgoingRequests)
        assertEquals(1L, stats.failedOutgoingRequests)
        assertEquals(1L, stats.failedOutgoingEleringRequests)
        assertEquals(0L, stats.failedOutgoingEntsoeRequests)
    }

    @Test
    fun `successful retry does not count as failed`() = runBlocking {
        val monitor = ServiceMonitor()
        var attempts = 0
        HttpClient(MockEngine {
            attempts++
            respond("", if (attempts == 1) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK)
        }) {
            install(outgoingMonitor(monitor))
            install(HttpRequestRetry) {
                retryOnServerErrors(maxRetries = 3)
                delayMillis { 0 }
            }
        }.use { client ->
            assertEquals(HttpStatusCode.OK, client.get("https://web-api.tp.entsoe.eu/").status)
        }

        assertEquals(2, attempts)
        assertEquals(1L, monitor.getStats().totalOutgoingRequests)
        assertEquals(0L, monitor.getStats().failedOutgoingRequests)
    }

    @Test
    fun `client errors count as failed without requiring response validation`() = runBlocking {
        val monitor = ServiceMonitor()
        HttpClient(MockEngine { respond("", HttpStatusCode.BadRequest) }) {
            install(outgoingMonitor(monitor))
        }.use { client ->
            assertEquals(HttpStatusCode.BadRequest, client.get("https://web-api.tp.entsoe.eu/").status)
        }
        assertEquals(1L, monitor.getStats().failedOutgoingRequests)
        assertEquals(1L, monitor.getStats().failedOutgoingEntsoeRequests)
    }

    @Test
    fun `transport errors and timeouts count once after retries and propagate`() = runBlocking {
        for (timeout in listOf(false, true)) {
            val monitor = ServiceMonitor()
            var attempts = 0
            HttpClient(MockEngine { request ->
                attempts++
                if (timeout) throw HttpRequestTimeoutException(request)
                throw IOException("Connection failed")
            }) {
                install(outgoingMonitor(monitor))
                install(HttpRequestRetry) {
                    retryOnException(maxRetries = 3, retryOnTimeout = true)
                    delayMillis { 0 }
                }
            }.use { client ->
                assertFailsWith<IOException> { client.get("https://web-api.tp.entsoe.eu/") }
            }
            assertEquals(4, attempts)
            assertEquals(1L, monitor.getStats().totalOutgoingRequests)
            assertEquals(1L, monitor.getStats().failedOutgoingRequests)
            assertEquals(1L, monitor.getStats().failedOutgoingEntsoeRequests)
        }
    }

    @Test
    fun `caller cancellation is not counted as upstream failure`() = runBlocking {
        val monitor = ServiceMonitor()
        HttpClient(MockEngine { throw CancellationException("Cancelled by caller") }) {
            install(outgoingMonitor(monitor))
        }.use { client ->
            assertFailsWith<CancellationException> { client.get("https://dashboard.elering.ee/") }
        }
        assertEquals(0L, monitor.getStats().failedOutgoingRequests)
    }
}
