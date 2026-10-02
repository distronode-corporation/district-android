package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.AvailableNumber
import com.distronode.districtai.core.model.ListedNumber
import com.distronode.districtai.core.model.NumberSearchResponse
import com.distronode.districtai.core.model.OwnedNumbersResponse
import com.distronode.districtai.core.network.ApiResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.distronode.districtai.core.network.testing.FakeDistrictApi

/**
 * The phone-number marketplace's two reads.
 *
 * ⛔ THE TWO DISTINCTIONS THIS LAYER EXISTS TO KEEP. A workspace with no carrier connected is a
 * 400 and must NOT become an empty success — that would tell an operator the carrier has nothing
 * in their area code when nothing was ever looked at. And a `partial` answer must stay a success
 * carrying a flag, because promoting it to a failure hides inventory the workspace owns while
 * ignoring it draws an incomplete list as a complete one.
 */
class NumbersRepositoryTest {

    private val available = AvailableNumber(
        phoneNumber = "+14165550111",
        capabilities = listOf("sms", "voice"),
        type = "local",
        monthlyPrice = 1.15,
    )

    private val owned = ListedNumber(
        phoneNumber = "+14165550100",
        capabilities = listOf("sms", "voice"),
        type = "local",
        status = "in-use",
        provider = "twilio",
    )

    // ── Search ───────────────────────────────────────────────────────────────

    @Test
    fun `a search sends its filters, and drops the blank ones`() = runTest {
        // ⚠️ The server distinguishes an omitted parameter from an empty one, so a blank area code
        // would reach the carrier as a literal filter rather than as "no filter".
        val api = FakeDistrictApi().apply {
            searchResult = ApiResult.Success(
                NumberSearchResponse(success = true, provider = "twilio", numbers = listOf(available)),
            )
        }

        NumbersRepository(api).search("ws-1", areaCode = "416", country = "US", type = "local")
        NumbersRepository(api).search("ws-1", areaCode = "   ", country = "", type = null)

        assertEquals(listOf("ws-1", "416", "US", "local", null), api.searchRequests.first())
        assertEquals(listOf("ws-1", null, null, null, null), api.searchRequests.last())
    }

    @Test
    fun `the type and provider filters are normalised the same way as the others`() = runTest {
        val api = FakeDistrictApi().apply {
            searchResult = ApiResult.Success(NumberSearchResponse(success = true, provider = "telnyx"))
        }

        NumbersRepository(api).search("ws-1", type = " ", provider = "telnyx")
        NumbersRepository(api).search("ws-1", type = "tollfree", provider = "")

        assertEquals(listOf("ws-1", null, null, null, "telnyx"), api.searchRequests.first())
        assertEquals(listOf("ws-1", null, null, "tollfree", null), api.searchRequests.last())
    }

    @Test
    fun `results are returned whole, provider included`() = runTest {
        val api = FakeDistrictApi().apply {
            searchResult = ApiResult.Success(
                NumberSearchResponse(success = true, provider = "telnyx", numbers = listOf(available)),
            )
        }

        val result = NumbersRepository(api).search("ws-1")

        val value = (result as ApiResult.Success).value
        // ⚠️ The carrier that ANSWERED, which need not be the one requested — the resolved
        // credentials decide.
        assertEquals("telnyx", value.provider)
        assertEquals(1, value.numbers.size)
    }

    @Test
    fun `an unconfigured workspace stays a 400, never an empty result`() = runTest {
        // ⛔ THE CONFLATION THIS ASSERTION EXISTS TO PREVENT. Converting this into
        // `Success(numbers = [])` would render "no numbers match your filters" for a workspace
        // that has not connected a carrier at all — an answer about inventory nobody looked at,
        // and one the operator would act on by trying a different area code forever.
        val message = "Messaging provider not configured for workspace"
        val api = FakeDistrictApi().apply {
            searchResult = ApiResult.HttpFailure(400, message)
        }

        val result = NumbersRepository(api).search("ws-1")

        assertEquals(400, (result as ApiResult.HttpFailure).status)
        // The server's own sentence survives: it also covers the narrower "that provider is not
        // connected" case, which this layer cannot distinguish.
        assertEquals(message, result.message)
    }

    @Test
    fun `a 200 that does not affirm success is contract drift, not an empty carrier`() = runTest {
        // ⛔ Every field of NumberSearchResponse has a default, so `{}` decodes into a
        // well-formed "the carrier has nothing".
        val api = FakeDistrictApi().apply {
            searchResult = ApiResult.Success(NumberSearchResponse())
        }

        assertTrue(NumbersRepository(api).search("ws-1") is ApiResult.DecodeFailure)
    }

    // ── Owned ────────────────────────────────────────────────────────────────

    @Test
    fun `owned numbers come back with the managed flag intact`() = runTest {
        val managed = owned.copy(phoneNumber = "+14165550199", provider = "telnyx", managed = true)
        val api = FakeDistrictApi().apply {
            ownedResult = ApiResult.Success(
                OwnedNumbersResponse(success = true, numbers = listOf(owned, managed)),
            )
        }

        val value = (NumbersRepository(api).owned("ws-1") as ApiResult.Success).value

        // ⛔ `managed` DECIDES WHAT MAY BE OFFERED FOR A ROW — the line sits on Distronode's
        // carrier account, so the tenant cannot release it. Losing the flag in this layer would
        // make the two kinds indistinguishable one layer up.
        assertEquals(listOf(false, true), value.numbers.map { it.managed })
        assertEquals("ws-1", api.requestedWorkspaceIds.last())
    }

    @Test
    fun `a partial list stays a success and keeps both the rows and the warning`() = runTest {
        // ⛔ THE 200-WITH-A-BANNER SHAPE. Promoting it to a failure would hide real inventory;
        // dropping the flag would draw a short list as a complete one. Only a success carrying
        // the flag can express "here is what one carrier could tell us".
        val api = FakeDistrictApi().apply {
            ownedResult = ApiResult.Success(
                OwnedNumbersResponse(
                    success = true,
                    numbers = listOf(owned),
                    partial = true,
                    failedProviders = listOf("telnyx"),
                ),
            )
        }

        val result = NumbersRepository(api).owned("ws-1")

        val value = (result as ApiResult.Success).value
        assertTrue(value.partial)
        assertEquals(listOf("telnyx"), value.failedProviders)
        assertTrue("the rows must survive the warning", value.numbers.isNotEmpty())
    }

    @Test
    fun `a total carrier failure is passed through as the failure it is`() = runTest {
        // ⚠️ The 502 branch: a carrier failed AND nothing at all resolved. This is the case where
        // an empty list WOULD read as "you own no numbers", which is why the server does not send
        // one.
        val api = FakeDistrictApi().apply {
            ownedResult = ApiResult.HttpFailure(502, "Could not reach telnyx to list numbers.")
        }

        val result = NumbersRepository(api).owned("ws-1")

        assertEquals(502, (result as ApiResult.HttpFailure).status)
        assertNull("a transport failure carries no machine-readable code here", result.code)
    }

    @Test
    fun `an empty owned envelope is drift rather than an empty inventory`() = runTest {
        val api = FakeDistrictApi().apply {
            ownedResult = ApiResult.Success(OwnedNumbersResponse())
        }

        assertTrue(NumbersRepository(api).owned("ws-1") is ApiResult.DecodeFailure)
    }
}
