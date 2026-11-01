package de.kitshn.api.tandoor

import de.kitshn.api.tandoor.route.TandoorAIImportRoute
import de.kitshn.api.tandoor.route.TandoorAIProviderRoute
import de.kitshn.api.tandoor.route.TandoorAllAuthRoute
import de.kitshn.api.tandoor.route.TandoorCookLogRoute
import de.kitshn.api.tandoor.route.TandoorFoodRoute
import de.kitshn.api.tandoor.route.TandoorKeywordRoute
import de.kitshn.api.tandoor.route.TandoorMealPlanRoute
import de.kitshn.api.tandoor.route.TandoorMealTypeRoute
import de.kitshn.api.tandoor.route.TandoorRecipeBookRoute
import de.kitshn.api.tandoor.route.TandoorRecipeFromSourceRoute
import de.kitshn.api.tandoor.route.TandoorRecipeRoute
import de.kitshn.api.tandoor.route.TandoorServerSettingsRoute
import de.kitshn.api.tandoor.route.TandoorShoppingRoute
import de.kitshn.api.tandoor.route.TandoorSpaceRoute
import de.kitshn.api.tandoor.route.TandoorStepRoute
import de.kitshn.api.tandoor.route.TandoorSupermarketRoute
import de.kitshn.api.tandoor.route.TandoorUnitRoute
import de.kitshn.api.tandoor.route.TandoorUserPreferenceRoute
import de.kitshn.api.tandoor.route.TandoorUserRoute
import de.kitshn.isTlsException
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

@Serializable
data class TandoorTimeoutSettings(
    val shortTimeout: Long = 10000L,
    val longTimeout: Long = 60000L
)

@Serializable
data class TandoorCredentialsCustomHeader(
    var field: String,
    var value: String
)

@Serializable
data class TandoorCredentialsToken(
    val token: String,
    val scope: String,
    val expires: String
)

@Serializable
data class TandoorCredentials(
    val instanceUrl: String,
    var username: String = "",
    val password: String = "",
    var token: TandoorCredentialsToken? = null,
    var cookie: String? = null,
    val customHeaders: List<TandoorCredentialsCustomHeader> = listOf(),
    val mtlsCertificateAlias: String? = null,
    val mtlsCertificateData: String? = null,
    val mtlsCertificatePassword: String? = null,
)

/**
 * Builds the HTTP client for one timeout. Production uses the platform engine
 * ([createTandoorHttpClient]); tests pass a client backed by a mock engine.
 */
typealias TandoorHttpClientFactory = (
    credentials: TandoorCredentials,
    timeoutMillis: Long,
    onCertificateRequested: () -> Unit
) -> HttpClient

class TandoorClient(
    val credentials: TandoorCredentials,
    var timeoutSettings: TandoorTimeoutSettings = TandoorTimeoutSettings(),
    var retryPolicy: TandoorRetryPolicy = TandoorRetryPolicy(),
    private val httpClientFactory: TandoorHttpClientFactory = ::createTandoorHttpClient
) {

    var certificateRequested: Boolean = false
    var tlsHandshakeFailed: Boolean = false

    /** `true` when the last connection failure was a cert error */
    val needsClientCertificate: Boolean get() = certificateRequested || tlsHandshakeFailed

    var httpClient = httpClientFactory(credentials, timeoutSettings.shortTimeout) {
        certificateRequested = true
    }

    var longHttpClient = httpClientFactory(credentials, timeoutSettings.longTimeout) {
        certificateRequested = true
    }

    fun configureTimeouts(settings: TandoorTimeoutSettings) {
        if(settings == timeoutSettings) return
        timeoutSettings = settings

        httpClient.close()
        longHttpClient.close()

        httpClient = httpClientFactory(credentials, timeoutSettings.shortTimeout) {
            certificateRequested = true
        }

        longHttpClient = httpClientFactory(credentials, timeoutSettings.longTimeout) {
            certificateRequested = true
        }
    }

    val container = TandoorContainer(this)
    val media = TandoorMedia(this)

    private val _lastCallSucceeded = MutableStateFlow(true)
    /**
     * Reflects the most recent network-layer outcome of any request through this client.
     * - 2xx response → `true`
     * - transport failure (no response, IOException-class) → `false`
     * - 4xx/5xx → left unchanged (server is reachable; it just refused)
     *
     * Combined with [NetworkObserver.isConnected] to derive [TandoorSession.isOnline].
     */
    val lastCallSucceeded: StateFlow<Boolean> = _lastCallSucceeded.asStateFlow()

    internal fun reportCallSuccess() { _lastCallSucceeded.value = true }
    internal fun reportCallNetworkFailure() { _lastCallSucceeded.value = false }

    val aiImport = TandoorAIImportRoute(this)
    val aiProvider = TandoorAIProviderRoute(this)
    val allAuth = TandoorAllAuthRoute(this)
    val cookLog = TandoorCookLogRoute(this)
    val keyword = TandoorKeywordRoute(this)
    val food = TandoorFoodRoute(this)
    val mealPlan = TandoorMealPlanRoute(this)
    val mealType = TandoorMealTypeRoute(this)
    val recipe = TandoorRecipeRoute(this)
    val recipeBook = TandoorRecipeBookRoute(this)
    val recipeFromSource = TandoorRecipeFromSourceRoute(this)
    val shopping = TandoorShoppingRoute(this)
    val space = TandoorSpaceRoute(this)
    val step = TandoorStepRoute(this)
    val supermarket = TandoorSupermarketRoute(this)
    val unit = TandoorUnitRoute(this)
    val user = TandoorUserRoute(this)
    val userPreference = TandoorUserPreferenceRoute(this)

    val serverSettings = TandoorServerSettingsRoute(this)

    suspend fun testConnection(ignoreAuth: Boolean): Boolean {
        try {
            getObject("/")
            return true
        } catch(e: TandoorRequestsError) {
            if (e.isTlsException) tlsHandshakeFailed = true
            if(ignoreAuth) return e.response?.status == HttpStatusCode.Forbidden
            return false
        } catch(_: SerializationException) {
            return false
        }
    }
}
