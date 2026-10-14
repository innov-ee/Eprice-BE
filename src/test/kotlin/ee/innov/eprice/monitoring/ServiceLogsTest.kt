package ee.innov.eprice.monitoring

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Base64
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServiceLogsTest {
    private val timestamp = Instant.parse("2026-10-14T10:30:00Z")

    @Test
    fun `tail evicts oldest entries and snapshots remain independent`() {
        val logs = ServiceLogs(capacity = 2, environment = emptyMap())
        logs.record(timestamp, "INFO", "test", "first")
        val before = logs.snapshot()
        logs.record(timestamp, "WARN", "test", "second")
        logs.record(timestamp, "ERROR", "test", "third")
        assertEquals(listOf("second", "third"), logs.snapshot().entries.map { it.message })
        assertEquals(listOf("first"), before.entries.map { it.message })
        assertEquals(2, logs.snapshot().capacity)
    }

    @Test
    fun `secrets and credentials are redacted before retention`() {
        val password = "private-admin-password"
        val basic = Base64.getEncoder().encodeToString("admin:$password".toByteArray())
        val logs = ServiceLogs(environment = mapOf(
            "ENTSOE_API_KEY" to "upstream-secret",
            "ADMIN_USERNAME" to "admin", "ADMIN_PASSWORD" to password,
            "MASTER_SECRET" to "master-secret", "BOOTSTRAP_KEYS" to "bootstrap-one, bootstrap-two"
        ))
        logs.record(timestamp, "INFO", "test", """
            GET https://upstream/prices?securityToken=unconfigured-token&country=EE
            GET /api?api_key=dynamic-key
            Authorization: Basic $basic
            X-API-Key: rotating-secret
            X-Bootstrap-Key: dynamic-bootstrap
            {"operational_key":"json-key", "password":"json-password"}
            upstream-secret master-secret bootstrap-one bootstrap-two $password
        """.trimIndent())
        val message = logs.snapshot().entries.single().message
        listOf("unconfigured-token", "dynamic-key", basic, "rotating-secret", "dynamic-bootstrap",
            "json-key", "json-password", "upstream-secret", "master-secret", "bootstrap-one", "bootstrap-two", password
        ).forEach { assertFalse(message.contains(it), "Secret remained in log: $it") }
        assertTrue(message.contains("country=EE"))
        assertTrue(message.contains("[redacted]"))
    }

    @Test
    fun `configured secrets cannot obscure credential field names before redaction`() {
        val logs = ServiceLogs(environment = mapOf("ADMIN_PASSWORD" to "password"))
        logs.record(timestamp, "INFO", "test", """{"password":"different-secret", "Authorization":"Bearer rotating-token"}""")
        val message = logs.snapshot().entries.single().message
        assertFalse(message.contains("different-secret"))
        assertFalse(message.contains("rotating-token"))
    }

    @Test
    fun `oversized entries are bounded after redaction`() {
        val logs = ServiceLogs(environment = emptyMap())
        logs.record(timestamp, "ERROR", "test", "x".repeat(5000))
        assertEquals("x".repeat(4000) + "\n[truncated]", logs.snapshot().entries.single().message)
    }

    @Test
    fun `concurrent writers maintain the capacity bound`() {
        val logs = ServiceLogs(capacity = 20, environment = emptyMap())
        val workers = Executors.newFixedThreadPool(4)
        try {
            workers.invokeAll((1..200).map { number -> java.util.concurrent.Callable {
                logs.record(timestamp, "INFO", "test", "entry $number")
                assertTrue(logs.snapshot().entries.size <= 20)
            } }).forEach { it.get() }
            assertEquals(20, logs.snapshot().entries.size)
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `appender captures application errors with traces and excludes debug and library noise`() {
        val logs = ServiceLogs(environment = emptyMap())
        val appender = ServiceLogAppender(logs).apply { context = LoggerContext(); start() }
        fun event(logger: String, level: Level) = LoggingEvent().apply {
            loggerName = logger
            this.level = level
            timeStamp = timestamp.toEpochMilli()
            message = "upstream failed"
        }
        appender.doAppend(event("ee.innov.eprice.test", Level.DEBUG))
        appender.doAppend(event("io.netty.test", Level.INFO))
        appender.doAppend(event("ee.innov.eprice.test", Level.ERROR).apply {
            throwableProxy = ThrowableProxy(IllegalStateException("connection failed"))
        })
        val entry = logs.snapshot().entries.single()
        assertEquals("ERROR", entry.level)
        assertEquals(timestamp.toString(), entry.timestamp)
        assertTrue(entry.message.contains("IllegalStateException: connection failed"))
        appender.stop()
    }
}
