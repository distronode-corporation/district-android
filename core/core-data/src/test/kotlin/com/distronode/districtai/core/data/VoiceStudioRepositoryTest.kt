package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.PersonaPatchRequest
import com.distronode.districtai.core.model.WorkspaceConfigSaveResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.core.network.testing.FakePersonaApi
import com.distronode.districtai.core.network.testing.VoiceStudioFixture
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Studio read, and a save that is always followed by it.
 *
 * ⛔ THE THREE SAVE OUTCOMES ARE THE POINT. A refused chain wrote nothing; a landed write with a
 * failed re-read wrote something this client cannot describe; only a landed write with a re-read
 * is a save the screen may compare against what it sent.
 */
class VoiceStudioRepositoryTest {

    private val studio = VoiceStudioFixture.studio
    private val request = PersonaPatchRequest(workspaceId = "ws-1", voice = "Kore")

    @Test
    fun `a read that affirms success is passed through, for the workspace asked about`() = runTest {
        val persona = FakePersonaApi().apply { voiceStudioResults.add(ApiResult.Success(studio)) }

        val result = VoiceStudioRepository(persona, FakeDistrictApi()).load("ws-1")

        assertEquals(ApiResult.Success(studio), result)
        assertEquals(listOf("ws-1"), persona.voiceStudioCalls)
    }

    @Test
    fun `a 200 that did not affirm success is a decode failure, not a studio`() = runTest {
        val persona = FakePersonaApi().apply {
            voiceStudioResults.add(ApiResult.Success(studio.copy(success = false)))
        }

        assertTrue(VoiceStudioRepository(persona, FakeDistrictApi()).load("ws-1") is ApiResult.DecodeFailure)
    }

    @Test
    fun `a failed read is passed through`() = runTest {
        val failure = ApiResult.Forbidden("Forbidden")
        val persona = FakePersonaApi().apply { voiceStudioResults.add(failure) }

        assertEquals(failure, VoiceStudioRepository(persona, FakeDistrictApi()).load("ws-1"))
    }

    @Test
    fun `a landed save re-reads the studio and hands it back`() = runTest {
        val persona = FakePersonaApi().apply { voiceStudioResults.add(ApiResult.Success(studio)) }
        val api = FakeDistrictApi()

        val outcome = VoiceStudioRepository(persona, api).save(request)

        assertEquals(VoiceStudioSave.Saved(studio), outcome)
        assertEquals(listOf(request), api.personaPatches)
        assertEquals(listOf("ws-1"), persona.voiceStudioCalls)
    }

    @Test
    fun `a refused chain wrote nothing and is not re-read`() = runTest {
        val refused = ApiResult.HttpFailure(400, "That voice chain cannot be saved.", "invalid_engine_mix")
        val persona = FakePersonaApi()
        val api = FakeDistrictApi().apply { savePersonaResult = refused }

        val outcome = VoiceStudioRepository(persona, api).save(request)

        assertEquals(VoiceStudioSave.NotSaved(refused), outcome)
        assertTrue(persona.voiceStudioCalls.isEmpty())
    }

    @Test
    fun `a 200 that did not affirm success is not a save`() = runTest {
        val api = FakeDistrictApi().apply {
            savePersonaResult = ApiResult.Success(WorkspaceConfigSaveResponse(success = false))
        }

        val outcome = VoiceStudioRepository(FakePersonaApi(), api).save(request)

        assertTrue((outcome as VoiceStudioSave.NotSaved).failure is ApiResult.DecodeFailure)
    }

    @Test
    fun `a landed write whose re-read failed is stale, never a failure`() = runTest {
        val offline = ApiResult.NetworkFailure(IOException("offline"))
        val persona = FakePersonaApi().apply { voiceStudioResults.add(offline) }

        val outcome = VoiceStudioRepository(persona, FakeDistrictApi()).save(request)

        assertEquals(VoiceStudioSave.SavedButStale(offline), outcome)
    }
}
