package com.distronode.districtai.ui.marketplace

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.ListedNumber
import com.distronode.districtai.core.model.ProviderStatusEntry
import com.distronode.districtai.core.model.SipTrunk
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.plural
import com.distronode.districtai.str
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Carrier tab's enumerations read through the wire vocabulary in every language: a carrier by
 * its name ("Twilio", not `twilio`), a SIP trunk's status ("Active"), and the A2P campaign types as
 * the portal names them ("Customer Care", not CUSTOMER_CARE). A value the vocabulary does not list
 * reads through the category's generic label.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class CarrierLabelsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val asked = mutableListOf<Any>()

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
        search: SearchState = SearchState.Idle,
    ) {
        composeRule.setContent {
            DistrictTheme {
                MarketplaceScreen(
                    state = MarketplaceUiState(
                        tab = tab,
                        owned = OwnedState.Ready(listOf(ListedNumber(phoneNumber = "+14165550142"))),
                        search = search,
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

    private fun shows(text: String) = composeRule.onNodeWithText(text).performScrollTo().assertIsDisplayed()

    @Test
    fun `a carrier reads by its name, and a key the vocabulary does not list generically`() {
        val entries = mapOf(
            "twilio" to ProviderStatusEntry(connected = true, accountName = "Acme"),
            "telnyx" to ProviderStatusEntry(connected = true, managed = true),
            "plivo" to ProviderStatusEntry(connected = true),
            "sinch" to ProviderStatusEntry(connected = false),
        )
        render(
            NumberProvisioningUiState(
                carrier = SectionState.Ready(
                    CarrierAccounts(entries, listOf("telnyx", "twilio", "plivo"), listOf("sinch")),
                ),
                verify = SectionState.Ready(VerifyService(enabled = false)),
                sip = SectionState.Ready(emptyList()),
            ),
            MarketplaceTab.CARRIER,
        )
        assertEquals("Twilio", str(R.string.wire_number_provider_twilio))
        shows(str(R.string.wire_number_provider_twilio))
        shows(str(R.string.wire_number_provider_telnyx))
        shows(str(R.string.wire_number_provider_sinch))
        shows(str(R.string.wire_number_provider_unknown, "plivo"))
        composeRule.onNodeWithText("twilio").assertDoesNotExist()
        composeRule.onNodeWithText("telnyx").assertDoesNotExist()
    }

    @Test
    fun `a SIP trunk's status reads by its label, and an unlisted one generically`() {
        render(
            NumberProvisioningUiState(
                carrier = SectionState.Ready(CarrierAccounts(emptyMap(), emptyList(), emptyList())),
                verify = SectionState.Ready(VerifyService(enabled = false)),
                sip = SectionState.Ready(
                    listOf(
                        SipTrunk(
                            id = "T1",
                            domain = "acme",
                            ipAccessControlList = listOf("1.2.3.4"),
                            status = "active",
                        ),
                        SipTrunk(
                            id = "T2",
                            domain = "old",
                            ipAccessControlList = emptyList(),
                            status = "suspended",
                            domainSid = "SD1",
                        ),
                    ),
                ),
            ),
            MarketplaceTab.CARRIER,
        )
        assertEquals("Active", str(R.string.wire_sip_trunk_status_active))
        val active = str(R.string.wire_sip_trunk_status_active)
        shows(str(R.string.numbers_sip_trunk_meta_line, active, plural(R.plurals.numbers_sip_address_count, 1)))
        val suspended = str(R.string.wire_sip_trunk_status_unknown, "suspended")
        shows(str(R.string.numbers_sip_trunk_meta_line, suspended, plural(R.plurals.numbers_sip_address_count, 0)))
    }

    @Test
    fun `the campaign types read as the portal names them and are sent as their wire values`() {
        render(
            NumberProvisioningUiState(
                a2pDraft = A2PDraft(businessName = "A", campaignDescription = "d", sampleOne = "1", sampleTwo = "2"),
            ),
            MarketplaceTab.CARRIER,
        )
        assertEquals("Customer Care", str(R.string.wire_a2p_campaign_type_customer_care))
        listOf(
            "CUSTOMER_CARE" to R.string.wire_a2p_campaign_type_customer_care,
            "LOW_VOLUME" to R.string.wire_a2p_campaign_type_low_volume,
            "MARKETING" to R.string.wire_a2p_campaign_type_marketing,
            "2FA" to R.string.wire_a2p_campaign_type_v_2_fa,
        ).forEach { (wire, label) ->
            composeRule.onNodeWithTag(choiceTag(wire)).assert(hasText(str(label)))
            composeRule.onNodeWithText(wire).assertDoesNotExist()
        }
        composeRule.onNodeWithTag(choiceTag("MARKETING")).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(
            A2PDraft(
                businessName = "A",
                campaignDescription = "d",
                sampleOne = "1",
                sampleTwo = "2",
                campaignType = "MARKETING",
            ),
            asked.single(),
        )
    }

    @Test
    @Config(qualifiers = "+en-rXA")
    fun `the carrier that answered a search reads by its label, not its key`() {
        // ⚠️ IN A PSEUDOLOCALE: the eyebrow upper-cases its text, so in English "Twilio" and the key
        // `twilio` print the same; here the label is accented and the key is not.
        render(NumberProvisioningUiState(), MarketplaceTab.SEARCH, search = SearchState.Ready("twilio", emptyList()))
        val label = str(R.string.marketplace_provider, str(R.string.wire_number_provider_twilio))
        assertTrue(label.uppercase() != str(R.string.marketplace_provider, "twilio").uppercase())
        composeRule.onNodeWithText(label, ignoreCase = true).assertExists()
    }
}
