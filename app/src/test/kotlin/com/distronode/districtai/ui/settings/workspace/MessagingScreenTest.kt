package com.distronode.districtai.ui.settings.workspace

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.MESSAGING_PROVIDER_SINCH
import com.distronode.districtai.core.model.MESSAGING_PROVIDER_TELNYX
import com.distronode.districtai.core.model.MESSAGING_PROVIDER_TWILIO
import com.distronode.districtai.core.model.MESSAGING_SOURCE_MANAGED
import com.distronode.districtai.core.model.ManagedAccount
import com.distronode.districtai.core.model.MessagingAccount
import com.distronode.districtai.core.model.MessagingResponse
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The messaging screen, for both audiences.
 *
 * ⛔ THE SCREEN HAS TWO SHAPES, EDITOR AND VIEWER, and the VIEWER one carries the assertion that
 * matters most: a viewer must reach this screen and find not one control. `assertDoesNotExist` is a weak assertion
 * against a typo'd handle, which is why every handle here comes from `MessagingHandles.kt` and is
 * derived rather than typed — a literal that matched nothing would make the viewer tests pass for
 * the wrong reason.
 *
 * ⛔ AND THE DELETE CONFIRMATION IS TESTED FOR ITS WORDS, NOT JUST ITS EXISTENCE. Removing an account
 * releases the hub's claim on every number only it held, and another tenant can then take one. A
 * dialog that said "are you sure" would be technically a confirmation and practically a trap.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class MessagingScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val populated = MessagingResponse(
        success = true,
        accounts = listOf(
            MessagingAccount(
                id = "acct-twilio",
                provider = "twilio",
                label = "Twilio (main)",
                credentialSource = "byok",
                phoneNumbers = listOf("+14165550111", "+14165550112"),
            ),
            MessagingAccount(
                id = "acct-telnyx",
                provider = "telnyx",
                label = "Telnyx (overflow)",
                credentialSource = "managed",
                phoneNumbers = listOf("+14165550113"),
            ),
        ),
        managedAccount = ManagedAccount(provider = "twilio", phoneNumbers = listOf("+14165550190")),
        defaultAccountId = "acct-telnyx",
        channelDefaults = mapOf("sms" to "acct-twilio"),
    )

    private fun ready(canEdit: Boolean = true, draft: MessagingDraft? = null) = MessagingUiState(
        load = MessagingLoadState.Ready(populated),
        canEdit = canEdit,
        draft = draft,
    )

    private class Recorder {
        val edited = mutableListOf<String?>()
        val defaults = mutableListOf<String>()
        val deletes = mutableListOf<String>()
        val channels = mutableListOf<Pair<String, String>>()
        var drafts: MessagingDraft? = null
        var draftCleared = false
        var saves = 0
        var tests = 0
        var creatorCell = ""
        var creatorSaves = 0
        var retries = 0
    }

    private fun render(state: MessagingUiState, rec: Recorder = Recorder()): Recorder {
        composeRule.setContent {
            DistrictTheme {
                MessagingScreen(
                    state = state,
                    onStartEditing = { rec.edited += it },
                    onEditDraft = { if (it == null) rec.draftCleared = true else rec.drafts = it },
                    onSaveAccount = { rec.saves += 1 },
                    onTestCredentials = { rec.tests += 1 },
                    onSetDefault = { rec.defaults += it },
                    onSetChannelDefault = { channel, id -> rec.channels += channel to id },
                    onDelete = { rec.deletes += it },
                    onEditCreatorCell = { rec.creatorCell = it },
                    onSaveCreatorCell = { rec.creatorSaves += 1 },
                    onRetry = { rec.retries += 1 },
                    onBack = {},
                )
            }
        }
        return rec
    }

    // ── The read, which every role sees ──────────────────────────────────────

    @Test
    fun `every account renders with its provider, credential source and number count`() {
        render(ready())

        composeRule.onNodeWithContentDescription(messagingAccountDescription("acct-twilio"))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(messagingAccountDescription("acct-telnyx"))
            .assertIsDisplayed()
        // ⚠️ `byok` vs `managed` is what tells an operator whose carrier account is being billed.
        composeRule.onNodeWithText("twilio · byok · 2 numbers", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("telnyx · managed · 1 numbers", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `the default is marked on its own row rather than stated separately`() {
        // ⚠️ "Which of these actually sends" is the question, and a sentence underneath a list does
        // not answer it at a glance.
        render(ready())

        composeRule.onNodeWithText("Default", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the platform account is its own section, never a row in the account list`() {
        // ⛔ IT HAS NO ID AND IS NOT A SENDER IDENTITY THE API ACCEPTS. `resolveSendingContext`
        // rejects a `from` that no entry in `accounts` owns, so a synthetic row would render a
        // pickable sender whose every send fails — and would carry an Edit button that 404s.
        render(ready())

        composeRule.onNodeWithContentDescription(MESSAGING_MANAGED_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("1 numbers on our carrier account", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `a workspace with no platform numbers draws no managed section at all`() {
        render(
            MessagingUiState(
                load = MessagingLoadState.Ready(populated.copy(managedAccount = null)),
                canEdit = true,
            ),
        )

        composeRule.onNodeWithContentDescription(MESSAGING_MANAGED_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a channel default naming an account that no longer exists shows the raw id`() {
        // ⚠️ HIDING IT WOULD MAKE A BROKEN OVERRIDE LOOK LIKE NO OVERRIDE. A channel default can
        // legitimately name an account someone deleted.
        render(
            MessagingUiState(
                load = MessagingLoadState.Ready(
                    populated.copy(channelDefaults = mapOf("whatsapp" to "acct-deleted")),
                ),
                canEdit = true,
            ),
        )

        composeRule.onNodeWithContentDescription(messagingChannelDescription("whatsapp"))
            .assertIsDisplayed()
        composeRule.onNodeWithText("acct-deleted", substring = true).assertIsDisplayed()
    }

    @Test
    fun `no carrier connected says so, and the add control is still reachable`() {
        // ⚠️ THE EMPTY STATE IS WHERE THE ADD BUTTON MATTERS MOST, which is why it is its own
        // section rather than a row appended to the list.
        render(
            MessagingUiState(
                load = MessagingLoadState.Ready(
                    MessagingResponse(success = true, accounts = emptyList()),
                ),
                canEdit = true,
            ),
        )

        composeRule.onNodeWithContentDescription(MESSAGING_EMPTY_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(MESSAGING_ADD_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a failed read offers a retry`() {
        val rec = render(
            MessagingUiState(
                load = MessagingLoadState.Failed(FailureText(UiText.Literal("Offline."))),
                canEdit = true,
            ),
        )

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_RETRY_DESCRIPTION).performClick()

        assertEquals(1, rec.retries)
    }

    @Test
    fun `loading draws skeletons`() {
        render(MessagingUiState(load = MessagingLoadState.Loading, canEdit = true))

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_LOADING_DESCRIPTION)
            .assertIsDisplayed()
    }

    // ── The viewer, whose whole screen is an absence ─────────────────────────

    @Test
    fun `a viewer sees the accounts and NOT ONE control`() {
        // ⛔ EVERY WRITE ON THIS ROUTE EXCLUDES `viewer`, so being offered any of these would be an
        // invitation to a 403. This is a real audience: the settings hub is open to viewers.
        render(ready(canEdit = false))

        composeRule.onNodeWithContentDescription(messagingAccountDescription("acct-twilio"))
            .assertIsDisplayed()

        composeRule.onNodeWithContentDescription(MESSAGING_ADD_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(messagingEditDescription("acct-twilio"))
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(messagingRemoveDescription("acct-twilio"))
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(messagingDefaultDescription("acct-twilio"))
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(messagingChannelSetDescription("sms"))
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(MESSAGING_CREATOR_CELL_DESCRIPTION)
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(MESSAGING_CREATOR_CELL_SAVE_DESCRIPTION)
            .assertDoesNotExist()
    }

    @Test
    fun `a viewer is told why, and is NOT told to go and do it on the web`() {
        // ⛔ THE CAPTION IS THE VIEWER'S BRANCH NOW. "Change it on the web dashboard" would be false
        // twice over: a mutating role changes it right here, and a viewer cannot change it there
        // either.
        render(ready(canEdit = false))

        composeRule.onNodeWithContentDescription(MESSAGING_READ_ONLY_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("read-only access", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a viewer with no channel overrides sees no channel section at all`() {
        // ⚠️ The section exists for a mutating role even when empty, because that is where the
        // control to add one lives. For a viewer an empty map is nothing to say.
        render(
            MessagingUiState(
                load = MessagingLoadState.Ready(populated.copy(channelDefaults = emptyMap())),
                canEdit = false,
            ),
        )

        composeRule.onNodeWithContentDescription(messagingChannelSetDescription("sms"))
            .assertDoesNotExist()
    }

    @Test
    fun `a mutating role gets the caption's editable branch instead`() {
        render(ready())

        composeRule.onNodeWithContentDescription(MESSAGING_READ_ONLY_DESCRIPTION)
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(MESSAGING_CREATOR_CELL_DESCRIPTION)
            .assertIsDisplayed()
    }

    // ── The controls a mutating role gets ────────────────────────────────────

    @Test
    fun `edit, make-default and remove each report the account they belong to`() {
        val rec = render(ready())

        composeRule.onNodeWithContentDescription(messagingEditDescription("acct-twilio"))
            .performClick()
        assertEquals(listOf<String?>("acct-twilio"), rec.edited)

        composeRule.onNodeWithContentDescription(messagingDefaultDescription("acct-twilio"))
            .performClick()
        assertEquals(listOf("acct-twilio"), rec.defaults)
    }

    @Test
    fun `the row that is already the default offers no make-default control`() {
        // ⚠️ ABSENT RATHER THAN DISABLED. The badge is already there; a greyed-out button beside it
        // adds a control that can never do anything.
        render(ready())

        composeRule.onNodeWithContentDescription(messagingDefaultDescription("acct-telnyx"))
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(messagingEditDescription("acct-telnyx"))
            .assertIsDisplayed()
    }

    @Test
    fun `add reports a null id, which is what opens a create`() {
        val rec = render(ready())

        composeRule.onNodeWithContentDescription(MESSAGING_ADD_DESCRIPTION).performClick()

        assertEquals(listOf<String?>(null), rec.edited)
    }

    @Test
    fun `removing is confirmed with wording that NAMES what is released`() {
        // ⛔ THE ASSERTION THIS DIALOG EXISTS FOR. Deleting frees the hub's claim on every number
        // only this account held, and another workspace can then take one. "Are you sure?" would be
        // a confirmation in form and a trap in practice.
        val rec = render(ready())

        composeRule.onNodeWithContentDescription(messagingRemoveDescription("acct-twilio"))
            .performClick()

        composeRule.onNodeWithText("released", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("another workspace can claim", substring = true)
            .assertIsDisplayed()
        // ⚠️ The COUNT is named too, from the read — "2 phone number(s)" is a different decision
        // from "some numbers".
        composeRule.onNodeWithText("2 phone number", substring = true).assertIsDisplayed()

        composeRule.onNodeWithContentDescription(MESSAGING_DELETE_CONFIRM_DESCRIPTION).performClick()
        assertEquals(listOf("acct-twilio"), rec.deletes)
    }

    @Test
    fun `cancelling the delete dialog deletes nothing`() {
        val rec = render(ready())

        composeRule.onNodeWithContentDescription(messagingRemoveDescription("acct-twilio"))
            .performClick()
        composeRule.onNodeWithContentDescription(MESSAGING_DELETE_CANCEL_DESCRIPTION).performClick()

        assertEquals(emptyList<String>(), rec.deletes)
        composeRule.onNodeWithContentDescription(MESSAGING_DELETE_CONFIRM_DESCRIPTION)
            .assertDoesNotExist()
    }

    @Test
    fun `the channel picker offers real accounts only, never the platform's`() {
        // ⛔ THE MANAGED ACCOUNT HAS NO ID AND `handleSetChannelDefault` ANSWERS 404 FOR AN ID THE
        // ACCOUNTS ARRAY DOES NOT CONTAIN, so offering it would be a pickable option that fails
        // twice over.
        val rec = render(ready())

        composeRule.onNodeWithContentDescription(messagingChannelSetDescription("voice"))
            .performClick()

        composeRule.onNodeWithContentDescription(messagingChannelOptionDescription("acct-twilio"))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(messagingChannelOptionDescription("acct-telnyx"))
            .assertIsDisplayed()

        composeRule.onNodeWithContentDescription(messagingChannelOptionDescription("acct-telnyx"))
            .performClick()
        composeRule.onNodeWithContentDescription(MESSAGING_CHANNEL_CONFIRM_DESCRIPTION)
            .performClick()

        assertEquals(listOf("voice" to "acct-telnyx"), rec.channels)
    }

    @Test
    fun `cancelling the channel picker sets nothing`() {
        val rec = render(ready())

        composeRule.onNodeWithContentDescription(messagingChannelSetDescription("sms")).performClick()
        composeRule.onNodeWithContentDescription(MESSAGING_CHANNEL_CANCEL_DESCRIPTION).performClick()

        assertEquals(emptyList<Pair<String, String>>(), rec.channels)
    }

    @Test
    fun `the channel picker is withheld when there is no account to point at`() {
        // ⚠️ EVERY OPTION WOULD FAIL THE ROUTE'S "Account not found" CHECK, and a picker with
        // nothing in it is a dead end rather than a feature.
        render(
            MessagingUiState(
                load = MessagingLoadState.Ready(
                    MessagingResponse(success = true, accounts = emptyList()),
                ),
                canEdit = true,
            ),
        )

        composeRule.onNodeWithContentDescription(messagingChannelSetDescription("sms"))
            .assertDoesNotExist()
    }

    @Test
    fun `the creator cell field is empty and says it cannot show the saved number`() {
        // ⛔ NOTHING THIS CLIENT CAN CALL RETURNS IT, so a seeded field could only be blank — which
        // is the shape that saves a blank over a real number.
        val rec = render(ready())

        composeRule.onNodeWithContentDescription(MESSAGING_CREATOR_CELL_HELP_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithText("cannot show the saved number", substring = true)
            .assertIsDisplayed()
        // ⚠️ DISABLED WHILE BLANK: the route answers 400 for an absent value and stores an empty
        // string for a blank one, so an accidental press must not be able to clear it.
        composeRule.onNodeWithContentDescription(MESSAGING_CREATOR_CELL_SAVE_DESCRIPTION)
            .assertIsNotEnabled()

        composeRule.onNodeWithContentDescription(MESSAGING_CREATOR_CELL_DESCRIPTION)
            .performTextInput("+14165550170")
        assertEquals("+14165550170", rec.creatorCell)
    }

    // ── The account form ─────────────────────────────────────────────────────

    @Test
    fun `an edit form says a blank credential box keeps the saved value`() {
        // ⛔ THE LOAD-BEARING PIECE OF COPY ON THIS SCREEN. It is the reason the form can exist
        // against a redacted read at all, and the reason the old "an operator would have to retype a
        // live carrier secret" objection does not hold.
        render(ready(draft = MessagingDraft.of(populated.accounts.first())))

        composeRule.onNodeWithContentDescription(MESSAGING_SECRET_KEEP_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Leave blank to keep the saved value", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(messagingCredentialDescription("accountSid"))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(messagingCredentialDescription("authToken"))
            .assertIsDisplayed()
    }

    @Test
    fun `a create form does NOT say that, because a blank there stores nothing`() {
        render(ready(draft = MessagingDraft()))

        composeRule.onNodeWithContentDescription(MESSAGING_SECRET_KEEP_DESCRIPTION)
            .assertDoesNotExist()
        // ⛔ AND SAVE IS DISABLED UNTIL EVERY SECRET IS TYPED. The route would accept the save and
        // store an account with no credentials, which then fails on its first send.
        composeRule.onNodeWithContentDescription(MESSAGING_SAVE_DESCRIPTION).assertIsNotEnabled()
    }

    @Test
    fun `switching provider on an existing account warns that the saved keys are cleared`() {
        // ⛔ THE ONE CASE WHERE A BLANK BOX IS NOT "KEEP". The route only carries stored secrets
        // forward when the provider is unchanged.
        render(
            ready(
                draft = MessagingDraft(
                    accountId = "acct-twilio",
                    provider = MESSAGING_PROVIDER_SINCH,
                    originalProvider = MESSAGING_PROVIDER_TWILIO,
                ),
            ),
        )

        composeRule.onNodeWithContentDescription(MESSAGING_PROVIDER_SWITCH_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithText("clears the saved keys", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(MESSAGING_SAVE_DESCRIPTION).assertIsNotEnabled()
    }

    @Test
    fun `the provider picker draws Sinch's five fields and Telnyx's one`() {
        render(ready(draft = MessagingDraft(provider = MESSAGING_PROVIDER_SINCH)))

        listOf("projectId", "keyId", "keySecret", "applicationKey", "applicationSecret")
            .forEach { key ->
                composeRule.onNodeWithContentDescription(messagingCredentialDescription(key))
                    .assertIsDisplayed()
            }
        // ⚠️ AND NOT TWILIO'S. A field list that leaked across providers would send `accountSid` as
        // an unrecognised PLAINTEXT identifier, which the route stores in the clear.
        composeRule.onNodeWithContentDescription(messagingCredentialDescription("accountSid"))
            .assertDoesNotExist()
    }

    @Test
    fun `the managed option is offered without being pre-validated`() {
        // ⛔ NOTHING THIS CLIENT READS SAYS WHETHER THE WORKSPACE IS ENTITLED. Hiding the option
        // would deny a legitimate one; the server's 403 names support and is what the operator sees.
        render(ready(draft = MessagingDraft()))

        composeRule.onNodeWithContentDescription(messagingSourceDescription(MESSAGING_SOURCE_MANAGED))
            .assertIsDisplayed()
    }

    @Test
    fun `the test button is withheld until every key is typed, and says why`() {
        // ⛔ THE ROUTE READS PLAINTEXT, UNSAVED CREDENTIALS. On an ordinary edit the boxes are blank
        // by design, so there is nothing to test with — and testing blanks would report a working
        // saved account as broken.
        render(ready(draft = MessagingDraft.of(populated.accounts.first())))

        composeRule.onNodeWithContentDescription(MESSAGING_TEST_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(MESSAGING_TEST_UNAVAILABLE_DESCRIPTION)
            .assertIsDisplayed()
    }

    @Test
    fun `a fully typed form offers the test button and reports its result`() {
        val rec = render(
            ready(
                draft = MessagingDraft(
                    secrets = mapOf("accountSid" to "AC_typed", "authToken" to "typed"),
                ),
            ).copy(test = MessagingTestState.Passed("Acme Ltd")),
        )

        composeRule.onNodeWithContentDescription(MESSAGING_TEST_DESCRIPTION).performClick()
        assertEquals(1, rec.tests)

        composeRule.onNodeWithContentDescription(MESSAGING_TEST_RESULT_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Acme Ltd", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a rejected probe shows the carrier's own words`() {
        render(
            ready(
                draft = MessagingDraft(
                    secrets = mapOf("accountSid" to "AC_wrong", "authToken" to "wrong"),
                ),
            ).copy(test = MessagingTestState.Rejected("Authenticate (20003)")),
        )

        composeRule.onNodeWithText("Authenticate (20003)", substring = true).assertIsDisplayed()
    }

    @Test
    fun `saving and cancelling the form report through their own callbacks`() {
        val rec = render(
            ready(draft = MessagingDraft.of(populated.accounts.first())),
        )

        composeRule.onNodeWithContentDescription(MESSAGING_SAVE_DESCRIPTION).performClick()
        assertEquals(1, rec.saves)

        composeRule.onNodeWithContentDescription(MESSAGING_CANCEL_DESCRIPTION).performClick()
        // ⚠️ A null draft is what closes the form — see `MessagingViewModel.editDraft`.
        assertEquals(true, rec.draftCleared)
    }

    @Test
    fun `typing into the numbers box flags the list as edited`() {
        // ⛔ THE FLAG IS WHAT DECIDES WHETHER THE SAVE TOUCHES THE HUB INDEX AT ALL. Without it, a
        // label change would rewrite the account's numbers from whatever this client rendered.
        val rec = render(ready(draft = MessagingDraft.of(populated.accounts.first())))

        composeRule.onNodeWithContentDescription(MESSAGING_NUMBERS_DESCRIPTION)
            .performTextInput("\n+14165550149")

        assertEquals(true, rec.drafts?.numbersEdited)
        composeRule.onNodeWithContentDescription(MESSAGING_NUMBERS_HELP_DESCRIPTION)
            .assertIsDisplayed()
    }

    // ── What the form looks like in every other state ────────────────────────

    private val twilioEdit get() = MessagingDraft.of(populated.accounts.first())

    private val typedTwilio
        get() = twilioEdit.copy(secrets = mapOf("accountSid" to "AC_typed", "authToken" to "typed"))

    @Test
    fun `an account the read carried with no label, provider or source reads as unknown`() {
        // ⚠️ THE ROW STILL RENDERS. A legacy row with nulls is an account that still sends, and the
        // operator has to be able to see it in order to fix it.
        val bare = populated.copy(
            accounts = listOf(MessagingAccount(id = "acct-bare")),
            managedAccount = ManagedAccount(provider = null, phoneNumbers = emptyList()),
            channelDefaults = mapOf("sms" to "acct-bare", "voice" to "acct-bare"),
        )
        render(MessagingUiState(load = MessagingLoadState.Ready(bare), canEdit = true))

        composeRule.onNodeWithText("unknown · unknown · 0 numbers").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(MESSAGING_MANAGED_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(messagingChannelDescription("voice")).assertIsDisplayed()
    }

    @Test
    fun `a write in flight locks every row's controls`() {
        render(ready().copy(defaultSave = SaveState.Saving))

        composeRule.onNodeWithContentDescription(messagingDefaultDescription("acct-twilio"))
            .assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(messagingEditDescription("acct-twilio"))
            .assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(messagingRemoveDescription("acct-twilio"))
            .assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(MESSAGING_CREATOR_CELL_DESCRIPTION).assertIsNotEnabled()
    }

    @Test
    fun `an account save in flight locks the whole form and says it is saving`() {
        render(ready(draft = typedTwilio).copy(accountSave = SaveState.Saving))

        listOf(
            MESSAGING_LABEL_DESCRIPTION,
            messagingProviderDescription(MESSAGING_PROVIDER_SINCH),
            messagingSourceDescription(MESSAGING_SOURCE_MANAGED),
            messagingCredentialDescription("accountSid"),
            MESSAGING_TEST_DESCRIPTION,
            MESSAGING_NUMBERS_DESCRIPTION,
            MESSAGING_MAKE_DEFAULT_DESCRIPTION,
            MESSAGING_SAVE_DESCRIPTION,
        ).forEach { composeRule.onNodeWithContentDescription(it).assertIsNotEnabled() }
        composeRule.onNodeWithText("Saving…").assertIsDisplayed()
    }

    @Test
    fun `a probe in flight says it is asking the carrier and shows no result yet`() {
        render(ready(draft = typedTwilio).copy(test = MessagingTestState.Running))

        composeRule.onNodeWithText("Checking with the carrier…").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(MESSAGING_TEST_RESULT_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a typed form that has not been probed shows no result`() {
        render(ready(draft = typedTwilio))

        composeRule.onNodeWithContentDescription(MESSAGING_TEST_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(MESSAGING_TEST_RESULT_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a pass with no account name still says the keys were accepted`() {
        render(ready(draft = typedTwilio).copy(test = MessagingTestState.Passed(null)))
        composeRule.onNodeWithText("The carrier accepted these keys.").assertIsDisplayed()
    }

    @Test
    fun `an unreachable carrier says so and does not blame the keys`() {
        render(
            ready(draft = typedTwilio).copy(
                test = MessagingTestState.Unreachable(FailureText(UiText.Literal("Too many checks."))),
            ),
        )

        composeRule.onNodeWithText("Too many checks.").assertIsDisplayed()
    }

    @Test
    fun `a create with a half-typed key set cannot be saved`() {
        // ⛔ A SID WITH NO TOKEN STORES A CREDENTIAL THAT CAN NEVER AUTHENTICATE.
        render(ready(draft = MessagingDraft(secrets = mapOf("accountSid" to "AC_only"))))

        composeRule.onNodeWithContentDescription(MESSAGING_SAVE_DESCRIPTION).assertIsNotEnabled()
    }

    @Test
    fun `picking another provider clears every typed key`() {
        // ⚠️ A HALF-TYPED KEY FOR ONE CARRIER would otherwise be sent as a plaintext identifier for
        // another, and stored in the clear.
        val rec = render(ready(draft = typedTwilio))

        composeRule.onNodeWithContentDescription(messagingProviderDescription(MESSAGING_PROVIDER_TELNYX))
            .performClick()

        assertEquals(MESSAGING_PROVIDER_TELNYX, rec.drafts?.provider)
        assertEquals(emptyMap<String, String>(), rec.drafts?.secrets)
    }

    @Test
    fun `typing a key adds it to the draft and keeps the others`() {
        val rec = render(ready(draft = twilioEdit.copy(secrets = mapOf("accountSid" to "AC_typed"))))

        composeRule.onNodeWithContentDescription(messagingCredentialDescription("authToken"))
            .performTextInput("tok")

        assertEquals(mapOf("accountSid" to "AC_typed", "authToken" to "tok"), rec.drafts?.secrets)
    }

    @Test
    fun `a Telnyx form asks for an API key by that name`() {
        render(ready(draft = MessagingDraft(provider = MESSAGING_PROVIDER_TELNYX)))

        composeRule.onNodeWithText("API KEY").assertIsDisplayed()
    }

    @Test
    fun `an account on a carrier this build does not know offers no key boxes and no probe`() {
        // ⚠️ THE FORM STILL OPENS, so the label and numbers stay editable, but there is no field
        // list to draw and nothing a probe could be dispatched on.
        render(
            ready(
                draft = MessagingDraft.of(
                    MessagingAccount(id = "acct-new", provider = "vonage", label = "Vonage"),
                ),
            ),
        )

        composeRule.onNodeWithContentDescription(MESSAGING_LABEL_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(MESSAGING_SECRET_KEEP_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(MESSAGING_TEST_UNAVAILABLE_DESCRIPTION)
            .assertDoesNotExist()
    }

    @Test
    fun `a delete of an unlabelled account names it by its id`() {
        render(
            MessagingUiState(
                load = MessagingLoadState.Ready(
                    populated.copy(accounts = listOf(MessagingAccount(id = "acct-bare", provider = "twilio"))),
                ),
                canEdit = true,
            ),
        )

        composeRule.onNodeWithContentDescription(messagingRemoveDescription("acct-bare")).performClick()

        composeRule.onNodeWithText("acct-bare", substring = true).assertIsDisplayed()
    }

    // ── Every dialog after the screen redraws under it ───────────────────────

    @Test
    fun `every dialog and control still reports through its callback after a redraw`() {
        // ⚠️ A NEW STATE WITH THE SAME CALLBACKS, which is what every write's re-read does to this
        // screen while a dialog may be open over it.
        val rec = Recorder()
        val current = mutableStateOf(ready())
        val onStartEditing: (String?) -> Unit = { rec.edited += it }
        val onEditDraft: (MessagingDraft?) -> Unit = {
            if (it == null) rec.draftCleared = true else rec.drafts = it
        }
        val onSetChannelDefault: (String, String) -> Unit = { channel, id -> rec.channels += channel to id }
        val onDelete: (String) -> Unit = { rec.deletes += it }
        composeRule.setContent {
            DistrictTheme {
                MessagingScreen(
                    state = current.value,
                    onStartEditing = onStartEditing,
                    onEditDraft = onEditDraft,
                    onSaveAccount = {},
                    onTestCredentials = {},
                    onSetDefault = {},
                    onSetChannelDefault = onSetChannelDefault,
                    onDelete = onDelete,
                    onEditCreatorCell = {},
                    onSaveCreatorCell = {},
                    onRetry = {},
                    onBack = {},
                )
            }
        }
        fun redraw(next: MessagingUiState) {
            current.value = next
            composeRule.waitForIdle()
        }

        composeRule.onNodeWithContentDescription(messagingRemoveDescription("acct-telnyx")).performClick()
        redraw(ready().copy(creatorCellDraft = "+1"))
        composeRule.onNodeWithContentDescription(MESSAGING_DELETE_CONFIRM_DESCRIPTION).performClick()

        composeRule.onNodeWithContentDescription(messagingChannelSetDescription("voice")).performClick()
        redraw(ready().copy(creatorCellDraft = "+14"))
        composeRule.onNodeWithContentDescription(messagingChannelOptionDescription("acct-telnyx"))
            .performClick()
        composeRule.onNodeWithContentDescription(MESSAGING_CHANNEL_CONFIRM_DESCRIPTION).performClick()

        redraw(ready(draft = twilioEdit))
        redraw(ready(draft = twilioEdit).copy(creatorCellDraft = "+141"))
        composeRule.onNodeWithContentDescription(MESSAGING_NUMBERS_DESCRIPTION)
            .performTextReplacement("+14165550149")
        composeRule.onNodeWithContentDescription(MESSAGING_CANCEL_DESCRIPTION).performClick()

        assertEquals(listOf("acct-telnyx"), rec.deletes)
        assertEquals(listOf("voice" to "acct-telnyx"), rec.channels)
        assertEquals("+14165550149", rec.drafts?.phoneNumbers)
        assertEquals(true, rec.draftCleared)
    }

    @Test
    fun `the channel picker names an unlabelled account by its id`() {
        var chosen: String? = null
        composeRule.setContent {
            DistrictTheme {
                ChannelDefaultDialog(
                    channel = "sms",
                    accounts = listOf(MessagingAccount(id = "acct-bare", provider = "twilio")),
                    onConfirm = { chosen = it },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("acct-bare").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(MESSAGING_CHANNEL_CONFIRM_DESCRIPTION).performClick()
        assertEquals("acct-bare", chosen)
    }

    @Test
    fun `a channel picker with no account to point at cannot be confirmed`() {
        // ⚠️ THE SCREEN HIDES THE LAUNCHER WITH NO ACCOUNTS; the dialog holds the same line on its
        // own, with nothing selected there is no id to send and the button says so.
        composeRule.setContent {
            DistrictTheme {
                ChannelDefaultDialog(channel = "sms", accounts = emptyList(), onConfirm = {}, onDismiss = {})
            }
        }

        composeRule.onNodeWithContentDescription(MESSAGING_CHANNEL_CONFIRM_DESCRIPTION).assertIsNotEnabled()
    }
}
