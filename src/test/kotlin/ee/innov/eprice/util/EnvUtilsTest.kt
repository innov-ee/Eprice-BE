package ee.innov.eprice.util

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class EnvUtilsTest {

    @Test
    fun `getEnvList returns default when env var not present`() {
        val result = getEnvList("NON_EXISTENT_VAR_12345", default = listOf("fallback"))
        assertEquals(listOf("fallback"), result)
    }

    @Test
    fun `getEnvList returns empty list default when not specified and env var not present`() {
        val result = getEnvList("NON_EXISTENT_VAR_12345")
        assertEquals(emptyList(), result)
    }
}
