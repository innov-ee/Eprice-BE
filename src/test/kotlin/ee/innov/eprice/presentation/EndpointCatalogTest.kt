package ee.innov.eprice.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EndpointCatalogTest {

    @Test
    fun `endpoints contains auth keys endpoint with default header and samples`() {
        val endpoints = EndpointCatalog.endpoints
        val keysEndpoint = endpoints.find { it.path == "/api/v1/keys" }

        assertNotNull(keysEndpoint, "Keys endpoint /api/v1/keys should be present in EndpointCatalog")
        assertEquals("GET", keysEndpoint.method)
        assertEquals(EndpointCategory.AUTHENTICATION, keysEndpoint.category)

        // Default header on endpoint doc
        assertTrue(keysEndpoint.headers.containsKey("X-Bootstrap-Key"), "Should have X-Bootstrap-Key in headers")
        val defaultHeaderValue = keysEndpoint.headers["X-Bootstrap-Key"]
        assertNotNull(defaultHeaderValue)
        assertTrue(defaultHeaderValue.isNotEmpty(), "Default header value should not be empty")

        // Description should document configured keys
        assertTrue(
            keysEndpoint.description.contains("BOOTSTRAP_KEYS"),
            "Description should mention BOOTSTRAP_KEYS"
        )

        // Valid sample
        val validSample = keysEndpoint.samples.find { it.label == "Fetch Key" }
        assertNotNull(validSample, "Fetch Key sample should exist")
        assertEquals(defaultHeaderValue, validSample.headers["X-Bootstrap-Key"])

        // Invalid sample for testing breaking auth
        val invalidSample = keysEndpoint.samples.find { it.label == "Invalid Key" }
        assertNotNull(invalidSample, "Invalid Key sample should exist")
        assertEquals("invalid-bootstrap-key", invalidSample.headers["X-Bootstrap-Key"])
    }

    @Test
    fun `non-auth endpoints have empty headers by default`() {
        val endpoints = EndpointCatalog.endpoints
        val monitorEndpoint = endpoints.find { it.path == "/monitor" }
        assertNotNull(monitorEndpoint)
        assertTrue(monitorEndpoint.headers.isEmpty(), "Monitor endpoint should not have default headers")

        val pricesEndpoint = endpoints.find { it.path.startsWith("/api/prices") }
        assertNotNull(pricesEndpoint)
        assertTrue(pricesEndpoint.headers.isEmpty(), "Prices endpoint should not have default headers")
    }
}
