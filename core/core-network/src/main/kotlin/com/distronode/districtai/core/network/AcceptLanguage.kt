package com.distronode.districtai.core.network

import okhttp3.Interceptor
import okhttp3.Response

/** The `Accept-Language` the app sends when its screens are in English. */
const val ACCEPT_LANGUAGE_EN: String = "en"

/** The `Accept-Language` the app sends when its screens are in Canadian French. */
const val ACCEPT_LANGUAGE_FR_CA: String = "fr-CA"

/**
 * Puts the app's language on every request as `Accept-Language`: [ACCEPT_LANGUAGE_FR_CA] or
 * [ACCEPT_LANGUAGE_EN], as district-core-swift's `L10nLocale.languageTag` sends it.
 *
 * ⛔ THE LANGUAGE THE SCREENS RESOLVED TO, READ AT EACH REQUEST, NOT THE DEVICE LOCALE AND NOT A
 * VALUE CAPTURED AT STARTUP. The server answers some text in that language (persona options, the
 * locale a signed-in account falls back to), so it has to agree with what the app is showing, and
 * a per-app language change must reach the very next request.
 *
 * ⚠️ A request that already names a language keeps it.
 */
class AcceptLanguageInterceptor(private val languageTag: () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(ACCEPT_LANGUAGE) != null) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header(ACCEPT_LANGUAGE, languageTag()).build())
    }

    private companion object {
        const val ACCEPT_LANGUAGE = "Accept-Language"
    }
}
