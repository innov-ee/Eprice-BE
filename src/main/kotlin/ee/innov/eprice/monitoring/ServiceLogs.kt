package ee.innov.eprice.monitoring

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.AppenderBase
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.log
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

@Serializable
data class ServiceLogEntry(val timestamp: String, val level: String, val logger: String, val message: String)

@Serializable
data class ServiceLogSnapshot(val entries: List<ServiceLogEntry>, val capacity: Int)

/** A bounded, process-local tail. Secrets are removed before anything is retained. */
class ServiceLogs(
    private val capacity: Int = 200,
    environment: Map<String, String> = System.getenv()
) {
    private val entries = ArrayDeque<ServiceLogEntry>()
    private val redactor = LogRedactor(environment)

    init {
        require(capacity > 0)
    }

    @Synchronized
    fun record(timestamp: Instant, level: String, logger: String, message: String) {
        val safeMessage = redactor.redact(message)
        val boundedMessage = if (safeMessage.length > 4000) safeMessage.take(4000) + "\n[truncated]" else safeMessage
        if (entries.size == capacity) entries.removeFirst()
        entries.addLast(ServiceLogEntry(timestamp.toString(), level, logger.take(200), boundedMessage))
    }

    @Synchronized
    fun snapshot(): ServiceLogSnapshot = ServiceLogSnapshot(entries.toList(), capacity)
}

internal class LogRedactor(environment: Map<String, String>) {
    private val secrets: List<String> = buildList {
        listOf("ENTSOE_API_KEY", "MASTER_SECRET", "ADMIN_PASSWORD", "BOOTSTRAP_KEYS").forEach { key ->
            val value = environment[key].orEmpty()
            if (key == "BOOTSTRAP_KEYS") addAll(value.split(',').map { it.trim() }) else add(value)
        }
        val username = environment["ADMIN_USERNAME"]
        val password = environment["ADMIN_PASSWORD"]
        if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
            add(Base64.getEncoder().encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8)))
        }
    }.filter { it.isNotBlank() }.flatMap {
        listOf(it, URLEncoder.encode(it, StandardCharsets.UTF_8))
    }.distinct().sortedByDescending { it.length }

    private val headers = Regex("""(?im)(\b(?:Authorization|X-API-Key|X-Bootstrap-Key)\s*[:=]\s*)[^\r\n]+""")
    private val queryParameters = Regex("""(?i)([?&](?:securityToken|api[_-]?key|token|password)=)[^&\s]+""")
    private val jsonCredentials = Regex("""(?i)("(?:operational_key|securityToken|api[_-]?key|password|master_secret|bootstrap_keys|authorization|x-api-key|x-bootstrap-key)"\s*:\s*")[^"]*(")""")

    fun redact(message: String): String {
        var safe = headers.replace(message) { "${it.groupValues[1]}[redacted]" }
        safe = queryParameters.replace(safe) { "${it.groupValues[1]}[redacted]" }
        safe = jsonCredentials.replace(safe) { "${it.groupValues[1]}[redacted]${it.groupValues[2]}" }
        for (secret in secrets) safe = safe.replace(secret, "[redacted]")
        return safe
    }
}

internal class ServiceLogAppender(
    private val logs: ServiceLogs,
    private val applicationLoggerName: String = "ktor.application"
) : AppenderBase<ILoggingEvent>() {
    override fun append(event: ILoggingEvent) {
        if (!event.level.isGreaterOrEqual(Level.INFO)) return
        if (!event.loggerName.startsWith("ee.innov.eprice.") && event.loggerName != applicationLoggerName) return
        val exception = event.throwableProxy?.let { "\n" + ThrowableProxyUtil.asString(it) }.orEmpty()
        logs.record(Instant.ofEpochMilli(event.timeStamp), event.level.toString(), event.loggerName, event.formattedMessage + exception)
    }
}

fun Application.configureServiceLogs() {
    val logs: ServiceLogs by inject()
    val context = LoggerFactory.getILoggerFactory() as LoggerContext
    val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
    val appender = ServiceLogAppender(logs, log.name).apply {
        this.context = context
        start()
    }
    root.addAppender(appender)
    environment.monitor.subscribe(ApplicationStopped) {
        root.detachAppender(appender)
        appender.stop()
    }
}
