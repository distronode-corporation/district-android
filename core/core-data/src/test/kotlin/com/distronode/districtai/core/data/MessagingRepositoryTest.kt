package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.MESSAGING_PROVIDER_TWILIO
import com.distronode.districtai.core.model.MESSAGING_SOURCE_BYOK
import com.distronode.districtai.core.model.ManagedAccount
import com.distronode.districtai.core.model.MessagingAccount
import com.distronode.districtai.core.model.MessagingAccountRequest
import com.distronode.districtai.core.model.MessagingAccountSaveResponse
import com.distronode.districtai.core.model.MessagingChannelDefaultResponse
import com.distronode.districtai.core.model.MessagingDefaultResponse
import com.distronode.districtai.core.model.MessagingMetaResponse
import com.distronode.districtai.core.model.MessagingProviderConfig
import com.distronode.districtai.core.model.MessagingResponse
import com.distronode.districtai.core.model.MessagingTestDetails
import com.distronode.districtai.core.model.MessagingTestRequest
import com.distronode.districtai.core.model.MessagingTestResponse
import com.distronode.districtai.core.network.ApiResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.core.network.testing.FakeMessagingApi

/**
 * The messaging read, the five writes, and the credential probe.
 *
 * ⛔ THE WRITES CARRY TWO FACTS WORTH TESTING, pinned as assertions: a delete releases phone-number
 * claims (covered by the ⛔ the screen's confirmation carries), and the route takes plaintext
 * credentials (covered below by the probe's outcome mapping, which is the only call that sends any).
 */
class MessagingRepositoryTest {

    private val populated = MessagingResponse(
        success = true,
        accounts = listOf(
            MessagingAccount(
                id = "acct-twilio",
                provider = "twilio",
                label = "Twilio (main)",
                credentialSource = "byok",
                phoneNumbers = listOf("+14165550111"),
            ),
        ),
        managedAccount = ManagedAccount(provider = "twilio", phoneNumbers = listOf("+14165550190")),
        defaultAccountId = "acct-twilio",
        channelDefaults = mapOf("sms" to "acct-twilio"),
    )

    private fun api(block: FakeMessagingApi.() -> Unit = {}): FakeDistrictApi =
        FakeDistrictApi().apply { messagingApi.block() }

    private val upsert = MessagingAccountRequest(
        workspaceId = "ws-1",
        activeProvider = MESSAGING_PROVIDER_TWILIO,
        credentialSource = MESSAGING_SOURCE_BYOK,
        providerConfig = MessagingProviderConfig(),
        accountId = "acct-twilio",
    )

    // ── The read ─────────────────────────────────────────────────────────────

    @Test
    fun `messaging returns the whole envelope and sends the workspace it was asked for`() = runTest {
        // ⚠️ THE WHOLE ENVELOPE, NOT JUST THE ACCOUNTS. `defaultAccountId` and `channelDefaults`
        // are what answer the only question this screen exists for — which identity a message
        // actually leaves from — so narrowing the return here would make that unanswerable.
        val fake = api { messagingResult = ApiResult.Success(populated) }

        val result = MessagingRepository(fake).messaging("ws-1")

        assertEquals(populated, (result as ApiResult.Success).value)
        assertEquals(listOf("ws-1"), fake.messagingApi.messagingRequests)
    }

    @Test
    fun `a workspace with no carrier is an empty list rather than a failure`() = runTest {
        // ⚠️ A REAL ACCOUNT STATE. A workspace that has connected nothing cannot send SMS, and that
        // has to render as an empty state that explains itself rather than as an error with a retry.
        val fake = api { messagingResult = ApiResult.Success(MessagingResponse(success = true)) }

        val value = (MessagingRepository(fake).messaging("ws-1") as ApiResult.Success).value
        assertEquals(emptyList<MessagingAccount>(), value.accounts)
        assertNull(value.managedAccount)
        assertNull(value.defaultAccountId)
    }

    @Test
    fun `a 200 that does not affirm success is drift, not a workspace with no accounts`() = runTest {
        // ⛔ SAME LIE AS EVERY SIBLING: `{}` decodes into a well-formed "no carrier connected",
        // which would send an operator to reconnect a carrier that is already connected.
        val fake = api { messagingResult = ApiResult.Success(MessagingResponse()) }

        assertTrue(MessagingRepository(fake).messaging("ws-1") is ApiResult.DecodeFailure)
    }

    @Test
    fun `a failure is passed through untouched`() = runTest {
        val fake = api { messagingResult = ApiResult.HttpFailure(500, "Internal Server Error") }

        val result = MessagingRepository(fake).messaging("ws-1")

        assertEquals(500, (result as ApiResult.HttpFailure).status)
    }

