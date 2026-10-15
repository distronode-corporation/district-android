package com.distronode.districtai.l10n

import com.distronode.districtai.core.model.AccountSettingsSaveResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeAccountApi
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** When the account's language is sent to the server, and what is sent. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountLanguageReporterTest {

    private class MemorySaved(var value: String? = null) : SavedAccountLocale {
        override fun read(): String? = value
        override fun write(locale: String?) {
            value = locale
        }
    }

    private val scope = TestScope(StandardTestDispatcher())
    private val api = FakeAccountApi()
    private val saved = MemorySaved()
    private var language = AppLanguage.FR
    private var signedIn = true
    private val reporter = AccountLanguageReporter(api, { language }, saved, scope, { signedIn })

    @Test
    fun `a sign-in always sends the language, as fr or en, and remembers it`() {
        saved.value = "fr"
        reporter.onSignedIn()
        scope.advanceUntilIdle()
        assertEquals(listOf("fr"), api.saved)
        language = AppLanguage.EN
        reporter.onSignedIn()
        scope.advanceUntilIdle()
        assertEquals(listOf("fr", "en"), api.saved)
        assertEquals("en", saved.value)
    }

    @Test
    fun `a start or a language change sends only a language not yet saved, and only with a session`() {
        reporter.onLanguageMaybeChanged()
        scope.advanceUntilIdle()
        assertEquals(listOf("fr"), api.saved)
        reporter.onLanguageMaybeChanged()
        scope.advanceUntilIdle()
        assertEquals("unchanged: nothing sent", listOf("fr"), api.saved)
        language = AppLanguage.EN
        signedIn = false
        reporter.onLanguageMaybeChanged()
        scope.advanceUntilIdle()
        assertEquals("signed out: nothing sent", listOf("fr"), api.saved)
        signedIn = true
        reporter.onLanguageMaybeChanged()
        scope.advanceUntilIdle()
        assertEquals(listOf("fr", "en"), api.saved)
    }

    @Test
    fun `a failed save is not remembered, so the next chance tries again, and a sign-out forgets`() {
        api.result = ApiResult.NetworkFailure(IOException("offline"))
        reporter.onLanguageMaybeChanged()
        scope.advanceUntilIdle()
        assertNull(saved.value)
        api.result = ApiResult.Success(AccountSettingsSaveResponse(success = true))
        reporter.onLanguageMaybeChanged()
        scope.advanceUntilIdle()
        assertEquals(listOf("fr", "fr"), api.saved)
        assertEquals("fr", saved.value)
        reporter.onSignedOut()
        assertNull(saved.value)
    }

    @Test
    fun `the two spellings of each language`() {
        assertEquals("fr-CA", AppLanguage.FR.acceptLanguage)
        assertEquals("en", AppLanguage.EN.acceptLanguage)
        assertEquals("fr", AppLanguage.FR.accountLocale)
        assertEquals("en", AppLanguage.EN.accountLocale)
    }
}
