package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.DistrictSetupResponse
import com.distronode.districtai.core.model.SetupProgress
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SetupApi
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the overview offers "Finish setting up on the web".
 *
 * ⛔ ONE YES AND FIVE NOS, AND EVERY NO IS A DIFFERENT REASON. Only the owner of a workspace that
 * is in the wizard and has not finished it sees the card. Not the owner (403), a workspace from
 * before the wizard (null progress), a finished wizard, a dropped connection and an undecodable
 * body all answer false, and none of them may surface as an error on the screen.
 */
class SetupRepositoryTest {

    private val midWizard = DistrictSetupResponse(setupProgress = SetupProgress(paidAt = "2026-09-23T15:04:05.000Z"))

    private fun repository(result: ApiResult<DistrictSetupResponse>): Pair<SetupRepository, FakeSetupApi> {
        val api = FakeSetupApi(result)
        return SetupRepository(api) to api
    }

    @Test
    fun `the owner mid-wizard is offered the web`() = runTest {
        val (repository, api) = repository(ApiResult.Success(midWizard))

        assertTrue(repository.needsWebSetup("ws-owner"))
        assertEquals(listOf("ws-owner"), api.requested)
    }

    @Test
    fun `a member is refused by the server and shown nothing`() = runTest {
        val (repository, _) = repository(ApiResult.Forbidden("Only the workspace owner can set up this workspace."))

        assertFalse(repository.needsWebSetup("ws-member"))
    }

    @Test
    fun `a workspace from before the wizard is shown nothing`() = runTest {
        val (repository, _) = repository(ApiResult.Success(DistrictSetupResponse(setupProgress = null)))

        assertFalse(repository.needsWebSetup("ws-old"))
    }

    @Test
    fun `a finished wizard is shown nothing`() = runTest {
        val finished = DistrictSetupResponse(
            setupProgress = SetupProgress(completedAt = "2026-09-24T09:00:00.000Z"),
        )
        val (repository, _) = repository(ApiResult.Success(finished))

        assertFalse(repository.needsWebSetup("ws-done"))
    }

    @Test
    fun `a network failure is shown nothing rather than an error`() = runTest {
        val (repository, _) = repository(ApiResult.NetworkFailure(IOException("offline")))

        assertFalse(repository.needsWebSetup("ws-offline"))
    }

    @Test
    fun `an undecodable body is shown nothing rather than an error`() = runTest {
        val (repository, _) = repository(ApiResult.DecodeFailure(IllegalStateException("drift"), "{}"))

        assertFalse(repository.needsWebSetup("ws-drift"))
    }
}

private class FakeSetupApi(private val result: ApiResult<DistrictSetupResponse>) : SetupApi {
    val requested = mutableListOf<String>()

    override suspend fun districtSetup(workspaceId: String): ApiResult<DistrictSetupResponse> {
        requested += workspaceId
        return result
    }
}