    // ── The upsert, and the one 5xx this client renders ───────────────────────

    @Test
    fun `a saved account carries both echoed ids and the request reaches the api verbatim`() =
        runTest {
            val fake = api {
                saveAccountResult = ApiResult.Success(
                    MessagingAccountSaveResponse(
                        success = true,
                        accountId = "acct-twilio",
                        defaultAccountId = "acct-telnyx",
                    ),
                )
            }

            val outcome = MessagingRepository(fake).saveAccount(upsert)

            val saved = outcome as MessagingWriteOutcome.Saved
            assertEquals("acct-twilio", saved.accountId)
            // ⚠️ NOT THE ACCOUNT THAT WAS SAVED. The stored default was another account and this
            // edit did not ask to become one, so the echo names the default that still applies.
            assertEquals("acct-telnyx", saved.defaultAccountId)
            assertEquals(listOf(upsert), fake.messagingApi.messagingSaves)
        }

    @Test
    fun `a 502 from the carrier probe is Unverifiable, carrying the server's own sentence`() =
        runTest {
            // ⛔ THE ONE 5xx IN THIS CLIENT WHOSE BODY IS SHOWN. `FailureText` refuses to render any
            // 5xx verbatim because several routes return raw exception messages there — but
            // `handleUpsert`'s 502 is an authored sentence naming the number and the carrier, and it
            // means "we could not ASK", not "that number is not yours". Flattening it into
            // NotSaved would render "something went wrong on our side" and lose both the number and
            // the advice.
            val message =
                "Could not verify ownership of +14165550111 with twilio right now. " +
                    "Please try again shortly."
            val fake = api { saveAccountResult = ApiResult.HttpFailure(502, message) }

            val outcome = MessagingRepository(fake).saveAccount(upsert)

            assertEquals(MessagingWriteOutcome.Unverifiable(message), outcome)
        }

    @Test
    fun `a 500 stays NotSaved, so its raw body is never rendered`() = runTest {
        // ⛔ THE COMPANION TO THE TEST ABOVE, AND THE REASON THE MATCH IS ON 502 AND NOT ON 5xx.
        // This route's own 500 arm answers `getErrorMessage(rawError)` — a Prisma or Postgres
        // message — so widening the branch to every 5xx would put internal schema detail on a
        // customer's screen.
        val fake = api {
            saveAccountResult =
                ApiResult.HttpFailure(500, "Invalid `prisma.workspace.update()` invocation")
        }

        val outcome = MessagingRepository(fake).saveAccount(upsert)

        assertTrue(outcome is MessagingWriteOutcome.NotSaved)
    }

    @Test
    fun `each of the route's three 403s stays NotSaved with its own sentence intact`() = runTest {
        // ⛔ NOT TRANSLATED HERE, DELIBERATELY. `ApiResult.Forbidden` already carries the server's
        // wording and `FailureText` already renders a 4xx verbatim and non-retryably, which is
        // exactly right for all three. Inventing local copy would replace a specific message with a
        // vaguer one — and two of these are the same non-disclosing sentence on purpose, so a client
        // that tried to tell them apart would be guessing.
        listOf(
            "Managed carrier credentials are not enabled for this workspace.",
            "Phone number not found in this workspace",
            "Managed numbers can only be added by purchasing them in this workspace.",
        ).forEach { sentence ->
            val fake = api { saveAccountResult = ApiResult.Forbidden(sentence) }

            val outcome = MessagingRepository(fake).saveAccount(upsert)

            val notSaved = outcome as MessagingWriteOutcome.NotSaved
            assertEquals(sentence, (notSaved.failure as ApiResult.Forbidden).message)
        }
    }

    @Test
    fun `a save that answers 200 without affirming success is drift, not a save`() = runTest {
        // ⛔ EVERY FIELD ON THE RESPONSE DEFAULTS, so `{}` would decode into "saved, with no ids" —
        // indistinguishable from a real edit, and the screen would re-read a list that had not
        // changed and report success for a write that never happened.
        val fake = api { saveAccountResult = ApiResult.Success(MessagingAccountSaveResponse()) }

        val outcome = MessagingRepository(fake).saveAccount(upsert)

        val notSaved = outcome as MessagingWriteOutcome.NotSaved
        assertTrue(notSaved.failure is ApiResult.DecodeFailure)
    }

    // ── The three id-shaped writes ───────────────────────────────────────────

