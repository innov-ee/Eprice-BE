package ee.innov.eprice.security

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KeyRotationServiceTest {

    private fun createServiceAt(instant: Instant): KeyRotationService {
        val fixedClock = Clock.fixed(instant, ZoneOffset.UTC)
        return KeyRotationService(clock = fixedClock)
    }

    @Test
    fun `deriveKey produces deterministic hex strings for identical bucket`() {
        val service = KeyRotationService()
        val key1 = service.deriveKey(100L)
        val key2 = service.deriveKey(100L)
        val keyDiff = service.deriveKey(101L)

        assertEquals(key1, key2)
        assertEquals(64, key1.length) // SHA-256 in hex is 64 chars
        assertNotEquals(key1, keyDiff)
    }

    @Test
    fun `isValid accepts key for current bucket`() {
        val now = Instant.parse("2026-09-01T12:00:00Z")
        val service = createServiceAt(now)
        val currentBucket = now.epochSecond / KeyRotationService.BUCKET_DURATION_SECONDS
        val currentKey = service.deriveKey(currentBucket)

        assertTrue(service.isValid(currentKey))
    }

    @Test
    fun `isValid accepts key from previous bucket providing 14-day grace window`() {
        val now = Instant.parse("2026-09-15T12:00:00Z")
        val service = createServiceAt(now)
        val currentBucket = now.epochSecond / KeyRotationService.BUCKET_DURATION_SECONDS
        val previousBucketKey = service.deriveKey(currentBucket - 1)

        assertTrue(service.isValid(previousBucketKey))
    }

    @Test
    fun `isValid accepts key from next bucket for clock skew tolerance`() {
        val now = Instant.parse("2026-09-01T12:00:00Z")
        val service = createServiceAt(now)
        val currentBucket = now.epochSecond / KeyRotationService.BUCKET_DURATION_SECONDS
        val nextBucketKey = service.deriveKey(currentBucket + 1)

        assertTrue(service.isValid(nextBucketKey))
    }

    @Test
    fun `isValid rejects keys older than 1 bucket`() {
        val now = Instant.parse("2026-09-30T12:00:00Z")
        val service = createServiceAt(now)
        val currentBucket = now.epochSecond / KeyRotationService.BUCKET_DURATION_SECONDS
        val expiredKey = service.deriveKey(currentBucket - 2)

        assertFalse(service.isValid(expiredKey))
    }

    @Test
    fun `isValid rejects keys more than 1 bucket in the future`() {
        val now = Instant.parse("2026-09-01T12:00:00Z")
        val service = createServiceAt(now)
        val currentBucket = now.epochSecond / KeyRotationService.BUCKET_DURATION_SECONDS
        val futureKey = service.deriveKey(currentBucket + 2)

        assertFalse(service.isValid(futureKey))
    }

    @Test
    fun `isValid rejects null blank or corrupted keys`() {
        val now = Instant.now()
        val service = createServiceAt(now)
        val currentBucket = now.epochSecond / KeyRotationService.BUCKET_DURATION_SECONDS
        val validKey = service.deriveKey(currentBucket)

        assertFalse(service.isValid(null))
        assertFalse(service.isValid(""))
        assertFalse(service.isValid("    "))
        assertFalse(service.isValid("invalid-key"))
        // Tampered single character
        val tamperedKey = if (validKey.last() == '0') validKey.dropLast(1) + '1' else validKey.dropLast(1) + '0'
        assertFalse(service.isValid(tamperedKey))
    }

    @Test
    fun `getKeyInfo calculates TTL bounded by 7 days recommended refresh`() {
        // Start of bucket
        val bucketStartEpoch = 1000L * KeyRotationService.BUCKET_DURATION_SECONDS
        val nowAtStart = Instant.ofEpochSecond(bucketStartEpoch)
        val serviceAtStart = createServiceAt(nowAtStart)
        val infoStart = serviceAtStart.getKeyInfo()

        // At the start of a 14-day bucket, remaining is 14 days, capped at recommended 7 days (604800s)
        assertEquals(KeyRotationService.RECOMMENDED_CLIENT_TTL_SECONDS, infoStart.ttlSeconds)

        // 13 days into bucket: only 1 day (86400s) remaining in bucket
        val nowNearEnd = Instant.ofEpochSecond(bucketStartEpoch + (13 * 86400L))
        val serviceNearEnd = createServiceAt(nowNearEnd)
        val infoNearEnd = serviceNearEnd.getKeyInfo()
        assertEquals(86400L, infoNearEnd.ttlSeconds)

        // Verify key in KeyInfo matches current bucket key
        val expectedKey = serviceAtStart.deriveKey(1000L)
        assertEquals(expectedKey, infoStart.operationalKey)
    }
}
