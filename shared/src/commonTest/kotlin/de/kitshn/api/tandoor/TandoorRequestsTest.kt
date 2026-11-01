package de.kitshn.api.tandoor

import de.kitshn.api.tandoor.model.TandoorPagedResponse
import de.kitshn.json
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tandoor API behavior against a scripted fake server (Ktor MockEngine).
 */
class TandoorRequestsTest {

    private val instance = "https://tandoor.test"
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private val noJitter = object : Random() {
        override fun nextBits(bitCount: Int): Int = 0
    }

    private class FakeServer(
        val client: TandoorClient,
        val requests: MutableList<HttpRequestData>
    )

    private fun fakeServer(
        retryPolicy: TandoorRetryPolicy = TandoorRetryPolicy(random = noJitter),
        credentials: TandoorCredentials = TandoorCredentials(
            instanceUrl = instance,
            token = TandoorCredentialsToken("secret-token", "read write", "never"),
            customHeaders = listOf(TandoorCredentialsCustomHeader("CF-Access-Client-Id", "abc"))
        ),
        handler: suspend MockRequestHandleScope.(request: HttpRequestData, call: Int) -> HttpResponseData
    ): FakeServer {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            handler(request, requests.size)
        }

        val client = TandoorClient(
            credentials = credentials,
            retryPolicy = retryPolicy,
            httpClientFactory = { _, timeout, _ ->
                HttpClient(engine) {
                    install(HttpTimeout) {
                        requestTimeoutMillis = timeout
                    }
                }
            }
        )
        return FakeServer(client, requests)
    }

    private fun MockRequestHandleScope.ok(body: String) =
        respond(body, HttpStatusCode.OK, jsonHeaders)

    // --- auth & request shape -------------------------------------------------

    @Test
    fun sendsAuthAndCustomHeadersToApiPath() = runTest {
        val server = fakeServer { _, _ -> ok("""{"ok":true}""") }

        val result = server.client.getObject("/recipe/1/")

        assertEquals("true", result["ok"]!!.jsonPrimitive.content)
        val request = server.requests.single()
        assertEquals("$instance/api/recipe/1/", request.url.toString())
        assertEquals("Bearer secret-token", request.headers[HttpHeaders.Authorization])
        assertEquals("abc", request.headers["CF-Access-Client-Id"])
        assertEquals(instance, request.headers[HttpHeaders.Referrer])
        assertTrue(server.client.lastCallSucceeded.value)
    }

    @Test
    fun atPrefixedEndpointsSkipApiPath() = runTest {
        val server = fakeServer { _, _ -> ok("{}") }

        server.client.getObject("@media/recipes/1.jpg")

        assertEquals("$instance/media/recipes/1.jpg", server.requests.single().url.toString())
    }

    @Test
    fun cookieSessionSendsCsrfToken() = runTest {
        val server = fakeServer(
            credentials = TandoorCredentials(
                instanceUrl = instance,
                cookie = "sessionid=s1; csrftoken=csrf123; other=x"
            )
        ) { _, _ -> ok("{}") }

        server.client.getObject("/space/")

        val request = server.requests.single()
        assertEquals("csrf123", request.headers["X-CSRFTOKEN"])
        assertNull(request.headers[HttpHeaders.Authorization])
    }

    // --- typed errors ---------------------------------------------------------

    @Test
    fun unauthorizedIsTypedAndNotRetried() = runTest {
        val server = fakeServer { _, _ -> respondError(HttpStatusCode.Unauthorized) }

        val error = assertFailsWith<TandoorRequestsError> { server.client.getObject("/user/") }

        assertEquals(TandoorErrorKind.Unauthorized, error.kind)
        assertEquals(401, error.statusCode)
        assertEquals(1, server.requests.size)
        // 401 means the server answered; the app must not show "offline".
        assertTrue(server.client.lastCallSucceeded.value)
    }

    @Test
    fun forbiddenAndNotFoundAreTyped() = runTest {
        val server = fakeServer { _, call ->
            if(call == 1) respondError(HttpStatusCode.Forbidden) else respondError(HttpStatusCode.NotFound)
        }

        val forbidden = assertFailsWith<TandoorRequestsError> { server.client.getObject("/space/") }
        val notFound = assertFailsWith<TandoorRequestsError> { server.client.getObject("/recipe/999/") }

        assertEquals(TandoorErrorKind.Forbidden, forbidden.kind)
        assertEquals(TandoorErrorKind.NotFound, notFound.kind)
        assertEquals(2, server.requests.size)
    }

    // --- retries --------------------------------------------------------------

    @Test
    fun getRecoversFromTransient503() = runTest {
        val server = fakeServer { _, call ->
            if(call < 3) respondError(HttpStatusCode.ServiceUnavailable) else ok("""{"id":1}""")
        }

        val result = server.client.getObject("/recipe/1/")

        assertEquals("1", result["id"]!!.jsonPrimitive.content)
        assertEquals(3, server.requests.size)
        // backoff 500ms + 1000ms (virtual time)
        assertEquals(1_500, currentTime)
    }

    @Test
    fun persistent500GivesUpAfterMaxRetries() = runTest {
        val server = fakeServer { _, _ -> respondError(HttpStatusCode.BadGateway) }

        val error = assertFailsWith<TandoorRequestsError> { server.client.getObject("/recipe/") }

        assertEquals(TandoorErrorKind.ServerError, error.kind)
        assertEquals(3, server.requests.size) // 1 + 2 retries (default policy)
    }

    @Test
    fun internalServerErrorIsNotRetried() = runTest {
        val server = fakeServer { _, _ -> respondError(HttpStatusCode.InternalServerError) }

        val error = assertFailsWith<TandoorRequestsError> { server.client.getObject("/recipe/") }

        assertEquals(TandoorErrorKind.ServerError, error.kind)
        assertEquals(1, server.requests.size)
    }

    @Test
    fun postIsNeverRetried() = runTest {
        val server = fakeServer { _, _ -> respondError(HttpStatusCode.ServiceUnavailable) }

        assertFailsWith<TandoorRequestsError> {
            server.client.postObject("/recipe/", buildJsonObject { put("name", "Paella") })
        }

        assertEquals(1, server.requests.size)
        assertEquals(HttpMethod.Post, server.requests.single().method)
    }

    @Test
    fun rateLimitHonorsRetryAfter() = runTest {
        val server = fakeServer { _, call ->
            if(call == 1) respond(
                "",
                HttpStatusCode.TooManyRequests,
                headersOf(HttpHeaders.RetryAfter, "3")
            ) else ok("{}")
        }

        server.client.getObject("/food/")

        assertEquals(2, server.requests.size)
        assertEquals(3_000, currentTime)
    }

    @Test
    fun transportFailureIsRetriedAndOnlineStateRecovers() = runTest {
        val server = fakeServer { _, call ->
            if(call == 1) throw Exception("Connection reset by peer") else ok("{}")
        }

        server.client.getObject("/shopping-list-entry/")

        assertEquals(2, server.requests.size)
        assertTrue(server.client.lastCallSucceeded.value)
    }

    @Test
    fun transportFailureMarksClientOffline() = runTest {
        val server = fakeServer(retryPolicy = TandoorRetryPolicy.None) { _, _ ->
            throw Exception("Unable to resolve host")
        }

        val error = assertFailsWith<TandoorRequestsError> { server.client.getObject("/") }

        assertEquals(TandoorErrorKind.Network, error.kind)
        assertTrue(error.isNetworkFailure)
        assertFalse(server.client.lastCallSucceeded.value)
    }

    @Test
    fun slowServerTimesOutWithoutRetry() = runTest {
        withContext(Dispatchers.Default) {
            val server = fakeServer { _, _ ->
                delay(5_000)
                ok("{}")
            }
            server.client.configureTimeouts(TandoorTimeoutSettings(shortTimeout = 200, longTimeout = 400))

            val error = assertFailsWith<TandoorRequestsError> { server.client.getObject("/recipe/") }

            assertEquals(TandoorErrorKind.Timeout, error.kind)
            assertEquals(1, server.requests.size)
        }
    }

    // --- schema ---------------------------------------------------------------

    @Test
    fun typedRouteDecodesUsersIgnoringUnknownFields() = runTest {
        val server = fakeServer { _, _ ->
            ok(
                """[
                  {"id":1,"username":"saul","display_name":"Saúl","groups":[{"id":1}],"new_field_v3":true},
                  {"id":2,"username":"guest","first_name":null}
                ]"""
            )
        }

        val users = server.client.user.getUsers()

        assertEquals(2, users.size)
        assertEquals("Saúl", users[0].display_name)
        assertNull(users[1].first_name)
        assertEquals("", users[1].display_name)
    }

    @Test
    fun pagedResponseToleratesMissingAndExtraFields() {
        val page = json.decodeFromString<TandoorPagedResponse<Int>>(
            """{"count":3,"next":null,"previous":"https://x/api/recipe/?page=1","results":[1,2,3],"extra":{}}"""
        )

        assertEquals(3, page.count)
        assertNull(page.next)
        assertNull(page.timestamp)
        assertEquals(listOf(1, 2, 3), page.results)
    }
}
