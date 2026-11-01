package de.kitshn.crash.acra

import android.app.Application
import kitshn.shared.BuildConfig
import org.acra.config.dialog
import org.acra.config.httpSender
import org.acra.data.StringFormat
import org.acra.ktx.initAcra

/**
 * Crash reports are only sent when a receiver is configured in kitshn.properties
 * (acra.http.uri). With an empty value nothing is initialized or sent.
 */
fun Application.initKitshnAcra() {
    if(BuildConfig.ACRA_HTTP_URI.isBlank()) return

    initAcra {
        buildConfigClass = BuildConfig::class.java
        reportFormat = StringFormat.JSON

        excludeMatchingSharedPreferencesKeys = listOf(
            "tandoor_credentials"
        )

        httpSender {
            uri = BuildConfig.ACRA_HTTP_URI
            basicAuthLogin = BuildConfig.ACRA_HTTP_BASIC_AUTH_LOGIN
            basicAuthPassword = BuildConfig.ACRA_HTTP_BASIC_AUTH_PASSWORD
        }

        dialog {
            reportDialogClass = AcraCrashReportDialog::class.java
            enabled = true
        }
    }
}