package ee.innov.eprice.di

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.koin.dsl.koinApplication
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals

class HttpClientTest {
    @Test
    fun `shared client and provider copies omit Accept-Charset and still decode UTF-8`() = runBlocking {
        val charsets = CopyOnWriteArrayList<String?>()
        val text = "Elektri hind: tõus"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            charsets.add(exchange.requestHeaders.getFirst("Accept-Charset"))
            val body = text.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "text/plain; charset=UTF-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val application = koinApplication { modules(appModule) }
        val client = application.koin.get<HttpClient>()
        try {
            val url = "http://127.0.0.1:${server.address.port}/"
            assertEquals(text, client.get(url).bodyAsText())
            client.config {}.use { providerClient ->
                assertEquals(text, providerClient.get(url).bodyAsText())
            }
            assertEquals(listOf(null, null), charsets.toList())
        } finally {
            client.close()
            application.close()
            server.stop(0)
        }
    }
}
