package ee.innov.eprice.monitoring

import org.junit.jupiter.api.Test
import java.util.Properties
import java.util.UUID
import kotlin.test.assertEquals

class ServiceIdentityTest {
    private val buildInfo = Properties().apply {
        setProperty("commit", "a".repeat(40))
        setProperty("builtAt", "2026-10-14T10:30:00Z")
    }

    @Test
    fun `promotion changes runtime identity but preserves artifact metadata`() {
        val acc = ServiceIdentity.load(mapOf("HOSTNAME" to "acc-container"), buildInfo)
        val prod = ServiceIdentity.load(
            mapOf("HOSTNAME" to "prod-container", "GIT_COMMIT" to "wrong-commit"),
            buildInfo
        )

        assertEquals("acc-container", acc.instanceId)
        assertEquals("prod-container", prod.instanceId)
        assertEquals("a".repeat(40), prod.commit)
        assertEquals(acc.commit, prod.commit)
        assertEquals(acc.builtAt, prod.builtAt)
    }

    @Test
    fun `explicit instance name overrides hostname`() {
        val identity = ServiceIdentity.load(mapOf("INSTANCE_ID" to "acc-1", "HOSTNAME" to "container"), buildInfo)
        assertEquals("acc-1", identity.instanceId)
    }

    @Test
    fun `missing or blank metadata is unknown with a generated instance ID`() {
        val identity = ServiceIdentity.load(mapOf("HOSTNAME" to ""), Properties())
        assertEquals("unknown", identity.commit)
        assertEquals("unknown", identity.builtAt)
        UUID.fromString(identity.instanceId)
    }
}
