package com.distronode.districtai.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.ApiEnvironment
import com.distronode.districtai.R
import com.distronode.districtai.core.model.BillingInvoice
import com.distronode.districtai.core.model.Contact
import com.distronode.districtai.core.model.ContactListResponse
import com.distronode.districtai.core.model.DeviceListResponse
import com.distronode.districtai.core.model.NativeDevice
import com.distronode.districtai.core.model.SETUP_STEP_DONE
import com.distronode.districtai.core.model.StripeBilling
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.billing.invoiceOpenDescription
import com.distronode.districtai.ui.calls.CALL_DETAIL_ROOT_DESCRIPTION
import com.distronode.districtai.ui.contacts.CONTACT_DETAIL_ROOT_DESCRIPTION
import com.distronode.districtai.ui.devices.DEVICES_CONFIRM_DEVICE_DESCRIPTION
import com.distronode.districtai.ui.devices.revokeRowDescription
import com.distronode.districtai.ui.marketplace.MARKETPLACE_BUY_WEB_DESCRIPTION
import com.distronode.districtai.ui.marketplace.marketplaceWebUrl
import com.distronode.districtai.ui.overview.OVERVIEW_FINISH_SETUP_ACTION_DESCRIPTION
import com.distronode.districtai.ui.overview.OverviewUiState
import com.distronode.districtai.ui.setup.NumberTags
import com.distronode.districtai.ui.setup.SETUP_WIZARD_ROUTE
import com.distronode.districtai.ui.setup.SetupTags
import com.distronode.districtai.ui.setup.setupState
import com.distronode.districtai.ui.setup.setupWizardRoute
import com.distronode.districtai.ui.settings.SETTINGS_DELETE_ACCOUNT_DESCRIPTION
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import com.distronode.districtai.core.network.testing.testCall

