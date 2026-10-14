package com.distronode.districtai.ui.contacts

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.data.InboxRepository
import com.distronode.districtai.core.data.MeetingsRepository
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.Contact
import com.distronode.districtai.core.model.RoomTokenResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.str
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.MainLooperDrain
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The video-call row on a contact: what each phase draws, and the path from the button to an
 * invitation and a room, through the real ViewModel on a fake API.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class ContactVideoCallUiTest {

    private val composeRule = createComposeRule()

    /** ⚠️ The drain is OUTER, so it runs after the activity has closed; see [MainLooperDrain]. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainLooperDrain()).around(composeRule)

    private val contact = Contact(
        id = "c1",
        workspaceId = "ws-1",
        name = "Ada",
        phoneNumber = "+14165550142",
        createdAt = "2026-10-13T14:30:00.000Z",
    )

    private fun ui(
        phase: ContactVideoCallPhase,
        onStart: (Contact) -> Unit = {},
        onJoin: (String) -> Unit = {},
        onDismiss: () -> Unit = {},
    ) = ContactVideoCallUi(phase, onStart, onJoin, onDismiss)

    private fun renderRow(ui: ContactVideoCallUi, contact: Contact = this.contact, busy: Boolean = false) {
        composeRule.setContent { DistrictTheme { ContactVideoCallRow(ui, contact, busy) } }
    }

    @Test
    fun `idle offers the action and hands it the contact`() {
        var started: Contact? = null
        renderRow(ui(ContactVideoCallPhase.Idle, onStart = { started = it }))

        composeRule.onNodeWithTag(CONTACT_VIDEO_START_TAG).assertIsEnabled().performClick()

        assertEquals(contact, started)
    }

    @Test
    fun `idle waits for the screen's own write`() {
        renderRow(ui(ContactVideoCallPhase.Idle), busy = true)

        composeRule.onNodeWithTag(CONTACT_VIDEO_START_TAG).assertIsNotEnabled()
    }

    @Test
    fun `idle is disabled for a contact with nowhere to send the link`() {
        renderRow(ui(ContactVideoCallPhase.Idle), contact = contact.copy(phoneNumber = null))

        composeRule.onNodeWithTag(CONTACT_VIDEO_START_TAG).assertIsNotEnabled()
    }

    @Test
    fun `starting is a disabled setting-up button`() {
        renderRow(ui(ContactVideoCallPhase.Starting))

        composeRule.onNodeWithTag(CONTACT_VIDEO_STARTING_TAG).assertIsNotEnabled()
    }

    @Test
    fun `sent opens the room by its minted name`() {
        var joined: String? = null
        renderRow(ui(ContactVideoCallPhase.Sent("meet_ws-1_call-1"), onJoin = { joined = it }))

        composeRule.onNodeWithTag(CONTACT_VIDEO_JOIN_TAG).performClick()

        assertEquals("meet_ws-1_call-1", joined)
    }

    @Test
    fun `a failure shows its message and only a dismiss`() {
        var dismissed = false
        val failure = FailureText(UiText.Resource(R.string.contact_video_send_failed), retryable = false)
        renderRow(ui(ContactVideoCallPhase.Failed(failure), onDismiss = { dismissed = true }))

        composeRule.onNodeWithTag(CONTACT_VIDEO_FAILURE_TAG).assertExists()
        composeRule.onNodeWithTag(CONTACT_VIDEO_START_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(CONTACT_VIDEO_DISMISS_TAG).performClick()

        assertTrue(dismissed)
    }

    @Test
    fun `the contact screen draws the row only when it is given one`() {
        val loaded = ContactDetailUiState.Content(contact = contact)
        composeRule.setContent {
            DistrictTheme {
                ContactDetailScreen(
                    state = loaded,
                    canMutate = true,
                    videoCall = ui(ContactVideoCallPhase.Idle),
                    onBack = {},
                    onRetry = {},
                    onSignIn = {},
                    onRename = {},
                    onDelete = {},
                    onDismissMutationFailure = {},
                    onEnrich = {},
                    onClearIntel = {},
                    onCheckDossierAgain = {},
                )
            }
        }

        composeRule.onNodeWithTag(CONTACT_VIDEO_START_TAG).performScrollTo().assertIsEnabled()
    }

    @Test
    fun `a viewer gets no row from the destination`() {
        var result: ContactVideoCallUi? = ui(ContactVideoCallPhase.Idle)
        composeRule.setContent { result = destinationUi(FakeDistrictApi(), WorkspaceRole.VIEWER) {} }
        composeRule.waitForIdle()

        assertNull(result)
    }

    @Test
    fun `from the button to a sent invitation and the room`() {
        val api = FakeDistrictApi().apply {
            roomTokenResult = ApiResult.Success(
                RoomTokenResponse(success = true, token = "jwt", url = "wss://x", guestPath = "/meet/m?e=1&s=s"),
            )
        }
        var joined: String? = null
        composeRule.setContent {
            DistrictTheme {
                destinationUi(
                    api,
                    WorkspaceRole.CLIENT
                ) { joined = it }?.let { ContactVideoCallRow(it, contact, false) }
            }
        }

        composeRule.onNodeWithTag(CONTACT_VIDEO_START_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CONTACT_VIDEO_JOIN_TAG).performClick()

        // The invitation is the catalog sentence around the server's link, resolved for sending.
        val send = api.sends.single()
        assertEquals(str(R.string.contact_video_invitation, "https://www.distronode.test/meet/m?e=1&s=s"), send.body)
        assertNull(send.subject)
        assertTrue(requireNotNull(joined).startsWith("meet_ws-1_call-"))
        assertEquals(joined, api.roomTokenRequests.single().roomName)
    }

    @Test
    fun `an email invitation carries the catalog subject`() {
        val api = FakeDistrictApi().apply {
            roomTokenResult = ApiResult.Success(RoomTokenResponse(success = true, guestPath = "/meet/m"))
        }
        val emailOnly = contact.copy(phoneNumber = null, email = "ada@example.com")
        composeRule.setContent {
            DistrictTheme {
                destinationUi(api, WorkspaceRole.AGENCY) {}?.let { ContactVideoCallRow(it, emailOnly, false) }
            }
        }

        composeRule.onNodeWithTag(CONTACT_VIDEO_START_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals(str(R.string.contact_video_email_subject), api.sends.single().subject)
    }

    @androidx.compose.runtime.Composable
    private fun destinationUi(api: FakeDistrictApi, role: WorkspaceRole, onJoin: (String) -> Unit) =
        contactVideoCallUi(
            meetings = MeetingsRepository(api),
            inbox = InboxRepository(api),
            workspaceId = "ws-1",
            contactId = "c1",
            role = role,
            webOrigin = "https://www.distronode.test",
            onJoin = onJoin,
        )
}
