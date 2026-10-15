package com.distronode.districtai.l10n

import android.content.Context
import com.distronode.districtai.R
import com.distronode.districtai.core.network.ACCEPT_LANGUAGE_EN
import com.distronode.districtai.core.network.ACCEPT_LANGUAGE_FR_CA
import com.distronode.districtai.core.network.ACCOUNT_LOCALE_EN
import com.distronode.districtai.core.network.ACCOUNT_LOCALE_FR
import com.distronode.districtai.core.network.AccountApi
import com.distronode.districtai.core.network.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The language the app's resources resolved to, outside composition: the same reading as
 * [appLanguage], so the server hears the language the screens are in, which follows the device's
 * language or, on Android 13+, the app's own (any French reads as fr-CA).
 */
internal fun Context.resolvedLanguage(): AppLanguage = languageOf(getString(R.string.l10n_language))

/** The `Accept-Language` for [this]: `fr-CA` or `en`. */
internal val AppLanguage.acceptLanguage: String
    get() = if (this == AppLanguage.FR) ACCEPT_LANGUAGE_FR_CA else ACCEPT_LANGUAGE_EN

/** What `POST /api/settings` stores for [this]: `fr` or `en`, never a language tag. */
internal val AppLanguage.accountLocale: String
    get() = if (this == AppLanguage.FR) ACCOUNT_LOCALE_FR else ACCOUNT_LOCALE_EN

/** The account language this installation last saved, so an unchanged one is not sent again. */
interface SavedAccountLocale {
    fun read(): String?
    fun write(locale: String?)
}

/** [SavedAccountLocale] in its own small preferences file. */
class PreferencesSavedAccountLocale(context: Context) : SavedAccountLocale {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY, null)

    override fun write(locale: String?) {
        prefs.edit().apply { if (locale == null) remove(KEY) else putString(KEY, locale) }.apply()
    }

    private companion object {
        const val FILE = "district_account_language"
        const val KEY = "saved_locale"
    }
}

/**
 * Tells the server which language this person reads the app in (`POST /api/settings`
 * `{profile: {locale}}`), so what it writes to them outside the app (email, push text, the web
 * portal's first language) is in the same one.
 *
 * ⛔ AT SIGN-IN ALWAYS, AND OTHERWISE ONLY WHEN THE LANGUAGE CHANGED. A sign-in may be another
 * person on this device, so it sends whatever was saved before. A process start or a language
 * change sends only when the resolved language differs from the one last saved, so an ordinary
 * launch costs nothing.
 *
 * ⚠️ A FAILED SAVE IS NOT RECORDED, so the next start or language change tries again. It is never
 * reported to the user: nothing on screen depends on it.
 */
class AccountLanguageReporter(
    private val api: AccountApi,
    private val language: () -> AppLanguage,
    private val saved: SavedAccountLocale,
    private val scope: CoroutineScope,
    private val hasSession: suspend () -> Boolean,
) {
    private val lock = Mutex()

    /** A session just started: send the language whatever was saved before. */
    fun onSignedIn() {
        scope.launch { save(force = true) }
    }

    /** The process started, or the device or app language may have changed. */
    fun onLanguageMaybeChanged() {
        scope.launch { if (hasSession()) save(force = false) }
    }

    /** Signed out: the next account starts from nothing saved. */
    fun onSignedOut() {
        saved.write(null)
    }

    private suspend fun save(force: Boolean) = lock.withLock {
        val locale = language().accountLocale
        if (!force && saved.read() == locale) return@withLock
        if (api.saveLocale(locale) is ApiResult.Success) saved.write(locale)
    }
}
