package com.distronode.districtai.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.model.Contact
import com.distronode.districtai.core.model.ContactListResponse
import com.distronode.districtai.core.model.ConversationSummary
import com.distronode.districtai.core.model.ConversationsResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.contacts.CONTACTS_READ_ONLY_DESCRIPTION
import com.distronode.districtai.ui.desk.DESK_REFUSED_DESCRIPTION
import com.distronode.districtai.ui.rooms.ROOMS_JOIN_DESCRIPTION
import com.distronode.districtai.ui.rooms.ROOMS_NAME_FIELD_DESCRIPTION
import com.distronode.districtai.ui.settings.workspace.CALL_HANDLING_VIEWER_DESCRIPTION
import com.distronode.districtai.ui.settings.workspace.MESSAGING_READ_ONLY_DESCRIPTION
import com.distronode.districtai.ui.settings.workspace.WORKSPACE_SETTINGS_CAPABILITIES_ROW_DESCRIPTION
import com.distronode.districtai.ui.settings.workspace.WORKSPACE_SETTINGS_KNOWLEDGE_ROW_DESCRIPTION
import com.distronode.districtai.ui.support.SUPPORT_REFUSED_DESCRIPTION
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A role that does not parse, arriving at every destination that reads one.
 *
 * ⛔ `Routes` WRITES A NULL ROLE AS THE LITERAL "none", AND `WorkspaceRole.fromWire` READS IT BACK AS
 * NULL, WHICH MUST MEAN NO PRIVILEGES. The builders' half is pinned in `RoutesTest`; this is the other
 * half: the destination that receives "none" offers no write and spends no request the server would
 * refuse, and a role-carrying hand-off from it passes the same "none" on rather than inventing one.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class NavHostFailClosedTest {

    private val composeRule = createComposeRule()

    /** ⚠️ The drain is OUTER, so it runs after the activity has closed; see [MainLooperDrain]. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainLooperDrain()).around(composeRule)

    private val harness = NavHostHarness(composeRule)

    @After
    fun tearDown() = harness.close()

    @Test
    fun `the bar passes a null overview role on as none`() {
        harness.overviewState = NavHostHarness.content(role = null)
        harness.render()

        composeRule.onNodeWithContentDescription(NAV_INBOX_DESCRIPTION).performClick()
        composeRule.waitForIdle()
        assertEquals(Routes.INBOX, harness.route())
        assertEquals("none", harness.argument(ARG_ROLE))

        composeRule.onNodeWithContentDescription(NAV_CONTACTS_DESCRIPTION).performClick()
        composeRule.waitForIdle()
        assertEquals("none", harness.argument(ARG_ROLE))
    }

    @Test
    fun `a thread opened from an inbox with no role records no read and carries none`() {
        // ⛔ Marking read is a WRITE, and a role that fails closed may not make one.
        harness.api.conversationsResult = ApiResult.Success(
            ConversationsResponse(
                success = true,
                conversations = listOf(
                    ConversationSummary(
                        key = "k",
                        threadKey = "contact:ct-4",
                        counterpart = "+14165550100",
                        contactId = "ct-4",
                        contactName = "Ada Lovelace",
                        contactPhone = "+14165550100",
                        canSms = true,
                    ),
                ),
            ),
        )
        harness.render()
        harness.navigate(Routes.inbox("ws-1", null))
        harness.awaitText("Ada Lovelace")

        harness.tapText("Ada Lovelace")

        assertEquals(Routes.THREAD, harness.route())
        assertEquals("none", harness.argument(ARG_ROLE))
        assertTrue(harness.api.markReads.isEmpty())
    }

    @Test
    fun `contacts with no role are read-only and pass none on to the detail`() {
        harness.api.contactsResult = ApiResult.Success(
            ContactListResponse(
                success = true,
                contacts = listOf(
                    Contact(
                        id = "ct-4",
                        workspaceId = "ws-1",
                        name = "Ada Lovelace",
                        createdAt = "2026-07-20T11:00:00.000Z",
                    ),
                ),
                total = 1,
            ),
        )
        harness.render()
        harness.navigate(Routes.contacts("ws-1", null))
        harness.awaitText("Ada Lovelace")
        harness.awaitDescription(CONTACTS_READ_ONLY_DESCRIPTION)

        harness.tapText("Ada Lovelace")

        assertEquals(Routes.CONTACT_DETAIL, harness.route())
        assertEquals("none", harness.argument(ARG_ROLE))
    }

    @Test
    fun `the settings hub with no role shows the viewer's rows and passes none on`() {
        harness.render()
        harness.navigate(Routes.workspaceSettings("ws-1", null))

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_CAPABILITIES_ROW_DESCRIPTION).assertDoesNotExist()
        harness.tap(WORKSPACE_SETTINGS_KNOWLEDGE_ROW_DESCRIPTION)

        assertEquals(Routes.WORKSPACE_SETTINGS_KNOWLEDGE, harness.route())
        assertEquals("none", harness.argument(ARG_ROLE))
    }

    @Test
    fun `the desk and support refuse a missing role without spending a request`() {
        harness.render()

        harness.navigate(Routes.desk("ws-1", null))
        harness.awaitDescription(DESK_REFUSED_DESCRIPTION)
        assertEquals(0, harness.desk.settingsReads)
        assertEquals(0, harness.desk.ticketReads)

        harness.navigate(Routes.support("ws-1", null))
        harness.awaitDescription(SUPPORT_REFUSED_DESCRIPTION)
        assertEquals(0, harness.support.listReads)
    }

    @Test
    fun `a room joined from a lobby with no role is opened with none, so it publishes nothing`() {
        harness.render()
        harness.navigate(Routes.rooms("ws-1", null))

        composeRule.onNodeWithContentDescription(ROOMS_NAME_FIELD_DESCRIPTION).performTextInput("standup")
        harness.tap(ROOMS_JOIN_DESCRIPTION)

        assertEquals(Routes.ACTIVE_ROOM, harness.route())
        assertEquals("none", harness.argument(ARG_ROLE))
    }

    @Test
    fun `messaging and call handling open read-only for a role that fails closed`() {
        harness.render()

        harness.navigate(Routes.workspaceSettings("ws-1", null, Routes.SECTION_MESSAGING))
        assertEquals("none", harness.argument(ARG_ROLE))
        harness.awaitDescription(MESSAGING_READ_ONLY_DESCRIPTION)

        harness.navigate(Routes.workspaceSettings("ws-1", null, Routes.SECTION_CALLS))
        harness.awaitDescription(CALL_HANDLING_VIEWER_DESCRIPTION)
    }

    @Test
    fun `the monitors a viewer may read still load for a role that fails closed`() {
        harness.render()

        harness.navigate(Routes.workflows("ws-1", null))
        assertEquals(listOf("ws-1"), harness.api.workflowListRequests)

        harness.navigate(Routes.workspaceSettings("ws-1", null, Routes.SECTION_KNOWLEDGE))
        assertEquals(listOf("ws-1"), harness.api.knowledgeListRequests)
    }
}
