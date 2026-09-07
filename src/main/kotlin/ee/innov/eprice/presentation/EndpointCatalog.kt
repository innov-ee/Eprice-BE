package ee.innov.eprice.presentation

import ee.innov.eprice.util.getEnvList
import kotlinx.serialization.Serializable

@Serializable
data class EndpointSample(
    val label: String,
    val path: String,
    val description: String? = null,
    val headers: Map<String, String> = emptyMap()
)

@Serializable
data class EndpointDoc(
    val category: String,
    val method: String,
    val path: String,
    val description: String,
    val samples: List<EndpointSample>,
    val headers: Map<String, String> = emptyMap()
)

object EndpointCategory {
    const val AUTHENTICATION = "Authentication & Keys"
    const val MONITORING = "Monitoring & Diagnostics"
    const val ENERGY_PRICES = "Energy Prices"
    const val PRICE_STATISTICS = "Price Statistics"
    const val CACHE_ADMINISTRATION = "Cache Administration"
}

object EndpointCatalog {
    val endpoints: List<EndpointDoc>
        get() {
            val rawBootstrapKeys = System.getenv("BOOTSTRAP_KEYS") ?: ""
            val bootstrapKeys = getEnvList("BOOTSTRAP_KEYS", default = emptyList())
            val defaultBootstrapKey = bootstrapKeys.firstOrNull().orEmpty()
            val defaultHeaders = if (defaultBootstrapKey.isNotEmpty()) {
                mapOf("X-Bootstrap-Key" to defaultBootstrapKey)
            } else {
                emptyMap()
            }

            val authDescription = buildString {
                append("Fetches dynamic operational API key using X-Bootstrap-Key header.")
                if (rawBootstrapKeys.isNotBlank()) {
                    append(" Configured BOOTSTRAP_KEYS: $rawBootstrapKeys")
                }
            }

            val authSamples = buildList {
                add(
                    EndpointSample(
                        label = "Fetch Key",
                        path = "/api/v1/auth/keys",
                        description = if (rawBootstrapKeys.isNotBlank()) "Default first key from BOOTSTRAP_KEYS: $defaultBootstrapKey" else null,
                        headers = defaultHeaders
                    )
                )
                if (bootstrapKeys.size > 1) {
                    bootstrapKeys.drop(1).forEachIndexed { index, key ->
                        add(
                            EndpointSample(
                                label = "Fetch Key (Key ${index + 2})",
                                path = "/api/v1/auth/keys",
                                description = "Configured alternate key: $key",
                                headers = mapOf("X-Bootstrap-Key" to key)
                            )
                        )
                    }
                }
                add(
                    EndpointSample(
                        label = "Invalid Key",
                        path = "/api/v1/auth/keys",
                        description = "Tests 401 Unauthorized using an invalid bootstrap key",
                        headers = mapOf("X-Bootstrap-Key" to "invalid-bootstrap-key")
                    )
                )
            }

            return listOf(
                EndpointDoc(
                    category = EndpointCategory.AUTHENTICATION,
                    method = "GET",
                    path = "/api/v1/auth/keys",
                    description = authDescription,
                    samples = authSamples,
                    headers = defaultHeaders
                ),
        EndpointDoc(
            category = EndpointCategory.MONITORING,
            method = "GET",
            path = "/monitor",
            description = "Service operational statistics, uptime, and request counters.",
            samples = listOf(
                EndpointSample(label = "Live Stats", path = "/monitor")
            )
        ),
        EndpointDoc(
            category = EndpointCategory.MONITORING,
            method = "GET",
            path = "/health",
            description = "Basic health check returning UP status.",
            samples = listOf(
                EndpointSample(label = "Health Check", path = "/health")
            )
        ),
        EndpointDoc(
            category = EndpointCategory.MONITORING,
            method = "GET",
            path = "/api",
            description = "Root API sanity check.",
            samples = listOf(
                EndpointSample(label = "API Status", path = "/api")
            )
        ),
        EndpointDoc(
            category = EndpointCategory.ENERGY_PRICES,
            method = "GET",
            path = "/api/prices/{countryCode?}",
            description = "Hourly energy prices for today and tomorrow.",
            samples = listOf(
                EndpointSample(label = "Estonia (EE)", path = "/api/prices/EE"),
                EndpointSample(label = "Latvia (LV)", path = "/api/prices/LV"),
                EndpointSample(label = "Lithuania (LT)", path = "/api/prices/LT"),
                EndpointSample(label = "Finland (FI)", path = "/api/prices/FI"),
                EndpointSample(label = "Germany (DE)", path = "/api/prices/DE"),
                EndpointSample(label = "France (FR)", path = "/api/prices/FR"),
                EndpointSample(label = "Sweden (SE3)", path = "/api/prices/SE3"),
                EndpointSample(label = "Norway (NO1)", path = "/api/prices/NO1"),
                EndpointSample(label = "Poland (PL)", path = "/api/prices/PL"),
                EndpointSample(label = "Default (EE)", path = "/api/prices")
            )
        ),
        EndpointDoc(
            category = EndpointCategory.ENERGY_PRICES,
            method = "GET",
            path = "/api/prices/{countryCode}/avg",
            description = "Rolling average electricity price.",
            samples = listOf(
                EndpointSample(label = "EE Rolling Avg", path = "/api/prices/EE/avg"),
                EndpointSample(label = "DE Rolling Avg", path = "/api/prices/DE/avg"),
                EndpointSample(label = "LV Rolling Avg", path = "/api/prices/LV/avg"),
                EndpointSample(label = "FI Rolling Avg", path = "/api/prices/FI/avg")
            )
        ),
        EndpointDoc(
            category = EndpointCategory.PRICE_STATISTICS,
            method = "GET",
            path = "/api/prices/{countryCode}/stats/summary",
            description = "Daily price summary with min, max, average, and timestamp bounds.",
            samples = listOf(
                EndpointSample(label = "EE Today Summary", path = "/api/prices/EE/stats/summary"),
                EndpointSample(label = "DE Today Summary", path = "/api/prices/DE/stats/summary"),
                EndpointSample(label = "FI Today Summary", path = "/api/prices/FI/stats/summary")
            )
        ),
        EndpointDoc(
            category = EndpointCategory.PRICE_STATISTICS,
            method = "GET",
            path = "/api/prices/{countryCode}/stats",
            description = "Calculates energy price statistics for named ranges, day counts, or custom dates.",
            samples = listOf(
                EndpointSample(label = "Default (Today)", path = "/api/prices/EE/stats"),
                EndpointSample(label = "Yesterday", path = "/api/prices/EE/stats?range=yesterday"),
                EndpointSample(label = "Today", path = "/api/prices/EE/stats?range=today"),
                EndpointSample(label = "Tomorrow", path = "/api/prices/EE/stats?range=tomorrow"),
                EndpointSample(label = "Past 7 Days", path = "/api/prices/EE/stats?days=7"),
                EndpointSample(label = "Past 30 Days", path = "/api/prices/EE/stats?days=30")
            )
        ),
        EndpointDoc(
            category = EndpointCategory.CACHE_ADMINISTRATION,
            method = "POST",
            path = "/api/cache/clear",
            description = "Clears all in-memory and file-backed caches (Requires Admin Basic Auth).",
            samples = listOf(
                EndpointSample(label = "Clear Caches (POST)", path = "/api/cache/clear")
            )
        )
    )
    }
}
