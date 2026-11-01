package de.kitshn.api.tandoor

import co.touchlab.kermit.Logger
import de.kitshn.isTlsException
import de.kitshn.json
import de.kitshn.redactForRelease
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.FormBuilder
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Coarse error category, so callers can react without inspecting status codes.
 */
enum class TandoorErrorKind {
    /** 401 — token missing, expired or revoked. */
    Unauthorized,

    /** 403 — authenticated, but not allowed (or missing CSRF/space permission). */
    Forbidden,

    /** 404 */
    NotFound,

    /** 429 */
    RateLimited,

    /** 5xx */
    ServerError,

    /** Any other 4xx. */
    ClientError,

    /** No answer within the configured timeout. */
    Timeout,

    /** TLS handshake / certificate problem. */
    Tls,

    /** Any other transport failure (DNS, refused, reset, offline, …). */
    Network
}

class TandoorRequestsError(
    val throwable: Throwable?,
    val response: HttpResponse?,
    private val additionalText: String = "",
    val overrideMessage: String? = null
) : Exception(throwable) {
    override val message: String
        get() = overrideMessage ?: ((
                throwable?.message
                    ?: (response?.request?.url.toString() + " | " + response?.request?.method.toString() + " | " + response?.status.toString())
                )
                + additionalText + throwable?.stackTraceToString())

    /** Server answered with 4xx/5xx — reachable, but refused. */
    val isServerRefused: Boolean get() = response != null

    /** No response at all — transport failure (DNS, connect, timeout, TLS, …). */
    val isNetworkFailure: Boolean get() = response == null

    /** HTTP status code, or null for transport failures. */
    val statusCode: Int? get() = response?.status?.value

    val kind: TandoorErrorKind
        get() {
            val status = statusCode
            if(status != null) return when(status) {
                401 -> TandoorErrorKind.Unauthorized
                403 -> TandoorErrorKind.Forbidden
                404 -> TandoorErrorKind.NotFound
                429 -> TandoorErrorKind.RateLimited
                in 500..599 -> TandoorErrorKind.ServerError
                else -> TandoorErrorKind.ClientError
            }

            val cause = throwable ?: return TandoorErrorKind.Network
            return when {
                cause.isTimeoutException() -> TandoorErrorKind.Timeout
                cause.isTlsException -> TandoorErrorKind.Tls
                else -> TandoorErrorKind.Network
            }
        }

    /** Server-provided Retry-After in seconds (numeric form only). */
    val retryAfterSeconds: Long?
        get() = response?.headers?.get(HttpHeaders.RetryAfter)?.trim()?.toLongOrNull()
}

private fun Throwable.isTimeoutException(): Boolean =
    generateSequence(this) { it.cause }.any {
        it is HttpRequestTimeoutException || it::class.simpleName.orEmpty().contains("Timeout")
    }

private fun TandoorClient.resolveUrl(endpoint: String): String =
    if(endpoint.startsWith("@")) {
        "${credentials.instanceUrl}/${endpoint.substring(1)}"
    } else {
        "${credentials.instanceUrl}/api${endpoint}"
    }

private fun HttpRequestBuilder.applyTandoorAuth(credentials: TandoorCredentials) {
    val token = credentials.token
    val cookie = credentials.cookie

    headers {
        if(token != null) set(HttpHeaders.Authorization, "Bearer ${token.token}")

        if(cookie != null) {
            val csrfToken =
                cookie.replace(Regex(".*csrftoken="), "").replace(Regex(";.*"), "")
            set("X-CSRFTOKEN", csrfToken)
            set(HttpHeaders.Cookie, cookie)
        }

        credentials.customHeaders.forEach {
            set(it.field, it.value)
        }

        set(HttpHeaders.Referrer, credentials.instanceUrl)
    }
}

/**
 * Sends one request with auth headers, maps failures to [TandoorRequestsError]
 * and retries according to [TandoorClient.retryPolicy].
 */
