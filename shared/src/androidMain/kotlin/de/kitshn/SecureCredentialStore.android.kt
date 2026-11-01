package de.kitshn

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import co.touchlab.kermit.Logger
import de.kitshn.api.tandoor.TandoorCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.mp.KoinPlatform.getKoin

private const val TAG = "SecureCredentialStore"
private const val PREFS_FILE = CREDENTIALS_STORE_NAME
private const val KEY_CREDENTIALS = "credentials"

/**
 * Credentials encrypted with AES-256-GCM; the key lives in the Android Keystore.
 *
 * The encrypted file can become unreadable (restored from a backup on another
 * device, Keystore reset after a lock-screen change, OEM Keystore bugs). In that
 * case the file is discarded and recreated: the user has to sign in again, but
 * the app never crashes on start or on login.
 */
actual class SecureCredentialStore actual constructor() {
    private val credentialsFlow = MutableStateFlow<TandoorCredentials?>(null)

    private val context: Context by lazy { getKoin().get<Context>() }

    private var prefs: SharedPreferences? = null

    init {
        credentialsFlow.value = try {
            readCredentials()
        } catch(e: Exception) {
            Logger.e(TAG, e) { "Stored credentials unreadable, resetting encrypted store" }
            resetStore()
            null
        }
    }

    actual fun getCredentials(): Flow<TandoorCredentials?> = credentialsFlow

    actual fun saveCredentials(credentials: TandoorCredentials?) {
        credentialsFlow.value = credentials

        try {
            write(credentials)
        } catch(e: Exception) {
            Logger.e(TAG, e) { "Writing credentials failed, retrying with a fresh encrypted store" }
            resetStore()
            try {
                write(credentials)
            } catch(e2: Exception) {
                // Session stays usable in memory; it will not survive an app restart.
                Logger.e(TAG, e2) { "Credentials could not be persisted" }
            }
        }
    }

    private fun write(credentials: TandoorCredentials?) {
        val editor = openPrefs().edit()
        if(credentials == null) {
            editor.remove(KEY_CREDENTIALS)
        } else {
            editor.putString(KEY_CREDENTIALS, json.encodeToString(credentials))
        }
        editor.apply()
    }

    private fun readCredentials(): TandoorCredentials? {
        val jsonStr = openPrefs().getString(KEY_CREDENTIALS, null) ?: return null
        return json.maybeDecodeFromString<TandoorCredentials>(jsonStr)
    }

    private fun openPrefs(): SharedPreferences {
        prefs?.let { return it }

        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ).also { prefs = it }
    }

    private fun resetStore() {
        prefs = null
        try {
            context.deleteSharedPreferences(PREFS_FILE)
        } catch(e: Exception) {
            Logger.e(TAG, e) { "Deleting encrypted credentials file failed" }
        }
    }
}