/**
 * Everything [DistrictNavHost] hands to another app: a browser, a media player, the share sheet.
 *
 * ⛔ WHAT IS PROVEN HERE IS THE WIRING, NOT THE LAUNCHER. `CustomTabsLauncher` has its own tests; what
 * only this graph can get wrong is WHICH URL a button hands over, from which context, and whether a
 * failed hand-off reaches the host's message channel or vanishes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class NavHostHandOffTest {

    private val composeRule = createComposeRule()

    /** ⚠️ The drain is OUTER, so it runs after the activity has closed; see [MainLooperDrain]. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainLooperDrain()).around(composeRule)

    private val harness = NavHostHarness(composeRule)

    private val browserMissing: String =
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.settings_browser_missing)

    @After
    fun tearDown() = harness.close()

    private fun opened(): List<String?> = harness.started.map { it.dataString }

    // ── The browser hand-offs ──────────────────────────────────────────────────────────────

    @Test
    fun `finishing setup opens the wizard in the app, and closing it reads the overview again`() {
        harness.installBrowser()
        harness.overviewState = NavHostHarness.content(showFinishSetup = true)
        harness.setup.setupResult = ApiResult.Forbidden("Only the workspace owner can set up this workspace.")
        harness.render()

        harness.tap(OVERVIEW_FINISH_SETUP_ACTION_DESCRIPTION)

        assertEquals(SETUP_WIZARD_ROUTE, harness.route())
        assertEquals("ws-1", harness.argument(ARG_WORKSPACE_ID))
        assertTrue("the wizard runs here, not in a browser", opened().isEmpty())
        harness.awaitDescription(SetupTags.REFUSED)
        assertEquals(listOf("ws-1"), harness.setup.reads)

        val listReads = harness.api.workspaceListReads
        harness.tap(SetupTags.DONE)
        assertEquals(Routes.OVERVIEW, harness.route())
        harness.awaitMain { harness.api.workspaceListReads > listReads }
    }

    @Test
    fun `the wizard opens for a workspace the list does not name, reading that workspace`() {
        harness.setup.setupResult = ApiResult.Forbidden("Only the workspace owner can set up this workspace.")
        harness.render()
        harness.navigate(setupWizardRoute("ws-elsewhere"))

        harness.awaitDescription(SetupTags.REFUSED)
        assertEquals("ws-elsewhere", harness.argument(ARG_WORKSPACE_ID))
        assertEquals(listOf("ws-elsewhere"), harness.setup.reads)
    }

    @Test
    fun `a wizard page with no browser is reported, and with one opens in it`() {
        harness.overviewState = NavHostHarness.content(showFinishSetup = true)
        harness.setup.setupResult = ApiResult.Success(setupState(business = SETUP_STEP_DONE, numbersHeld = 0))
        harness.render()
        harness.tap(OVERVIEW_FINISH_SETUP_ACTION_DESCRIPTION)
        harness.awaitDescription(NumberTags.OPEN_PAGE)

        harness.tap(NumberTags.OPEN_PAGE)
        assertEquals(listOf(browserMissing), harness.messages)
        assertTrue(opened().isEmpty())

        harness.installBrowser()
        harness.tap(NumberTags.OPEN_PAGE)
        assertEquals(1, opened().size)
        assertTrue(opened().single().orEmpty().startsWith("https://"))
        assertEquals("the second tap opened, so nothing more was said", listOf(browserMissing), harness.messages)
    }

    @Test
    fun `account deletion opens in the app, never the browser`() {
        // ⛔ IN THE APP SINCE THE iOS PARITY PORT: the re-authentication and the typed email happen
        // here, and the deletion page stays only as the store listing's data-deletion URL.
        harness.installBrowser()
        harness.render()
        harness.navigate(Routes.SETTINGS)

        harness.tap(SETTINGS_DELETE_ACCOUNT_DESCRIPTION)

        assertEquals("account/delete", harness.route())
        assertTrue("nothing opens in a browser", harness.started.isEmpty())
        assertTrue(harness.messages.isEmpty())
    }

    @Test
    fun `buying a number opens the web marketplace, and a missing browser is reported`() {
        harness.render()
        harness.navigate(Routes.marketplace("ws-1", WorkspaceRole.AGENCY))

        harness.tap(MARKETPLACE_BUY_WEB_DESCRIPTION)
        assertEquals(listOf(browserMissing), harness.messages)

        harness.installBrowser()
        harness.tap(MARKETPLACE_BUY_WEB_DESCRIPTION)
        assertEquals(listOf(marketplaceWebUrl(ApiEnvironment.baseUrl)), opened())
    }

    @Test
    fun `an invoice opens its own hosted Stripe page`() {
        harness.installBrowser()
        harness.api.stripeBillingResult = ApiResult.Success(
            StripeBilling(
                invoices = listOf(
                    BillingInvoice(id = "in_paid", hostedInvoiceUrl = "https://invoice.stripe.test/in_paid"),
                ),
            ),
        )
        harness.render()
        harness.navigate(Routes.billing("ws-1", WorkspaceRole.AGENCY))

        harness.tap(invoiceOpenDescription("in_paid"))

        assertEquals(listOf("https://invoice.stripe.test/in_paid"), opened())
    }

    // ── The overview's retry ───────────────────────────────────────────────────────────────

    @Test
    fun `retry on an unavailable overview asks the hoisted view model to read again`() {
        harness.overviewState = OverviewUiState.Unavailable(message = UiText.Literal("Could not load."))
        harness.render()
        val before = harness.api.workspaceListReads

        harness.tapText(overviewRetry())

        assertTrue(
            "the retry must reach the overview ViewModel's load",
            harness.api.workspaceListReads > before,
        )
    }

    @Test
    fun `retry on a loaded overview refreshes it`() {
        harness.render()
        val before = harness.api.workspaceListReads

        harness.tapText(overviewRetry())

        assertTrue(harness.api.workspaceListReads > before)
    }

    private fun overviewRetry(): String =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(R.string.overview_retry)

    // ── Drill-downs that need data ─────────────────────────────────────────────────────────

    @Test
    fun `revoking another device goes to the server and keeps this session`() {
        harness.api.devicesResult = ApiResult.Success(
            DeviceListResponse(
                success = true,
                devices = listOf(NativeDevice(deviceId = "device-other", platform = "ios", createdAt = "2026-07-20")),
            ),
        )
        harness.render()
        harness.navigate(Routes.DEVICES)
        val epoch = harness.container.sessionSignal.epoch.value

        harness.tap(revokeRowDescription("device-other"))
        harness.tap(DEVICES_CONFIRM_DEVICE_DESCRIPTION)

        assertEquals(listOf("revoke:device-other"), harness.api.deviceWrites)
        assertEquals(
            "another device's revoke must not sign this one out",
            epoch,
            harness.container.sessionSignal.epoch.value,
        )
    }

    @Test
    fun `a contact row opens that contact with the list's role`() {
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
        harness.navigate(Routes.contacts("ws-1", WorkspaceRole.CLIENT))
        harness.awaitText("Ada Lovelace")

        harness.tapText("Ada Lovelace")

        assertEquals(Routes.CONTACT_DETAIL, harness.route())
        assertEquals("ct-4", harness.argument(ARG_CONTACT_ID))
        assertEquals("client", harness.argument(ARG_ROLE))
        harness.awaitDescription(CONTACT_DETAIL_ROOT_DESCRIPTION)
    }

    @Test
    fun `a call log row opens that call`() {
        harness.api.callsResult = ApiResult.Success(listOf(testCall(id = "call-9")))
        harness.render()
        harness.navigate(Routes.callLog("ws-1"))
        harness.awaitText("Ada")

        harness.tapText("Ada")

        assertEquals(Routes.CALL_DETAIL, harness.route())
        assertEquals("call-9", harness.argument(ARG_CALL_ID))
        harness.awaitDescription(CALL_DETAIL_ROOT_DESCRIPTION)
    }
}
