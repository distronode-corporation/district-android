package com.distronode.districtai.ui.scheduling.admin.developer

import com.distronode.districtai.core.data.MembersRepository
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.SchedulingApiKey
import com.distronode.districtai.core.model.SchedulingApiKeyCreated
import com.distronode.districtai.core.model.SchedulingItems
import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.model.SchedulingOAuthConnection
import com.distronode.districtai.core.model.SchedulingWebhook
import com.distronode.districtai.core.model.SchedulingWebhookCreated
import com.distronode.districtai.core.model.SchedulingWebhookDelivery
import com.distronode.districtai.core.model.SchedulingWebhookEvent
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.str
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_ADMIN_REFRESH_TAG
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_CANCEL_TAG
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_DISMISS_TAG
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_NOTICE_TAG
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_REJECTION_TAG
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_RETRY_TAG
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminDeps
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminTestRig
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.schedulingMe
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The developer screen as a member sees it: the three tabs, the deliveries, every dialog. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class SchedulingDeveloperScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val rig = SchedulingAdminTestRig()
    private val scope = TestScope(StandardTestDispatcher())
    private val calls = mutableListOf<String>()
    private val removals = SchedulingDeveloperRemovals(scope, rig.adminRepository, "ws") { calls += "removed" }
    private val writes = SchedulingDeveloperWrites(scope, rig.adminRepository, "ws") { calls += "changed" }
    private var state by mutableStateOf(SchedulingDeveloperUiState(timezone = "UTC", canManage = true))

    private val offline = FailureText(UiText.Resource(R.string.scheduling_admin_failure_offline), retryable = true)

    private val key = SchedulingApiKey(id = "k1", name = "Zapier", createdAt = "2026-09-01T12:00:00Z")
    private val app = SchedulingOAuthConnection(id = "o1", clientName = "Claude", lastUsedAt = "2026-09-02T09:30:00Z")
    private val hook = SchedulingWebhook(
        id = "wh1",
        url = "https://hooks.example.com/in",
        events = listOf("booking.created", "booking.paid"),
    )
    private val paused = SchedulingWebhook(
        id = "wh2",
        url = "https://b.example.com",
        events = emptyList(),
        isActive = false,
    )

    private fun show() {
        composeRule.setContent {
            DistrictTheme {
                SchedulingDeveloperScreen(
                    state = state,
                    removals = removals,
                    writes = writes,
                    onBack = { calls += "back" },
                    onRefresh = { calls += "refresh" },
                    onSelectTab = { calls += "tab:${it.id}" },
                    onToggleDeliveries = { calls += "toggle:$it" },
                    onRetryDeliveries = { calls += "retry:$it" },
                )
            }
        }
    }

    private fun settle() {
        scope.advanceUntilIdle()
        composeRule.waitForIdle()
    }

    private fun clipboard(): ClipboardManager =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)

    @Test
    fun `a tab is loading until read, says when it failed, and switches on a tap`() {
        show()
        composeRule.onNodeWithTag(SCHEDULING_DEVELOPER_ROOT_TAG).assertExists()
        composeRule.onNodeWithTag(SCHEDULING_DEVELOPER_LOADING_TAG).assertExists()
        state = state.copy(keys = SectionState.Loading)
        composeRule.onNodeWithTag(SCHEDULING_DEVELOPER_LOADING_TAG).assertExists()
        state = state.copy(keys = SectionState.Failed(offline))
        composeRule.onNodeWithTag(SCHEDULING_DEVELOPER_FAILURE_TAG).assertExists()
        composeRule.onNodeWithTag(SCHEDULING_ADMIN_REFRESH_TAG).performClick()
        val webhooksTab = "$SCHEDULING_DEVELOPER_TAB_TAG-${SchedulingDeveloperTab.WEBHOOKS}"
        composeRule.onNodeWithTag(webhooksTab).performClick()
        assertEquals(listOf("refresh", "tab:webhooks"), calls)
        composeRule.onNodeWithText(str(R.string.scheduling_admin_common_times_in, "UTC")).assertExists()
    }

    @Test
    fun `keys show their dates, and a manager revokes one after confirming`() {
        rig.admin.fail(SchedulingAdminOp.API_KEYS_DELETE, ApiResult.NetworkFailure(IOException("x")))
        state = state.copy(keys = SectionState.Ready(listOf(key)))
        show()
        val lastUsed = str(R.string.scheduling_admin_developer_never)
        composeRule.onNodeWithText(
            str(R.string.scheduling_admin_developer_key_value, "Sep 1, 2026, 12:00", lastUsed),
        ).assertExists()
        composeRule.onNodeWithTag("$DEVELOPER_KEY_REVOKE_TAG-k1").performClick()
        val prompt = str(R.string.scheduling_admin_developer_key_revoke_prompt, "Zapier")
        composeRule.onNodeWithText(prompt).assertIsDisplayed()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_CANCEL_TAG).performClick()
        composeRule.onNodeWithTag(DEVELOPER_REMOVE_DIALOG_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag("$DEVELOPER_KEY_REVOKE_TAG-k1").performClick()
        composeRule.onNodeWithTag(DEVELOPER_REMOVE_CONFIRM_TAG).performClick()
        composeRule.onNodeWithTag("$DEVELOPER_KEY_REVOKE_TAG-k1").assertIsNotEnabled()
        settle()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_failure_offline)).assertIsDisplayed()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_DISMISS_TAG).performClick()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_failure_offline)).assertDoesNotExist()

        rig.admin.clear(SchedulingAdminOp.API_KEYS_DELETE)
        rig.admin.value(SchedulingAdminOp.API_KEYS_DELETE, SchedulingNoContent(ok = true))
        composeRule.onNodeWithTag("$DEVELOPER_KEY_REVOKE_TAG-k1").performClick()
        composeRule.onNodeWithTag(DEVELOPER_REMOVE_CONFIRM_TAG).performClick()
        settle()
        assertEquals(listOf("removed"), calls)
    }

    @Test
    fun `an empty key list says so, and a member who may not manage sees no writes`() {
        state = state.copy(keys = SectionState.Ready(emptyList()))
        show()
        composeRule.onNodeWithTag(DEVELOPER_KEYS_EMPTY_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(DEVELOPER_KEY_CREATE_TAG).assertExists()
        state = state.copy(
            keys = SectionState.Ready(listOf(key, key.copy(id = "k2", lastUsedAt = ""))),
            canManage = false,
        )
        composeRule.onNodeWithTag(DEVELOPER_KEY_CREATE_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag("$DEVELOPER_KEY_REVOKE_TAG-k1").assertDoesNotExist()
        composeRule.onNodeWithTag("$DEVELOPER_KEY_ROW_TAG-k2").assertExists()
    }

    @Test
    fun `a new key is named, shown once with a copy that marks the clip sensitive, and dropped on Done`() {
        rig.admin.value(
            SchedulingAdminOp.API_KEYS_CREATE,
            SchedulingApiKeyCreated(id = "k9", name = "Zapier", key = "cno_live_secret"),
        )
        state = state.copy(keys = SectionState.Ready(emptyList()))
        show()
        composeRule.onNodeWithTag(DEVELOPER_KEY_CREATE_TAG).performClick()
        composeRule.onNodeWithTag(DEVELOPER_KEY_SUBMIT_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_REJECTION_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(DEVELOPER_KEY_NAME_TAG).performTextInput("Zapier")
        composeRule.onNodeWithTag(DEVELOPER_KEY_SUBMIT_TAG).performClick()
        settle()
        val title = str(R.string.scheduling_admin_developer_key_reveal_title, "Zapier")
        composeRule.onNodeWithText(title).assertIsDisplayed()
        composeRule.onNodeWithTag(DEVELOPER_SECRET_VALUE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("cno_live_secret").assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_developer_key_copy_action)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_developer_key_copied)).assertIsDisplayed()
        val clip = clipboard().primaryClip!!
        assertEquals("cno_live_secret", clip.getItemAt(0).text.toString())
        assertEquals(str(R.string.scheduling_admin_developer_clip_key), clip.description.label.toString())
        assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        composeRule.onNodeWithTag(DEVELOPER_SECRET_DONE_TAG).performClick()
        composeRule.onNodeWithTag(DEVELOPER_SECRET_DIALOG_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("cno_live_secret").assertDoesNotExist()
        assertEquals(listOf("changed"), calls)
    }

    @Test
    fun `a failed mint offers a retry where retrying can help`() {
        rig.admin.fail(SchedulingAdminOp.API_KEYS_CREATE, ApiResult.NetworkFailure(IOException("x")))
        writes.keys.open()
        show()
        composeRule.onNodeWithTag(DEVELOPER_KEY_NAME_TAG).performTextInput("CI")
        composeRule.onNodeWithTag(DEVELOPER_KEY_SUBMIT_TAG).performClick()
        settle()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_RETRY_TAG).performClick()
        settle()
        assertEquals(2, rig.admin.callsTo(SchedulingAdminOp.API_KEYS_CREATE).size)
    }

    @Test
    fun `connected apps carry the MCP address with a placeholder key, and a manager revokes one`() {
        rig.admin.value(SchedulingAdminOp.OAUTH_CONNECTIONS_DELETE, SchedulingNoContent(ok = true))
        state = state.copy(
            tab = SchedulingDeveloperTab.APPS,
            publicHost = "acme-book.example",
            apps = SectionState.Ready(listOf(app)),
        )
        show()
        composeRule.onNodeWithText("https://acme-book.example/mcp").assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_developer_mcp_auth_value)).assertIsDisplayed()
        composeRule.onNodeWithText("Sep 2, 2026, 09:30").assertIsDisplayed()
        composeRule.onNodeWithTag("$DEVELOPER_APP_REVOKE_TAG-o1").performClick()
        val prompt = str(R.string.scheduling_admin_developer_app_revoke_prompt, "Claude")
        composeRule.onNodeWithText(prompt).assertIsDisplayed()
        composeRule.onNodeWithTag(DEVELOPER_REMOVE_CONFIRM_TAG).performClick()
        settle()
        assertEquals(listOf("removed"), calls)

        state = state.copy(
            publicHost = null,
            apps = SectionState.Ready(listOf(app, app.copy(id = "o2"))),
            canManage = false,
        )
        composeRule.onNodeWithText(str(R.string.scheduling_admin_developer_mcp_no_host)).assertIsDisplayed()
        composeRule.onNodeWithTag(DEVELOPER_MCP_URL_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag("$DEVELOPER_APP_REVOKE_TAG-o1").assertDoesNotExist()
        state = state.copy(apps = SectionState.Ready(emptyList()))
        composeRule.onNodeWithTag(DEVELOPER_APPS_EMPTY_TAG).assertIsDisplayed()
    }

    @Test
    fun `webhooks show on or off and their events, open deliveries, and a manager deletes one`() {
        rig.admin.value(SchedulingAdminOp.WEBHOOKS_DELETE, SchedulingNoContent(ok = true))
        state = state.copy(
            tab = SchedulingDeveloperTab.WEBHOOKS,
            webhooks = SectionState.Ready(listOf(hook, paused)),
        )
        show()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_developer_webhook_on)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_developer_webhook_off)).assertIsDisplayed()
        composeRule.onNodeWithText("booking.created, booking.paid").assertIsDisplayed()
        composeRule.onNodeWithTag("$DEVELOPER_WEBHOOK_DELIVERIES_TAG-wh1").performClick()
        composeRule.onNodeWithTag("$DEVELOPER_WEBHOOK_DELETE_TAG-wh1").performClick()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_developer_webhook_delete_prompt)).assertIsDisplayed()
        composeRule.onNodeWithTag(DEVELOPER_REMOVE_CONFIRM_TAG).performClick()
        settle()
        assertEquals(listOf("toggle:wh1", "removed"), calls)

        state = state.copy(canManage = false)
        composeRule.onNodeWithTag("$DEVELOPER_WEBHOOK_DELIVERIES_TAG-wh1").assertExists()
        composeRule.onNodeWithTag("$DEVELOPER_WEBHOOK_EDIT_TAG-wh1").assertDoesNotExist()
        state = state.copy(webhooks = SectionState.Ready(emptyList()))
        composeRule.onNodeWithTag(DEVELOPER_WEBHOOKS_EMPTY_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(DEVELOPER_WEBHOOK_ADD_TAG).assertDoesNotExist()
    }

    @Test
    fun `deliveries load, fail with a retry, are empty, or read each outcome`() {
        state = state.copy(
            tab = SchedulingDeveloperTab.WEBHOOKS,
            webhooks = SectionState.Ready(listOf(hook, paused)),
            deliveries = SchedulingOpenDeliveries("wh1"),
        )
        show()
        composeRule.onNodeWithTag(DEVELOPER_DELIVERIES_LOADING_TAG).assertExists()
        state = state.copy(deliveries = SchedulingOpenDeliveries("wh1", SectionState.Failed(offline)))
        composeRule.onNodeWithTag(SCHEDULING_WRITE_RETRY_TAG).performClick()
        assertEquals(listOf("retry:wh1"), calls)
        state = state.copy(deliveries = SchedulingOpenDeliveries("wh1", SectionState.Ready(emptyList())))
        composeRule.onNodeWithTag(DEVELOPER_DELIVERIES_EMPTY_TAG).assertIsDisplayed()

        fun delivery(id: String, status: String, code: Int? = null, at: String? = "2026-09-03T10:00:00Z") =
            SchedulingWebhookDelivery(
                id = id,
                webhookId = "wh1",
                event = "booking.created",
                status = status,
                attemptCount = 1,
                responseStatus = code,
                lastAttemptedAt = at,
            )
        val rows = listOf(
            delivery("d1", "success", 200),
            delivery("d2", "pending", at = null),
            delivery("d3", "failed"),
            delivery("d4", "failed", 0),
            delivery("d5", "failed", 500),
            delivery("d6", "bounced"),
        )
        state = state.copy(deliveries = SchedulingOpenDeliveries("wh1", SectionState.Ready(rows)))
        composeRule.onNodeWithText(str(R.string.scheduling_admin_developer_delivery_delivered)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_developer_delivery_waiting)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_developer_not_tried_yet)).assertIsDisplayed()
        val noAnswer = composeRule.onAllNodes(
            hasText(str(R.string.scheduling_admin_developer_delivery_no_answer)),
        )
        assertEquals(2, noAnswer.fetchSemanticsNodes().size)
        val rejected = str(R.string.scheduling_admin_developer_delivery_rejected, "500")
        composeRule.onNodeWithText(rejected).assertIsDisplayed()
        composeRule.onNodeWithText("bounced").assertIsDisplayed()
        composeRule.onNodeWithTag("$DEVELOPER_DELIVERY_TAG-d6").assertExists()
    }

    @Test
    fun `a new webhook checks its URL, offers the four booking events and every field, and shows its secret once`() {
        rig.admin.value(
            SchedulingAdminOp.WEBHOOKS_CREATE,
            SchedulingWebhookCreated(
                id = "wh3",
                url = "https://hooks.example.com/in",
                events = listOf("booking.created"),
                secret = "whsec_1",
            ),
        )
        state = state.copy(tab = SchedulingDeveloperTab.WEBHOOKS, webhooks = SectionState.Ready(emptyList()))
        show()
        composeRule.onNodeWithTag(DEVELOPER_WEBHOOK_ADD_TAG).performClick()
        composeRule.onNodeWithTag("$DEVELOPER_WEBHOOK_FIELD_TAG-attendee_email").assertExists()
        // The web portal's four: nothing records a meeting, so the other three would never fire.
        SchedulingWebhookEvent.entries.forEach { event ->
            val tick = composeRule.onNodeWithTag("$DEVELOPER_WEBHOOK_EVENT_TAG-${event.wire}")
            if (event in SchedulingWebhookEvent.OFFERED) tick.assertExists() else tick.assertDoesNotExist()
        }
        composeRule.onNodeWithTag(DEVELOPER_WEBHOOK_SUBMIT_TAG).performClick()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_developer_webhook_url_missing)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_developer_webhook_events_missing)).assertIsDisplayed()
        composeRule.onNodeWithTag(DEVELOPER_WEBHOOK_URL_TAG).performTextInput("https://hooks.example.com/in")
        composeRule.onNodeWithTag("$DEVELOPER_WEBHOOK_EVENT_TAG-booking.created").performClick()
        composeRule.onNodeWithTag("$DEVELOPER_WEBHOOK_FIELD_TAG-attendee_email").performClick()
        composeRule.onNodeWithTag(DEVELOPER_WEBHOOK_SUBMIT_TAG).performClick()
        settle()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_developer_webhook_secret_title)).assertIsDisplayed()
        composeRule.onNodeWithText(
            str(R.string.scheduling_admin_developer_webhook_secret_note, "https://hooks.example.com/in"),
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(DEVELOPER_SECRET_COPY_TAG).performClick()
        composeRule.waitForIdle()
        assertEquals("whsec_1", clipboard().primaryClip!!.getItemAt(0).text.toString())
        composeRule.onNodeWithTag(DEVELOPER_SECRET_DONE_TAG).performClick()
        composeRule.onNodeWithText("whsec_1").assertDoesNotExist()
        val fields = rig.admin.callsTo(SchedulingAdminOp.WEBHOOKS_CREATE).single().params["fields"].toString()
        assertTrue(fields.contains("attendee_email"))
    }

    @Test
    fun `an edit shows the URL as a fact, names the events a save would drop, and says when it saved`() {
        rig.admin.value(SchedulingAdminOp.WEBHOOKS_PATCH, SchedulingNoContent(ok = true))
        state = state.copy(tab = SchedulingDeveloperTab.WEBHOOKS, webhooks = SectionState.Ready(listOf(hook)))
        show()
        composeRule.onNodeWithTag("$DEVELOPER_WEBHOOK_EDIT_TAG-wh1").performClick()
        composeRule.onNodeWithTag(DEVELOPER_WEBHOOK_URL_TAG).assertDoesNotExist()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_developer_webhook_url_fixed)).assertIsDisplayed()
        composeRule.onNodeWithText(
            str(R.string.scheduling_admin_developer_webhook_dropped_events, "booking.paid"),
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(DEVELOPER_WEBHOOK_SUBMIT_TAG).performClick()
        settle()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_NOTICE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_developer_webhook_updated)).assertIsDisplayed()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_CANCEL_TAG).performClick()
        composeRule.onNodeWithTag(DEVELOPER_WEBHOOK_DIALOG_TAG).assertDoesNotExist()
        // A webhook added with no secret says so and cannot be added twice.
        rig.admin.value(
            SchedulingAdminOp.WEBHOOKS_CREATE,
            SchedulingWebhookCreated(id = "wh4", url = "https://c.example.com", events = listOf("booking.created")),
        )
        writes.webhooks.openCreate()
        writes.webhooks.editUrl("https://c.example.com")
        writes.webhooks.toggleEvent(SchedulingWebhookEvent.BOOKING_CREATED, true)
        writes.webhooks.submit()
        settle()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_developer_webhook_added)).assertIsDisplayed()
        composeRule.onNodeWithTag(DEVELOPER_WEBHOOK_SUBMIT_TAG).assertIsNotEnabled()
    }

    @Test
    fun `a removal or a mint on its way disables its controls`() {
        val gate = CompletableDeferred<Unit>()
        rig.admin.gate = gate
        rig.admin.value(SchedulingAdminOp.OAUTH_CONNECTIONS_DELETE, SchedulingNoContent(ok = true))
        rig.admin.value(SchedulingAdminOp.WEBHOOKS_DELETE, SchedulingNoContent(ok = true))
        state = state.copy(tab = SchedulingDeveloperTab.APPS, apps = SectionState.Ready(listOf(app)))
        show()
        composeRule.onNodeWithTag("$DEVELOPER_APP_REVOKE_TAG-o1").performClick()
        composeRule.onNodeWithTag(DEVELOPER_REMOVE_CONFIRM_TAG).performClick()
        settle()
        composeRule.onNodeWithTag("$DEVELOPER_APP_REVOKE_TAG-o1").assertIsNotEnabled()

        state = state.copy(tab = SchedulingDeveloperTab.WEBHOOKS, webhooks = SectionState.Ready(listOf(hook)))
        composeRule.onNodeWithTag("$DEVELOPER_WEBHOOK_DELETE_TAG-wh1").performClick()
        composeRule.onNodeWithTag(DEVELOPER_REMOVE_CONFIRM_TAG).performClick()
        settle()
        composeRule.onNodeWithTag("$DEVELOPER_WEBHOOK_DELETE_TAG-wh1").assertIsNotEnabled()

        writes.keys.open()
        writes.keys.editName("CI")
        writes.keys.submit()
        settle()
        composeRule.onNodeWithTag(DEVELOPER_KEY_NAME_TAG).assertIsNotEnabled()
        rig.admin.gate = null
        gate.complete(Unit)
        settle()
    }

    @Test
    fun `the route re-reads when the session changes`() {
        rig.admin.value(SchedulingAdminOp.ME_GET, schedulingMe(timezone = "UTC"))
        rig.admin.value(SchedulingAdminOp.API_KEYS_LIST, SchedulingItems(listOf(key)))
        var epoch by mutableStateOf(0)
        composeRule.setContent {
            val deps = SchedulingAdminDeps(
                admin = rig.adminRepository,
                scheduling = rig.scheduling,
                workspaceId = "ws-epoch",
                sessionEpoch = epoch,
                onBack = {},
                navigate = {},
                openUrl = { true },
                members = MembersRepository(rig.district),
            )
            DistrictTheme { SchedulingDeveloperRoute(deps) }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { rig.admin.callsTo(SchedulingAdminOp.API_KEYS_LIST).size == 1 }
        epoch = 1
        composeRule.waitForIdle()
        assertEquals(2, rig.admin.callsTo(SchedulingAdminOp.ME_GET).size)
    }

    @Test
    fun `the route reads the context and the first tab through the real view model`() {
        rig.admin.value(SchedulingAdminOp.ME_GET, schedulingMe(timezone = "UTC"))
        rig.admin.value(SchedulingAdminOp.API_KEYS_LIST, SchedulingItems(listOf(key)))
        val deps = SchedulingAdminDeps(
            admin = rig.adminRepository,
            scheduling = rig.scheduling,
            workspaceId = "ws-route",
            sessionEpoch = 0,
            onBack = { calls += "back" },
            navigate = {},
            openUrl = { true },
            members = MembersRepository(rig.district),
        )
        composeRule.setContent { DistrictTheme { SchedulingDeveloperRoute(deps) } }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasTestTag("$DEVELOPER_KEY_ROW_TAG-k1")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(SCHEDULING_ADMIN_REFRESH_TAG).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            rig.admin.callsTo(SchedulingAdminOp.API_KEYS_LIST).size == 2
        }
        // The webhooks tab, one row's deliveries (which fail offline), and their retry.
        rig.admin.value(SchedulingAdminOp.WEBHOOKS_LIST, SchedulingItems(listOf(hook)))
        composeRule.onNodeWithTag("$SCHEDULING_DEVELOPER_TAB_TAG-${SchedulingDeveloperTab.WEBHOOKS}").performClick()
        val deliveries = "$DEVELOPER_WEBHOOK_DELIVERIES_TAG-wh1"
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasTestTag(deliveries)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("$DEVELOPER_WEBHOOK_DELIVERIES_TAG-wh1").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasTestTag(SCHEDULING_WRITE_RETRY_TAG)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(SCHEDULING_WRITE_RETRY_TAG).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            rig.admin.callsTo(SchedulingAdminOp.WEBHOOKS_DELIVERIES).size == 2
        }
    }

    @Test
    fun `every dialog's Cancel closes it, and a failed webhook save offers a retry`() {
        val gate = CompletableDeferred<Unit>()
        rig.admin.fail(SchedulingAdminOp.WEBHOOKS_PATCH, ApiResult.NetworkFailure(IOException("x")))
        state = state.copy(
            tab = SchedulingDeveloperTab.WEBHOOKS,
            apps = SectionState.Ready(listOf(app)),
            webhooks = SectionState.Ready(listOf(hook)),
        )
        show()
        writes.keys.open()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_CANCEL_TAG).performClick()
        composeRule.onNodeWithTag(DEVELOPER_KEY_DIALOG_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag("$DEVELOPER_WEBHOOK_DELETE_TAG-wh1").performClick()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_CANCEL_TAG).performClick()
        composeRule.onNodeWithTag(DEVELOPER_REMOVE_DIALOG_TAG).assertDoesNotExist()
        removals.appRevoke.ask(app)
        composeRule.onNodeWithTag(SCHEDULING_WRITE_CANCEL_TAG).performClick()
        composeRule.onNodeWithTag(DEVELOPER_REMOVE_DIALOG_TAG).assertDoesNotExist()

        rig.admin.gate = gate
        writes.webhooks.openEdit(hook)
        composeRule.onNodeWithTag(DEVELOPER_WEBHOOK_SUBMIT_TAG).performClick()
        settle()
        composeRule.onNodeWithTag("$DEVELOPER_WEBHOOK_EVENT_TAG-booking.created").assertIsNotEnabled()
        rig.admin.gate = null
        gate.complete(Unit)
        settle()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_RETRY_TAG).performClick()
        settle()
        assertEquals(2, rig.admin.callsTo(SchedulingAdminOp.WEBHOOKS_PATCH).size)
        writes.close()
        writes.webhooks.openCreate()
        rig.admin.gate = CompletableDeferred()
        writes.webhooks.editUrl("https://a.example.com")
        writes.webhooks.toggleEvent(SchedulingWebhookEvent.BOOKING_CREATED, true)
        writes.webhooks.submit()
        settle()
        composeRule.onNodeWithTag(DEVELOPER_WEBHOOK_URL_TAG).assertIsNotEnabled()
    }
}
