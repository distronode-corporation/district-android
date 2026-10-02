package com.distronode.districtai.ui.contacts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.Contact
import com.distronode.districtai.core.model.ContactCompany
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import com.distronode.districtai.ui.MainLooperDrain
import org.junit.rules.RuleChain

/**
 * The dossier section of the contact detail screen.
 *
 * ⛔ THE VISIBILITY MATRIX IS THE POINT OF THIS FILE. The enrich control spends money — one tap is
 * one external crawl plus one LLM synthesis, on a non-idempotent endpoint whose server-side rate
 * limit is fail-open — so it is offered only when BOTH the role may mutate AND the contact is
 * genuinely enrichable. Every other combination has to show nothing, and "nothing" is the
 * assertion that silently stops meaning anything if the handle drifts, which is why the constants
 * are shared with the screen rather than retyped.
 */
@RunWith(AndroidJUnit4::class)
// ⛔ A TALL VIEWPORT. This screen is a `verticalScroll` Column and `assertIsDisplayed` checks
// visible BOUNDS rather than existence, so the dossier cards sit below the fold on a phone-sized
// Robolectric display — failing with "is not displayed" against nodes that are perfectly present.
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class ContactDossierScreenTest {

    private val composeRule = createComposeRule()

    /** ⚠️ The drain is OUTER, so it runs after the activity has closed; see [MainLooperDrain]. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainLooperDrain()).around(composeRule)

    private fun contact(
        dgiStatus: String? = null,
        dgiError: String? = null,
        intelligence: JsonObject? = null,
        company: ContactCompany? = null,
    ) = Contact(
        id = "c1",
        workspaceId = "ws-1",
        name = "Ada Lovelace",
        phoneNumber = "+14165550142",
        company = company,
        dgiStatus = dgiStatus,
        dgiError = dgiError,
        intelligence = intelligence,
        createdAt = "2026-08-15T14:30:00.000Z",
    )

    private fun render(
        contact: Contact,
        canMutate: Boolean = true,
        onEnrich: () -> Unit = {},
        onClearIntel: () -> Unit = {},
        pollFailure: FailureText? = null,
        onCheckDossierAgain: () -> Unit = {},
    ) {
        composeRule.setContent {
            DistrictTheme {
                ContactDetailScreen(
                    state = ContactDetailUiState.Content(contact, pollFailure = pollFailure),
                    canMutate = canMutate,
                    onBack = {},
                    onRetry = {},
                    onRename = {},
                    onDelete = {},
                    onDismissMutationFailure = {},
                    onEnrich = onEnrich,
                    onClearIntel = onClearIntel,
                    onCheckDossierAgain = onCheckDossierAgain,
                )
            }
        }
    }

    private fun scrollTo(description: String) =
        composeRule.onNodeWithContentDescription(description).performScrollTo()

    // ── The enrich control's visibility matrix ───────────────────────────────

    @Test
    fun `an enrichable contact offers the control to a role that may mutate`() {
        render(contact(dgiStatus = null), canMutate = true)

        scrollTo(CONTACT_DETAIL_ENRICH_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a failed dossier is enrichable again`() {
        // ⚠️ "failed" IS TERMINAL AND OFFERABLE. Retrying a crawl that failed is exactly what the
        // operator wants, and it is the only way past a failed dossier short of clearing it.
        render(contact(dgiStatus = "failed", dgiError = "rate limited"), canMutate = true)

        scrollTo(CONTACT_DETAIL_ENRICH_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a viewer is never offered the control, however enrichable the contact`() {
        // ⛔ The server excludes `viewer` from both DGI writes, so the button could only 403.
        render(contact(dgiStatus = null), canMutate = false)

        composeRule.onNodeWithContentDescription(CONTACT_DETAIL_ENRICH_DESCRIPTION)
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(CONTACT_DETAIL_CLEAR_INTEL_DESCRIPTION)
            .assertDoesNotExist()
    }

    @Test
    fun `a running crawl hides the control rather than disabling it`() {
        // ⛔ THE EXPENSIVE MISTAKE. Offering it here lets one impatient tap buy a second crawl and
        // a second model run for a contact already being enriched — and the server's rate limit
        // is fail-open, so nothing behind the button would stop it.
        render(contact(dgiStatus = "crawling"), canMutate = true)

        composeRule.onNodeWithContentDescription(CONTACT_DETAIL_ENRICH_DESCRIPTION)
            .assertDoesNotExist()
    }

    @Test
    fun `a completed dossier is not offered a re-run`() {
        render(contact(dgiStatus = "complete"), canMutate = true)

        composeRule.onNodeWithContentDescription(CONTACT_DETAIL_ENRICH_DESCRIPTION)
            .assertDoesNotExist()
    }

    @Test
    fun `enrich fires with no confirmation, because it is additive and reversible`() {
        // ⚠️ Deliberately NOT confirmed, unlike clear: enrichment adds data and `clear-intel`
        // undoes it. Its guard is that the control only exists when the contact is enrichable.
        var enrichments = 0
        render(contact(dgiStatus = null), onEnrich = { enrichments += 1 })

        scrollTo(CONTACT_DETAIL_ENRICH_DESCRIPTION).performClick()

        assertEquals(1, enrichments)
    }

    // ── Clear, and its confirmation ──────────────────────────────────────────

    @Test
    fun `clearing is confirmed before it fires`() {
        // ⛔ NOT RECOVERABLE, AND NOT FREE TO UNDO: getting the dossier back means paying for
        // another crawl and another model run. The dialog says that rather than asking a bare
        // "are you sure".
        var clears = 0
        render(contact(dgiStatus = "complete"), onClearIntel = { clears += 1 })

        scrollTo(CONTACT_DETAIL_CLEAR_INTEL_DESCRIPTION).performClick()
        assertEquals("tapping clear must only open the confirmation", 0, clears)

        // ⚠️ NOT `scrollTo`: a dialog is not inside the screen's scrollable column, and
        // performScrollTo on it throws.
        composeRule.onNodeWithContentDescription(CONTACT_DETAIL_CLEAR_INTEL_CONFIRM_DESCRIPTION)
            .performClick()
        assertEquals(1, clears)
    }

    @Test
    fun `a contact with nothing to clear is offered no clear control`() {
        render(contact(dgiStatus = null, intelligence = null, company = null))

        composeRule.onNodeWithContentDescription(CONTACT_DETAIL_CLEAR_INTEL_DESCRIPTION)
            .assertDoesNotExist()
    }

    // ── Rendering the dossier itself ─────────────────────────────────────────

    @Test
    fun `known shapes render as labelled rows`() {
        render(
            contact(
                dgiStatus = "complete",
                intelligence = JsonObject(
                    mapOf(
                        "executiveSummary" to JsonPrimitive("Wants Thursday."),
                        "topics" to JsonArray(
                            listOf(JsonPrimitive("booking"), JsonPrimitive("pricing")),
                        ),
                        "score" to JsonPrimitive(87),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("Executive summary").assertIsDisplayed()
        composeRule.onNodeWithText("Wants Thursday.").assertIsDisplayed()
        composeRule.onNodeWithText("booking · pricing").assertIsDisplayed()
        composeRule.onNodeWithText("87").assertIsDisplayed()
    }

    @Test
    fun `a nested object renders one level deep as sub-rows`() {
        render(
            contact(
                dgiStatus = "complete",
                intelligence = JsonObject(
                    mapOf(
                        "firmographics" to JsonObject(
                            mapOf("employees" to JsonPrimitive("50-100")),
                        ),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("Firmographics").assertIsDisplayed()
        composeRule.onNodeWithText("Employees").assertIsDisplayed()
        composeRule.onNodeWithText("50-100").assertIsDisplayed()
    }

    @Test
    fun `an unrecognised shape degrades to readable JSON instead of crashing`() {
        // ⛔ THE ONLY DATA IN THIS APP NO GATE VALIDATES. The contract fixtures pin that
        // `intelligence` is an object and say nothing about its contents, which come from a model
        // whose prompt changes. Anything unfamiliar has to stay VISIBLE — an operator who can see
        // an odd shape can report it, whereas a silently dropped field looks like the enrichment
        // found nothing.
        render(
            contact(
                dgiStatus = "complete",
                intelligence = JsonObject(
                    mapOf(
                        "competitors" to JsonArray(
                            listOf(JsonObject(mapOf("name" to JsonPrimitive("Acme")))),
                        ),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithContentDescription(CONTACT_DETAIL_DOSSIER_RAW_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Competitors").assertIsDisplayed()
    }

    @Test
    fun `firmographics render in the dossier, where clearing them is not a surprise`() {
        // ⚠️ `company` IS WRITTEN BY THE SAME PIPELINE AS `intelligence` AND IS NULLED BY THE SAME
        // CLEAR, so it belongs here rather than beside the phone number the operator typed.
        render(
            contact(
                dgiStatus = "complete",
                company = ContactCompany(
                    name = "Analytical Engines",
                    domain = "engines.test",
                    industry = "compute",
                ),
            ),
        )

        scrollTo(CONTACT_DETAIL_DOSSIER_COMPANY_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Analytical Engines").assertIsDisplayed()
        composeRule.onNodeWithText("engines.test").assertIsDisplayed()
    }

    @Test
    fun `an all-empty company blob renders nothing at all`() {
        // ⚠️ The column is Json? and an empty object is a shape the pipeline genuinely writes; an
        // empty "Company" heading would read as missing data rather than as absent data.
        render(contact(dgiStatus = "complete", company = ContactCompany()))

        composeRule.onNodeWithContentDescription(CONTACT_DETAIL_DOSSIER_COMPANY_DESCRIPTION)
            .assertDoesNotExist()
    }

    // ── The status badge ─────────────────────────────────────────────────────

    /**
     * ⛔ THE PIPELINE ADVANCES pending -> crawling -> synthesizing, AND ALL THREE ARE IN FLIGHT. A
     * badge keyed on "pending" alone goes dark the moment the crawler starts, which reads as
     * "finished, and it found nothing" for a job that is still running.
     *
     * ⚠️ THREE TESTS RATHER THAN ONE LOOP, because `setContent` may be called only once per
     * ComposeTestRule — a loop over it throws "Content has already been set" on the second pass,
     * which fails for a reason that has nothing to do with the badge.
     */
    @Test
    fun `a pending dossier reads as in progress`() {
        render(contact(dgiStatus = "pending"))

        scrollTo(CONTACT_DETAIL_DOSSIER_PENDING_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a crawling dossier reads as in progress`() {
        render(contact(dgiStatus = "crawling"))

        scrollTo(CONTACT_DETAIL_DOSSIER_PENDING_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a synthesizing dossier reads as in progress`() {
        render(contact(dgiStatus = "synthesizing"))

        scrollTo(CONTACT_DETAIL_DOSSIER_PENDING_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a null status reads as an absence, never as a queued job`() {
        // ⛔ `clear-intel` RESETS THE STATUS TO NULL SO NOTHING RE-CRAWLS, so null means "no
        // dossier and none queued". A spinner here would promise something that never arrives and
        // would leave the enrich control hidden forever.
        render(contact(dgiStatus = null))

        composeRule.onNodeWithContentDescription(CONTACT_DETAIL_DOSSIER_PENDING_DESCRIPTION)
            .assertDoesNotExist()
        composeRule.onNodeWithText("No dossier for this contact.").assertIsDisplayed()
    }

    @Test
    fun `a company with blank fields shows only the ones it has`() {
        render(
            contact(
                dgiStatus = "complete",
                company = ContactCompany(name = "  ", domain = "engines.test", industry = ""),
            ),
        )

        scrollTo(CONTACT_DETAIL_DOSSIER_COMPANY_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("engines.test").assertIsDisplayed()
        composeRule.onNodeWithText("Industry").assertDoesNotExist()
    }

    @Test
    fun `while a mutation is in flight, enrich and clear are disabled`() {
        composeRule.setContent {
            DistrictTheme {
                ContactDetailScreen(
                    state = ContactDetailUiState.Content(
                        contact(dgiStatus = "failed", dgiError = "Timed out"),
                        saving = true,
                    ),
                    canMutate = true,
                    onBack = {},
                    onRetry = {},
                    onRename = {},
                    onDelete = {},
                    onDismissMutationFailure = {},
                    onEnrich = {},
                    onClearIntel = {},
                    onCheckDossierAgain = {},
                )
            }
        }

        scrollTo(CONTACT_DETAIL_ENRICH_DESCRIPTION).assertIsNotEnabled()
        scrollTo(CONTACT_DETAIL_CLEAR_INTEL_DESCRIPTION).assertIsNotEnabled()
    }

    @Test
    fun `the clear confirmation survives a poll landing under it, and cancel sends nothing`() {
        // ⚠️ The dossier poll replaces the contact every few seconds while a crawl runs. An open
        // confirmation must stay open across that, and its buttons must still do what they say.
        var clears = 0
        var state by mutableStateOf<ContactDetailUiState>(
            ContactDetailUiState.Content(contact(dgiStatus = "complete")),
        )
        composeRule.setContent {
            DistrictTheme {
                ContactDetailScreen(
                    state = state,
                    canMutate = true,
                    onBack = {},
                    onRetry = {},
                    onRename = {},
                    onDelete = {},
                    onDismissMutationFailure = {},
                    onEnrich = {},
                    onClearIntel = { clears += 1 },
                    onCheckDossierAgain = {},
                )
            }
        }
        scrollTo(CONTACT_DETAIL_CLEAR_INTEL_DESCRIPTION).performClick()

        state = ContactDetailUiState.Content(contact(dgiStatus = "complete", company = ContactCompany(name = "New")))
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(CONTACT_DETAIL_CLEAR_INTEL_CONFIRM_DESCRIPTION).assertIsDisplayed()

        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithContentDescription(CONTACT_DETAIL_CLEAR_INTEL_CONFIRM_DESCRIPTION).assertDoesNotExist()
        assertEquals(0, clears)

        scrollTo(CONTACT_DETAIL_CLEAR_INTEL_DESCRIPTION).performClick()
        composeRule.onNodeWithContentDescription(CONTACT_DETAIL_CLEAR_INTEL_CONFIRM_DESCRIPTION).performClick()
        assertEquals(1, clears)
    }

    @Test
    fun `a company whose domain is blank shows its name and no domain row`() {
        render(contact(dgiStatus = "complete", company = ContactCompany(name = "Analytical Engines", domain = " ")))

        scrollTo(CONTACT_DETAIL_DOSSIER_COMPANY_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Analytical Engines").assertIsDisplayed()
        composeRule.onNodeWithText("Domain").assertDoesNotExist()
    }

    // ── A poll that stopped on a failure ─────────────────────────────────────

    @Test
    fun `a failed poll says so beside the badge and offers a re-check`() {
        // ⛔ BEFORE THIS CARD, A FAILED POLL WAS SILENT: the badge said "building" for good and
        // nothing on screen could restart the watch.
        var checks = 0
        render(
            contact(dgiStatus = "pending"),
            pollFailure = FailureText(message = UiText.Literal("You are offline."), retryable = true),
            onCheckDossierAgain = { checks += 1 },
        )

        scrollTo(CONTACT_DETAIL_POLL_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Could not check on the dossier. You are offline.").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performScrollTo().performClick()

        assertEquals(1, checks)
    }

    @Test
    fun `a failed poll that retrying cannot fix offers no re-check`() {
        render(
            contact(dgiStatus = "pending"),
            pollFailure = FailureText(message = UiText.Literal("Signed out."), retryable = false),
        )

        scrollTo(CONTACT_DETAIL_POLL_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertDoesNotExist()
    }
}
