package ee.innov.eprice.presentation

import kotlinx.serialization.Serializable

@Serializable
data class EndpointSample(
    val label: String,
    val path: String,
    val description: String? = null
)

@Serializable
data class EndpointDoc(
    val category: String,
    val method: String,
    val path: String,
    val description: String,
    val samples: List<EndpointSample>
)

object EndpointCatalog {
    val endpoints: List<EndpointDoc> = listOf(
        EndpointDoc(
            category = "Monitoring & Diagnostics",
            method = "GET",
            path = "/monitor",
            description = "Service operational statistics, uptime, and request counters.",
            samples = listOf(
                EndpointSample(label = "Live Stats", path = "/monitor")
            )
        ),
        EndpointDoc(
            category = "Monitoring & Diagnostics",
            method = "GET",
            path = "/health",
            description = "Basic health check returning UP status.",
            samples = listOf(
                EndpointSample(label = "Health Check", path = "/health")
            )
        ),
        EndpointDoc(
            category = "Monitoring & Diagnostics",
            method = "GET",
            path = "/api",
            description = "Root API sanity check.",
            samples = listOf(
                EndpointSample(label = "API Status", path = "/api")
            )
        ),
        EndpointDoc(
            category = "Energy Prices",
            method = "GET",
            path = "/api/prices/{countryCode?}",
            description = "Hourly energy prices for today and tomorrow.",
            samples = listOf(
                EndpointSample(label = "Estonia (EE)", path = "/api/prices/EE"),
                EndpointSample(label = "Latvia (LV)", path = "/api/prices/LV"),
                EndpointSample(label = "Lithuania (LT)", path = "/api/prices/LT"),
                EndpointSample(label = "Finland (FI)", path = "/api/prices/FI"),
                EndpointSample(label = "Default (EE)", path = "/api/prices")
            )
        ),
        EndpointDoc(
            category = "Energy Prices",
            method = "GET",
            path = "/api/prices/{countryCode}/avg",
            description = "Rolling average electricity price.",
            samples = listOf(
                EndpointSample(label = "EE Rolling Avg", path = "/api/prices/EE/avg"),
                EndpointSample(label = "LV Rolling Avg", path = "/api/prices/LV/avg"),
                EndpointSample(label = "FI Rolling Avg", path = "/api/prices/FI/avg")
            )
        ),
        EndpointDoc(
            category = "Price Statistics",
            method = "GET",
            path = "/api/prices/{countryCode}/stats/summary",
            description = "Daily price summary with min, max, average, and timestamp bounds.",
            samples = listOf(
                EndpointSample(label = "EE Today Summary", path = "/api/prices/EE/stats/summary"),
                EndpointSample(label = "FI Today Summary", path = "/api/prices/FI/stats/summary")
            )
        ),
        EndpointDoc(
            category = "Price Statistics",
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
            category = "Cache Administration",
            method = "GET",
            path = "/api/cache/clear",
            description = "Clears all in-memory and file-backed caches.",
            samples = emptyList()
        )
    )
}
