// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.HqConfirmResponse
import com.distronode.districtai.core.model.HqProposalCancelResponse
import com.distronode.districtai.core.model.HqVoiceContextResponse
import com.distronode.districtai.core.model.HqVoiceDiscardResponse
import com.distronode.districtai.core.model.HqVoiceEndResponse
import com.distronode.districtai.core.model.HqVoiceProposal
import com.distronode.districtai.core.model.HqVoiceSettingsResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.core.network.testing.FakeHqVoiceApi
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The voice repository holds every answer to its envelope, and a credential to being joinable. */
class HqVoiceRepositoryTest {

    private val voice = FakeHqVoiceApi()
    private val hq = FakeDistrictApi()
    private val repository = HqVoiceRepository(voice, hq)
    private val offline = ApiResult.NetworkFailure(IOException("offline"))

    private val proposal = HqVoiceProposal(
        proposalId = "p-1",
        tool = "update_persona",
        args = JsonObject(mapOf("greeting" to JsonPrimitive("Hi"))),
        summary = "Change the greeting.",
    )

    private fun echoing(tool: String) =
        ApiResult.Success(HqConfirmResponse(success = true, executed = true, tool = tool))

    @Test
    fun `a joinable credential is answered as it came`() = runTest {
        val result = repository.voiceToken("ws-1", "fr", emptyList())

        assertEquals(FakeHqVoiceApi.CREDENTIAL, (result as ApiResult.Success).value)
        assertEquals("fr", voice.tokenRequests.single().second)
    }

    @Test
    fun `a credential without its key, or not an HQ room, is never handed to a caller`() = runTest {
        voice.tokenResult = ApiResult.Success(FakeHqVoiceApi.CREDENTIAL.copy(e2ee = null))
        assertTrue(repository.voiceToken("ws-1", "en", emptyList()) is ApiResult.DecodeFailure)

        voice.tokenResult = ApiResult.Success(FakeHqVoiceApi.CREDENTIAL.copy(roomName = "meet_x"))
        assertTrue(repository.voiceToken("ws-1", "en", emptyList()) is ApiResult.DecodeFailure)

        voice.tokenResult = ApiResult.Success(FakeHqVoiceApi.CREDENTIAL.copy(success = false))
        assertTrue(repository.voiceToken("ws-1", "en", emptyList()) is ApiResult.DecodeFailure)

        voice.tokenResult = offline
        assertEquals(offline, repository.voiceToken("ws-1", "en", emptyList()))
    }

    @Test
    fun `the ends answer whether this call ended or discarded the session`() = runTest {
        assertEquals(ApiResult.Success(true), repository.end("ws-1", "s1"))
        assertEquals(ApiResult.Success(true), repository.discard("ws-1", "s1"))

        voice.endResult = ApiResult.Success(HqVoiceEndResponse(success = true, ended = false))
        voice.discardResult = ApiResult.Success(HqVoiceDiscardResponse(success = true, discarded = false))
        assertEquals(ApiResult.Success(false), repository.end("ws-1", "s1"))
        assertEquals(ApiResult.Success(false), repository.discard("ws-1", "s1"))

        voice.endResult = ApiResult.Success(HqVoiceEndResponse())
        voice.discardResult = ApiResult.Success(HqVoiceDiscardResponse())
        assertTrue(repository.end("ws-1", "s1") is ApiResult.DecodeFailure)
        assertTrue(repository.discard("ws-1", "s1") is ApiResult.DecodeFailure)

        voice.endResult = offline
        voice.discardResult = offline
        assertEquals(offline, repository.end("ws-1", "s1"))
        assertEquals(offline, repository.discard("ws-1", "s1"))
        assertEquals(List(4) { listOf("end:s1", "discard:s1") }.flatten(), voice.calls)
    }

    @Test
    fun `the screen report, the cancel and the settings are held to their envelopes`() = runTest {
        assertEquals(ApiResult.Success(Unit), repository.reportScreen("ws-1", "hq_r", 1, "hq"))
        assertEquals(listOf(1 to "hq"), voice.contextReports)
        assertEquals(ApiResult.Success(Unit), repository.cancel("ws-1", "p-1"))
        assertEquals("cancel:p-1", voice.calls.last())
        assertEquals(ApiResult.Success(FakeHqVoiceApi.SETTINGS), repository.settings("ws-1"))
        assertEquals(
            ApiResult.Success(FakeHqVoiceApi.SETTINGS),
            repository.saveSettings("ws-1", FakeHqVoiceApi.SETTINGS)
        )

        voice.contextResult = ApiResult.Success(HqVoiceContextResponse())
        voice.cancelResult = ApiResult.Success(HqProposalCancelResponse())
        voice.settingsResult = ApiResult.Success(HqVoiceSettingsResponse())
        voice.saveResult = ApiResult.Success(HqVoiceSettingsResponse())
        assertTrue(repository.reportScreen("ws-1", "hq_r", 2, "hq") is ApiResult.DecodeFailure)
        assertTrue(repository.cancel("ws-1", "p-1") is ApiResult.DecodeFailure)
        assertTrue(repository.settings("ws-1") is ApiResult.DecodeFailure)
        assertTrue(repository.saveSettings("ws-1", FakeHqVoiceApi.SETTINGS) is ApiResult.DecodeFailure)

        voice.contextResult = offline
        voice.cancelResult = offline
        voice.settingsResult = offline
        voice.saveResult = offline
        assertEquals(offline, repository.reportScreen("ws-1", "hq_r", 3, "hq"))
        assertEquals(offline, repository.cancel("ws-1", "p-1"))
        assertEquals(offline, repository.settings("ws-1"))
        assertEquals(offline, repository.saveSettings("ws-1", FakeHqVoiceApi.SETTINGS))
    }

    @Test
    fun `a tap confirm sends the proposal untouched with its id, and checks the echo`() = runTest {
        hq.hqConfirmResult = echoing("update_persona")

        val result = repository.confirm("ws-1", proposal)

        assertEquals(HqConfirmation("update_persona", true), (result as ApiResult.Success).value)
        val sent = hq.hqConfirms.single()
        assertEquals("ws-1", sent.workspaceId)
        assertEquals(proposal.tool, sent.confirm.tool)
        assertEquals(proposal.args, sent.confirm.args)
        assertEquals("p-1", sent.confirm.proposalId)
    }

    @Test
    fun `a confirm that echoes another tool, or no success, or fails, is not a confirmation`() = runTest {
        hq.hqConfirmResult = echoing("delete_contact")
        assertTrue(repository.confirm("ws-1", proposal) is ApiResult.DecodeFailure)

        hq.hqConfirmResult = ApiResult.Success(HqConfirmResponse(success = false, tool = "update_persona"))
        assertTrue(repository.confirm("ws-1", proposal) is ApiResult.DecodeFailure)

        hq.hqConfirmResult = offline
        assertEquals(offline, repository.confirm("ws-1", proposal))
        assertEquals("each confirm is sent once", 3, hq.hqConfirms.size)
    }
}
