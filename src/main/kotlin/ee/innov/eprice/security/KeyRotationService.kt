package ee.innov.eprice.security

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class KeyRotationInfo(
    val operationalKey: String,
    val ttlSeconds: Long,
    val expiresAtUtc: String
)

class KeyRotationService(
    private val masterSecret: ByteArray = (System.getenv("MASTER_SECRET") ?: "dev-eprice-master-secret-change-in-production-32b")
        .toByteArray(StandardCharsets.UTF_8),
    private val clock: Clock = Clock.systemUTC()
) {
    companion object {
        // 14-day key bucket (2 weeks = 14 * 86400 = 1,209,600 seconds)
        const val BUCKET_DURATION_SECONDS = 14L * 86400L // 1,209,600s

        // Recommended client proactive refresh interval: 7 days (1 week = 604,800s)
        const val RECOMMENDED_CLIENT_TTL_SECONDS = 7L * 86400L // 604,800s
    }

    /**
     * Derives the current key and returns TTL metadata for client consumption.
     */
    fun getKeyInfo(now: Instant = clock.instant()): KeyRotationInfo {
        val epochSeconds = now.epochSecond
        val currentBucket = epochSeconds / BUCKET_DURATION_SECONDS
        val operationalKey = deriveKey(currentBucket)

        val secondsIntoBucket = epochSeconds % BUCKET_DURATION_SECONDS
        val secondsUntilBucketExpires = BUCKET_DURATION_SECONDS - secondsIntoBucket

        // TTL given to client: minimum of 7 days or remaining seconds in current bucket
        val ttl = minOf(RECOMMENDED_CLIENT_TTL_SECONDS, secondsUntilBucketExpires)
        val expirationInstant = now.plusSeconds(secondsUntilBucketExpires + BUCKET_DURATION_SECONDS) // 14-day grace

        return KeyRotationInfo(
            operationalKey = operationalKey,
            ttlSeconds = ttl,
            expiresAtUtc = expirationInstant.toString()
        )
    }

    /**
     * Validates whether a provided key matches the current bucket, the previous bucket (14-day grace),
     * or the next bucket (clock drift / future skew). Uses constant-time comparison to prevent timing attacks.
     */
    fun isValid(key: String?, now: Instant = clock.instant()): Boolean {
        if (key.isNullOrBlank()) return false

        val currentBucket = now.epochSecond / BUCKET_DURATION_SECONDS
        val keyBytes = key.toByteArray(StandardCharsets.UTF_8)

        // Check current, previous (-1 for grace), and next (+1 for clock drift)
        val validBuckets = listOf(currentBucket, currentBucket - 1, currentBucket + 1)

        for (bucket in validBuckets) {
            val candidateBytes = deriveKey(bucket).toByteArray(StandardCharsets.UTF_8)
            if (MessageDigest.isEqual(keyBytes, candidateBytes)) {
                return true
            }
        }
        return false
    }

    /**
     * Deterministic HMAC-SHA256 calculation for a bucket index.
     */
    internal fun deriveKey(bucket: Long): String {
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(masterSecret, "HmacSHA256"))
        }
        val hash = mac.doFinal(bucket.toString().toByteArray(StandardCharsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
