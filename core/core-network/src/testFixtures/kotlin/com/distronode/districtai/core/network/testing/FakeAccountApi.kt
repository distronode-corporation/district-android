package com.distronode.districtai.core.network.testing

import com.distronode.districtai.core.model.AccountSettingsSaveResponse
import com.distronode.districtai.core.network.AccountApi
import com.distronode.districtai.core.network.ApiResult

/** An [AccountApi] that records every saved locale and answers as configured (success by default). */
class FakeAccountApi : AccountApi {

    val saved: MutableList<String> = mutableListOf()

    var result: ApiResult<AccountSettingsSaveResponse> =
        ApiResult.Success(AccountSettingsSaveResponse(success = true, message = "Settings saved successfully"))

    override suspend fun saveLocale(locale: String): ApiResult<AccountSettingsSaveResponse> {
        saved += locale
        return result
    }
}
