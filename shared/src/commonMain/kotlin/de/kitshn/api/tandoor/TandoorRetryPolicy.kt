package de.kitshn.api.tandoor

import io.ktor.http.HttpMethod
import kotlin.random.Random

/**
 * Decides when a failed Tandoor request is retried and how long to wait in between.
 *
 * Only idempotent methods are retried, so a POST/PATCH is never sent twice.
 * Retried: temporary server answers ([retryableStatusCodes]) and transport failures
 * such as a reset connection. Not retried: timeouts (the user already waited the full
 * timeout) and TLS errors (retrying cannot fix a certificate problem).
 */
data class TandoorRetryPolicy(
    val maxRetries: Int = 2,
    val baseDelayMillis: Long = 500L,
    val maxDelayMillis: Long = 8_000L,
    val retryableStatusCodes: Set<Int> = setOf(408, 429, 502, 503, 504),
    val random: Random = Random.Default
) {

    init {
        require(maxRetries >= 0) { "maxRetries must be >= 0" }
        require(baseDelayMillis >= 0) { "baseDelayMillis must be >= 0" }
        require(maxDelayMillis >= baseDelayMillis) { "maxDelayMillis must be >= baseDelayMillis" }
    }

    fun isIdempotent(method: HttpMethod): Boolean = method in IDEMPOTENT_METHODS

    /**
     * @param attempt number of retries already made (0 after the first failure)
     */
    fun shouldRetry(method: HttpMethod, attempt: Int, error: TandoorRequestsError): Boolean {
        if(attempt >= maxRetries) return false
        if(!isIdempotent(method)) return false

        return when(error.kind) {
            TandoorErrorKind.RateLimited,
            TandoorErrorKind.ServerError,
            TandoorErrorKind.ClientError -> error.statusCode in retryableStatusCodes
            TandoorErrorKind.Network -> true
            TandoorErrorKind.Timeout,
            TandoorErrorKind.Tls,
            TandoorErrorKind.Unauthorized,
            TandoorErrorKind.Forbidden,
            TandoorErrorKind.NotFound -> false
        }
    }

    /**
     * Wait time before retry number [attempt] + 1.
     * A server-provided Retry-After (seconds) wins over exponential backoff.
     */
    fun delayMillis(attempt: Int, retryAfterSeconds: Long?): Long {
        if(retryAfterSeconds != null && retryAfterSeconds >= 0)
            return (retryAfterSeconds * 1000L).coerceAtMost(maxDelayMillis)

        val exponential = baseDelayMillis * (1L shl attempt.coerceIn(0, 20))
        val jitter = if(baseDelayMillis > 0) random.nextLong(0, baseDelayMillis / 2 + 1) else 0L
        return (exponential + jitter).coerceAtMost(maxDelayMillis)
    }

    companion object {
        private val IDEMPOTENT_METHODS = setOf(
            HttpMethod.Get,
            HttpMethod.Head,
            HttpMethod.Put,
            HttpMethod.Delete,
            HttpMethod.Options
        )

        /** Disables retries entirely. */
        val None = TandoorRetryPolicy(maxRetries = 0)
    }
}