private suspend fun TandoorClient.execute(
    endpoint: String,
    method: HttpMethod,
    client: HttpClient,
    configure: HttpRequestBuilder.() -> Unit
): HttpResponse {
    val url = resolveUrl(endpoint)
    var attempt = 0

    while(true) {
        Logger.d("TandoorRequests") { "Method: $method, URL: ${credentials.instanceUrl.redactForRelease()}/api${endpoint}, attempt: ${attempt + 1}" }

        val error = try {
            val response = client.request {
                url(url)
                this.method = method
                applyTandoorAuth(credentials)
                // explicit receiver: a bare build() would resolve to HttpRequestBuilder.build()
                configure(this)
            }

            // 4xx/5xx still means Tandoor answered — server reachable, just refused.
            reportCallSuccess()
            if(response.status.isSuccess()) return response

            TandoorRequestsError(null, response)
        } catch(e: CancellationException) {
            throw e
        } catch(e: TandoorRequestsError) {
            throw e
        } catch(e: Exception) {
            Logger.w("TandoorRequests.kt") { "Transport failure: ${e::class.simpleName}: ${e.message}" }
            reportCallNetworkFailure()
            TandoorRequestsError(e, null)
        }

        if(!retryPolicy.shouldRetry(method, attempt, error)) throw error

        val wait = retryPolicy.delayMillis(attempt, error.retryAfterSeconds)
        Logger.i("TandoorRequests") { "Retrying $method after ${error.kind} in ${wait}ms" }
        delay(wait)
        attempt++
    }
}

suspend fun TandoorClient.reqAny(
    endpoint: String,
    _method: HttpMethod,
    data: Any? = null,
    contentType: ContentType? = null,
    custom: HttpRequestBuilder.() -> Unit = { },
    customHttpClient: HttpClient? = null
): HttpResponse = execute(endpoint, _method, customHttpClient ?: httpClient) {
    if(data != null && contentType != null) {
        setBody(data.toString())
        contentType(contentType)
    }

    custom()
}

suspend fun TandoorClient.req(
    endpoint: String,
    _method: HttpMethod,
    data: JsonObject? = null
): HttpResponse {
    return reqAny(endpoint, _method, data?.toString(), ContentType.Application.Json)
}

suspend fun TandoorClient.reqArray(
    endpoint: String,
    method: HttpMethod,
    data: JsonArray? = null
): JsonArray {
    return json.decodeFromString(
        reqAny(
            endpoint,
            method,
            data?.toString(),
            ContentType.Application.Json
        ).readRawBytes().decodeToString()
    )
}

suspend fun TandoorClient.reqObject(
    endpoint: String,
    method: HttpMethod,
    data: JsonObject? = null
): JsonObject {
    return json.decodeFromString(
        reqAny(
            endpoint,
            method,
            data?.toString(),
            ContentType.Application.Json
        ).readRawBytes().decodeToString()
    )
}

suspend fun TandoorClient.reqByteArray(
    endpoint: String,
    method: HttpMethod,
    data: JsonObject? = null
): ByteArray {
    return reqAny(endpoint, method, data?.toString(), ContentType.Application.Json).readRawBytes()
}

suspend fun TandoorClient.reqMultipart(
    endpoint: String,
    _method: HttpMethod,
    _formData: FormBuilder.() -> Unit
): HttpResponse = execute(endpoint, _method, longHttpClient) {
    setBody(
        MultiPartFormDataContent(
            formData(_formData)
        )
    )
}

suspend fun TandoorClient.getObject(endpoint: String) = reqObject(endpoint, HttpMethod.Get)
suspend fun TandoorClient.getArray(endpoint: String) = reqArray(endpoint, HttpMethod.Get)
suspend fun TandoorClient.getByteArray(endpoint: String) = reqByteArray(endpoint, HttpMethod.Get)

suspend fun TandoorClient.postObject(endpoint: String, data: JsonObject) =
    reqObject(endpoint, HttpMethod.Post, data)

suspend fun TandoorClient.postArray(endpoint: String, data: JsonArray) =
    reqArray(endpoint, HttpMethod.Post, data)

suspend fun TandoorClient.put(endpoint: String, data: JsonObject) =
    req(endpoint, HttpMethod.Put, data)

suspend fun TandoorClient.putObject(endpoint: String, data: JsonObject) =
    reqObject(endpoint, HttpMethod.Put, data)

suspend fun TandoorClient.putArray(endpoint: String, data: JsonArray) =
    reqArray(endpoint, HttpMethod.Put, data)

suspend fun TandoorClient.postMultipart(endpoint: String, _formData: FormBuilder.() -> Unit) =
    reqMultipart(endpoint, HttpMethod.Post, _formData)

suspend fun TandoorClient.putMultipart(endpoint: String, _formData: FormBuilder.() -> Unit) =
    reqMultipart(endpoint, HttpMethod.Put, _formData)

suspend fun TandoorClient.patchObject(endpoint: String, data: JsonObject) =
    reqObject(endpoint, HttpMethod.Patch, data)

suspend fun TandoorClient.patchArray(endpoint: String, data: JsonArray) =
    reqArray(endpoint, HttpMethod.Patch, data)

suspend fun TandoorClient.delete(endpoint: String) =
    req(endpoint, HttpMethod.Delete)