    @Test
    fun `setDefault sends the action and the id, and returns the echoed default`() = runTest {
        val fake = api {
            setDefaultResult =
                ApiResult.Success(MessagingDefaultResponse(success = true, defaultAccountId = "acct-2"))
        }

        val result = MessagingRepository(fake).setDefaultAccount("ws-1", "acct-2")

        assertEquals("acct-2", (result as ApiResult.Success).value.defaultAccountId)
        // ⛔ THE ACTION STRING IS THE ONLY THING SEPARATING THIS FROM A DELETION, because both are a
        // PATCH to the same URL with an `accountId`. Asserted on the request rather than on a call
        // count for that reason.
        val sent = fake.messagingApi.messagingDefaults.single()
        assertEquals("setDefault", sent.action)
        assertEquals("acct-2", sent.accountId)
        assertEquals("ws-1", sent.workspaceId)
    }

    @Test
    fun `delete sends the delete action, and its response cannot say what was released`() = runTest {
        val fake = api {
            deleteAccountResult =
                ApiResult.Success(MessagingDefaultResponse(success = true, defaultAccountId = "acct-1"))
        }

        val result = MessagingRepository(fake).deleteAccount("ws-1", "acct-2")

        assertEquals("acct-1", (result as ApiResult.Success).value.defaultAccountId)
        val sent = fake.messagingApi.messagingDeletes.single()
        assertEquals("delete", sent.action)
        assertEquals("acct-2", sent.accountId)
    }

    @Test
    fun `a delete of the LAST account answers an ABSENT default, which is null here`() = runTest {
        // ⚠️ `mirrorLegacy` assigns undefined for an empty account list and JSON.stringify drops the
        // key, so "there is no default now" and "the field did not arrive" are the same bytes. That
        // is why nothing renders a delete from this response alone — the list is re-read.
        val fake = api {
            deleteAccountResult = ApiResult.Success(MessagingDefaultResponse(success = true))
        }

        val result = MessagingRepository(fake).deleteAccount("ws-1", "acct-1")

        assertNull((result as ApiResult.Success).value.defaultAccountId)
    }

    @Test
    fun `a 404 from a delete is passed through, because it means the list on screen is stale`() =
        runTest {
            val fake = api { deleteAccountResult = ApiResult.NotFound("Account not found") }

            val result = MessagingRepository(fake).deleteAccount("ws-1", "acct-gone")

            assertEquals("Account not found", (result as ApiResult.NotFound).message)
        }

    @Test
    fun `setDefault and delete share one envelope guard`() = runTest {
        // ⚠️ ONE SHAPE, ONE CHECK. Both answer `{success, defaultAccountId}`, so a structurally
        // empty 200 has to be caught for both — a guard on one of them would leave the other
        // reporting a write that never happened.
        val defaulted = api { setDefaultResult = ApiResult.Success(MessagingDefaultResponse()) }
        assertTrue(
            MessagingRepository(defaulted).setDefaultAccount("ws-1", "a") is ApiResult.DecodeFailure,
        )

        val deleted = api { deleteAccountResult = ApiResult.Success(MessagingDefaultResponse()) }
        assertTrue(
            MessagingRepository(deleted).deleteAccount("ws-1", "a") is ApiResult.DecodeFailure,
        )
    }

    @Test
    fun `setChannelDefault returns the whole merged map and validates its envelope`() = runTest {
        val fake = api {
            setChannelDefaultResult = ApiResult.Success(
                MessagingChannelDefaultResponse(
                    success = true,
                    channelDefaults = mapOf("sms" to "acct-1", "voice" to "acct-2"),
                ),
            )
        }

        val result = MessagingRepository(fake).setChannelDefault("ws-1", "voice", "acct-2")

        // ⚠️ THE WHOLE MAP, because the route merges and echoes. A response carrying only the
        // written key would be indistinguishable from a route that replaced the map.
        assertEquals(
            mapOf("sms" to "acct-1", "voice" to "acct-2"),
            (result as ApiResult.Success).value.channelDefaults,
        )
        val sent = fake.messagingApi.messagingChannelDefaults.single()
        assertEquals("setChannelDefault", sent.action)
        assertEquals("voice", sent.channel)

        val drift = api {
            setChannelDefaultResult = ApiResult.Success(MessagingChannelDefaultResponse())
        }
        val rejected = MessagingRepository(drift).setChannelDefault("ws-1", "sms", "a")
        assertTrue(rejected is ApiResult.DecodeFailure)

        val forbidden = ApiResult.Forbidden("Only an owner can change senders.")
        val refused = api { setChannelDefaultResult = forbidden }
        assertEquals(forbidden, MessagingRepository(refused).setChannelDefault("ws-1", "sms", "a"))
    }

