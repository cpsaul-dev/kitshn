package de.kitshn.api.tandoor

import de.kitshn.api.tandoor.route.TandoorLoginResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headers
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Username/password sign-in against a fake django-allauth headless server,
 * replaying the responses of a real Tandoor 2 instance.
 */
class TandoorAllAuthRouteTest {

    private val instance = "https://tandoor.test"

    private val csrfCookie = "csrftoken=csrf1; Path=/; SameSite=Lax"

    private val sessionEndpoint = "/_allauth/browser/v1/auth/session"
    private val loginEndpoint = "/_allauth/browser/v1/auth/login"

    private class Server(val client: TandoorClient, val requests: MutableList<HttpRequestData>)

    private fun server(
        identifier: String,
        password: String = "pw",
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
    ): Server {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            handler(request)
        }
        val client = TandoorClient(
            credentials = TandoorCredentials(instance, username = identifier, password = password),
            retryPolicy = TandoorRetryPolicy.None,
            httpClientFactory = { _, _, _ -> HttpClient(engine) }
        )
        return Server(client, requests)
    }

    private fun MockRequestHandleScope.session() = respond(
        """{"status":401,"data":{"flows":[{"id":"login"}]},"meta":{"is_authenticated":false}}""",
        HttpStatusCode.Unauthorized,
        headers {
            append(HttpHeaders.ContentType, "application/json")
            append(HttpHeaders.SetCookie, csrfCookie)
        }
    )

    private fun MockRequestHandleScope.loggedIn() = respond(
        """{"status":200,"meta":{"is_authenticated":true}}""",
        HttpStatusCode.OK,
        headers {
            append(HttpHeaders.ContentType, "application/json")
            append(HttpHeaders.SetCookie, "sessionid=sess42; HttpOnly; Path=/")
            append(HttpHeaders.SetCookie, "csrftoken=csrf2; Path=/")
        }
    )

    private fun MockRequestHandleScope.mismatch(code: String) = respond(
        """{"status":400,"errors":[{"message":"The credentials you specified are not correct.","code":"$code","param":"password"}]}""",
        HttpStatusCode.BadRequest,
        headersOf(HttpHeaders.ContentType, "application/json")
    )

    private fun HttpRequestData.bodyText(): String = (body as? TextContent)?.text.orEmpty()

    @Test
    fun usernameLoginReturnsSessionCookie() = runTest {
        val s = server("saul") { req ->
            when(req.url.encodedPath) {
                sessionEndpoint -> session()
                else -> loggedIn()
            }
        }

        val result = s.client.allAuth.login()

        assertEquals(TandoorLoginResult.Success("sessionid=sess42; csrftoken=csrf2"), result)
        val login = s.requests.last()
        assertEquals(HttpMethod.Post, login.method)
        assertEquals("csrf1", login.headers["X-CSRFToken"])
        assertTrue(login.bodyText().contains("\"username\":\"saul\""))
    }

    @Test
    fun emailIsRetriedWithEmailField() = runTest {
        val s = server(" saul@example.com ") { req ->
            when {
                req.url.encodedPath == sessionEndpoint -> session()
                req.bodyText().contains("\"username\"") -> mismatch("username_password_mismatch")
                else -> loggedIn()
            }
        }

        val result = s.client.allAuth.login()

        assertIs<TandoorLoginResult.Success>(result)
        val logins = s.requests.filter { it.url.encodedPath == loginEndpoint }
        assertEquals(2, logins.size)
        assertTrue(logins[1].bodyText().contains("\"email\":\"saul@example.com\""))
    }

    @Test
    fun wrongPasswordIsReportedAsInvalidCredentials() = runTest {
        val s = server("saul") { req ->
            if(req.url.encodedPath == sessionEndpoint) session() else mismatch("username_password_mismatch")
        }

        val result = assertIs<TandoorLoginResult.Failure>(s.client.allAuth.login())

        assertEquals(TandoorLoginResult.Reason.InvalidCredentials, result.reason)
        assertEquals("username_password_mismatch", result.serverCode)
        assertEquals(400, result.status)
        // a plain username is not retried as email
        assertEquals(1, s.requests.count { it.url.encodedPath == loginEndpoint })
    }

    @Test
    fun wrongPasswordForEmailTriesBothFields() = runTest {
        val s = server("saul@example.com") { req ->
            when {
                req.url.encodedPath == sessionEndpoint -> session()
                req.bodyText().contains("\"username\"") -> mismatch("username_password_mismatch")
                else -> mismatch("email_password_mismatch")
            }
        }

        val result = assertIs<TandoorLoginResult.Failure>(s.client.allAuth.login())

        assertEquals(TandoorLoginResult.Reason.InvalidCredentials, result.reason)
        assertEquals("email_password_mismatch", result.serverCode)
        assertEquals(2, s.requests.count { it.url.encodedPath == loginEndpoint })
    }

    @Test
    fun pendingSecondFactorIsDetected() = runTest {
        val s = server("saul") { req ->
            if(req.url.encodedPath == sessionEndpoint) session() else respond(
                """{"status":401,"data":{"flows":[{"id":"login"},{"id":"mfa_authenticate","is_pending":true}]},"meta":{"is_authenticated":false}}""",
                HttpStatusCode.Unauthorized,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val result = assertIs<TandoorLoginResult.Failure>(s.client.allAuth.login())

        assertEquals(TandoorLoginResult.Reason.SecondFactorRequired, result.reason)
    }

    @Test
    fun missingCsrfCookieIsReported() = runTest {
        val s = server("saul") { _ ->
            respond("<html>not tandoor</html>", HttpStatusCode.NotFound)
        }

        val result = assertIs<TandoorLoginResult.Failure>(s.client.allAuth.login())

        assertEquals(TandoorLoginResult.Reason.CsrfUnavailable, result.reason)
        assertEquals(404, result.status)
        assertEquals(1, s.requests.size)
    }

    @Test
    fun networkFailureIsThrownNotHiddenAsWrongPassword() = runTest {
        val s = server("saul") { _ -> throw Exception("Unable to resolve host") }

        val error = assertFailsWith<TandoorRequestsError> { s.client.allAuth.login() }

        assertEquals(TandoorErrorKind.Network, error.kind)
    }
}
