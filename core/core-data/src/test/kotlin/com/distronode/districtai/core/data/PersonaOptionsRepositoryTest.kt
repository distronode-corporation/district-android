package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.E2eeInfo
import com.distronode.districtai.core.model.PersonaOptionsResponse
import com.distronode.districtai.core.model.PersonaPreviewForm
import com.distronode.districtai.core.model.PersonaPreviewTokenResponse
import com.distronode.districtai.core.network.ApiResult
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue read, and the credential for one billed audition.
 *
 * ⛔ EVERY REFUSAL IN THIS FILE IS ABOUT A SESSION THAT WOULD CONNECT AND BE SILENTLY USELESS. A
 * room name without the `preview_` prefix is answered by the STORED persona rather than the form on
 * screen; a missing or blank passphrase leaves this phone the only unencrypted participant in a
 * room everyone else encrypted, publishing and hearing noise. Neither surfaces as an error later,
 * which is why both are refused here rather than rendered.
 */
class PersonaOptionsRepositoryTest {

    private val okToken = PersonaPreviewTokenResponse(
        success = true,
        token = "jwt",
        url = "wss://media",
        roomName = "preview_ws-1_0000",
        e2ee = E2eeInfo(key = "YfxKDUkaaGp2WrLLGHCHbe2nn5ArCWBd+x+k7EzDr/8="),
    )

    private fun repository(api: FakePersonaApi) = PersonaOptionsRepository(api)

    @Test
    fun `a successful catalogue read is passed through`() = runTest {
        val api = FakePersonaApi().apply {
            optionsResult = ApiResult.Success(PersonaOptionsResponse(success = true, region = "eu"))
        }

        val result = repository(api).options("ws-1")

        assertTrue(result is ApiResult.Success)
        assertEquals("eu", (result as ApiResult.Success).value.region)
    }

    @Test
    fun `a 200 that did not affirm success is a failure, not a catalogue`() = runTest {
        // ⛔ A ROUTE FALLING INTO ITS ERROR BRANCH AFTER THE HEADERS ARE WRITTEN ANSWERS A
        // WELL-FORMED `{success:false}` WITH A 200. Adopting that as a catalogue would give the
        // form empty pickers and no error — and an empty picker saved is a cleared voice.
        val api = FakePersonaApi().apply {
            optionsResult = ApiResult.Success(PersonaOptionsResponse(success = false))
        }

        assertTrue(repository(api).options("ws-1") is ApiResult.DecodeFailure)
    }

    @Test
    fun `a transport failure is forwarded so the form can go read-only`() = runTest {
        // ⛔ AND MUST NEVER FALL BACK TO A BUILT-IN CATALOGUE. A hardcoded list does not fail when
        // it drifts: every value it offers is still accepted, stored, and then silently substituted
        // by the agent, with a 200.
        val api = FakePersonaApi().apply {
            optionsResult = ApiResult.NetworkFailure(IOException("down"))
        }

        assertTrue(repository(api).options("ws-1") is ApiResult.NetworkFailure)
    }

    @Test
    fun `a valid preview credential is passed through, with the key untouched`() = runTest {
        val api = FakePersonaApi().apply { previewResult = ApiResult.Success(okToken) }

        val result = repository(api).previewToken("ws-1", PersonaPreviewForm(name = "Ada"))

        assertTrue(result is ApiResult.Success)
        // ⛔ VERBATIM. Decoding the base64 text to 32 raw bytes would select a different key
        // derivation, and both sides would still join.
        assertEquals(okToken.e2ee?.key, (result as ApiResult.Success).value.e2ee?.key)
    }

    @Test
    fun `the unsaved form reaches the route exactly once`() = runTest {
        // ⛔ BILLABLE AND NOT IDEMPOTENT. Nothing in this client may retry it, so the count is the
        // assertion that matters as much as the content.
        val api = FakePersonaApi().apply { previewResult = ApiResult.Success(okToken) }
        val form = PersonaPreviewForm(greeting = "Thanks for calling.", modelId = "deepgram-pipeline")

        repository(api).previewToken("ws-1", form)

        assertEquals(1, api.previewCalls.size)
        assertEquals("ws-1" to form, api.previewCalls.single())
    }

    @Test
    fun `a room without the preview prefix is refused`() = runTest {
        // ⛔ THE AGENT BRANCHES ON THAT PREFIX to read the persona out of the token metadata. A
        // room named anything else is answered by the SAVED persona — the opposite of what the
        // screen promises, and it would sound completely convincing.
        val api = FakePersonaApi().apply {
            previewResult = ApiResult.Success(okToken.copy(roomName = "call_ws-1_0000"))
        }

        assertTrue(repository(api).previewToken("ws-1", PersonaPreviewForm()) is ApiResult.DecodeFailure)
    }

    @Test
    fun `an absent or blank encryption key is refused rather than joined`() = runTest {
        // ⛔ A `preview_*` ROOM IS ALWAYS ENCRYPTED, so absence is the server failing to derive a
        // key rather than "join in the clear". ⛔ AND A BLANK KEY IS NOT "no encryption": it
        // derives a real AES key nobody else derives, so the join succeeds and every track is
        // noise, with nothing reporting it.
        val absent = FakePersonaApi().apply {
            previewResult = ApiResult.Success(okToken.copy(e2ee = null))
        }
        assertTrue(
            repository(absent).previewToken("ws-1", PersonaPreviewForm()) is ApiResult.DecodeFailure,
        )

        val blank = FakePersonaApi().apply {
            previewResult = ApiResult.Success(okToken.copy(e2ee = E2eeInfo(key = "")))
        }
        assertTrue(
            repository(blank).previewToken("ws-1", PersonaPreviewForm()) is ApiResult.DecodeFailure,
        )
    }

    @Test
    fun `a credential with no join token is refused`() = runTest {
        val api = FakePersonaApi().apply {
            previewResult = ApiResult.Success(okToken.copy(token = "", url = ""))
        }

        assertTrue(repository(api).previewToken("ws-1", PersonaPreviewForm()) is ApiResult.DecodeFailure)
    }
}
