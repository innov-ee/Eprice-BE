package ee.innov.eprice.monitoring

import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import kotlinx.coroutines.CancellationException

// Install before HttpRequestRetry so failures are counted once, after retries finish.
internal fun outgoingMonitor(monitor: ServiceMonitor) = createClientPlugin("OutgoingMonitor") {
    onRequest { request, _ ->
        monitor.incrementOutgoing(request.url.host)
    }

    on(Send) { request ->
        val call = try {
            proceed(request)
        } catch (cause: Exception) {
            // Caller cancellation is not an upstream failure.
            if (cause !is CancellationException) {
                monitor.incrementFailedOutgoing(request.url.host)
            }
            throw cause
        }
        if (call.response.status.value >= 400) {
            monitor.incrementFailedOutgoing(request.url.host)
        }
        call
    }
}
