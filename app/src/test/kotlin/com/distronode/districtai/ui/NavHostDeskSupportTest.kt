package com.distronode.districtai.ui

import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.model.DeskSettings
import com.distronode.districtai.core.model.DeskSettingsResponse
import com.distronode.districtai.core.model.DeskTicketCreateResponse
import com.distronode.districtai.core.model.DeskTicketSummary
import com.distronode.districtai.core.model.DeskTicketsResponse
import com.distronode.districtai.core.model.SupportRequestCreateResponse
import com.distronode.districtai.core.model.SupportRequestDetail
import com.distronode.districtai.core.model.SupportRequestDetailResponse
import com.distronode.districtai.core.model.SupportRequestListResponse
import com.distronode.districtai.core.model.SupportRequestSummary
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.desk.DESK_COMPOSE_CANCEL_DESCRIPTION
import com.distronode.districtai.ui.desk.DESK_COMPOSE_MESSAGE_DESCRIPTION
import com.distronode.districtai.ui.desk.DESK_COMPOSE_ROOT_DESCRIPTION
import com.distronode.districtai.ui.desk.DESK_COMPOSE_SUBJECT_DESCRIPTION
import com.distronode.districtai.ui.desk.DESK_COMPOSE_SUBMIT_DESCRIPTION
import com.distronode.districtai.ui.desk.DESK_LOGO_CHOOSE_DESCRIPTION
import com.distronode.districtai.ui.desk.DESK_SETTINGS_ROOT_DESCRIPTION
import com.distronode.districtai.ui.inbox.PickerProvider
import com.distronode.districtai.ui.support.SUPPORT_COMPOSE_CANCEL_DESCRIPTION
import com.distronode.districtai.ui.support.SUPPORT_COMPOSE_MESSAGE_DESCRIPTION
import com.distronode.districtai.ui.support.SUPPORT_COMPOSE_ROOT_DESCRIPTION
import com.distronode.districtai.ui.support.SUPPORT_COMPOSE_SUBJECT_DESCRIPTION
import com.distronode.districtai.ui.support.SUPPORT_COMPOSE_SUBMIT_DESCRIPTION
import com.distronode.districtai.ui.support.SUPPORT_NEW_DESCRIPTION
import com.distronode.districtai.ui.support.SUPPORT_REQUEST_CLOSE_DESCRIPTION
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * The Desk (a workspace's customers writing to it) and Support (the workspace writing to us).
 *
 * ⛔ THE CONFIRMATIONS ARE THE GRAPH'S, NOT THE SCREENS'. A submit's outcome is turned into a sentence
 * and handed to the host's message channel here, the sheet is closed here, and the outcome is
 * acknowledged here so it is said once. Each test reads what the host was told.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class NavHostDeskSupportTest {

    private val composeRule = createComposeRule()

    /** ⚠️ The drain is OUTER, so it runs after the activity has closed; see [MainLooperDrain]. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainLooperDrain()).around(composeRule)

    private val harness = NavHostHarness(composeRule)

    @After
    fun tearDown() = harness.close()

    private fun string(id: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(id, *args)

    private fun type(description: String, text: String) {
        composeRule.onNodeWithContentDescription(description).performTextInput(text)
        composeRule.waitForIdle()
    }

    // ── The desk ───────────────────────────────────────────────────────────────────────────

    private val ticket = DeskTicketSummary(
        id = "ticket-uuid-1",
        reference = 7,
        displayReference = "T-7",
        subject = "Printer on fire",
        status = "open",
    )

    private fun openDesk() {
        harness.desk.settingsResult = ApiResult.Success(
            DeskSettingsResponse(success = true, settings = DeskSettings(enabled = true)),
        )
        harness.desk.ticketsResult = ApiResult.Success(DeskTicketsResponse(success = true, tickets = listOf(ticket)))
        harness.render()
        harness.navigate(Routes.desk("ws-1", WorkspaceRole.AGENCY))
    }

    private fun raiseDeskTicket() {
        harness.tapText(string(R.string.desk_compose_title))
        type(DESK_COMPOSE_SUBJECT_DESCRIPTION, "Printer on fire")
        type(DESK_COMPOSE_MESSAGE_DESCRIPTION, "Second floor.")
        harness.tap(DESK_COMPOSE_SUBMIT_DESCRIPTION)
    }

    @Test
    fun `a raised ticket is confirmed by its reference, once, and the sheet closes`() {
        harness.desk.createResult = ApiResult.Success(DeskTicketCreateResponse(success = true, ticket = ticket))
        openDesk()

        raiseDeskTicket()

        assertEquals(listOf(string(R.string.desk_created, "T-7")), harness.messages)
        assertEquals(listOf("Printer on fire"), harness.desk.createDrafts.map { it.subject })
        composeRule.onNodeWithContentDescription(DESK_COMPOSE_ROOT_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a deduplicated submit says it was already raised rather than quoting a reference it lacks`() {
        harness.desk.createResult = ApiResult.Success(DeskTicketCreateResponse(success = true, ticket = null))
        openDesk()

        raiseDeskTicket()

        assertEquals(listOf(string(R.string.desk_created_duplicate)), harness.messages)
    }

    @Test
    fun `cancelling the sheet discards the draft and sends nothing`() {
        openDesk()
        harness.tapText(string(R.string.desk_compose_title))
        type(DESK_COMPOSE_SUBJECT_DESCRIPTION, "Half a thought")

        harness.tap(DESK_COMPOSE_CANCEL_DESCRIPTION)

        composeRule.onNodeWithContentDescription(DESK_COMPOSE_ROOT_DESCRIPTION).assertDoesNotExist()
        assertTrue(harness.desk.createDrafts.isEmpty())
        // ⚠️ Reopened, the sheet starts empty: the draft went with the dismissal.
        harness.tapText(string(R.string.desk_compose_title))
        composeRule.onNodeWithContentDescription(DESK_COMPOSE_SUBJECT_DESCRIPTION).assertExists()
        assertTrue(
            composeRule.onAllNodesWithTextCount("Half a thought") == 0,
        )
    }

    @Test
    fun `a ticket row opens that ticket by its id, and the settings action opens the queue's settings`() {
        openDesk()

        harness.tapText("Printer on fire")
        assertEquals(Routes.DESK_TICKET, harness.route())
        assertEquals("ticket-uuid-1", harness.argument(ARG_TICKET_ID))
        assertEquals("agency", harness.argument(ARG_ROLE))

        harness.back()
        harness.tapText(string(R.string.desk_settings_open))
        assertEquals(Routes.DESK_SETTINGS, harness.route())
        harness.awaitDescription(DESK_SETTINGS_ROOT_DESCRIPTION)
    }

    @Test
    fun `the desk logo is picked image-only and uploaded, and backing out uploads nothing`() {
        Robolectric.setupContentProvider(PickerProvider::class.java, PICKER_AUTHORITY)
        PickerProvider.reset(byteArrayOf(1, 2, 3))
        harness.desk.settingsResult = ApiResult.Success(
            DeskSettingsResponse(success = true, settings = DeskSettings(enabled = true)),
        )
        harness.render()
        harness.navigate(Routes.deskSettings("ws-1", WorkspaceRole.AGENCY))

        harness.registry.pickedUri = null
        harness.tap(DESK_LOGO_CHOOSE_DESCRIPTION)
        assertEquals(0, harness.desk.logoUploads)

        harness.registry.pickedUri = Uri.parse("content://$PICKER_AUTHORITY/photo")
        harness.tap(DESK_LOGO_CHOOSE_DESCRIPTION)
        harness.awaitMain { harness.desk.logoUploads == 1 }

        assertEquals(listOf("image/jpeg"), harness.desk.uploadedMimeTypes)
        val picks = harness.registry.launched.filterIsInstance<PickVisualMediaRequest>()
        assertEquals(2, picks.size)
        picks.forEach { assertEquals(ActivityResultContracts.PickVisualMedia.ImageOnly, it.mediaType) }
    }

    // ── Support ────────────────────────────────────────────────────────────────────────────

    private fun openSupport(requests: List<SupportRequestSummary> = emptyList()) {
        harness.support.listResult = ApiResult.Success(SupportRequestListResponse(success = true, requests = requests))
        harness.render()
        harness.navigate(Routes.support("ws-1", WorkspaceRole.CLIENT))
    }

    private fun raiseSupportRequest() {
        harness.tap(SUPPORT_NEW_DESCRIPTION)
        type(SUPPORT_COMPOSE_SUBJECT_DESCRIPTION, "Calls drop")
        type(SUPPORT_COMPOSE_MESSAGE_DESCRIPTION, "Since Tuesday.")
        harness.tap(SUPPORT_COMPOSE_SUBMIT_DESCRIPTION)
    }

    @Test
    fun `a filed request is confirmed with its key and the dialog closes`() {
        harness.support.createResult =
            ApiResult.Success(SupportRequestCreateResponse(success = true, issueKey = "DA-42"))
        openSupport()

        raiseSupportRequest()

        assertEquals(listOf(string(R.string.support_filed, "DA-42")), harness.messages)
        composeRule.onNodeWithContentDescription(SUPPORT_COMPOSE_ROOT_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a deduplicated request says it was already sent`() {
        harness.support.createResult =
            ApiResult.Success(SupportRequestCreateResponse(success = true, deduplicated = true))
        openSupport()

        raiseSupportRequest()

        assertEquals(listOf(string(R.string.support_duplicate)), harness.messages)
    }

    @Test
    fun `a pending request is a success that promises the reference, not an error`() {
        harness.support.createResult = ApiResult.Success(SupportRequestCreateResponse(success = true))
        openSupport()

        raiseSupportRequest()

        assertEquals(listOf(string(R.string.support_pending)), harness.messages)
    }

    @Test
    fun `cancelling the support dialog sends nothing`() {
        openSupport()
        harness.tap(SUPPORT_NEW_DESCRIPTION)

        harness.tap(SUPPORT_COMPOSE_CANCEL_DESCRIPTION)

        composeRule.onNodeWithContentDescription(SUPPORT_COMPOSE_ROOT_DESCRIPTION).assertDoesNotExist()
        assertTrue(harness.support.createDrafts.isEmpty())
    }

    @Test
    fun `a filed request opens by its issue key and an unfiled one by our own row id`() {
        openSupport(
            listOf(
                SupportRequestSummary(issueKey = "DA-42", id = "row-1", subject = "Filed one", statusCategory = "NEW"),
                SupportRequestSummary(issueKey = null, id = "row-2", subject = "Unfiled one", statusCategory = "NEW"),
            ),
        )

        harness.tapText("Filed one")
        assertEquals(Routes.SUPPORT_REQUEST, harness.route())
        assertEquals("DA-42", harness.argument(ARG_REQUEST_KEY))

        harness.back()
        harness.tapText("Unfiled one")
        assertEquals("row-2", harness.argument(ARG_REQUEST_KEY))
        assertEquals("client", harness.argument(ARG_ROLE))
    }

    @Test
    fun `closing a request reports the desk's own status word`() {
        harness.support.detailResult = ApiResult.Success(
            SupportRequestDetailResponse(
                success = true,
                request = SupportRequestDetail(
                    issueKey = "DA-42",
                    id = "row-1",
                    subject = "Calls drop",
                    statusName = "Open",
                    statusCategory = "NEW",
                    closeable = true,
                ),
            ),
        )
        harness.render()
        harness.navigate(Routes.supportRequest("ws-1", WorkspaceRole.AGENCY, "DA-42"))

        harness.tap(SUPPORT_REQUEST_CLOSE_DESCRIPTION)

        assertEquals(listOf("DA-42"), harness.support.closedKeys)
        assertEquals(listOf(string(R.string.support_closed, "Done")), harness.messages)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size

    private companion object {
        const val PICKER_AUTHORITY = "navhost.desk.picker"
    }
}
