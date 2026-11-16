package com.distronode.districtai.ui.account

import com.distronode.districtai.core.network.ApiResult
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which failures carry the service's code and its sentence, and which carry neither. */
class ReauthWordingTest {

    private val failures: List<ApiResult.Failure> = listOf(
        ApiResult.Forbidden("f", "c-f"),
        ApiResult.NotFound("n", "c-n"),
        ApiResult.RateLimited("r", code = "c-r"),
        ApiResult.HttpFailure(400, "h", "c-h"),
        ApiResult.RegionsDegraded("d", listOf("eu")),
        ApiResult.Unauthorized(null),
        ApiResult.NetworkFailure(IOException("x")),
        ApiResult.DecodeFailure(IllegalStateException(), ""),
    )

    @Test
    fun `the code comes from the four failures that carry one`() {
        assertEquals(listOf("c-f", "c-n", "c-r", "c-h", null, null, null, null), failures.map { it.code })
    }

    @Test
    fun `the service's sentence comes from the five failures that carry one`() {
        assertEquals(listOf("f", "n", "r", "h", "d", null, null, null), failures.map { it.serverMessage })
    }

    @Test
    fun `a proof says nothing`() {
        assertNull(ssoFailure(SsoReauthOutcome.Proved(300)))
    }
}
