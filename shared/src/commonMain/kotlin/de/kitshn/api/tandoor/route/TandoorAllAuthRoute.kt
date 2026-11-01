package de.kitshn.api.tandoor.route

import de.kitshn.api.tandoor.TandoorClient
import de.kitshn.api.tandoor.TandoorRequestsError
import de.kitshn.api.tandoor.reqAny
import de.kitshn.json
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.parseServerSetCookieHeader
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Outcome of a username/password sign-in. */
sealed interface TandoorLoginResult {

    /** @property cookie `credentials.cookie`-compatible session cookie string */
    data class Success(val cookie: String) : TandoorLoginResult

    /**
     * @property serverMessage human-readable message from the server, when it sent one
     * @property serverCode allauth error code (e.g. `username_password_mismatch`)
     */
    data class Failure(
        val reason: Reason,
        val serverMessage: String? = null,
        val serverCode: String? = null,
        val status: Int? = null
    ) : TandoorLoginResult

    enum class Reason {
        /** Wrong username/email or password. */
        InvalidCredentials,

        /** Password accepted, but the account requires a second factor (TOTP/WebAuthn). */
        SecondFactorRequired,

        /** The server did not hand out a CSRF cookie — allauth headless missing or blocked by a proxy. */
        CsrfUnavailable,

        /** Anything else (unexpected status, missing session cookie, …). */
        Unexpected
    }
}

/**
 * Uses django-allauth headless API (https://docs.allauth.org/en/latest/headless/),
 * which replaces Tandoor's outdated `api-token-auth` endpoint.
 *
 * This deliberately drives the "browser" client, not the "app" client: the
 * X-Session-Token header does not work for auth (yet), so the cookie approach
 * is used instead.
 *
 * Tandoor accepts both username and email (`login_methods: ["username", "email"]`),
 * but allauth needs the matching field name. The identifier is sent as `username`
 * first; when that is rejected and it looks like an email address, it is retried
 * as `email`.
 */
class TandoorAllAuthRoute(client: TandoorClient) : TandoorBaseRoute(client) {

    private fun extractCookies(response: HttpResponse): Map<String, String> =
        response.headers.getAll(HttpHeaders.SetCookie).orEmpty().associate {
            val cookie = parseServerSetCookieHeader(it)
            cookie.name to cookie.value
        }

    suspend fun login(): TandoorLoginResult {
        // Django's CSRF middleware requires a token to already be present
        // before it'll accept the login POST below, so prime one first.
        val csrfResponse = try {
            client.reqAny("@_allauth/browser/v1/auth/session", HttpMethod.Get)
        } catch(e: TandoorRequestsError) {
            e.response ?: throw e
        }

        val csrfToken = extractCookies(csrfResponse)["csrftoken"]
            ?: return TandoorLoginResult.Failure(
                TandoorLoginResult.Reason.CsrfUnavailable,
                status = csrfResponse.status.value
            )

        val identifier = client.credentials.username.trim()

        val asUsername = attempt("username", identifier, csrfToken)
        if(asUsername.isInvalidCredentials() && identifier.looksLikeEmail())
            return attempt("email", identifier, csrfToken)

        return asUsername
    }

    private suspend fun attempt(
        field: String,
        identifier: String,
        csrfToken: String
    ): TandoorLoginResult {
        val obj = buildJsonObject {
            put(field, identifier)
            put("password", client.credentials.password)
        }

        val response = try {
            client.reqAny(
                endpoint = "@_allauth/browser/v1/auth/login",
                _method = HttpMethod.Post,
                data = obj.toString(),
                contentType = ContentType.Application.Json,
                custom = {
                    headers {
                        append("Cookie", "csrftoken=$csrfToken")
                        append("X-CSRFToken", csrfToken)
                    }
                }
            )
        } catch(e: TandoorRequestsError) {
            // transport failures have no response: let the caller show the network error
            val errorResponse = e.response ?: throw e
            return parseFailure(errorResponse)
        }

        val cookies = extractCookies(response)
        val sessionId = cookies["sessionid"]
            ?: return TandoorLoginResult.Failure(
                TandoorLoginResult.Reason.Unexpected,
                status = response.status.value
            )
        val newCsrfToken = cookies["csrftoken"] ?: csrfToken

        return TandoorLoginResult.Success("sessionid=$sessionId; csrftoken=$newCsrfToken")
    }

    private suspend fun parseFailure(response: HttpResponse): TandoorLoginResult.Failure {
        val status = response.status.value
        val body = try {
            json.parseToJsonElement(response.bodyAsText()).jsonObject
        } catch(_: Exception) {
            null
        }

        val firstError = body?.get("errors")?.let { it as? JsonArray }
            ?.firstOrNull()?.let { it as? JsonObject }
        val message = firstError?.get("message")?.jsonPrimitive?.content
        val code = firstError?.get("code")?.jsonPrimitive?.content

        val reason = when {
            status == 401 && body.hasPendingFlow("mfa_authenticate") ->
                TandoorLoginResult.Reason.SecondFactorRequired

            status == 400 && code in INVALID_CREDENTIAL_CODES ->
                TandoorLoginResult.Reason.InvalidCredentials

            else -> TandoorLoginResult.Reason.Unexpected
        }

        return TandoorLoginResult.Failure(reason, message, code, status)
    }

    private fun JsonObject?.hasPendingFlow(id: String): Boolean {
        val flows = this?.get("data")?.let { it as? JsonObject }?.get("flows") ?: return false
        return try {
            flows.jsonArray.any {
                val flow = it.jsonObject
                flow["id"]?.jsonPrimitive?.content == id &&
                        flow["is_pending"]?.jsonPrimitive?.content == "true"
            }
        } catch(_: IllegalArgumentException) {
            false
        }
    }

    private fun TandoorLoginResult.isInvalidCredentials() =
        this is TandoorLoginResult.Failure && reason == TandoorLoginResult.Reason.InvalidCredentials

    private fun String.looksLikeEmail() = EMAIL_REGEX.matches(this)

    private companion object {
        val INVALID_CREDENTIAL_CODES = setOf(
            "username_password_mismatch",
            "email_password_mismatch",
            "invalid"
        )

        val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}
