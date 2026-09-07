package ee.innov.eprice.util

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class EnvUtilsTest {

    @Test
    fun `getEnv returns default when env var not present and default provided`() {
        val result = getEnv("NON_EXISTENT_VAR_12345", default = "fallback")
        assertEquals("fallback", result)
    }

    @Test
    fun `getEnv throws IllegalStateException when env var not present and no default provided`() {
        val exception = assertThrows<IllegalStateException> {
            getEnv("NON_EXISTENT_VAR_12345")
        }
        assertEquals("NON_EXISTENT_VAR_12345 environment variable is not set.", exception.message)
    }

    @Test
    fun `getEnvList returns default when env var not present and default provided`() {
        val result = getEnvList("NON_EXISTENT_VAR_12345", default = listOf("fallback"))
        assertEquals(listOf("fallback"), result)
    }

    @Test
    fun `getEnvList throws IllegalStateException when env var not present and no default provided`() {
        val exception = assertThrows<IllegalStateException> {
            getEnvList("NON_EXISTENT_VAR_12345")
        }
        assertEquals("NON_EXISTENT_VAR_12345 environment variable is not set.", exception.message)
    }
}
