package ee.innov.eprice.monitoring

import kotlinx.serialization.Serializable
import java.util.Properties

@Serializable
data class ServiceIdentity(
    val commit: String,
    val builtAt: String
) {
    companion object {
        fun load(
            buildInfo: Properties = loadBuildInfo()
        ): ServiceIdentity = ServiceIdentity(
            // Read artifact metadata only: runtime variables must not relabel the code.
            commit = buildInfo.getProperty("commit").nonBlankOr("unknown"),
            builtAt = buildInfo.getProperty("builtAt").nonBlankOr("unknown")
        )

        private fun loadBuildInfo(): Properties = Properties().apply {
            ServiceIdentity::class.java.getResourceAsStream("/build-info.properties")?.use { load(it) }
        }

        private fun String?.nonBlankOr(fallback: String): String = this?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
    }
}
