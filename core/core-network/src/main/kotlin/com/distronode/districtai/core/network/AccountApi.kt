package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.AccountSettingsSaveResponse
import kotlinx.serialization.json.JsonPrimitive

/** The value `POST /api/settings` stores for an English account. */
const val ACCOUNT_LOCALE_EN: String = "en"

/** The value `POST /api/settings` stores for a French account. */
const val ACCOUNT_LOCALE_FR: String = "fr"

/**
 * The signed-in person's own settings, `/api/settings`: today only the language the account is in.
 *
 * ⛔ THE STORED VALUE IS `fr`, NOT `fr-CA`. The route's schema is `z.enum(["en", "fr"])`, the set of
 * copy catalogs the website has, and answers 400 to a language tag (district-core-swift's
 * `L10nLocale.settingsValue`). The server uses it for what it writes to this person outside the
 * app: email, push text, and the web portal's first language.
 */
interface AccountApi {

    /** Store [locale] ([ACCOUNT_LOCALE_EN] or [ACCOUNT_LOCALE_FR]) as the account's language. */
    suspend fun saveLocale(locale: String): ApiResult<AccountSettingsSaveResponse>
}

/** [AccountApi] over [DistrictApiClient]. */
class HttpAccountApi(private val client: DistrictApiClient) : AccountApi {

    /** ⚠️ Only `profile.locale` is sent: an absent key leaves the name and time zone as they are. */
    override suspend fun saveLocale(locale: String): ApiResult<AccountSettingsSaveResponse> =
        client.send(
            method = "POST",
            segments = SETTINGS,
            serializer = AccountSettingsSaveResponse.serializer(),
            body = jsonObjectOf("profile" to jsonObjectOf("locale" to JsonPrimitive(locale))),
        )

    private companion object {
        val SETTINGS = listOf("api", "settings")
    }
}
