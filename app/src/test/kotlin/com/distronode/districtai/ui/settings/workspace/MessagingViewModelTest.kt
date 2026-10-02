package com.distronode.districtai.ui.settings.workspace

import com.distronode.districtai.core.data.MessagingRepository
import com.distronode.districtai.core.model.MESSAGING_PROVIDER_SINCH
import com.distronode.districtai.core.model.MESSAGING_PROVIDER_TELNYX
import com.distronode.districtai.core.model.MESSAGING_PROVIDER_TWILIO
import com.distronode.districtai.core.model.MESSAGING_SOURCE_MANAGED
import com.distronode.districtai.core.model.MessagingAccount
import com.distronode.districtai.core.model.MessagingAccountSaveResponse
import com.distronode.districtai.core.model.MessagingChannelDefaultResponse
import com.distronode.districtai.core.model.MessagingDefaultResponse
import com.distronode.districtai.core.model.MessagingResponse
import com.distronode.districtai.core.model.MessagingTestDetails
import com.distronode.districtai.core.model.MessagingTestResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import org.junit.Rule
import com.distronode.districtai.core.network.testing.MainDispatcherRule

/**
 * The messaging screen's state machine.
 *
 * ⛔ THE ASSERTIONS THAT MATTER MOST ON THIS SURFACE ARE ABOUT WHAT IS **NOT** SENT. A blank secret
 * box must reach the wire as an ABSENT key (that is what makes an edit against a redacted read
 * safe), an untouched phone-number box must not send a list at all (sending one rewrites the numbers
 * that route inbound calls, and clearing it releases them), and a `viewer` must not be able to reach
 * any write even by calling the ViewModel directly. Each of those is a fact about the REQUEST, which
 * is why the fake records requests rather than counting calls.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessagingViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private val twilio = MessagingAccount(
        id = "acct-twilio",
        provider = MESSAGING_PROVIDER_TWILIO,
        label = "Twilio (main)",
        credentialSource = "byok",
        phoneNumbers = listOf("+14165550111", "+14165550112"),
    )

    private val telnyx = MessagingAccount(
        id = "acct-telnyx",
        provider = MESSAGING_PROVIDER_TELNYX,
        label = "Telnyx (overflow)",
        credentialSource = "byok",
        phoneNumbers = listOf("+14165550113"),
    )

    private val populated = MessagingResponse(
        success = true,
        accounts = listOf(twilio, telnyx),
        defaultAccountId = "acct-telnyx",
        channelDefaults = mapOf("sms" to "acct-twilio"),
    )

    private fun api() = FakeDistrictApi().apply {
        messagingApi.messagingResult = ApiResult.Success(populated)
    }

    private fun viewModel(
        api: FakeDistrictApi,
        role: WorkspaceRole? = WorkspaceRole.CLIENT,
    ) = MessagingViewModel(MessagingRepository(api), "ws-1", role)

    // ── Loading ──────────────────────────────────────────────────────────────

    @Test
    fun `the initial load reads once and reports the whole envelope`() = runTest {
        val api = api()
        val model = viewModel(api)
        advanceUntilIdle()

        val state = model.state.value
        assertEquals(populated, (state.load as MessagingLoadState.Ready).messaging)
        assertEquals(listOf("ws-1"), api.messagingApi.messagingRequests)
        assertTrue(state.canEdit)
    }

    @Test
    fun `a failed read is a retryable failure rather than an empty account list`() = runTest {
        val api = api().apply {
            messagingApi.messagingResult = ApiResult.HttpFailure(503, "Service Unavailable")
        }
        val model = viewModel(api)
        advanceUntilIdle()

        // ⛔ "We could not look" is not "there is nothing". A workspace with no carrier cannot send
        // SMS, and rendering an outage as that state would send an operator to reconnect a carrier
        // that is already connected.
        assertTrue(model.state.value.load is MessagingLoadState.Failed)
        assertNull(model.state.value.messaging)
    }

    @Test
    fun `load discards an open draft, because the client can no longer vouch for the list`() =
        runTest {
            val api = api()
            val model = viewModel(api)
            advanceUntilIdle()
            model.startEditing("acct-twilio")
            assertEquals("acct-twilio", model.state.value.draft?.accountId)

            // ⛔ THE SESSION-CHANGE PATH. `OnSessionChanged` replays exactly this, and an edit form
            // still pointing at an account id from before is how a save lands on the wrong row.
            model.load()
            advanceUntilIdle()

            assertNull(model.state.value.draft)
        }

    // ── The role gate ────────────────────────────────────────────────────────

    @Test
    fun `a viewer can read and cannot reach a single write, even calling the model directly`() =
        runTest {
            // ⛔ THE UI GATE IS NOT THE ONLY GATE, WHICH IS THE POINT OF THIS TEST. Hiding a button
            // is a UX decision; every write re-checks the role at its own call site, so "the control
            // was not drawn" never becomes the thing holding a 403 back.
            val api = api()
            val model = viewModel(api, WorkspaceRole.VIEWER)
            advanceUntilIdle()

            assertFalse(model.state.value.canEdit)
            assertEquals(populated, (model.state.value.load as MessagingLoadState.Ready).messaging)

            model.startEditing(null)
            model.startEditing("acct-twilio")
            model.saveAccount()
            model.setDefault("acct-twilio")
            model.setChannelDefault("sms", "acct-twilio")
            model.deleteAccount("acct-twilio")
            model.editCreatorCell("+14165550170")
            model.saveCreatorCell()
            model.testCredentials()
            advanceUntilIdle()

            assertNull(model.state.value.draft)
            assertTrue(api.messagingApi.messagingSaves.isEmpty())
            assertTrue(api.messagingApi.messagingDefaults.isEmpty())
            assertTrue(api.messagingApi.messagingChannelDefaults.isEmpty())
            assertTrue(api.messagingApi.messagingDeletes.isEmpty())
            assertTrue(api.messagingApi.messagingMetaWrites.isEmpty())
            assertTrue(api.messagingApi.messagingTests.isEmpty())
            // ⚠️ And exactly one request was made: the read, which the route does admit them to.
            assertEquals(listOf("ws-1"), api.messagingApi.messagingRequests)
        }

    @Test
    fun `an unparseable role is treated as a viewer, not as a client`() = runTest {
        // ⛔ FAILS CLOSED. `WorkspaceRole.fromWire` answers null for a role string this build does
        // not know, and the tempting default is CLIENT because that is the server's fallback for a
        // member with no explicit row — but the server reaches that conclusion having CONFIRMED the
        // membership. Here null means the role could not be established.
        val model = viewModel(api(), role = null)
        advanceUntilIdle()

        assertFalse(model.state.value.canEdit)
        assertFalse(model.state.value.canEditNow)
    }

    // ── The form ─────────────────────────────────────────────────────────────

    @Test
    fun `editing an account seeds every visible field and NO credential`() = runTest {
        val model = viewModel(api())
        advanceUntilIdle()

        model.startEditing("acct-twilio")

        val draft = model.state.value.draft!!
        assertEquals("acct-twilio", draft.accountId)
        assertEquals(MESSAGING_PROVIDER_TWILIO, draft.provider)
        assertEquals("Twilio (main)", draft.label)
        assertEquals("+14165550111\n+14165550112", draft.phoneNumbers)
        // ⛔ NOTHING TO SEED A SECRET FROM, AND THAT IS THE REDACTION WORKING. The GET projects five
        // keys per account and not one of them is a credential, so a pre-filled box could only ever
        // hold a placeholder — which is precisely how a form saves a placeholder over a live key.
        assertEquals(emptyMap<String, String>(), draft.secrets)
        // ⚠️ FALSE EVEN THOUGH THE BOX IS PRE-FILLED. The numbers are shown so they can be READ;
        // only a keystroke makes them part of the save.
        assertFalse(draft.numbersEdited)
        assertFalse(draft.isCreate)
    }

    @Test
    fun `editing an id the read does not hold opens nothing, rather than opening a create`() =
        runTest {
            val model = viewModel(api())
            advanceUntilIdle()

            model.startEditing("acct-deleted-on-the-web")

            // ⛔ A STALE ROW IS THE ORDINARY CAUSE, and turning "edit the account that is gone" into
            // "add a new one" is exactly how a duplicate carrier account gets made.
            assertNull(model.state.value.draft)
        }

    @Test
    fun `a null id opens a create whose secrets are REQUIRED`() = runTest {
        val model = viewModel(api())
        advanceUntilIdle()

        model.startEditing(null)

        val draft = model.state.value.draft!!
        assertTrue(draft.isCreate)
        assertTrue(draft.secretsRequired)
        // ⛔ A CREATE CANNOT SAVE WITH BLANK SECRETS, because for a create a blank means "store
        // nothing" rather than "keep" — the route deletes a secret field that arrives blank with
        // nothing stored, and the account would save cleanly and then fail on its first send.
        assertFalse(draft.canSave)
    }

    @Test
    fun `editDraft ignores an update when no form is open, and null closes the one that is`() =
        runTest {
            val model = viewModel(api())
            advanceUntilIdle()

            model.editDraft(MessagingDraft(label = "orphan"))
            assertNull(model.state.value.draft)

            model.startEditing("acct-twilio")
            model.editDraft(model.state.value.draft!!.copy(label = "renamed"))
            assertEquals("renamed", model.state.value.draft?.label)

            model.editDraft(null)
            assertNull(model.state.value.draft)
        }

    @Test
    fun `any edit retires a previous probe result`() = runTest {
        // ⛔ A GREEN "the carrier accepted these keys" SITTING UNDER A KEY THAT HAS SINCE BEEN
        // RETYPED IS A CLAIM ABOUT A VALUE NOBODY TESTED.
        val api = api().apply {
            messagingApi.testCredentialsResult =
                ApiResult.Success(MessagingTestResponse(success = true))
        }
        val model = viewModel(api)
        advanceUntilIdle()
        model.startEditing(null)
        model.editDraft(
            model.state.value.draft!!.copy(
                secrets = mapOf("accountSid" to "AC_one", "authToken" to "tok"),
            ),
        )
        model.testCredentials()
        advanceUntilIdle()
        assertTrue(model.state.value.test is MessagingTestState.Passed)

        model.editDraft(model.state.value.draft!!.copy(secrets = mapOf("accountSid" to "AC_two")))

        assertEquals(MessagingTestState.Idle, model.state.value.test)
    }

    // ── The upsert, and the two things it must not send ───────────────────────

    @Test
    fun `an ordinary edit sends NO secret key and NO phoneNumbers`() = runTest {
        // ⛔ THE SINGLE MOST CONSEQUENTIAL ASSERTION IN THIS FILE. An absent secret means "keep the
        // stored ciphertext" and an absent `phoneNumbers` means the hub index is never rewritten. If
        // either started arriving as an empty value, a label change would wipe the workspace's
        // carrier credentials or release the numbers that route its inbound calls — and the response
        // would look identical either way.
        val api = api()
        val model = viewModel(api)
        advanceUntilIdle()
        model.startEditing("acct-twilio")
        model.editDraft(model.state.value.draft!!.copy(label = "Twilio (renamed)"))

        model.saveAccount()
        advanceUntilIdle()

        val sent = api.messagingApi.messagingSaves.single()
        assertEquals("acct-twilio", sent.accountId)
        assertEquals("Twilio (renamed)", sent.label)
        assertNull(sent.providerConfig.accountSid)
        assertNull(sent.providerConfig.authToken)
        assertNull(sent.providerConfig.phoneNumbers)
        // ⚠️ `makeDefault` IS OMITTED RATHER THAN SENT AS FALSE. An absent key and `false` mean the
        // same thing to the route, and asserting a negative it never reads would be noise.
        assertNull(sent.makeDefault)
    }

    @Test
    fun `a blank secret box is omitted while a typed one is sent`() = runTest {
        val api = api()
        val model = viewModel(api)
        advanceUntilIdle()
        model.startEditing("acct-twilio")
        model.editDraft(
            model.state.value.draft!!.copy(
                // ⚠️ Whitespace only, which is what a fumbled tap into a box produces. It has to be
                // read as "keep", not as a credential of one space.
                secrets = mapOf("accountSid" to "   ", "authToken" to "  new-token  "),
            ),
        )

        model.saveAccount()
        advanceUntilIdle()

        val config = api.messagingApi.messagingSaves.single().providerConfig
        assertNull(config.accountSid)
        // ⚠️ TRIMMED. The route stores what it is sent, and a token with a trailing space fails at
        // the carrier with an error that points nowhere near this form.
        assertEquals("new-token", config.authToken)
    }

    @Test
    fun `touching the numbers box sends the parsed list, and clearing it sends an empty one`() =
        runTest {
            val api = api()
            val model = viewModel(api)
            advanceUntilIdle()
            model.startEditing("acct-twilio")
            model.editDraft(
                model.state.value.draft!!.copy(
                    // ⚠️ Commas and newlines both, with blanks between — an operator pasting from a
                    // spreadsheet produces exactly this.
                    phoneNumbers = "+14165550111,\n\n +14165550149 \n",
                    numbersEdited = true,
                ),
            )

            model.saveAccount()
            advanceUntilIdle()

            assertEquals(
                listOf("+14165550111", "+14165550149"),
                api.messagingApi.messagingSaves.single().providerConfig.phoneNumbers,
            )

            // ⛔ AND AN EMPTIED BOX IS AN EMPTY LIST, NOT AN ABSENT ONE. That is a real instruction —
            // remove every number from this account and release its claims — so it must reach the
            // wire rather than being smoothed into "no change". The screen's help text is what warns
            // about it; the client's job is to send what was meant.
            model.startEditing("acct-twilio")
            model.editDraft(
                model.state.value.draft!!.copy(phoneNumbers = "", numbersEdited = true),
            )
            model.saveAccount()
            advanceUntilIdle()

            assertEquals(
                emptyList<String>(),
                api.messagingApi.messagingSaves.last().providerConfig.phoneNumbers,
            )
        }

    @Test
    fun `switching provider clears the typed secrets and demands a full set`() = runTest {
        val api = api()
        val model = viewModel(api)
        advanceUntilIdle()
        model.startEditing("acct-twilio")
        model.editDraft(
            model.state.value.draft!!.copy(secrets = mapOf("accountSid" to "AC_half-typed")),
        )

        // ⛔ THE ROUTE ONLY CARRIES STORED SECRETS FORWARD WHEN THE PROVIDER IS UNCHANGED, so after a
        // switch a blank box stores nothing. The draft has to know that or the form would offer a
        // save that produces an account with no credentials.
        model.editDraft(model.state.value.draft!!.copy(provider = MESSAGING_PROVIDER_SINCH))

        val draft = model.state.value.draft!!
        assertTrue(draft.secretsRequired)
        assertFalse(draft.canSave)
        // ⚠️ The half-typed Twilio SID is still in the map here because clearing it is the PICKER's
        // job, not the draft's — but it can never be sent, since Sinch's field list does not name
        // it. Asserted so the two halves of that reasoning stay visible.
        model.saveAccount()
        advanceUntilIdle()
        assertTrue(api.messagingApi.messagingSaves.isEmpty())
    }

    @Test
    fun `a save is refused locally when a provider's secret set is only half filled`() = runTest {
        // ⛔ A HALF-FILLED SET IS WORSE THAN AN EMPTY ONE. The route encrypts what it gets and drops
        // what it does not, so a Sinch account saved with a key id and no key secret stores a
        // credential that can never authenticate — and fails at send time, far from this form.
        val api = api()
        val model = viewModel(api)
        advanceUntilIdle()
        model.startEditing(null)
        model.editDraft(
            model.state.value.draft!!.copy(
                provider = MESSAGING_PROVIDER_SINCH,
                secrets = mapOf("projectId" to "p", "keyId" to "k"),
            ),
        )

        model.saveAccount()
        advanceUntilIdle()

        assertTrue(api.messagingApi.messagingSaves.isEmpty())
        // ⚠️ THE DRAFT SURVIVES. Losing typed credentials to a local refusal would be two losses for
        // one mistake.
        assertEquals(MESSAGING_PROVIDER_SINCH, model.state.value.draft?.provider)
    }

    @Test
    fun `a successful save closes the form and re-reads, and never patches the list`() = runTest {
        val api = api()
        val model = viewModel(api)
        advanceUntilIdle()
        model.startEditing("acct-twilio")
        model.editDraft(model.state.value.draft!!.copy(label = "Renamed"))

        model.saveAccount()
        advanceUntilIdle()

        assertEquals(SaveState.Saved, model.state.value.accountSave)
        assertNull(model.state.value.draft)
        // ⛔ THE RE-READ IS THE ONLY THING THAT REDRAWS THE LIST. The save echoes ids only, and the
        // route trims the label and may substitute a generated one — so the on-screen row still
        // shows the SERVER's label, not the typed one.
        assertEquals(listOf("ws-1", "ws-1"), api.messagingApi.messagingRequests)
        assertEquals("Twilio (main)", model.state.value.accounts.first().label)
    }

    @Test
    fun `a failed save keeps the form open with everything typed still in it`() = runTest {
        val api = api().apply {
            messagingApi.saveAccountResult =
                ApiResult.Forbidden("Phone number not found in this workspace")
        }
        val model = viewModel(api)
        advanceUntilIdle()
        model.startEditing("acct-twilio")
        model.editDraft(
            model.state.value.draft!!.copy(secrets = mapOf("authToken" to "typed-by-hand")),
        )

        model.saveAccount()
        advanceUntilIdle()

        assertTrue(model.state.value.accountSave is SaveState.Failed)
        assertEquals("typed-by-hand", model.state.value.draft?.secrets?.get("authToken"))
    }

    @Test
    fun `a 502 from the carrier probe is shown verbatim and IS retryable`() = runTest {
        // ⛔ THE ONE 5xx THIS APP RENDERS. It is an authored sentence naming the number and the
        // carrier, it means "we could not ASK" rather than "that number is not yours", and the
        // number stays unclaimed — so pressing save again in a minute is the correct advice, unlike
        // every other refusal on this form.
        val message = "Could not verify ownership of +14165550149 with twilio right now."
        val api = api().apply {
            messagingApi.saveAccountResult = ApiResult.HttpFailure(502, message)
        }
        val model = viewModel(api)
        advanceUntilIdle()
        model.startEditing("acct-twilio")

        model.saveAccount()
        advanceUntilIdle()

        val failed = model.state.value.accountSave as SaveState.Failed
        assertTrue(failed.failure.retryable)
        assertEquals(
            com.distronode.districtai.ui.UiText.Literal(message),
            failed.failure.message,
        )
    }

    @Test
    fun `a managed request that is not entitled reports the server's sentence, not ours`() =
        runTest {
            val sentence =
                "Managed carrier credentials are not enabled for this workspace. " +
                    "Contact support to switch to a managed plan."
            val api = api().apply {
                messagingApi.saveAccountResult = ApiResult.Forbidden(sentence)
            }
            val model = viewModel(api)
            advanceUntilIdle()
            model.startEditing("acct-twilio")
            model.editDraft(
                model.state.value.draft!!.copy(credentialSource = MESSAGING_SOURCE_MANAGED),
            )

            model.saveAccount()
            advanceUntilIdle()

            // ⛔ NOTHING PRE-DECIDES ENTITLEMENT CLIENT-SIDE. Nothing this app reads says whether the
            // workspace is on a managed plan, so the option is offered and the server's refusal —
            // which names support — is what the operator sees. ⚠️ Not retryable: the plan will not
            // change because a button was pressed twice.
            val failed = model.state.value.accountSave as SaveState.Failed
            assertFalse(failed.failure.retryable)
            assertEquals(
                com.distronode.districtai.ui.UiText.Literal(sentence),
                failed.failure.message,
            )
            assertEquals(
                MESSAGING_SOURCE_MANAGED,
                api.messagingApi.messagingSaves.single().credentialSource,
            )
        }

    // ── The default, the channels, and the delete ─────────────────────────────

    @Test
    fun `setDefault writes then re-reads, and reports its own banner`() = runTest {
        val api = api().apply {
            messagingApi.setDefaultResult = ApiResult.Success(
                MessagingDefaultResponse(success = true, defaultAccountId = "acct-twilio"),
            )
        }
        val model = viewModel(api)
        advanceUntilIdle()

        model.setDefault("acct-twilio")
        advanceUntilIdle()

        assertEquals(SaveState.Saved, model.state.value.defaultSave)
        assertEquals("acct-twilio", api.messagingApi.messagingDefaults.single().accountId)
        assertEquals(listOf("ws-1", "ws-1"), api.messagingApi.messagingRequests)
    }

    @Test
    fun `setChannelDefault sends the channel it was given`() = runTest {
        val api = api().apply {
            messagingApi.setChannelDefaultResult = ApiResult.Success(
                MessagingChannelDefaultResponse(
                    success = true,
                    channelDefaults = mapOf("sms" to "acct-twilio", "voice" to "acct-telnyx"),
                ),
            )
        }
        val model = viewModel(api)
        advanceUntilIdle()

        model.setChannelDefault("voice", "acct-telnyx")
        advanceUntilIdle()

        assertEquals(SaveState.Saved, model.state.value.channelSave)
        val sent = api.messagingApi.messagingChannelDefaults.single()
        assertEquals("voice", sent.channel)
        assertEquals("acct-telnyx", sent.accountId)
    }

    @Test
    fun `deleting sends the delete action and re-reads`() = runTest {
        val api = api().apply {
            messagingApi.deleteAccountResult = ApiResult.Success(
                MessagingDefaultResponse(success = true, defaultAccountId = "acct-twilio"),
            )
        }
        val model = viewModel(api)
        advanceUntilIdle()

        model.deleteAccount("acct-telnyx")
        advanceUntilIdle()

        assertEquals(SaveState.Saved, model.state.value.deleteSave)
        assertEquals("acct-telnyx", api.messagingApi.messagingDeletes.single().accountId)
        assertEquals(listOf("ws-1", "ws-1"), api.messagingApi.messagingRequests)
    }

    @Test
    fun `a failed re-read after a successful write does NOT overwrite the write's notice`() =
        runTest {
            // ⛔ TWO SEPARATE FIELDS PRECISELY SO BOTH CAN BE TRUE. Telling an operator their change
            // failed when only the read did invites them to make it again — and on this surface
            // "again" can mean a second carrier account or a second release of a phone number.
            val api = api()
            val model = viewModel(api)
            advanceUntilIdle()
            api.messagingApi.messagingResult = ApiResult.NetworkFailure(java.io.IOException("down"))

            model.setDefault("acct-twilio")
            advanceUntilIdle()

            assertEquals(SaveState.Saved, model.state.value.defaultSave)
            assertTrue(model.state.value.load is MessagingLoadState.Failed)
        }

    // ── The creator cell number ──────────────────────────────────────────────

    @Test
    fun `the creator cell is trimmed, cleared on success, and never re-read`() = runTest {
        val api = api()
        val model = viewModel(api)
        advanceUntilIdle()
        model.editCreatorCell("  +14165550170 ")

        model.saveCreatorCell()
        advanceUntilIdle()

        assertEquals("+14165550170", api.messagingApi.messagingMetaWrites.single().creatorCellNumber)
        assertEquals(SaveState.Saved, model.state.value.metaSave)
        assertEquals("", model.state.value.creatorCellDraft)
        // ⚠️ NO RE-READ. The stored value is not on the messaging GET, so re-reading could not
        // confirm it — and a failed list read would get to replace a successful save notice.
        assertEquals(listOf("ws-1"), api.messagingApi.messagingRequests)
    }

    @Test
    fun `a blank creator cell is refused locally rather than writing an empty string`() = runTest {
        val api = api()
        val model = viewModel(api)
        advanceUntilIdle()
        model.editCreatorCell("   ")

        model.saveCreatorCell()
        advanceUntilIdle()

        assertTrue(api.messagingApi.messagingMetaWrites.isEmpty())
        assertEquals(SaveState.Idle, model.state.value.metaSave)
    }

    @Test
    fun `a failed creator cell save keeps what was typed`() = runTest {
        val api = api().apply {
            messagingApi.saveCreatorCellResult = ApiResult.HttpFailure(400, "Nothing to update")
        }
        val model = viewModel(api)
        advanceUntilIdle()
        model.editCreatorCell("+14165550170")

        model.saveCreatorCell()
        advanceUntilIdle()

        assertTrue(model.state.value.metaSave is SaveState.Failed)
        assertEquals("+14165550170", model.state.value.creatorCellDraft)
    }

    // ── The credential probe ─────────────────────────────────────────────────

    @Test
    fun `the probe is refused locally when the form does not hold every key`() = runTest {
        // ⛔ THE ROUTE READS PLAINTEXT, UNSAVED CREDENTIALS, so on an ordinary edit there is nothing
        // to test with. Sending blanks would report the carrier's 401 as though the SAVED keys were
        // broken — which on this screen is the belief that leads someone to retype a live key they
        // never needed to touch.
        val api = api()
        val model = viewModel(api)
        advanceUntilIdle()
        model.startEditing("acct-twilio")

        model.testCredentials()
        advanceUntilIdle()

        assertTrue(api.messagingApi.messagingTests.isEmpty())
        assertEquals(MessagingTestState.Idle, model.state.value.test)
        assertFalse(model.state.value.draft!!.canTest)
    }

    @Test
    fun `a complete Sinch form can be tested, and the request carries the provider`() = runTest {
        val api = api().apply {
            messagingApi.testCredentialsResult = ApiResult.Success(
                MessagingTestResponse(
                    success = true,
                    details = MessagingTestDetails(message = "Credentials verified"),
                ),
            )
        }
        val model = viewModel(api)
        advanceUntilIdle()
        model.startEditing(null)
        model.editDraft(
            model.state.value.draft!!.copy(
                provider = MESSAGING_PROVIDER_SINCH,
                secrets = mapOf(
                    "projectId" to "proj",
                    "keyId" to "kid",
                    "keySecret" to "ksec",
                    "applicationKey" to "akey",
                    "applicationSecret" to "asec",
                ),
            ),
        )
        assertTrue(model.state.value.draft!!.canTest)

        model.testCredentials()
        advanceUntilIdle()

        val sent = api.messagingApi.messagingTests.single()
        // ⛔ THE TEST ROUTE DISPATCHES ON `providerConfig.provider`, unlike the save route which
        // deletes the key. Omitting it here is a 400 "Unknown provider" for credentials that are
        // perfectly good.
        assertEquals(MESSAGING_PROVIDER_SINCH, sent.providerConfig.provider)
        assertEquals("proj", sent.providerConfig.projectId)
        assertEquals(MessagingTestState.Passed("Credentials verified"), model.state.value.test)
    }

    @Test
    fun `a rejected probe is a 200 and reads as a credential problem`() = runTest {
        val api = api().apply {
            messagingApi.testCredentialsResult = ApiResult.Success(
                MessagingTestResponse(success = false, error = "Authenticate (20003)"),
            )
        }
        val model = viewModel(api)
        advanceUntilIdle()
        model.startEditing(null)
        model.editDraft(
            model.state.value.draft!!.copy(
                secrets = mapOf("accountSid" to "AC_wrong", "authToken" to "wrong"),
            ),
        )

        model.testCredentials()
        advanceUntilIdle()

        assertEquals(MessagingTestState.Rejected("Authenticate (20003)"), model.state.value.test)
    }

    @Test
    fun `an unreachable probe says nothing about the credentials`() = runTest {
        // ⛔ A DIFFERENT ANSWER FROM Rejected. Merging the two would tell someone their working
        // credentials are wrong because their phone lost signal.
        val api = api().apply {
            messagingApi.testCredentialsResult =
                ApiResult.RateLimited("Too many connection tests for this workspace.")
        }
        val model = viewModel(api)
        advanceUntilIdle()
        model.startEditing(null)
        model.editDraft(
            model.state.value.draft!!.copy(
                secrets = mapOf("accountSid" to "AC_ok", "authToken" to "ok"),
            ),
        )

        model.testCredentials()
        advanceUntilIdle()

        assertTrue(model.state.value.test is MessagingTestState.Unreachable)
    }

    @Test
    fun `the probe cannot be started with no form open`() = runTest {
        val api = api()
        val model = viewModel(api)
        advanceUntilIdle()

        model.testCredentials()
        advanceUntilIdle()

        assertTrue(api.messagingApi.messagingTests.isEmpty())
    }

    @Test
    fun `a busy state blocks every entry point, so a second press cannot race the first`() =
        runTest {
            // ⛔ THE GUARD THAT STOPS A CREATE HAPPENING TWICE. `saveAccount` with no accountId is
            // the one non-idempotent call here, and `busy` covering the whole round trip is what
            // makes a double tap on a slow network impossible rather than merely unlikely.
            val api = api()
            val model = viewModel(api)
            advanceUntilIdle()
            model.startEditing(null)
            model.editDraft(
                model.state.value.draft!!.copy(
                    secrets = mapOf("accountSid" to "AC_new", "authToken" to "tok"),
                ),
            )

            model.saveAccount()
            // ⚠️ NOT advanced: the save is in flight, which is exactly the window a second press
            // lands in.
            assertTrue(model.state.value.busy)
            model.saveAccount()
            model.setDefault("acct-twilio")
            model.deleteAccount("acct-twilio")
            model.startEditing("acct-twilio")
            advanceUntilIdle()

            assertEquals(1, api.messagingApi.messagingSaves.size)
            assertTrue(api.messagingApi.messagingDefaults.isEmpty())
            assertTrue(api.messagingApi.messagingDeletes.isEmpty())
        }

    @Test
    fun `a create names the account the server minted, not one this client invented`() = runTest {
        val api = api().apply {
            messagingApi.saveAccountResult = ApiResult.Success(
                MessagingAccountSaveResponse(
                    success = true,
                    accountId = "acct-9f2c",
                    defaultAccountId = "acct-9f2c",
                ),
            )
        }
        val model = viewModel(api)
        advanceUntilIdle()
        model.startEditing(null)
        model.editDraft(
            model.state.value.draft!!.copy(
                label = "Second Twilio",
                makeDefault = true,
                secrets = mapOf("accountSid" to "AC_new", "authToken" to "tok"),
            ),
        )

        model.saveAccount()
        advanceUntilIdle()

        val sent = api.messagingApi.messagingSaves.single()
        // ⛔ NO accountId ON A CREATE. Inventing one client-side would make the route treat it as an
        // edit and answer 404 "Account not found".
        assertNull(sent.accountId)
        assertEquals(true, sent.makeDefault)
        assertEquals("AC_new", sent.providerConfig.accountSid)
        assertEquals(SaveState.Saved, model.state.value.accountSave)
    }
}
