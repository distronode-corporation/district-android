package com.distronode.districtai.core.data

import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import java.net.URLDecoder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The calendar Connect hand-off: the SSO route with a `next` that lands on the provider's consent
 * screen, built exactly as district-ios builds it.
 */
class SchedulingCalendarHandOffTest {

    private val webOrigin = "https://www.distronode.com"

    private fun repository(api: FakeDistrictApi, origin: String) =
        SchedulingRepository(api, origin, "districtai://handoff")

    @Test
    fun `the next names the provider and a return_to encoded inside it`() = runTest {
        val api = FakeDistrictApi()

        val result = repository(api, "$webOrigin/").calendarConnectHandOff("ws-1", "google")

        val next = api.schedulingApi.schedulingSsoRequests.single().substringAfter("ws-1|")
        assertEquals(
            "/v1/calendar/connect?provider=google" +
                "&return_to=https%3A%2F%2Fwww.distronode.com%2Fdashboard%2Fdistrict%2Fscheduling%2Fcalendar",
            next,
        )
        // ⛔ No literal `://` may survive into `next`: the route's `safeReturnPath` drops any that does.
        assertFalse(next.contains("://"))
        assertEquals(
            "https://www.distronode.com/dashboard/district/scheduling/calendar",
            URLDecoder.decode(next.substringAfter("return_to="), "UTF-8"),
        )
        assertEquals("https://acme-book.distronode.com/v1/auth/sso?token=stub", (result as ApiResult.Success).value)
    }

    @Test
    fun `a refusal passes through untouched`() = runTest {
        val refused = ApiResult.HttpFailure(409, "not ready")
        val api = FakeDistrictApi().apply { schedulingApi.schedulingSsoResult = refused }

        assertSame(refused, repository(api, webOrigin).calendarConnectHandOff("ws-1", "microsoft"))
    }

    @Test
    fun `only the RFC 3986 unreserved set survives the encode, and multi-byte text is escaped per byte`() {
        assertEquals("aZ09-._~", encodeComponent("aZ09-._~"))
        assertEquals("%21%27%28%29%2A%20%26%3D%2F%3F%3A", encodeComponent("!'()* &=/?:"))
        assertEquals("%C3%A9", encodeComponent("é"))
    }
}
