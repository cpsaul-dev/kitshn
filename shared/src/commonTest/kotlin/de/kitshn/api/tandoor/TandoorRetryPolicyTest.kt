package de.kitshn.api.tandoor

import io.ktor.http.HttpMethod
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TandoorRetryPolicyTest {

    private val noJitter = object : Random() {
        override fun nextBits(bitCount: Int): Int = 0
    }

    private val policy = TandoorRetryPolicy(
        maxRetries = 3,
        baseDelayMillis = 500,
        maxDelayMillis = 4_000,
        random = noJitter
    )

    private val networkError = TandoorRequestsError(Exception("connection reset"), null)

    @Test
    fun onlyIdempotentMethodsAreRetried() {
        assertTrue(policy.shouldRetry(HttpMethod.Get, 0, networkError))
        assertTrue(policy.shouldRetry(HttpMethod.Put, 0, networkError))
        assertTrue(policy.shouldRetry(HttpMethod.Delete, 0, networkError))
        assertFalse(policy.shouldRetry(HttpMethod.Post, 0, networkError))
        assertFalse(policy.shouldRetry(HttpMethod.Patch, 0, networkError))
    }

    @Test
    fun stopsAfterMaxRetries() {
        assertTrue(policy.shouldRetry(HttpMethod.Get, 2, networkError))
        assertFalse(policy.shouldRetry(HttpMethod.Get, 3, networkError))
    }

    @Test
    fun timeoutsAreNotRetried() {
        val timeout = TandoorRequestsError(Exception("x", SocketTimeoutLike()), null)
        assertEquals(TandoorErrorKind.Timeout, timeout.kind)
        assertFalse(policy.shouldRetry(HttpMethod.Get, 0, timeout))
    }

    @Test
    fun exponentialBackoffIsCapped() {
        assertEquals(500, policy.delayMillis(0, null))
        assertEquals(1_000, policy.delayMillis(1, null))
        assertEquals(2_000, policy.delayMillis(2, null))
        assertEquals(4_000, policy.delayMillis(3, null))
        assertEquals(4_000, policy.delayMillis(10, null))
    }

    @Test
    fun retryAfterWinsAndIsCapped() {
        assertEquals(2_000, policy.delayMillis(0, 2))
        assertEquals(4_000, policy.delayMillis(0, 120))
    }

    @Test
    fun jitterStaysWithinHalfBaseDelay() {
        val jittered = TandoorRetryPolicy(baseDelayMillis = 500, maxDelayMillis = 10_000)
        repeat(200) {
            val d = jittered.delayMillis(1, null)
            assertTrue(d in 1_000..1_250, "delay $d out of range")
        }
    }

    @Test
    fun noneDisablesRetries() {
        assertFalse(TandoorRetryPolicy.None.shouldRetry(HttpMethod.Get, 0, networkError))
    }

    @Test
    fun invalidConfigurationIsRejected() {
        assertFailsWith<IllegalArgumentException> { TandoorRetryPolicy(maxRetries = -1) }
        assertFailsWith<IllegalArgumentException> {
            TandoorRetryPolicy(baseDelayMillis = 1_000, maxDelayMillis = 10)
        }
    }

    private class SocketTimeoutLike : Exception("read timed out")
}
