package ee.innov.eprice.monitoring

import org.junit.jupiter.api.Test
import java.util.Properties
import kotlin.test.assertEquals

class ServiceIdentityTest {
    private val buildInfo = Properties().apply {
        setProperty("commit", "a".repeat(40))
        setProperty("builtAt", "2026-10-14T10:30:00Z")
    }

    @Test
    fun `loads artifact metadata`() {
        val identity = ServiceIdentity.load(buildInfo)

        assertEquals("a".repeat(40), identity.commit)
        assertEquals("2026-10-14T10:30:00Z", identity.builtAt)
    }

    @Test
    fun `missing or blank metadata is unknown`() {
        val identity = ServiceIdentity.load(Properties().apply { setProperty("builtAt", " ") })
        assertEquals("unknown", identity.commit)
        assertEquals("unknown", identity.builtAt)
    }
}
