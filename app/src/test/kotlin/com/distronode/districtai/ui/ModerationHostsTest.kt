package com.distronode.districtai.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.data.BlockSubject
import com.distronode.districtai.core.data.ContactBlockingRepository
import com.distronode.districtai.core.data.SupportRepository
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.BlockedContact
import com.distronode.districtai.core.model.BlockedContactsResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeContactBlockingApi
import com.distronode.districtai.core.network.testing.FakeSupportApi
import com.distronode.districtai.str
import com.distronode.districtai.ui.contacts.BlockUi
import com.distronode.districtai.ui.contacts.BlockedContactsStore
import com.distronode.districtai.ui.inbox.ThreadModeration
import com.distronode.districtai.ui.report.REPORT_ROOT_TAG
import com.distronode.districtai.ui.report.ReportTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The wiring helpers the navigation graph puts Report and Block on screens with. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class ModerationHostsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val support = SupportRepository(FakeSupportApi())
    private val blockingApi = FakeContactBlockingApi()
    private val store = BlockedContactsStore(ContactBlockingRepository(blockingApi))

    @Test
    fun `a Report action opens its dialog, and none is offered when not allowed`() {
        var denied: (() -> Unit)? = {}
        composeRule.setContent {
            DistrictTheme {
                denied = reportAction(false, ReportTarget.HqAnswer, "ws", support)
                val open = reportAction(true, ReportTarget.Call("c1"), "ws", support)
                TextButton(onClick = { open?.invoke() }, modifier = Modifier.testTag("open")) { Text("open") }
            }
        }
        assertNull(denied)
        composeRule.onNodeWithTag("open").performClick()
        composeRule.onNodeWithTag(REPORT_ROOT_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.report_cancel)).performClick()
        composeRule.onNodeWithTag(REPORT_ROOT_TAG).assertDoesNotExist()
    }

    @Test
    fun `the block state reads the store, and a block goes through it`() {
        blockingApi.blockedResult = ApiResult.Success(
            BlockedContactsResponse(true, listOf(BlockedContact("c1", "Ada", null, "2026-10-13T12:00:00.000Z"))),
        )
        var ui: BlockUi? = null
        composeRule.setContent { DistrictTheme { ui = blockUi(store, "ws", BlockSubject.Contact("c1")) } }
        composeRule.waitForIdle()
        assertTrue(ui!!.blocked)
        ui!!.onSetBlocked(false)
        composeRule.waitForIdle()
        assertEquals(false, blockingApi.writes.single().blocked)
        ui!!.onDismissFailure()
    }

    @Test
    fun `a thread's subject comes from its key, and a block closes it`() {
        var contactThread: ThreadModeration? = null
        var addressThread: ThreadModeration? = null
        var malformed: ThreadModeration? = ThreadModeration({}, {}, false, null, {})
        var viewer: ThreadModeration? = ThreadModeration({}, {}, false, null, {})
        var closed = 0
        composeRule.setContent {
            DistrictTheme {
                contactThread = threadModeration(true, "contact:c1", "Ada", "ws", store, support) { closed++ }
                addressThread = threadModeration(true, "addr:+14165550142", null, "ws", store, support) {}
                malformed = threadModeration(true, "nonsense", null, "ws", store, support) {}
                viewer = threadModeration(false, "contact:c1", "Ada", "ws", store, support) {}
            }
        }
        assertNull(malformed)
        assertNull(viewer)
        assertNotNull(addressThread)
        contactThread!!.onBlock()
        composeRule.waitForIdle()
        assertEquals(1, closed)
        assertEquals("c1", blockingApi.writes.single().contactId)
        addressThread!!.onBlock()
        composeRule.waitForIdle()
        assertEquals("+14165550142", blockingApi.writes.last().phoneNumber)
        contactThread!!.onDismissFailure()
        composeRule.runOnIdle { contactThread!!.onReport() }
        composeRule.onNodeWithTag(REPORT_ROOT_TAG).assertIsDisplayed()
    }
}