    @Test
    fun `the meta write narrows to Unit, because there is nothing to read back`() = runTest {
        val fake = api { saveCreatorCellResult = ApiResult.Success(MessagingMetaResponse(success = true)) }

        val result = MessagingRepository(fake).saveCreatorCell("ws-1", "+14165550170")

        assertEquals(Unit, (result as ApiResult.Success).value)
        val sent = fake.messagingApi.messagingMetaWrites.single()
        assertEquals("meta", sent.action)
        assertEquals("+14165550170", sent.creatorCellNumber)

        val drift = api { saveCreatorCellResult = ApiResult.Success(MessagingMetaResponse()) }
        assertTrue(
            MessagingRepository(drift).saveCreatorCell("ws-1", "+1") is ApiResult.DecodeFailure,
        )
    }

    // ── The credential probe, whose failure answer is a 200 ───────────────────

    private val testRequest = MessagingTestRequest(
        workspaceId = "ws-1",
        providerConfig = MessagingProviderConfig(
            provider = MESSAGING_PROVIDER_TWILIO,
            accountSid = "AC_typed",
            authToken = "typed",
        ),
    )

    @Test
    fun `a passing probe carries whatever the provider volunteered`() = runTest {
        val fake = api {
            testCredentialsResult = ApiResult.Success(
                MessagingTestResponse(
                    success = true,
                    details = MessagingTestDetails(friendlyName = "Acme Ltd", status = "active"),
                ),
            )
        }

        val outcome = MessagingRepository(fake).testCredentials(testRequest)

        assertEquals(MessagingTestOutcome.Passed("Acme Ltd"), outcome)
        assertEquals(listOf(testRequest), fake.messagingApi.messagingTests)
    }

    @Test
    fun `a passing probe on Sinch or Telnyx falls back to the bare message`() = runTest {
        // ⚠️ TWO SHAPES BEHIND ONE BUTTON. Twilio answers `{friendlyName, status}`; the other two
        // answer `{message}`. Whichever is present is the only thing worth showing.
        val fake = api {
            testCredentialsResult = ApiResult.Success(
                MessagingTestResponse(
                    success = true,
                    details = MessagingTestDetails(message = "Credentials verified"),
                ),
            )
        }

        assertEquals(
            MessagingTestOutcome.Passed("Credentials verified"),
            MessagingRepository(fake).testCredentials(testRequest),
        )
    }

    @Test
    fun `a passing probe with NO details is still a pass`() = runTest {
        // ⚠️ An empty `details` is a legitimate success, not a decode problem — so the outcome
        // carries a null detail rather than the repository fabricating one or failing.
        val fake = api {
            testCredentialsResult = ApiResult.Success(MessagingTestResponse(success = true))
        }

        assertEquals(
            MessagingTestOutcome.Passed(null),
            MessagingRepository(fake).testCredentials(testRequest),
        )
    }

    @Test
    fun `a REJECTED probe is a 200 and must NOT be turned into a decode failure`() = runTest {
        // ⛔ THE WHOLE REASON THIS CALL SKIPS `rejectedEnvelope`. The route answers a carrier's 401
        // with HTTP 200 and `{success:false, error}` on purpose, so the operator reads "these keys do
        // not authenticate". Running the envelope guard would render the button's only interesting
        // outcome as "this version of the app does not understand the response", with no retry —
        // for a working app talking to a working server.
        val fake = api {
            testCredentialsResult = ApiResult.Success(
                MessagingTestResponse(success = false, error = "Authenticate (20003)"),
            )
        }

        val outcome = MessagingRepository(fake).testCredentials(testRequest)

        assertEquals(MessagingTestOutcome.Rejected("Authenticate (20003)"), outcome)
    }

    @Test
    fun `a rejection with no error string still reads as a rejection, not as a pass`() = runTest {
        val fake = api {
            testCredentialsResult = ApiResult.Success(MessagingTestResponse(success = false))
        }

        assertEquals(
            MessagingTestOutcome.Rejected(""),
            MessagingRepository(fake).testCredentials(testRequest),
        )
    }

    @Test
    fun `a transport failure says nothing about the credentials`() = runTest {
        // ⛔ SEPARATE FROM Rejected BECAUSE THEY ARE DIFFERENT ANSWERS. Merging them would tell
        // someone their working credentials are wrong because their phone lost signal — on the one
        // screen where believing that leads to retyping a live carrier secret. The 429 from the
        // 10/min cap lands here too, for the same reason.
        val fake = api { testCredentialsResult = ApiResult.RateLimited("Too many connection tests") }

        val outcome = MessagingRepository(fake).testCredentials(testRequest)

        assertTrue((outcome as MessagingTestOutcome.Unreachable).failure is ApiResult.RateLimited)
    }
}
