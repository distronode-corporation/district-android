package com.distronode.districtai.ui.marketplace

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.CountryRequirements
import com.distronode.districtai.core.model.ListedNumber
import com.distronode.districtai.core.model.NumberRegistration
import com.distronode.districtai.core.model.ProviderStatusEntry
import com.distronode.districtai.core.model.RegistrationDocument
import com.distronode.districtai.core.model.RegulatoryRequirement
import com.distronode.districtai.core.model.SipTrunk
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.str
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Registrations and Carrier tabs, a number's actions, and the confirmations, state by state. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class NumberManagementScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val asked = mutableListOf<Any>()
    private val failure = FailureText(UiText.Literal("Could not read it."))

    private val handlers = ProvisioningHandlers(
        onRetryCarrier = { asked += "retryCarrier" },
        onRetryVerify = { asked += "retryVerify" },
        onRetrySip = { asked += "retrySip" },
        onSetVerify = { asked += "verify:$it" },
        onSipDraft = { asked += it },
        onCreateSip = { asked += "createSip" },
        onLookupNumber = { asked += "lookup:$it" },
        onRequestConfirmation = { asked += it },
        onA2PDraft = { asked += it },
        onTollFreeDraft = { asked += it },
        onRegistrationDraft = { asked += it },
        onCheckRequirements = { asked += "requirements" },
        onStartRegistration = { asked += "start" },
        onRetryRegistrations = { asked += "retryRegistrations" },
        onRemoveDocument = { bundle, document -> asked += "remove:$bundle:$document" },
    )

    private fun render(
        state: NumberProvisioningUiState,
        tab: MarketplaceTab,
        canProvision: Boolean = true,
    ) {
        composeRule.setContent {
            DistrictTheme {
                MarketplaceScreen(
                    state = MarketplaceUiState(
                        tab = tab,
                        owned = OwnedState.Ready(listOf(ListedNumber(phoneNumber = "+14165550142")))
                    ),
                    role = if (canProvision) WorkspaceRole.AGENCY else WorkspaceRole.VIEWER,
                    onSelectTab = { asked += it },
                    onUpdateForm = {},
                    onSearch = {},
                    onRetryOwned = {},
                    onOpenWeb = {},
                    onBack = {},
                    provisioning = ProvisioningUi(
                        state = state,
                        canProvision = canProvision,
                        handlers = handlers,
                        onOpenNumber = { asked += "open:${it.phoneNumber}" },
                        onCloseNumber = { asked += "close" },
                        onConfigure = { asked += "configure:$it" },
                        onConfirm = { asked += "confirm" },
                        onDismissConfirmation = { asked += "dismiss" },
                    ),
                )
            }
        }
    }

    private fun tap(tag: String) = composeRule.onNodeWithTag(tag).performScrollTo().performClick()

    private fun shows(text: String) = composeRule.onNodeWithText(text).performScrollTo().assertIsDisplayed()

    // ── The tabs and the owned list ─────────────────────────────────────────────────────────────

    @Test
    fun `the four tabs select, and an owned row opens its actions`() {
        render(NumberProvisioningUiState(), MarketplaceTab.OWNED)
        tap(MARKETPLACE_TAB_REGISTRATIONS_DESCRIPTION)
        tap(MARKETPLACE_TAB_CARRIER_DESCRIPTION)
        tap(ownedNumberTag("+14165550142"))
        assertEquals(listOf(MarketplaceTab.REGISTRATIONS, MarketplaceTab.CARRIER, "open:+14165550142"), asked)
        shows(str(R.string.numbers_read_only))
    }

    @Test
    fun `a viewer is told who can change numbers`() {
        render(NumberProvisioningUiState(), MarketplaceTab.OWNED, canProvision = false)
        shows(str(R.string.numbers_read_only_viewer))
    }

    // ── Carrier ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the carrier tab while loading`() {
        render(NumberProvisioningUiState(), MarketplaceTab.CARRIER)
        composeRule.onNodeWithTag(CARRIER_TAB_TAG).assertIsDisplayed()
    }

    @Test
    fun `carrier reads that failed offer a retry each`() {
        render(
            NumberProvisioningUiState(
                carrier = SectionState.Failed(failure),
                verify = SectionState.Failed(failure),
                sip = SectionState.Failed(failure),
            ),
            MarketplaceTab.CARRIER,
        )
        listOf(CARRIER_FAILURE_TAG, VERIFY_FAILURE_TAG, SIP_FAILURE_TAG).forEach {
            composeRule.onNodeWithTag(it).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `no carrier connected is said, not drawn as an empty list`() {
        render(
            NumberProvisioningUiState(
                carrier = SectionState.Ready(CarrierAccounts(emptyMap(), emptyList(), emptyList()))
            ),
            MarketplaceTab.CARRIER,
        )
        shows(str(R.string.numbers_carrier_none_title))
    }

    @Test
    fun `connected, managed and refused carriers each say what they are`() {
        val entries = mapOf(
            "twilio" to ProviderStatusEntry(
                connected = true,
                accountName = "Acme",
                balance = "12.50",
                currency = "USD",
                numberCount = 3,
            ),
            "telnyx" to ProviderStatusEntry(connected = true, balance = "4.00"),
            "managed" to ProviderStatusEntry(connected = true, managed = true),
            "sinch" to ProviderStatusEntry(connected = false),
        )
        render(
            NumberProvisioningUiState(
                carrier = SectionState.Ready(
                    CarrierAccounts(entries, listOf("managed", "telnyx", "twilio"), listOf("sinch"))
                ),
                verify = SectionState.Ready(VerifyService(enabled = false)),
                sip = SectionState.Ready(emptyList()),
            ),
            MarketplaceTab.CARRIER,
        )
        shows("Acme")
        shows(str(R.string.numbers_carrier_balance_with_currency, "12.50", "USD"))
        shows(str(R.string.numbers_carrier_balance_amount, "4.00"))
        shows(str(R.string.numbers_carrier_managed))
        shows(str(R.string.numbers_carrier_refused))
        shows(str(R.string.numbers_verify_off))
        shows(str(R.string.numbers_sip_empty_body))
        tap(VERIFY_TOGGLE_TAG)
        assertEquals(listOf("verify:true"), asked)
    }

    @Test
    fun `an enabled sender offers to stop, and the SIP form creates a trunk`() {
        render(
            NumberProvisioningUiState(
                carrier = SectionState.Ready(
                    CarrierAccounts(mapOf("t" to ProviderStatusEntry(connected = true)), listOf("t"), emptyList())
                ),
                verify = SectionState.Ready(VerifyService(enabled = true)),
                sip = SectionState.Ready(
                    listOf(
                        SipTrunk(id = "T1", domain = "acme", ipAccessControlList = listOf("1.2.3.4"), status = "active")
                    ),
                ),
                sipDraft = SipTrunkDraft(name = "PBX", domain = "acme", ipAddresses = "1.2.3.4"),
            ),
            MarketplaceTab.CARRIER,
        )
        shows(str(R.string.numbers_verify_on))
        shows(str(R.string.numbers_verify_disable_action))
        shows(str(R.string.numbers_sip_unprovisioned))
        composeRule.onNodeWithTag(
            numberFieldTag(R.string.numbers_sip_name_label)
        ).performScrollTo().performTextInput("X")
        tap(SIP_CREATE_TAG)
        tap(VERIFY_TOGGLE_TAG)
        assertEquals(
            listOf(SipTrunkDraft(name = "XPBX", domain = "acme", ipAddresses = "1.2.3.4"), "createSip", "verify:false"),
            asked
        )
    }

    @Test
    fun `the lookup asks for a confirmation and shows its answer`() {
        render(
            NumberProvisioningUiState(
                lookupNumber = "+14165550142",
                lookupResult = LookupSummary(valid = true, lines = listOf("+14165550142", "CA")),
            ),
            MarketplaceTab.CARRIER,
        )
        tap(LOOKUP_TAG)
        assertEquals(listOf<Any>(NumberConfirmation.Lookup), asked)
        shows("+14165550142 · CA")
    }

    @Test
    fun `an invalid lookup says so, and a failed one says why`() {
        render(
            NumberProvisioningUiState(
                lookupResult = LookupSummary(valid = false, lines = emptyList()),
                lookupWrite = NumberWriteState.Failed(
                    FailureText(UiText.Literal("Lookup failed.")),
                    resubmitAllowed = false
                ),
            ),
            MarketplaceTab.CARRIER,
        )
        shows(str(R.string.numbers_lookup_invalid))
        shows("Lookup failed.")
        shows(str(R.string.numbers_write_unrepeatable))
        composeRule.onNodeWithTag(LOOKUP_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `the A2P and toll-free forms choose, and ask for a confirmation when ready`() {
        render(
            NumberProvisioningUiState(
                a2pDraft = A2PDraft(businessName = "A", campaignDescription = "d", sampleOne = "1", sampleTwo = "2"),
                tollFreeDraft = TollFreeDraft(phoneNumber = "+1", businessName = "A", optInImageUrl = "u"),
            ),
            MarketplaceTab.CARRIER,
        )
        // ⚠️ THE CHOICES SCROLL SIDEWAYS, so a later one can sit off-screen: clicked by its action.
        composeRule.onNodeWithTag(choiceTag("MARKETING")).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag(choiceTag("Notifications")).performSemanticsAction(SemanticsActions.OnClick)
        tap(A2P_SUBMIT_TAG)
        tap(TFV_SUBMIT_TAG)
        assertEquals(
            listOf(
                A2PDraft(
                    businessName = "A",
                    campaignDescription = "d",
                    sampleOne = "1",
                    sampleTwo = "2",
                    campaignType = "MARKETING"
                ),
                TollFreeDraft(phoneNumber = "+1", businessName = "A", optInImageUrl = "u", useCase = "Notifications"),
                NumberConfirmation.A2P,
                NumberConfirmation.TollFree,
            ),
            asked,
        )
    }

    @Test
    fun `the forms stay put while a carrier write runs`() {
        render(NumberProvisioningUiState(carrierWrite = NumberWriteState.Running), MarketplaceTab.CARRIER)
        composeRule.onNodeWithTag(A2P_SUBMIT_TAG).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(TFV_SUBMIT_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `a viewer sees the carrier but none of its controls`() {
        render(
            NumberProvisioningUiState(
                verify = SectionState.Ready(VerifyService(enabled = false)),
                sip = SectionState.Ready(emptyList()),
            ),
            MarketplaceTab.CARRIER,
            canProvision = false,
        )
        composeRule.onNodeWithTag(VERIFY_TOGGLE_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(SIP_CREATE_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(LOOKUP_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(A2P_SUBMIT_TAG).assertDoesNotExist()
        shows(str(R.string.numbers_compliance_no_status))
    }

    // ── Registrations ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a viewer is told filings need another role`() {
        render(NumberProvisioningUiState(), MarketplaceTab.REGISTRATIONS, canProvision = false)
        shows(str(R.string.numbers_viewer_cannot_provision))
        composeRule.onNodeWithTag(REGISTRATION_START_TAG).assertDoesNotExist()
    }

    @Test
    fun `the start form checks requirements and starts a filing`() {
        render(
            NumberProvisioningUiState(
                registrationDraft = RegistrationDraft(country = "DE"),
                requirements = RequirementsState.Loading,
            ),
            MarketplaceTab.REGISTRATIONS,
        )
        tap(REQUIREMENTS_CHECK_TAG)
        tap(REGISTRATION_START_TAG)
        composeRule.onNodeWithTag(
            numberFieldTag(R.string.numbers_registration_name_label)
        ).performScrollTo().performTextInput("B")
        assertEquals(listOf("requirements", "start", RegistrationDraft(country = "DE", friendlyName = "B")), asked)
    }

    @Test
    fun `without a country nothing can be checked or started`() {
        render(NumberProvisioningUiState(registrations = SectionState.Failed(failure)), MarketplaceTab.REGISTRATIONS)
        composeRule.onNodeWithTag(REQUIREMENTS_CHECK_TAG).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(REGISTRATION_START_TAG).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(REGISTRATIONS_FAILURE_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `requirements show what the country asks for, or that it asks nothing`() {
        val regulation = CountryRequirements(
            friendlyName = "Germany local",
            requirements = listOf(
                RegulatoryRequirement(
                    kind = "end_user",
                    name = "Business",
                    fields = listOf("business_name", "website")
                ),
                RegulatoryRequirement(kind = "document", name = "Proof of address"),
            ),
        )
        render(
            NumberProvisioningUiState(requirements = RequirementsState.Ready(regulation, purchasable = false)),
            MarketplaceTab.REGISTRATIONS,
        )
        shows("Germany local")
        shows(str(R.string.numbers_requirements_not_purchasable))
        shows("business_name, website")
        shows(str(R.string.numbers_requirement_no_fields))
        shows(str(R.string.numbers_requirement_kind_document))
    }

    @Test
    fun `a country with no regulation says so`() {
        render(
            NumberProvisioningUiState(requirements = RequirementsState.NoRegulation("CA", purchasable = false)),
            MarketplaceTab.REGISTRATIONS,
        )
        shows(str(R.string.numbers_requirements_none_heading, "CA"))
        shows(str(R.string.numbers_requirements_not_purchasable))
    }

    @Test
    fun `a purchasable country with no regulation, and a failed requirements read`() {
        render(
            NumberProvisioningUiState(requirements = RequirementsState.NoRegulation("US", purchasable = true)),
            MarketplaceTab.REGISTRATIONS,
        )
        composeRule.onNodeWithText(str(R.string.numbers_requirements_not_purchasable)).assertDoesNotExist()
    }

    @Test
    fun `a failed requirements read offers a retry`() {
        render(
            NumberProvisioningUiState(requirements = RequirementsState.Failed(failure)),
            MarketplaceTab.REGISTRATIONS
        )
        composeRule.onNodeWithTag(REQUIREMENTS_FAILURE_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `no filings yet is an empty state under the countries already open`() {
        render(
            NumberProvisioningUiState(
                registrations = SectionState.Ready(Registrations(emptyList(), listOf("CA"), listOf("US", "GB"))),
            ),
            MarketplaceTab.REGISTRATIONS,
        )
        shows(str(R.string.numbers_registrations_empty_title))
        shows("US, GB")
        shows("CA")
    }

    @Test
    fun `a draft offers its submit and its documents' removal, with every status labelled`() {
        val draft = NumberRegistration(
            id = "BU1",
            isoCountry = "DE",
            numberType = "local",
            friendlyName = "Berlin",
            status = "draft",
            rejectionReasons = listOf("Address unreadable."),
            documents = listOf(
                RegistrationDocument(id = "D1", requirementName = "Proof", stored = true),
                RegistrationDocument(id = "D2", requirementName = "Licence", submitted = true),
                RegistrationDocument(id = "D3", requirementName = "Other"),
            ),
        )
        val others = listOf(
            "pending-review",
            "in-review",
            "twilio-approved",
            "twilio-rejected",
            "novel"
        ).mapIndexed { i, status ->
            NumberRegistration(id = "BU-$i", isoCountry = "FR", numberType = "mobile", status = status)
        }
        render(
            NumberProvisioningUiState(
                registrations = SectionState.Ready(Registrations(listOf(draft) + others, emptyList(), emptyList()))
            ),
            MarketplaceTab.REGISTRATIONS,
        )
        shows("Berlin")
        shows("Address unreadable.")
        shows(str(R.string.numbers_registration_document_stored))
        shows(str(R.string.numbers_registration_document_filed))
        shows(str(R.string.numbers_registration_document_recorded))
        listOf(
            R.string.numbers_registration_status_draft,
            R.string.numbers_registration_status_pending_review,
            R.string.numbers_registration_status_in_review,
            R.string.numbers_registration_status_approved,
            R.string.numbers_registration_status_refused,
        ).forEach { shows(str(it)) }
        shows("novel")
        tap(documentRemoveTag("D1"))
        tap(registrationSubmitTag("BU1"))
        composeRule.onNodeWithTag(registrationSubmitTag("BU-0")).assertDoesNotExist()
        assertEquals(listOf("remove:BU1:D1", NumberConfirmation.SubmitRegistration("BU1", "DE")), asked)
    }

    @Test
    fun `a running filing write disarms the draft's controls`() {
        val draft = NumberRegistration(
            id = "BU1",
            status = "draft",
            documents = listOf(RegistrationDocument(id = "D1"))
        )
        render(
            NumberProvisioningUiState(
                registrations = SectionState.Ready(Registrations(listOf(draft), emptyList(), emptyList())),
                registrationWrite = NumberWriteState.Running,
            ),
            MarketplaceTab.REGISTRATIONS,
        )
        composeRule.onNodeWithTag(documentRemoveTag("D1")).assertDoesNotExist()
        composeRule.onNodeWithTag(registrationSubmitTag("BU1")).performScrollTo().assertIsNotEnabled()
    }

    // ── A number's actions and the confirmations ────────────────────────────────────────────────

    @Test
    fun `a number's actions reapply routing and ask before a release`() {
        render(
            NumberProvisioningUiState(
                selected = ListedNumber(phoneNumber = "+14165550142", provider = "twilio", status = "active"),
                numberWrite = NumberWriteState.Done(
                    UiText.Resource(R.string.numbers_release_warning_eyebrow),
                    listOf("Rule kept.")
                ),
            ),
            MarketplaceTab.OWNED,
        )
        composeRule.onNodeWithText(str(R.string.numbers_release_warning_eyebrow) + " Rule kept.").assertIsDisplayed()
        composeRule.onNodeWithTag(NUMBER_CONFIGURE_TAG).performClick()
        composeRule.onNodeWithTag(NUMBER_RELEASE_TAG).performClick()
        composeRule.onNodeWithTag(NUMBER_ACTIONS_CLOSE_TAG).performClick()
        assertEquals(listOf("configure:+14165550142", NumberConfirmation.Release("+14165550142"), "close"), asked)
    }

    @Test
    fun `a managed line and a viewer get no number actions`() {
        render(
            NumberProvisioningUiState(selected = ListedNumber(phoneNumber = "+1", managed = true)),
            MarketplaceTab.OWNED,
        )
        composeRule.onNodeWithText(str(R.string.numbers_managed_no_release)).assertIsDisplayed()
        composeRule.onNodeWithTag(NUMBER_RELEASE_TAG).assertDoesNotExist()
    }

    @Test
    fun `a viewer opening a number is told why there is nothing to do`() {
        render(
            NumberProvisioningUiState(selected = ListedNumber(phoneNumber = "+1")),
            MarketplaceTab.OWNED,
            canProvision = false
        )
        composeRule.onNodeWithText(str(R.string.numbers_viewer_cannot_provision)).assertIsDisplayed()
    }

    @Test
    fun `a disarmed number write disables its actions`() {
        render(
            NumberProvisioningUiState(
                selected = ListedNumber(phoneNumber = "+1"),
                numberWrite = NumberWriteState.Running
            ),
            MarketplaceTab.OWNED,
        )
        composeRule.onNodeWithTag(NUMBER_RELEASE_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(NUMBER_CONFIGURE_TAG).assertIsNotEnabled()
    }

    @Test
    fun `every confirmation names what it does, and confirms or cancels`() {
        val expected = mapOf(
            NumberConfirmation.Release("+1") to R.string.numbers_release_confirm_title,
            NumberConfirmation.SubmitRegistration("BU1", "DE") to R.string.numbers_submit_confirm_title,
            NumberConfirmation.A2P to R.string.numbers_a2p_confirm_title,
            NumberConfirmation.TollFree to R.string.numbers_tfv_confirm_title,
            NumberConfirmation.Lookup to R.string.numbers_lookup_confirm_title,
        )
        var current: NumberConfirmation = NumberConfirmation.Lookup
        val state = androidx.compose.runtime.mutableStateOf(current)
        composeRule.setContent {
            DistrictTheme {
                NumberConfirmDialog(state.value, onConfirm = { asked += "confirm" }, onDismiss = { asked += "dismiss" })
            }
        }
        expected.forEach { (confirmation, title) ->
            current = confirmation
            state.value = current
            composeRule.waitForIdle()
            composeRule.onNodeWithText(str(title)).assertIsDisplayed()
        }
        composeRule.onNodeWithTag(NUMBER_CONFIRM_ACTION_TAG).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(NUMBER_CONFIRM_CANCEL_TAG).performClick()
        assertEquals(listOf("confirm", "dismiss"), asked)
    }

    @Test
    fun `the open confirmation is drawn over the marketplace`() {
        render(NumberProvisioningUiState(confirmation = NumberConfirmation.Release("+1")), MarketplaceTab.OWNED)
        composeRule.onNodeWithText(str(R.string.numbers_release_confirm_body, "+1")).assertIsDisplayed()
        composeRule.onNodeWithTag(NUMBER_CONFIRM_ACTION_TAG).performClick()
        assertEquals(listOf<Any>("confirm"), asked)
    }
}
